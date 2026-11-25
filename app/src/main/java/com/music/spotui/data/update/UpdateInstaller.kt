package com.music.spotui.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateFileProvider : FileProvider()

internal object UpdateInstaller {
    class VerificationException(message: String) : IOException(message)

    private const val MAX_BYTES = 300L * 1024 * 1024
    private val downloadHosts = setOf(
        "github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com",
    )
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()

    suspend fun download(context: Context, info: UpdateChecker.UpdateInfo, progress: (Int?) -> Unit): File =
        runInterruptible(Dispatchers.IO) {
            val url = info.downloadUrl?.toHttpUrlOrNull()
            require(url != null && url.host == "github.com" && url.encodedPath.contains("/releases/download/"))
            require(info.version.matches(Regex("[0-9.]+")))
            val directory = File(context.cacheDir, "apk_updates").apply { mkdirs() }
            val apk = File(directory, "Spotidroid_${info.version}.apk")
            if (apk.isFile && runCatching { verify(context, apk, info.sha256) }.isSuccess) {
                return@runInterruptible apk
            }
            val partial = File.createTempFile("download-", ".apk", directory)
            try {
                transfer(url, partial, progress)
                verify(context, partial, info.sha256)
                if (!partial.renameTo(apk)) throw IOException("No se pudo guardar la APK")
                directory.listFiles { file -> file.name.startsWith("Spotidroid_") && file != apk }?.forEach { it.delete() }
                apk
            } finally {
                partial.delete()
            }
        }

    private fun transfer(initialUrl: HttpUrl, destination: File, progress: (Int?) -> Unit) {
        var url = initialUrl
        repeat(6) {
            require(url.isHttps && url.port == 443 && url.host in downloadHosts)
            val request = Request.Builder().url(url).header("User-Agent", "Spotidroid-App").build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    url = response.header("Location")?.let(url::resolve) ?: throw IOException("Redirección sin destino")
                } else {
                    if (!response.isSuccessful) throw IOException("GitHub respondió con HTTP ${response.code}")
                    val body = response.body
                    val length = body.contentLength()
                    if (length > MAX_BYTES) throw IOException("La APK supera el tamaño permitido")
                    var total = 0L
                    var lastPercent = -1
                    progress(if (length > 0) 0 else null)
                    body.byteStream().use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_BYTES) throw IOException("La APK supera el tamaño permitido")
                                output.write(buffer, 0, count)
                                if (length > 0) {
                                    val percent = (total * 100 / length).toInt().coerceIn(0, 99)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        progress(percent)
                                    }
                                }
                            }
                        }
                    }
                    if (total == 0L || (length > 0 && total != length)) throw IOException("Descarga incompleta")
                    return
                }
            }
        }
        throw IOException("Demasiadas redirecciones de GitHub")
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, apk: File, expectedHash: String?) {
        if (expectedHash != null) {
            val sha256 = MessageDigest.getInstance("SHA-256")
            apk.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha256.update(buffer, 0, count)
                }
            }
            val actual = sha256.digest().joinToString("") { "%02x".format(it) }
            if (!expectedHash.equals(actual, ignoreCase = true)) {
                throw VerificationException("El archivo descargado no coincide con la huella de GitHub. Vuelve a descargarlo.")
            }
        }
        val manager = context.packageManager
        // Some Android builds only collect archive certificates when the legacy flag is also set.
        val flags = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val incoming = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw VerificationException("Android no pudo leer la APK descargada.")
        val installed = manager.getPackageInfo(context.packageName, flags)
        fun signers(info: PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null)
                ?.takeIf { it.isNotEmpty() }?.toSet() ?: info.signatures.orEmpty().toSet()
        val installedSigners = signers(installed)
        val incomingSigners = signers(incoming)
        if (incoming.packageName != installed.packageName) {
            throw VerificationException("La APK de GitHub pertenece a otro paquete de Android.")
        }
        if (installedSigners.isEmpty() || incomingSigners.isEmpty()) {
            throw VerificationException("Android no pudo leer los certificados de la APK o de la app instalada.")
        }
        if (incomingSigners != installedSigners) {
            throw VerificationException("La APK de GitHub está firmada con un certificado distinto al de la app instalada.")
        }
    }

    fun launch(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
