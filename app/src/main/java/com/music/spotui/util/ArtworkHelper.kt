package com.music.spotui.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaMetadata
import com.music.spotui.data.preferences.getDownloadedEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object ArtworkHelper {

    private fun cleanSongId(songId: String?): String? =
        songId?.removePrefix("song/")?.removePrefix("playlist/")?.removePrefix("album/")?.trim()?.takeIf { it.isNotBlank() }

    fun attachLocalFileArtwork(builder: MediaMetadata.Builder, file: File): MediaMetadata.Builder {
        val contentUri = ArtworkContentProvider.getContentUri(file)
        builder.setArtworkUri(contentUri)
        runCatching {
            val bytes = file.readBytes()
            if (bytes.isNotEmpty()) {
                builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }
        return builder
    }

    /**
     * Attaches the best available artwork (local downloaded cover file, embedded ID3 picture,
     * Glide disk cache, or remote URI) to a [MediaMetadata.Builder] for system notifications.
     */
    fun attachArtwork(
        builder: MediaMetadata.Builder,
        context: Context,
        coverUri: String,
        songId: String? = null,
        streamUrl: String? = null
    ): MediaMetadata.Builder {
        val cleanCover = coverUri.trim()
        val cleanStream = streamUrl?.trim().orEmpty()
        val rawPath = if (cleanStream.startsWith("file://")) Uri.parse(cleanStream).path.orEmpty() else cleanStream
        val rawId = cleanSongId(songId)

        val dir = File(context.filesDir, "downloads")

        // 1. Check for a saved local download cover file by songId
        if (!rawId.isNullOrBlank()) {
            val localCover = File(dir, "${rawId}_cover.jpg")
            if (localCover.exists() && localCover.length() > 0) {
                return attachLocalFileArtwork(builder, localCover)
            }
            val localCoverAlt = File(dir, "${rawId}.jpg")
            if (localCoverAlt.exists() && localCoverAlt.length() > 0) {
                return attachLocalFileArtwork(builder, localCoverAlt)
            }
        }

        // 2. Match by streamUrl / query / path in downloaded entries
        if (cleanStream.isNotBlank()) {
            val dlEntries = runCatching { getDownloadedEntries(context) }.getOrDefault(emptyList())
            val matchedEntry = dlEntries.firstOrNull { (song, path) ->
                song.url == cleanStream || path == cleanStream || (rawPath.isNotBlank() && path == rawPath)
            }
            if (matchedEntry != null) {
                val localCover = File(dir, "${matchedEntry.first.id}_cover.jpg")
                if (localCover.exists() && localCover.length() > 0) {
                    return attachLocalFileArtwork(builder, localCover)
                }
                ensureDownloadedCover(context, matchedEntry.first)
            }
        }

        // 3. Check if streamUrl is a local audio file with embedded cover art
        if (rawPath.isNotBlank() && (rawPath.startsWith("/") || cleanStream.startsWith("content:"))) {
            val retriever = MediaMetadataRetriever()
            try {
                if (cleanStream.startsWith("content:")) {
                    retriever.setDataSource(context, Uri.parse(cleanStream))
                } else {
                    retriever.setDataSource(rawPath)
                }
                val embedded = retriever.embeddedPicture
                if (embedded != null && embedded.isNotEmpty()) {
                    builder.setArtworkData(embedded, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    return builder
                }
            } catch (e: Exception) {
                // Ignore retriever failure
            } finally {
                runCatching { retriever.release() }
            }
        }

        // 4. Check if coverUri is already a local file or content URI
        if (cleanCover.startsWith("file:")) {
            val f = runCatching { File(Uri.parse(cleanCover).path ?: "") }.getOrNull()
            if (f != null && f.exists() && f.length() > 0) {
                return attachLocalFileArtwork(builder, f)
            }
        }
        if (cleanCover.startsWith("content:")) {
            return builder.setArtworkUri(Uri.parse(cleanCover))
        }

        // 5. Check Glide disk cache safely
        if (cleanCover.isNotBlank()) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val cachedFile = runCatching {
                    com.bumptech.glide.Glide.with(context.applicationContext)
                        .downloadOnly()
                        .load(cleanCover)
                        .onlyRetrieveFromCache(true)
                        .submit()
                        .get()
                }.getOrNull()

                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                    if (!rawId.isNullOrBlank()) {
                        val dest = File(dir, "${rawId}_cover.jpg")
                        if (!dest.exists()) runCatching { cachedFile.copyTo(dest, overwrite = true) }
                    }
                    return attachLocalFileArtwork(builder, cachedFile)
                }
            } else {
                if (!rawId.isNullOrBlank()) {
                    val dest = File(dir, "${rawId}_cover.jpg")
                    if (dest.exists() && dest.length() > 0) {
                        return attachLocalFileArtwork(builder, dest)
                    }
                }
            }

            // 6. Fallback
            if (!rawId.isNullOrBlank()) {
                val dest = File(dir, "${rawId}_cover.jpg")
                builder.setArtworkUri(ArtworkContentProvider.getContentUri(dest))
            } else {
                builder.setArtworkUri(Uri.parse(cleanCover))
            }
            return builder
        }

        return builder
    }

    /** Ensure a downloaded song has its cover image cached/saved locally to disk. */
    fun ensureDownloadedCover(context: Context, song: com.music.spotui.data.entity.SongsModel) {
        ensureDownloadedCover(context, song.id.toString(), song.coverUri)
    }

    /** Ensure a cover image for any item (song, playlist, album, artist) is cached/saved locally to disk. */
    fun ensureDownloadedCover(context: Context, id: String, coverUri: String) {
        if (coverUri.isBlank()) return
        val cleanId = cleanSongId(id) ?: id.trim()
        val dir = File(context.filesDir, "downloads").apply { mkdirs() }
        val coverFile = File(dir, "${cleanId}_cover.jpg")
        if (coverFile.exists() && coverFile.length() > 0) return

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching {
                com.bumptech.glide.Glide.with(appContext)
                    .downloadOnly()
                    .load(coverUri)
                    .submit()
                    .get()
            }.getOrNull()
            if (file != null && file.exists()) {
                runCatching { file.copyTo(coverFile, overwrite = true) }
            } else {
                runCatching {
                    val conn = (java.net.URL(coverUri).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 10000
                        instanceFollowRedirects = true
                    }
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { input ->
                            coverFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
    }
}
