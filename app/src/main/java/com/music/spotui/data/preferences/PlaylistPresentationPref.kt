package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.api.SpotifySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.security.MessageDigest

/** Personal labels and visibility for Spotify lists. No edits are sent to Spotify. */
internal object PlaylistPresentationPref {
    data class Presentation(val alias: String = "", val hiddenAs: String = "")

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
        Presentation(row.optString("alias"), row.optString("hiddenAs"))
    }.getOrDefault(Presentation())

    fun entries(context: Context): Map<String, Presentation> {
        val prefix = accountPrefix(context)
        return prefs(context).all.mapNotNull { (key, raw) ->
            if (key.startsWith(prefix) && raw is String) key.removePrefix(prefix) to decode(raw)
            else null
        }.toMap()
    }

    @Synchronized
    fun update(context: Context, spotifyId: String, change: (Presentation) -> Presentation) {
        val key = accountPrefix(context) + spotifyId
        val store = prefs(context)
        val previous = store.getString(key, null)?.let(::decode) ?: Presentation()
        val next = change(previous)
        if (previous == next) return
        val editor = store.edit()
        if (next == Presentation()) editor.remove(key)
        else editor.putString(key, JSONObject().put("alias", next.alias).put("hiddenAs", next.hiddenAs).toString())
        editor.apply()
        changes.update { it + 1 }
    }
}
