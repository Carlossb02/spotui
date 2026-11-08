package com.music.spotui.data.api

import android.content.Context
import com.music.spotui.data.entity.SavedPlaylist
import org.json.JSONArray
import org.json.JSONObject

internal data class CachedMembershipEdit(val saved: Boolean, val version: Long, val createdAt: Long)
internal data class CachedPlaylistSnapshot(val playlists: List<SavedPlaylist>, val edits: Map<String, Map<String, CachedMembershipEdit>>)

/** Private read-through cache. Cookies are never written: accounts use a SHA-256 cache key. */
internal object SavedPlaylistCache {
    private fun key(account: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
    fun read(context: Context, account: String): CachedPlaylistSnapshot = runCatching {
        val raw = context.getSharedPreferences("playlist_membership_cache_v1", Context.MODE_PRIVATE).getString(key(account), null)
            ?: return@runCatching CachedPlaylistSnapshot(emptyList(), emptyMap())
        val json = JSONObject(raw)
        val lists = json.getJSONArray("playlists")
        val playlists = (0 until lists.length()).map { i ->
            val item = lists.getJSONObject(i)
            val ids = item.getJSONArray("tracks")
            SavedPlaylist(item.getString("id"), item.getString("name"), item.optString("cover"),
                trackIds = (0 until ids.length()).map { ids.getString(it) }.toSet(), checked = true)
        }
        val savedEdits = json.optJSONArray("edits") ?: JSONArray()
        val edits = mutableMapOf<String, MutableMap<String, CachedMembershipEdit>>()
        for (i in 0 until savedEdits.length()) {
            val e = savedEdits.getJSONObject(i)
            edits.getOrPut(e.getString("playlist")) { mutableMapOf() }[e.getString("track")] =
                CachedMembershipEdit(e.getBoolean("saved"), e.getLong("version"), e.getLong("created"))
        }
        CachedPlaylistSnapshot(playlists, edits)
    }.getOrDefault(CachedPlaylistSnapshot(emptyList(), emptyMap()))

    fun write(context: Context, account: String, snapshot: CachedPlaylistSnapshot) {
        val lists = JSONArray()
        snapshot.playlists.filter { !it.local && it.checked }.forEach { list ->
            lists.put(JSONObject().put("id", list.id).put("name", list.name).put("cover", list.coverUri)
                .put("tracks", JSONArray(list.trackIds.toList())))
        }
        val edits = JSONArray()
        snapshot.edits.forEach { (playlist, tracks) -> tracks.forEach { (track, e) ->
            edits.put(JSONObject().put("playlist", playlist).put("track", track).put("saved", e.saved)
                .put("version", e.version).put("created", e.createdAt))
        } }
        context.getSharedPreferences("playlist_membership_cache_v1", Context.MODE_PRIVATE).edit()
            .putString(key(account), JSONObject().put("playlists", lists).put("edits", edits).toString()).apply()
    }
}
