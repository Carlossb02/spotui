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
    private fun prefs(context: Context) = context.getSharedPreferences("library_catalog_v1", Context.MODE_PRIVATE)

    fun removedPlaylistIds(context: Context, session: String = SpotifySession.spDc(context)): Set<String> =
        prefs(context).getStringSet("removed:" + key(session), emptySet()).orEmpty()
    @Synchronized fun peek(context: Context): List<LibraryEntry>? =
        memory.takeIf { account == SpotifySession.spDc(context) }

    @Synchronized fun read(context: Context): List<LibraryEntry> {
        val session = SpotifySession.spDc(context)
        if (account == session && memory != null) return memory.orEmpty()
        account = session
        val raw = prefs(context).getString(key(session), null)
        memory = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).map { index ->
                val o = a.getJSONObject(index)
                LibraryEntry(o.getString("id"), o.getString("name"), o.optString("subtitle"), o.optString("cover"), o.getBoolean("playlist"), o.optString("artists"))
            }
        }.getOrDefault(emptyList())
        if (raw == null && memory.isNullOrEmpty()) {
            val removed = removedPlaylistIds(context, session)
            memory = SavedPlaylistCache.read(context, session).playlists.filterNot { it.id in removed }.map {
                LibraryEntry(it.id, it.name, "Playlist", it.coverUri, true)
            }
        }
        return memory.orEmpty()
    }
    @Synchronized fun replace(context: Context, expectedAccount: String, entries: List<LibraryEntry>) {
        if (SpotifySession.spDc(context) != expectedAccount) return
        account = expectedAccount
        val pendingAlbums = SpotifyPendingWrites.entries(context).filter { it.kind == "album" }.associateBy { it.target }
        val retained = memory.orEmpty().filter { !it.isPlaylist && pendingAlbums[it.spotifyId]?.saved == true }
        memory = (entries + retained).filterNot { it.isLocal || it.isPodcast || (!it.isPlaylist && pendingAlbums[it.spotifyId]?.saved == false) }
            .distinctBy { it.isPlaylist to it.spotifyId }
        val a = JSONArray()
        memory.orEmpty().forEach { e -> a.put(JSONObject().put("id", e.spotifyId).put("name", e.name)
            .put("subtitle", e.subtitle).put("cover", e.coverUri).put("playlist", e.isPlaylist).put("artists", e.artists)) }
        prefs(context).edit().putString(key(expectedAccount), a.toString()).apply()
    }
    fun playlistEntries(playlists: List<SpotifyPlaylist>) = playlists.map { p ->
        LibraryEntry(SpotifySync.cleanPlaylistId(p.id), p.name,
            "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""), p.images.firstOrNull()?.url.orEmpty(), true)
    }
    @Synchronized fun playlistsLoaded(context: Context, expectedAccount: String, playlists: List<SpotifyPlaylist>) {
        if (SpotifySession.spDc(context) != expectedAccount) return
        val previous = read(context)
        val present = playlists.map { SpotifySync.cleanPlaylistId(it.id) }.toSet()
        val previousRemoved = removedPlaylistIds(context, expectedAccount)
        val removed = (previousRemoved +
            previous.filter { it.isPlaylist }.map { it.spotifyId }) - present
        if (removed != previousRemoved) {
            prefs(context).edit().putStringSet("removed:" + key(expectedAccount), removed).apply()
        }
        replace(context, expectedAccount, playlistEntries(playlists) + previous.filterNot { it.isPlaylist })
        if (removed != previousRemoved) com.music.spotui.sync.OwnLibraryChanges.changed()
    }

    @Synchronized fun playlistRemovalConfirmed(context: Context, expectedAccount: String, id: String) {
        if (SpotifySession.spDc(context) != expectedAccount) return
        val remaining = read(context).filterNot { it.isPlaylist && it.spotifyId == id }
        prefs(context).edit().putStringSet("removed:" + key(expectedAccount),
            removedPlaylistIds(context, expectedAccount) + id).apply()
        replace(context, expectedAccount, remaining)
    }

    private fun key(account: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
}
