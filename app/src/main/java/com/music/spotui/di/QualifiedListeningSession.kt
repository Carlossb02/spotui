package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.HistoryEntry

/** Monotonic audible time, independent of media position: seeking never earns a play. */
internal class QualifiedListeningSession(
    private val song: SongsModel, private val startedAt: Long, private val eventId: String,
) {
    private var lastAt: Long? = null
    private var wasPlaying = false
    private var listenedMs = 0L
    private var emittedDuration = -1L
    fun sample(now: Long, playing: Boolean, engineDurationMs: Long): HistoryEntry? {
        val previous = lastAt
        if (wasPlaying && previous != null && now >= previous) listenedMs += now - previous
        lastAt = now
        wasPlaying = playing
        val duration = song.durationMs.toLong().takeIf { it > 0 } ?: engineDurationMs.coerceAtLeast(0L)
        if (listenedMs < 30_000L || (emittedDuration >= 0L && (duration <= 0L || emittedDuration == duration))) return null
        emittedDuration = duration
        return HistoryEntry(startedAt, song.id, song.title, song.singer, song.album, song.coverUri, song.url,
            duration, song.spotifyTrackId, qualified = true, eventId = eventId, mediaType = if (com.music.spotui.data.preferences.isHistoryEpisodeSource(song.url) ||
                com.music.spotui.data.preferences.isHistoryEpisodeSource(song.spotifyTrackId)) "podcast" else "music")
    }
}
