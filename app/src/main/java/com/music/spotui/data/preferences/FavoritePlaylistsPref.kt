package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.api.Api
import com.music.spotui.data.entity.LibraryEntry
import org.json.JSONArray
import org.json.JSONObject

/** A locally saved shortcut to a Spotify playlist owned by someone else.
 * This preference does NOT follow the playlist in Spotify or grant editing rights.
 */
object FavoritePlaylistsPref {
    private const val FILE = "favorite_external_playlists_v1"
    private const val KEY = "saved_playlists"

    private fun idOf(id: String) = Api.cleanId(id)

    fun entries(context: Context): List<LibraryEntry> = runCatching {
        val json = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]")
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = idOf(o.optString("id"))
            if (id.isBlank()) null else LibraryEntry(
                spotifyId = id,
                name = o.optString("name").ifBlank { "Playlist" },
                subtitle = o.optString("subtitle").ifBlank { "Playlist guardada" },
                coverUri = o.optString("cover"),
                isPlaylist = true,
            )
        }.distinctBy { idOf(it.spotifyId) }
    }.getOrElse { emptyList() }

    /** Backup of the user's saved shortcuts. No Spotify write permissions required. */
    fun exportBackup(context: Context): JSONArray = JSONArray().also { result ->
        entries(context).forEach { entry ->
            result.put(JSONObject().apply {
                put("id", entry.spotifyId)
                put("name", entry.name)
                put("subtitle", entry.subtitle)
                put("cover", entry.coverUri)
            })
        }
    }

    /** Restore only when a v2+ backup explicitly contains this field. */
    @Synchronized
    fun restoreBackup(context: Context, saved: JSONArray): Int {
        val seen = HashSet<String>()
        val filtered = JSONArray()
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            val id = idOf(item.optString("id"))
            if (id.isBlank() || id.startsWith("local_pl_") || !seen.add(id)) continue
            filtered.put(JSONObject().apply {
                put("id", id)
                put("name", item.optString("name").ifBlank { "Playlist" })
                put("subtitle", item.optString("subtitle").ifBlank { "Playlist guardada" })
                put("cover", item.optString("cover"))
            })
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, filtered.toString()).commit()
        Api.HomeCache.library = null
        return filtered.length()
    }

    fun isFavorite(context: Context, id: String): Boolean = entries(context).any {
        idOf(it.spotifyId) == idOf(id)
    }

    /** Return true when added, false when removed. */
    @Synchronized
    fun toggle(context: Context, id: String, name: String, cover: String, owner: String): Boolean {
        val key = idOf(id)
        if (key.isBlank() || key.startsWith("local_pl_")) return false
        val existing = entries(context)
        val wasSaved = existing.any { idOf(it.spotifyId) == key }
        val updated = if (wasSaved) existing.filterNot { idOf(it.spotifyId) == key } else {
            listOf(LibraryEntry(
                spotifyId = key,
                name = name.ifBlank { "Playlist" },
                subtitle = "Playlist" + owner.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                coverUri = cover,
                isPlaylist = true,
            )) + existing
        }
        val json = JSONArray().apply {
            updated.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.spotifyId)
                    put("name", entry.name)
                    put("subtitle", entry.subtitle)
                    put("cover", entry.coverUri)
                })
            }
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
        Api.HomeCache.library = null
        com.music.spotui.util.BackupHelper.scheduleAutoBackup(context)
        return !wasSaved
    }
}
