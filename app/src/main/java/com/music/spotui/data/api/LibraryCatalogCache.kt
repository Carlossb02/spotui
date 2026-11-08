package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.models.SpotifyPlaylist
import com.music.spotui.data.entity.LibraryEntry
import org.json.JSONArray
import org.json.JSONObject

/** Catalog metadata shared by Library and the membership scan, scoped to the Spotify account. */
internal object LibraryCatalogCache {
    private var account: String? = null
    private var memory: List<LibraryEntry>? = null
    @Synchronized fun peek(context: Context): List<LibraryEntry>? =
        memory.takeIf { account == SpotifySession.spDc(context) }

    @Synchronized fun read(context: Context): List<LibraryEntry> {
        val session = SpotifySession.spDc(context)
        if (account == session && memory != null) return memory.orEmpty()
        account = session
        memory = runCatching {
            val raw = context.getSharedPreferences("library_catalog_v1", Context.MODE_PRIVATE).getString(key(session), null)
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { index ->
                val o = a.getJSONObject(index)
                LibraryEntry(o.getString("id"), o.getString("name"), o.optString("subtitle"), o.optString("cover"), o.getBoolean("playlist"), o.optString("artists"))
            }
        }.getOrDefault(emptyList())
        if (memory.isNullOrEmpty()) {
            memory = SavedPlaylistCache.read(context, session).playlists.map {
                LibraryEntry(it.id, it.name, "Playlist", it.coverUri, true)
            }
        }
        return memory.orEmpty()
    }
    @Synchronized fun replace(context: Context, expectedAccount: String, entries: List<LibraryEntry>) {
        if (SpotifySession.spDc(context) != expectedAccount) return
        account = expectedAccount
        memory = entries.filterNot { it.isLocal || it.isPodcast }.distinctBy { it.isPlaylist to it.spotifyId }
        val a = JSONArray()
        memory.orEmpty().forEach { e -> a.put(JSONObject().put("id", e.spotifyId).put("name", e.name)
            .put("subtitle", e.subtitle).put("cover", e.coverUri).put("playlist", e.isPlaylist).put("artists", e.artists)) }
        context.getSharedPreferences("library_catalog_v1", Context.MODE_PRIVATE).edit().putString(key(expectedAccount), a.toString()).apply()
    }
    fun playlistEntries(playlists: List<SpotifyPlaylist>) = playlists.map { p ->
        LibraryEntry(SpotifySync.cleanPlaylistId(p.id), p.name,
            "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""), p.images.firstOrNull()?.url.orEmpty(), true)
    }
    @Synchronized fun playlistsLoaded(context: Context, expectedAccount: String, playlists: List<SpotifyPlaylist>) {
        if (SpotifySession.spDc(context) != expectedAccount) return
        val albums = read(context).filterNot { it.isPlaylist }
        replace(context, expectedAccount, playlistEntries(playlists) + albums)
    }
    private fun key(account: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
}
