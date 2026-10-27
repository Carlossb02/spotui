package com.music.spotui.data.preferences

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks downloaded (offline) tracks. Each entry is keyed by the song id and stores
 * the full [SongsModel] plus the local file path the audio was saved to, so the
 * Downloads screen can list them offline and [com.music.spotui.di.SongPlayer]
 * can play the local file instead of re-resolving + streaming from YouTube.
 */
private const val PREF = "Downloads"
private const val KEY_DL_SORT = "download_sort_option"
private const val KEY_DL_DESC = "download_sort_desc"

/** One lightweight invalidation stream shared by all song lists. */
private val _downloadsVersion = MutableStateFlow(0L)
val downloadsVersion = _downloadsVersion.asStateFlow()
internal fun notifyDownloadsChanged() { _downloadsVersion.update { it + 1L } }

/** Downloads reside in private, persistent filesDir/downloads, never in cacheDir or Music/.
 * The folder survives normal app updates and Android deletes it on uninstall.
 */
fun privateDownloadsDir(context: Context): File = File(context.filesDir, "downloads").apply { mkdirs() }

private fun validDownloadFile(context: Context, path: String): File? = runCatching {
    if (path.isBlank()) return@runCatching null
    val file = File(path).canonicalFile
    if (file.parentFile != privateDownloadsDir(context).canonicalFile) return@runCatching null
    if (file.extension.lowercase() !in setOf("m4a", "flac", "mp3")) return@runCatching null
    file.takeIf { it.isFile && it.length() > 0L }
}.getOrNull()

/** Only actual, playable files count as downloaded; restored metadata alone does not. */
fun getDownloadedSongIds(context: Context): Set<String> =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).all
        .mapNotNull { (key, value) ->
            if (key.toIntOrNull() == null) return@mapNotNull null
            val path = (value as? String)?.let(::parse)?.second ?: return@mapNotNull null
            key.takeIf { validDownloadFile(context, path) != null }
        }.toSet()

enum class DownloadSortOption(val label: String) {
    DATE("Added"),
    TITLE("Title"),
    ARTIST("Artist")
}

fun getDownloadsSortOption(context: Context): DownloadSortOption {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val saved = prefs.getString(KEY_DL_SORT, DownloadSortOption.DATE.name)
    return runCatching { DownloadSortOption.valueOf(saved!!) }.getOrDefault(DownloadSortOption.DATE)
}

fun isDownloadsSortDescending(context: Context): Boolean {
    return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_DL_DESC, true) // Default true for DATE (recent)
}

fun setDownloadsSortOption(context: Context, option: DownloadSortOption, desc: Boolean) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString(KEY_DL_SORT, option.name)
        .putBoolean(KEY_DL_DESC, desc)
        .apply()
}

private fun SongsModel.toJson(filePath: String): String = JSONObject().apply {
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
    put("filePath", filePath)
}.toString()

private fun parse(json: String): Pair<SongsModel, String>? = runCatching {
    val o = JSONObject(json)
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
    ) to o.optString("filePath")
}.getOrNull()

fun addDownload(context: Context, song: SongsModel, filePath: String) {
    val file = validDownloadFile(context, filePath)
        ?: throw IllegalArgumentException("Only complete downloads under filesDir/downloads may be registered")
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString(song.id.toString(), song.toJson(file.absolutePath))
        .commit()
    DownloadRestorePref.removePending(context, song.id.toString())
    notifyDownloadsChanged()
    com.music.spotui.util.BackupHelper.scheduleAutoBackup(context)
}

/** Delete the real audio and cached cover, not merely the Downloads preference. */
fun removeDownload(context: Context, songId: String): Boolean {
    if (songId.toIntOrNull() == null) return false
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val registeredPath = prefs.getString(songId, null)?.let(::parse)?.second
    val dir = privateDownloadsDir(context)
    // Remove every format that may remain if the track was previously replaced.
    val audioFiles = listOf("m4a", "mp3", "flac").map { File(dir, "$songId.$it") }
    val registeredFile = registeredPath?.let { validDownloadFile(context, it) }
    val candidates = (audioFiles + listOfNotNull(registeredFile)).distinctBy { it.absolutePath }
    val removed = candidates.map { !it.exists() || it.delete() }.all { it }
    if (!removed) return false // Do not claim deletion when the file remains on disk.
    listOf("${songId}_cover.jpg", "${songId}.jpg").forEach { name ->
        runCatching { File(dir, name).delete() }
    }
    prefs.edit().remove(songId).commit()
    DownloadRestorePref.removePending(context, songId)
    notifyDownloadsChanged()
    com.music.spotui.util.BackupHelper.scheduleAutoBackup(context)
    return true
}

fun isDownloaded(context: Context, songId: String): Boolean {
    val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(songId, null) ?: return false
    val path = parse(json)?.second ?: return false
    return validDownloadFile(context, path) != null
}

fun getDownloadedSongs(context: Context): List<SongsModel> {
    val entries = getDownloadedEntries(context)
    entries.forEach { (song, _) ->
        com.music.spotui.util.ArtworkHelper.ensureDownloadedCover(context, song)
    }
    val sortOption = getDownloadsSortOption(context)
    val descending = isDownloadsSortDescending(context)
    return when (sortOption) {
        DownloadSortOption.TITLE -> if (descending) entries.map { it.first }.sortedByDescending { it.title.lowercase() } else entries.map { it.first }.sortedBy { it.title.lowercase() }
        DownloadSortOption.ARTIST -> if (descending) entries.map { it.first }.sortedByDescending { it.singer.lowercase() } else entries.map { it.first }.sortedBy { it.singer.lowercase() }
        DownloadSortOption.DATE -> if (descending) entries.sortedByDescending { (_, path) -> File(path).lastModified() }.map { it.first } else entries.sortedBy { (_, path) -> File(path).lastModified() }.map { it.first }
    }
}

/** Local file path for a track query (SongsModel.url), if downloaded and present on disk. */
fun downloadedPathForQuery(context: Context, query: String): String? {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    for (v in prefs.all.values) {
        val (song, path) = (v as? String)?.let(::parse) ?: continue
        if (song.url == query && validDownloadFile(context, path) != null) {
            com.music.spotui.util.ArtworkHelper.ensureDownloadedCover(context, song)
            return path
        }
    }
    return null
}

/** Every verified downloaded track paired with its private on-disk audio file. */
fun getDownloadedEntries(context: Context): List<Pair<SongsModel, String>> =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .all.values.mapNotNull { value ->
            val (song, path) = (value as? String)?.let(::parse) ?: return@mapNotNull null
            val file = validDownloadFile(context, path) ?: return@mapNotNull null
            song to file.absolutePath
        }

/** Remove actual private audio, including any unused format, then update its metadata.
 * Active downloads are cancelled first so they cannot recreate deleted files.
 */
fun clearAllDownloads(context: Context): Int {
    com.music.spotui.di.SongPlayer.downloadQueue.value.forEach { task ->
        com.music.spotui.di.SongPlayer.cancelDownload(task.song.url)
    }
    val ids = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).all.keys
        .filter { it.toIntOrNull() != null }
    var removed = 0
    ids.forEach { if (removeDownload(context, it)) removed++ }
    if (removed == ids.size) {
        OfflineCollectionsPref.clearAll(context)
        DownloadRestorePref.clearPending(context)
    }
    return removed
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(120).ifBlank { "track" }

/**
 * Copy every downloaded track out of the app's private storage into the shared
 * **Music/spotui** folder as `Artist - Title.<ext>`, so files show up in normal
 * file managers / music apps (no root needed). Uses MediaStore on API 29+.
 * Returns (exportedCount, destinationLabel).
 */
fun exportDownloads(context: Context): Pair<Int, String> {
    val entries = getDownloadedEntries(context).filter { it.second.isNotBlank() && File(it.second).exists() }
    if (entries.isEmpty()) return 0 to "No downloaded files to export"
    val count = entries.count { (song, path) -> exportFile(context, song, path) }
    return count to "Music/spotui"
}

/** Export a single downloaded track to public Music/spotui. Returns true on success. */
fun exportDownload(context: Context, song: SongsModel): Boolean {
    val path = getDownloadedEntries(context).firstOrNull { it.first.id == song.id }?.second
        ?.takeIf { it.isNotBlank() && File(it).exists() } ?: return false
    return exportFile(context, song, path)
}

/** Copy one private download file into shared Music/spotui as "Artist - Title.<ext>". */
private fun exportFile(context: Context, song: SongsModel, path: String): Boolean = runCatching {
    val src = File(path)
    if (!src.exists()) return false
    val ext = src.extension.ifBlank { "flac" }
    val mime = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "audio/*"
    }
    val displayName = "${sanitizeFileName("${song.singer} - ${song.title}")}.$ext"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/spotui")
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "spotui",
        ).apply { mkdirs() }
        src.inputStream().use { input -> File(dir, displayName).outputStream().use { input.copyTo(it) } }
    }
    true
}.getOrDefault(false)
