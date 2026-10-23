package com.music.spotui.ui.notification

import android.content.Context
import android.net.Uri
import com.music.spotui.data.entity.SongsModel
import java.io.File

/** Podcast artwork is durable user-library data, not disposable stream cache. */
object PodcastCover {
    fun isEpisode(song: SongsModel): Boolean = song.url.startsWith("yt_ep:")

    /** Id-scoped file outside cacheDir, survives Settings > Clear stream cache. */
    fun preferredUri(context: Context, song: SongsModel): String {
        if (!isEpisode(song)) return song.coverUri
        val file = File(File(context.filesDir, "downloads"), "${song.id}_cover.jpg")
        return if (file.isFile && file.length() > 0L) Uri.fromFile(file).toString()
        else song.coverUri
    }

    fun showArtworkKey(id: String): String = "podcast_${id.hashCode()}"

    fun hasSavedShowCover(context: Context, id: String): Boolean =
        File(File(context.filesDir, "downloads"), "${showArtworkKey(id)}_cover.jpg")
            .let { it.isFile && it.length() > 0L }

    /** Followed-show thumbnail cached by the same durable artwork service. */
    fun preferredShowUri(context: Context, id: String, remoteUri: String): String {
        val file = File(File(context.filesDir, "downloads"), "${showArtworkKey(id)}_cover.jpg")
        return if (file.isFile && file.length() > 0L) Uri.fromFile(file).toString()
        else remoteUri
    }

    /** Off-main-thread fetch + bounded decode handled by PlaybackArtwork. */
    fun preserve(context: Context, song: SongsModel) {
        if (isEpisode(song) && song.coverUri.isNotBlank()) {
            PlaybackArtwork.ensure(context.applicationContext, song.id.toString(), song.coverUri)
        }
    }
}
