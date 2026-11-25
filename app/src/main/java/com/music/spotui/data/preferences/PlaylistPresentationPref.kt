package com.music.spotui.data.preferences

import android.content.Context
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


}
