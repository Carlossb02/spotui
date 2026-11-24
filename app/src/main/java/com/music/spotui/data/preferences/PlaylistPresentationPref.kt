package com.music.spotui.data.preferences

import android.content.Context
import android.net.Uri
import java.io.File
import com.music.spotui.data.api.SpotifySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.security.MessageDigest

/** Presentation overrides stored on this device. */
internal object PlaylistPresentationPref {
    data class Presentation(
        val alias: String = "",
        val hiddenAs: String = "",
        val description: String? = null,
        val coverUri: String = "",
    )

    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun accountPrefix(context: Context): String {
        val session = SpotifySession.spDc(context)
        return MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it) } + ":"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("playlist_presentation", Context.MODE_PRIVATE)

    private fun decode(raw: String): Presentation = runCatching {
        val row = JSONObject(raw)
        Presentation(
            alias = row.optString("alias"),
            hiddenAs = row.optString("hiddenAs"),
            description = if (row.has("description")) row.optString("description") else null,
            coverUri = row.optString("coverUri"),
        )
    }.getOrDefault(Presentation())

    fun entries(context: Context): Map<String, Presentation> {
        val prefix = accountPrefix(context)
        return prefs(context).all.mapNotNull { (key, raw) ->
            if (raw !is String) null
            else when {
                key.startsWith(prefix) -> key.removePrefix(prefix) to decode(raw)
                key.startsWith("local:") -> key.removePrefix("local:") to decode(raw)
                else -> null
            }
        }.toMap()
    }

    private fun key(context: Context, playlistId: String): String =
        (if (playlistId.startsWith("local_pl_")) "local:" else accountPrefix(context)) + playlistId

    @Synchronized
    fun update(context: Context, playlistId: String, change: (Presentation) -> Presentation) {
        val key = key(context, playlistId)
        val store = prefs(context)
        val previous = store.getString(key, null)?.let(::decode) ?: Presentation()
        val next = change(previous)
        if (previous == next) return
        val editor = store.edit()
        if (next == Presentation()) editor.remove(key)
        else editor.putString(key, JSONObject().put("alias", next.alias).put("hiddenAs", next.hiddenAs)
            .put("description", next.description).put("coverUri", next.coverUri).toString())
        editor.apply()
        changes.update { it + 1 }
    }

    /** Save a private copy because a picked image can live in a cloud provider. Call on IO. */
    fun saveDetails(
        context: Context, playlistId: String, description: String?,
        pickedCover: Uri?, resetCover: Boolean,
    ) {
        val folder = File(context.filesDir, "playlist_covers")
        val storeKey = key(context, playlistId)
        var newFile: File? = null
        try {
            if (pickedCover != null) {
                check(folder.isDirectory || folder.mkdirs()) { "No se pudo guardar la portada." }
                val target = File.createTempFile("cover_", ".image", folder)
                newFile = target
                val resolver = context.contentResolver
                check(resolver.getType(pickedCover)?.startsWith("image/") != false) { "Selecciona un archivo de imagen." }
                val input = resolver.openInputStream(pickedCover) ?: error("No se pudo abrir la imagen.")
                input.use { stream ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var size = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            size += count
                            check(size <= 16L * 1024 * 1024) { "La portada supera los 16 MB." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(target.path, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "El archivo no contiene una imagen válida." }
            }
            val obsoleteCover = synchronized(this) {
                check(key(context, playlistId) == storeKey) { "La cuenta de Spotify ha cambiado." }
                val previous = prefs(context).getString(storeKey, null)?.let(::decode) ?: Presentation()
                val nextCover = newFile?.let { Uri.fromFile(it).toString() }
                    ?: if (resetCover) "" else previous.coverUri
                update(context, playlistId) { it.copy(description = description, coverUri = nextCover) }
                previous.coverUri.takeIf { it != nextCover }
            }
            val previousPath = obsoleteCover?.takeIf { it.isNotBlank() }?.let { Uri.parse(it).path }
            if (previousPath != null) {
                val candidate = File(previousPath)
                if (candidate.parentFile == folder) runCatching { candidate.delete() }
            }
        } catch (failure: Exception) {
            newFile?.delete()
            throw failure
        }
    }

}
