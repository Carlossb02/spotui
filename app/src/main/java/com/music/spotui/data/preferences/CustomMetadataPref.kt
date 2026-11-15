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
        val updatedAt:Long=0,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized fun saveOverride(
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
        val retainedCover=coverUri.trim()
        val override = TrackMetadataOverride(title, singer, album, retainedCover,System.currentTimeMillis())
        keys.forEach { current[it] = override }
        saveAll(context, current)
    }

    fun getOverride(context: Context, song: SongsModel): TrackMetadataOverride? {
        val current = getAllOverrides(context)
        return preferredMetadataOverride(getSongKeys(song),current)
    }

    fun applyOverride(context: Context, song: SongsModel): SongsModel {
        val override = getOverride(context, song) ?: return song
        return applyMetadataEdit(song, override)
    }

    /** Apply the current edits to a whole memory snapshot with one preferences parse. */
    fun applyOverrides(context: Context, songs: List<SongsModel>): List<SongsModel> {
        val overrides = getAllOverrides(context)
        if (overrides.isEmpty()) return songs
        return songs.map { song ->
            val edit = preferredMetadataOverride(getSongKeys(song),overrides) ?: return@map song
            val changed = applyMetadataEdit(song, edit)
            if ((changed.title != song.title || changed.singer != song.singer) && song.url.startsWith("spotify:track:"))
                changed.copy(url = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(song.spotifyTrackId, changed.title, changed.singer))
            else changed
        }
    }

    private fun getSongKeys(song: SongsModel): List<String> {
        val keys = mutableListOf<String>()
        if (song.spotifyTrackId.isNotBlank()) {
            val clean = song.spotifyTrackId.removePrefix("spotify:track:").removePrefix("track:").substringBefore("?").trim()
            if (clean.isNotBlank()) keys.add("spotify:$clean")
        }
        if (song.id != 0 && song.id != -1) keys.add("id:${song.id}")
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
                        updatedAt=item.optLong("updatedAt",0),
                    )
                }
            }
            result.toMap()
        }.getOrDefault(emptyMap())
        memory = Memory(store, raw, loaded)
        return loaded
    }

    @Synchronized fun restoreOverrides(context: Context, map: Map<String, TrackMetadataOverride>) {
        val current = getAllOverrides(context).toMutableMap()
        map.forEach { (key, value) ->
            if (value.updatedAt >= (current[key]?.updatedAt ?: Long.MIN_VALUE)) current[key] = value
        }
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
                put("updatedAt",v.updatedAt)
            })
        }
        check(prefs(context).edit().putString(KEY, obj.toString()).commit()) {"Could not save metadata"}
        com.music.spotui.sync.OwnLibraryChanges.changed()
    }
}

internal fun preferredMetadataOverride(keys:List<String>,values:Map<String,CustomMetadataPref.TrackMetadataOverride>)=
    keys.mapNotNull {values[it]}.maxByOrNull {it.updatedAt}

internal fun applyMetadataEdit(song: SongsModel, edit: CustomMetadataPref.TrackMetadataOverride) = song.copy(
    title = edit.title.ifBlank { song.title }, singer = edit.singer.ifBlank { song.singer },
    album = edit.album, coverUri = edit.coverUri)
