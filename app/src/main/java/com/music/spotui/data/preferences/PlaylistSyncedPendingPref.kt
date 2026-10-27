package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

/** Tracks confirmed added to Spotify while its playlist feed/cache catches up.
 * Never records an unconfirmed remote mutation. Entries are only a read-through
 * overlay for the UI and are not considered local/offline-only tracks.
 */
object PlaylistSyncedPendingPref {
    private const val FILE = "spotify_playlist_recently_synced_v1"

    fun tracks(context: Context, playlistId: String): List<SongsModel> = runCatching {
        val key = SpotifySync.cleanPlaylistId(playlistId)
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val list = JSONArray(raw)
        (0 until list.length()).mapNotNull { i ->
            val obj = list.getJSONObject(i)
            val trackId = SpotifySync.cleanTrackId(obj.optString("spotifyId"))
            if (trackId.isBlank()) null else SongsModel(
                id = obj.optInt("id"), title = obj.optString("title"),
                album = obj.optString("album"), singer = obj.optString("artist"),
                coverUri = obj.optString("cover"), url = obj.optString("url"),
                spotifyTrackId = trackId,
            )
        }
    }.getOrDefault(emptyList())

    fun contains(context: Context, playlistId: String, trackId: String): Boolean {
        val id = SpotifySync.cleanTrackId(trackId)
        return id.isNotBlank() && tracks(context, playlistId).any { it.spotifyTrackId == id }
    }

    fun addConfirmed(context: Context, playlistId: String, song: SongsModel) {
        val id = SpotifySync.cleanTrackId(song.spotifyTrackId)
        if (id.isBlank()) return
        write(context, playlistId, tracks(context, playlistId).filterNot { it.spotifyTrackId == id } +
            song.copy(spotifyTrackId = id))
    }

    fun remove(context: Context, playlistId: String, trackId: String) {
        val id = SpotifySync.cleanTrackId(trackId)
        write(context, playlistId, tracks(context, playlistId).filterNot { it.spotifyTrackId == id })
    }

    private fun write(context: Context, playlistId: String, songs: List<SongsModel>) {
        val json = JSONArray()
        songs.forEach { song ->
            json.put(JSONObject().apply {
                put("spotifyId", song.spotifyTrackId)
                put("id", song.id)
                put("title", song.title)
                put("album", song.album)
                put("artist", song.singer)
                put("cover", song.coverUri)
                put("url", song.url)
            })
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(SpotifySync.cleanPlaylistId(playlistId), json.toString()).apply()
    }
}
