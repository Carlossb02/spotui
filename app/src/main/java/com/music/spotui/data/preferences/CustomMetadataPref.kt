package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject

/**
 * Stores user-edited metadata overrides (title, singer, album, coverUri)
 * for tracks, keyed by their track ID / Spotify ID / URI.
 */
object CustomMetadataPref {
    private const val PREF = "CustomMetadata"
    private const val KEY = "overrides_json"
    private const val LOCAL_EDITED_KEYS = "verified_local_edits"

    /** Records are added only after an explicit local-origin check by the editor. */
    fun markVerifiedLocalEdit(context: Context, song: SongsModel) {
        val keys = getSongKeys(song)
        if (keys.isEmpty()) return
        val values = prefs(context).getStringSet(LOCAL_EDITED_KEYS, emptySet()).orEmpty().toMutableSet()
        values.addAll(keys)
        prefs(context).edit().putStringSet(LOCAL_EDITED_KEYS, values).apply()
    }

    fun wasVerifiedLocalEdit(context: Context, song: SongsModel): Boolean {
        val keys = prefs(context).getStringSet(LOCAL_EDITED_KEYS, emptySet()).orEmpty()
        return getSongKeys(song).any { it in keys }
    }

    /** Migration for edits saved by older versions of the app. */
    fun hasExistingEdit(context: Context, song: SongsModel): Boolean =
        getOverride(context, song) != null

    data class TrackMetadataOverride(
        val title: String,
        val singer: String,
        val album: String,
        val coverUri: String,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun saveOverride(
        context: Context,
        song: SongsModel,
        title: String,
        singer: String,
        album: String,
        coverUri: String,
    ) {
        val keys = getSongKeys(song)
        if (keys.isEmpty()) return
        val current = getAllOverrides(context).toMutableMap()
        val retainedCover=coverUri.trim().ifBlank { keys.firstNotNullOfOrNull { current[it]?.coverUri?.takeIf(String::isNotBlank) } ?: song.coverUri }
        val override = TrackMetadataOverride(title, singer, album, retainedCover)
        keys.forEach { current[it] = override }
        saveAll(context, current)
    }

    fun getOverride(context: Context, song: SongsModel): TrackMetadataOverride? {
        val current = getAllOverrides(context)
        getSongKeys(song).forEach { key ->
            current[key]?.let { return it }
        }
        return null
    }

    fun applyOverride(context: Context, song: SongsModel): SongsModel {
        val override = getOverride(context, song) ?: return song
        return song.copy(
            title = override.title.ifBlank { song.title },
            singer = override.singer.ifBlank { song.singer },
            album = override.album.ifBlank { song.album },
            coverUri = override.coverUri.ifBlank { song.coverUri }
        )
    }

    /** Apply the current edits to a whole memory snapshot with one preferences parse. */
    fun applyOverrides(context: Context, songs: List<SongsModel>): List<SongsModel> {
        val overrides = getAllOverrides(context)
        if (overrides.isEmpty()) return songs
        return songs.map { song ->
            val edit = getSongKeys(song).firstNotNullOfOrNull { overrides[it] } ?: return@map song
            val changed = song.copy(title = edit.title.ifBlank { song.title }, singer = edit.singer.ifBlank { song.singer },
                album = edit.album.ifBlank { song.album }, coverUri = edit.coverUri.ifBlank { song.coverUri })
            if ((changed.title != song.title || changed.singer != song.singer) && song.url.startsWith("spotify:track:"))
                changed.copy(url = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(song.spotifyTrackId, changed.title, changed.singer))
            else changed
        }
    }

    private fun getSongKeys(song: SongsModel): List<String> {
        val keys = mutableListOf<String>()
        if (song.id != 0 && song.id != -1) keys.add("id:${song.id}")
        if (song.spotifyTrackId.isNotBlank()) {
            val clean = song.spotifyTrackId.removePrefix("spotify:track:").removePrefix("track:").substringBefore("?").trim()
            if (clean.isNotBlank()) keys.add("spotify:$clean")
        }
        if (song.url.isNotBlank()) keys.add("url:${song.url}")
        return keys
    }

    private data class Memory(val prefs: android.content.SharedPreferences, val raw: String?, val values: Map<String, TrackMetadataOverride>)
    @Volatile private var memory: Memory? = null
    @Synchronized fun getAllOverrides(context: Context): Map<String, TrackMetadataOverride> {
        val store = prefs(context)
        val raw = store.getString(KEY, null)
        memory?.takeIf { it.prefs === store && it.raw == raw }?.let { return it.values }
        val loaded = if (raw == null) emptyMap() else runCatching {
            val obj = JSONObject(raw)
            val result = mutableMapOf<String, TrackMetadataOverride>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val item = obj.optJSONObject(k)
                if (item != null) {
                    result[k] = TrackMetadataOverride(
                        title = item.optString("title"),
                        singer = item.optString("singer"),
                        album = item.optString("album"),
                        coverUri = item.optString("coverUri"),
                    )
                }
            }
            result.toMap()
        }.getOrDefault(emptyMap())
        memory = Memory(store, raw, loaded)
        return loaded
    }

    fun restoreOverrides(context: Context, map: Map<String, TrackMetadataOverride>) {
        val current = getAllOverrides(context).toMutableMap()
        current.putAll(map)
        saveAll(context, current)
    }

    private fun saveAll(context: Context, map: Map<String, TrackMetadataOverride>) {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, JSONObject().apply {
                put("title", v.title)
                put("singer", v.singer)
                put("album", v.album)
                put("coverUri", v.coverUri)
            })
        }
        prefs(context).edit().putString(KEY, obj.toString()).apply()
    }
}
