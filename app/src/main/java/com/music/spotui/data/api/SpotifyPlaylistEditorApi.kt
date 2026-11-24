package com.music.spotui.data.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate OAuth grant for official playlist editing, scoped to the user's own lists. */
internal object SpotifyPlaylistEditorApi {
    const val REDIRECT_URI = "com.music.spotidroid.playlists://callback"
    private const val KEY_ALIAS = "spotify_playlist_oauth"
    private const val SCOPES = "playlist-modify-public playlist-modify-private playlist-read-private user-read-private ugc-image-upload"
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<AuthStatus>(AuthStatus.Idle)
    val status = mutableStatus.asStateFlow()

    sealed interface AuthStatus {
        data object Idle : AuthStatus
        data object Connecting : AuthStatus
        data object Connected : AuthStatus
        data class Failed(val message: String) : AuthStatus
    }

    data class Details(
        val name: String,
        val description: String,
        val public: Boolean?,
        val collaborative: Boolean,
    )

    private data class Pending(val clientId: String, val verifier: String, val state: String, val started: Long)
    private data class Token(val access: String, val refresh: String, val expiration: Long)
    private class SpotifyHttpError(val code: Int, message: String) : IllegalStateException(message)

    @Volatile private var pending: Pending? = null
    private var token: Token? = null

    private fun prefs(context: Context) = context.getSharedPreferences(KEY_ALIAS, Context.MODE_PRIVATE)
    fun clientId(context: Context): String = prefs(context).getString("client_id", "").orEmpty()
    fun prepareConnection() { mutableStatus.value = AuthStatus.Idle }

    fun connect(context: Context, clientId: String) {
        val id = clientId.trim()
        if (!id.matches(Regex("[a-fA-F0-9]{32}"))) {
            mutableStatus.value = AuthStatus.Failed("Introduce el Client ID de tu aplicación de Spotify.")
            return
        }
        val request = Pending(id, randomString(), randomString(), SystemClock.elapsedRealtime())
        pending = request
        val challenge = urlSafe(MessageDigest.getInstance("SHA-256").digest(request.verifier.toByteArray(Charsets.US_ASCII)))
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", request.state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
        mutableStatus.value = AuthStatus.Connecting
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: android.content.ActivityNotFoundException) {
            pending = null
            mutableStatus.value = AuthStatus.Failed("No hay un navegador disponible para conectar Spotify.")
        }
    }

    @Synchronized
    fun receiveRedirect(context: Context, uri: Uri) {
        if (uri.scheme != "com.music.spotidroid.playlists" || uri.host != "callback" || !uri.path.isNullOrEmpty()) return
        val request = pending ?: return
        if (uri.getQueryParameter("state") != request.state) return
        pending = null
        val code = uri.getQueryParameter("code")
        if (code.isNullOrEmpty() || SystemClock.elapsedRealtime() - request.started > 600_000L) {
            mutableStatus.value = AuthStatus.Failed("La autorización se canceló o caducó.")
            return
        }
        scope.launch {
            mutex.withLock {
                try {
                    val fresh = tokenRequest(
                        FormBody.Builder().add("grant_type", "authorization_code")
                            .add("client_id", request.clientId).add("code", code)
                            .add("redirect_uri", REDIRECT_URI).add("code_verifier", request.verifier)
                    )
                    saveGrant(context, request.clientId, fresh)
                    mutableStatus.value = AuthStatus.Connected
                } catch (_: Exception) {
                    mutableStatus.value = AuthStatus.Failed("Spotify rechazó la autorización. Comprueba el Client ID y la URI de retorno.")
                }
            }
        }
    }

    suspend fun details(context: Context, id: String, expectedOwner: String): Details = withContext(Dispatchers.IO) {
        checkedOwner(context, id, expectedOwner).second
    }

    suspend fun save(
        context: Context,
        id: String,
        expectedOwner: String,
        changes: JSONObject,
        cover: Uri?,
    ) = withContext(Dispatchers.IO) {
        val coverData = cover?.let { jpegBase64(context, it) }
        val (access, _) = checkedOwner(context, id, expectedOwner)
        if (changes.length() > 0) {
            api(access, "playlists/$id", changes.toString().toRequestBody("application/json".toMediaType()))
        }
        if (coverData != null) {
            try {
                api(access, "playlists/$id/images", coverData.toRequestBody("image/jpeg".toMediaType()))
            } catch (error: Exception) {
                if (changes.length() > 0) {
                    throw IllegalStateException("Datos guardados. Falló la portada: ${error.message}", error)
                }
                throw error
            }
        }
    }

    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank()

    private suspend fun checkedOwner(context: Context, id: String, expectedOwner: String): Pair<String, Details> {
        require(id.matches(Regex("[A-Za-z0-9]{22}")) && expectedOwner.isNotBlank()) { "No se ha comprobado el propietario de esta lista." }
        val auth = mutex.withLock { accessToken(context) }
        val account = JSONObject(api(auth, "me")).optString("id")
        val playlist = JSONObject(api(auth, "playlists/$id?fields=name,description,public,collaborative,owner(id)"))
        check(account == expectedOwner && playlist.optJSONObject("owner")?.optString("id") == account) {
            "Autoriza la cuenta de Spotify propietaria de esta lista."
        }
        return auth to Details(
            playlist.getString("name"),
            playlist.optString("description").takeUnless { playlist.isNull("description") }.orEmpty(),
            if (playlist.isNull("public")) null else playlist.getBoolean("public"),
            playlist.optBoolean("collaborative"),
        )
    }

    private fun accessToken(context: Context): String {
        token?.takeIf { it.expiration > SystemClock.elapsedRealtime() + 60_000 }?.let { return it.access }
        val clientId = clientId(context)
        val refresh = token?.refresh ?: readRefresh(context)
        check(clientId.isNotBlank() && !refresh.isNullOrBlank()) { "Conecta tu cuenta de Spotify para editar la lista." }
        val fresh = try {
            tokenRequest(FormBody.Builder().add("grant_type", "refresh_token")
                .add("client_id", clientId).add("refresh_token", refresh), refresh)
        } catch (error: SpotifyHttpError) {
            if (error.code == 400 || error.code == 401) {
                token = null
                prefs(context).edit().remove("refresh").apply()
                throw IllegalStateException("Vuelve a conectar tu cuenta de Spotify.", error)
            }
            throw error
        }
        saveGrant(context, clientId, fresh)
        return fresh.access
    }

    private fun tokenRequest(form: FormBody.Builder, oldRefresh: String = ""): Token {
        val json = JSONObject(execute(Request.Builder().url("https://accounts.spotify.com/api/token").post(form.build()).build()))
        val refresh = json.optString("refresh_token", oldRefresh)
        check(refresh.isNotBlank()) { "Spotify no devolvió la autorización de edición." }
        return Token(json.getString("access_token"), refresh, SystemClock.elapsedRealtime() + 1_000L * json.getLong("expires_in"))
    }

    private fun api(token: String, path: String, body: RequestBody? = null): String {
        val request = Request.Builder().url("https://api.spotify.com/v1/$path")
            .header("Authorization", "Bearer $token")
        if (body != null) request.put(body)
        return execute(request.build())
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw SpotifyHttpError(response.code, when (response.code) {
            401 -> "La autorización de Spotify caducó."
            403 -> "Spotify denegó el permiso. Comprueba el acceso de tu cuenta a la aplicación en el panel de Spotify."
            429 -> "Spotify ha limitado las solicitudes. Inténtalo más tarde."
            else -> "Spotify devolvió un error (${response.code})."
        })
        response.body?.string().orEmpty()
    }

    private fun randomString() = urlSafe(ByteArray(32).also(random::nextBytes))
    private fun urlSafe(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
    }

    private fun saveGrant(context: Context, clientId: String, fresh: Token) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = urlSafe(cipher.iv + cipher.doFinal(fresh.refresh.toByteArray(Charsets.UTF_8)))
        prefs(context).edit().putString("client_id", clientId).putString("refresh", encoded).apply()
        token = fresh
    }

    private fun readRefresh(context: Context): String? = runCatching {
        val encoded = prefs(context).getString("refresh", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.URL_SAFE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun jpegBase64(context: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "No se pudo abrir la imagen." }
        val options = BitmapFactory.Options()
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1024) {
            options.inSampleSize = maxOf(options.inSampleSize, 1) * 2
        }
        val bitmap = requireNotNull(context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }) { "No se pudo leer la imagen." }
        try {
            for (quality in 90 downTo 30 step 10) {
                val jpeg = ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg) && jpeg.size() <= 192_000) {
                    return Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
                }
            }
            error("La imagen supera el tamaño que admite Spotify. Elige otra portada.")
        } finally {
            bitmap.recycle()
        }
    }
}

class SpotifyPlaylistOAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.let { SpotifyPlaylistEditorApi.receiveRedirect(applicationContext, it) }
        finish()
    }
}
