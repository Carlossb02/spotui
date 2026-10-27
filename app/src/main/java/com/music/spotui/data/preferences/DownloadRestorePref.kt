package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only metadata is stored in JSON backups. The original audio is NOT in the backup.
 * On a new device these tracks remain visible as 'restore pending' until downloaded again.
 */
object DownloadRestorePref {
    private const val PREF = "download_restore_manifest_v2"
    private const val KEY = "pending_tracks"

    fun encodeSong(song: SongsModel): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("album", song.album)
        put("singer", song.singer)
        put("coverUri", song.coverUri)
        put("url", song.url)
        put("spotifyTrackId", song.spotifyTrackId)
        put("isLocalSpotifyTrack", song.isLocalSpotifyTrack)
        put("explicit", song.explicit)
        put("durationMs", song.durationMs)
        put("artistIds", song.artistIds)
    }

    fun decodeSong(obj: JSONObject): SongsModel? = runCatching {
        val id = obj.getInt("id")
        val url = obj.optString("url")
        if (url.isBlank()) return@runCatching null
        SongsModel(
            id = id,
            title = obj.optString("title"),
            album = obj.optString("album"),
            singer = obj.optString("singer"),
            coverUri = obj.optString("coverUri"),
            url = url,
            spotifyTrackId = obj.optString("spotifyTrackId"),
            isLocalSpotifyTrack = obj.optBoolean("isLocalSpotifyTrack", false),
            explicit = obj.optBoolean("explicit", false),
            durationMs = obj.optInt("durationMs", 0),
            artistIds = obj.optString("artistIds", ""),
        )
    }.getOrNull()

    @Synchronized
    fun pending(context: Context): List<SongsModel> = runCatching {
        val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]")
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(::decodeSong)
        }.distinctBy { it.id }.filterNot { isDownloaded(context, it.id.toString()) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun addPending(context: Context, songs: List<SongsModel>) {
        val merged = (pending(context) + songs)
            .filter { it.url.isNotBlank() && !isDownloaded(context, it.id.toString()) }
            .distinctBy { it.id }
        write(context, merged)
    }

    @Synchronized
    fun removePending(context: Context, songId: String) {
        write(context, pending(context).filterNot { it.id.toString() == songId })
    }

    @Synchronized
    fun clearPending(context: Context) = write(context, emptyList())

    @Synchronized
    private fun write(context: Context, songs: List<SongsModel>) {
        val arr = JSONArray().apply { songs.forEach { put(encodeSong(it)) } }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).commit()
        notifyDownloadsChanged()
        com.music.spotui.util.BackupHelper.scheduleAutoBackup(context)
    }

    /** Backup entries only reference a file by basename, never absolute paths from another device. */
    fun exportManifest(context: Context): JSONArray {
        val arr = JSONArray()
        val saved = getDownloadedEntries(context)
        val ids = HashSet<Int>()
        saved.forEach { (song, path) ->
            ids.add(song.id)
            arr.put(JSONObject().apply {
                put("song", encodeSong(song))
                put("fileName", File(path).name)
            })
        }
        pending(context).filterNot { it.id in ids }.forEach { song ->
            arr.put(JSONObject().apply {
                put("song", encodeSong(song))
                put("fileName", "")
            })
        }
        return arr
    }

    /** Restores file-backed entries ONLY if the private audio really exists on this device.
     * Missing files remain pending and can be redownloaded from the Downloads screen.
     * Paths supplied in a backup are never trusted.
     */
    fun restoreManifest(context: Context, arr: JSONArray): Pair<Int, Int> {
        val dir = File(context.filesDir, "downloads")
        val pending = mutableListOf<SongsModel>()
        val seen = HashSet<Int>()
        var available = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val song = entry.optJSONObject("song")?.let(::decodeSong) ?: continue
            if (!seen.add(song.id)) continue
            if (isDownloaded(context, song.id.toString())) {
                available++
                continue
            }
            val baseName = entry.optString("fileName")
            // A backup never grants access to arbitrary paths outside filesDir/downloads.
            val supported = listOf("m4a", "flac", "mp3")
            val candidates = mutableListOf<File>()
            if (supported.any { baseName == "${song.id}.$it" }) {
                candidates += File(dir, baseName)
            }
            supported.forEach { candidates += File(dir, "${song.id}.$it") }
            val existing = candidates.firstOrNull { it.isFile && it.length() > 0L }
            if (existing != null) {
                addDownload(context, song, existing.absolutePath)
                available++
            } else {
                pending.add(song)
            }
        }
        addPending(context, pending)
        return available to pending.size
    }
}
