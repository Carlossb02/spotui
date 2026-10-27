package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

/** Locally added YouTube tracks in Spotify playlists. Never sent to Spotify. */
object PlaylistAdditionsPref {
    private const val PREF = "playlist_youtube_additions_v1"

    fun tracks(context: Context, playlistId: String): List<SongsModel> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(playlistId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val url = obj.optString("url")
                if (!url.startsWith("yt_local:")) null else SongsModel(
                    id = url.hashCode() and 0x7fffffff,
                    title = obj.optString("title"),
                    album = obj.optString("album"),
                    singer = obj.optString("artist"),
                    coverUri = obj.optString("cover"),
                    url = url,
                )
            }.map { CustomMetadataPref.applyOverride(context, it) }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, playlistId: String, song: SongsModel) {
        val merged = tracks(context, playlistId).filterNot { it.url == song.url } + song
        write(context, playlistId, merged)
    }

    fun remove(context: Context, playlistId: String, url: String) {
        write(context, playlistId, tracks(context, playlistId).filterNot { it.url == url })
    }

    fun updateSong(context: Context, song: SongsModel, title: String, artist: String, album: String, cover: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.all.keys.forEach { id ->
            val existing = tracks(context, id)
            if (existing.any { it.url == song.url }) {
                write(context, id, existing.map {
                    if (it.url == song.url) it.copy(title = title, singer = artist, album = album, coverUri = cover) else it
                })
            }
        }
    }

    private fun write(context: Context, playlistId: String, songs: List<SongsModel>) {
        val arr = JSONArray()
        songs.forEach { song ->
            arr.put(JSONObject().apply {
                put("url", song.url)
                put("title", song.title)
                put("artist", song.singer)
                put("album", song.album)
                put("cover", song.coverUri)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(playlistId, arr.toString()).apply()
    }
}
