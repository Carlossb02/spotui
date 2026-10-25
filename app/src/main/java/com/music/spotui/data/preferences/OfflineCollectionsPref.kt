package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

data class OfflineCollection(
    val id: String,
    val name: String,
    val coverUri: String,
    val artists: String,
    val isPlaylist: Boolean,
    val songs: List<SongsModel>
)

object OfflineCollectionsPref {
    private const val PREF = "OfflineCollections"

    private fun SongsModel.toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("album", album)
        put("singer", singer)
        put("coverUri", coverUri)
        put("url", url)
        put("spotifyTrackId", spotifyTrackId)
        put("isLocalSpotifyTrack", isLocalSpotifyTrack)
        put("explicit", explicit)
        put("durationMs", durationMs)
        put("artistIds", artistIds)
    }

    private fun parseSongFromObj(o: JSONObject): SongsModel? = runCatching {
        SongsModel(
            id = o.getInt("id"),
            title = o.getString("title"),
            album = o.optString("album"),
            singer = o.getString("singer"),
            coverUri = o.optString("coverUri"),
            url = o.getString("url"),
            spotifyTrackId = o.optString("spotifyTrackId"),
            isLocalSpotifyTrack = o.optBoolean("isLocalSpotifyTrack", false),
            explicit = o.optBoolean("explicit", false),
            durationMs = o.optInt("durationMs", 0),
            artistIds = o.optString("artistIds", ""),
        )
    }.getOrNull()

    private fun OfflineCollection.toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("coverUri", coverUri)
        put("artists", artists)
        put("isPlaylist", isPlaylist)
        val songArray = JSONArray()
        songs.forEach { songArray.put(it.toJsonObject()) }
        put("songs", songArray)
    }.toString()

    private fun parseCollection(json: String): OfflineCollection? = runCatching {
        val o = JSONObject(json)
        val songArray = o.getJSONArray("songs")
        val songList = mutableListOf<SongsModel>()
        for (i in 0 until songArray.length()) {
            parseSongFromObj(songArray.getJSONObject(i))?.let { songList.add(it) }
        }
        OfflineCollection(
            id = o.getString("id"),
            name = o.getString("name"),
            coverUri = o.getString("coverUri"),
            artists = o.getString("artists"),
            isPlaylist = o.getBoolean("isPlaylist"),
            songs = songList
        )
    }.getOrNull()

    fun saveCollection(
        context: Context,
        id: String,
        name: String,
        coverUri: String,
        artists: String,
        isPlaylist: Boolean,
        songs: List<SongsModel>
    ) {
        val col = OfflineCollection(id, name, coverUri, artists, isPlaylist, songs)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(id, col.toJson())
            .apply()
    }

    fun getCollection(context: Context, id: String): OfflineCollection? {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(id, null) ?: return null
        return parseCollection(json)?.let { col ->
            val updatedSongs = col.songs.map { CustomMetadataPref.applyOverride(context, it) }
            col.copy(songs = updatedSongs)
        }
    }

    /** Cheap membership snapshot for the Library's Downloaded chip.
     * Avoid constructing song models, applying metadata or touching cover files. */
    fun getDownloadedCollectionKeys(context: Context, downloadedIds: Set<String>): Set<String> {
        if (downloadedIds.isEmpty()) return emptySet()
        val result = HashSet<String>()
        val values = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).all.values
        for (raw in values) {
            val collection = runCatching { JSONObject(raw as? String ?: return@runCatching null) }.getOrNull() ?: continue
            val songs = collection.optJSONArray("songs") ?: continue
            var found = false
            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                if (song.optString("id") in downloadedIds) { found = true; break }
            }
            if (found) {
                val id = collection.optString("id")
                val name = collection.optString("name")
                if (id.isNotBlank()) result.add("id:${com.music.spotui.data.api.Api.cleanId(id)}")
                if (name.isNotBlank()) result.add("name:${collection.optBoolean("isPlaylist")}:$name".lowercase())
            }
        }
        return result
    }

    fun getOfflineCollections(context: Context): List<OfflineCollection> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val allCollections = prefs.all.values.mapNotNull { (it as? String)?.let { json ->
            parseCollection(json)?.let { col ->
                val updatedSongs = col.songs.map { CustomMetadataPref.applyOverride(context, it) }
                col.copy(songs = updatedSongs)
            }
        } }
        // Filter: only return collections that have at least one downloaded song
        return allCollections.filter { col ->
            col.songs.any { song ->
                com.music.spotui.data.preferences.isDownloaded(context, song.id.toString())
            }
        }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
