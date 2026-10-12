package com.music.spotui.ui.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Cache en memoria de carátulas ya decodificadas, listas para publicarse en la
 * sesión como `artworkData` (bytes JPEG). Android Auto pinta el panel "reproduciendo
 * ahora" del modo navegador grande a partir de este bitmap, sin depender de que
 * pueda resolver una URI (http/file) por su cuenta.
 *
 * - Carga fuera del hilo principal.
 * - Reduce a [MAX_PX] para no pasarse del límite de las transacciones Binder.
 * - Backoff tras un fallo para no martillear la red.
 */
object PlaybackArtwork {

    private const val MAX_PX = 512
    private const val JPEG_QUALITY = 88
    private const val MAX_DOWNLOAD_BYTES = 6 * 1024 * 1024
    private const val RETRY_AFTER_MS = 20_000L

    private val cache = object : LruCache<String, ByteArray>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun get(id: String): ByteArray? = cache.get(id)

    /**
     * Garantiza que la carátula de [id] esté en caché. [onReady] se invoca en el hilo
     * principal solo si hubo que cargarla (si ya estaba en caché no se llama).
     */
    fun ensure(context: Context, id: String, coverUri: String, onReady: () -> Unit = {}) {
        if (coverUri.isBlank() || cache.get(id) != null) return
        val failed = failedAt[id]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return
        if (!inflight.add(id)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                val bytes = load(appContext, coverUri)
                if (bytes != null) {
                    cache.put(id, bytes)
                    val cleanId = id.removePrefix("song/").removePrefix("playlist/").removePrefix("album/").removePrefix("artist/").trim()
                    if (cleanId.isNotBlank()) {
                        val dir = File(appContext.filesDir, "downloads").apply { mkdirs() }
                        val coverFile = File(dir, "${cleanId}_cover.jpg")
                        if (!coverFile.exists() || coverFile.length() == 0L) {
                            runCatching { coverFile.writeBytes(bytes) }
                        }
                    }
                    failedAt.remove(id)
                    main.post(onReady)
                } else {
                    failedAt[id] = System.currentTimeMillis()
                }
            } catch (e: Throwable) {
                android.util.Log.w("PlaybackArtwork", "No se pudo cargar carátula de $id", e)
                failedAt[id] = System.currentTimeMillis()
            } finally {
                inflight.remove(id)
            }
        }
    }

    private fun load(context: Context, coverUri: String): ByteArray? {
        val raw = readBytes(context, coverUri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX && bounds.outHeight / (sample * 2) >= MAX_PX) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val scaled = if (maxOf(decoded.width, decoded.height) > MAX_PX) {
            val ratio = MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun readBytes(context: Context, coverUri: String): ByteArray? {
        return when {
            coverUri.startsWith("http://") || coverUri.startsWith("https://") -> {
                val conn = (URL(coverUri).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) return null
                    conn.inputStream.use { it.readLimited() }
                } finally {
                    conn.disconnect()
                }
            }

            coverUri.startsWith("/") -> File(coverUri).takeIf { it.exists() }?.inputStream()?.use { it.readLimited() }

            else -> context.contentResolver.openInputStream(Uri.parse(coverUri))?.use { it.readLimited() }
        }
    }

    private fun java.io.InputStream.readLimited(): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_DOWNLOAD_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
