package com.music.spotui.ui.notification

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.music.spotui.MainActivity
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.SpotifyWebPlayer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hosts a [MediaSession] over the app's single ExoPlayer (owned by [SongPlayer]).
 * This is what surfaces the track in the system notification center / lock screen
 * and routes the notification's transport controls (play/pause/seek/next/prev)
 * back into playback. Next/previous are wired to the in-app queue because our
 * player only ever holds one resolved stream at a time (YouTube URLs are resolved
 * lazily per track), so we advance the queue ourselves rather than via a playlist.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var currentSongState: CurrentSongState

    private var mediaSession: MediaSession? = null
    private var webPlayer: WebMediaPlayer? = null
    private var showingWeb = false

    override fun onCreate() {
        super.onCreate()
        SongPlayer.ensureCreated(this)
        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
        val base = SongPlayer.exoPlayer ?: return

        // Tapping the notification opens the app (back on the Now Playing screen).
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        webPlayer = WebMediaPlayer(mainLooper, currentSongState) { forward -> advance(forward) }

        mediaSession = MediaSession.Builder(this, wrap(base))
            .setSessionActivity(sessionActivity)
            .build()

        // When a crossfade promotes a new ExoPlayer instance, re-bind the session to it
        // (runs on the main thread; setPlayer is the supported way to swap a session's player).
        SongPlayer.onPlayerSwapped = { newPlayer ->
            if (!showingWeb) mediaSession?.player = wrap(newPlayer)
        }

        // As the hidden web player streams, keep the notification in sync and swap
        // the session between the web player (during web playback) and the ExoPlayer.
        SpotifyWebPlayer.onStateChanged = {
            syncSessionPlayer()
            if (showingWeb) {
                webPlayer?.refresh()
                // Reflect the web player's real play/pause state into the in-app UI
                // so the on-screen icon matches after the notification's pause.
                currentSongState.updatePlayingState(SpotifyWebPlayer.isPlaying)
            }
        }
    }

    /** Point the media session at whichever engine is currently producing audio. */
    private fun syncSessionPlayer() {
        val wantWeb = SongPlayer.webPlaybackActive()
        if (wantWeb == showingWeb) return
        showingWeb = wantWeb
        val session = mediaSession ?: return
        session.player = if (wantWeb) {
            webPlayer ?: return
        } else {
            wrap(SongPlayer.exoPlayer ?: return)
        }
    }

    /** Wrap an ExoPlayer so the media session routes next/previous to our in-app queue
     *  (the player only ever holds one resolved stream at a time). */
    private fun wrap(base: Player): ForwardingPlayer = object : ForwardingPlayer(base) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = advance(forward = true)
        override fun seekToNextMediaItem() = advance(forward = true)
        override fun seekToPrevious() = advance(forward = false)
        override fun seekToPreviousMediaItem() = advance(forward = false)
    }

    /** Advance the in-app queue one step in the given direction and start it. */
    private fun advance(forward: Boolean) {
        val queue = currentSongState.queue.value
        if (queue.isEmpty()) return
        val curId = currentSongState.songId.value
        val cur = queue.indexOfFirst { it.id == curId }
            .let { if (it >= 0) it else currentSongState.songIndex.value }
            .coerceIn(0, queue.size - 1)
        val nextIdx = if (forward) {
            if (cur < queue.size - 1) cur + 1 else 0
        } else {
            if (cur > 0) cur - 1 else queue.size - 1
        }
        val song = queue[nextIdx]
        currentSongState.updateSongState(
            song.coverUri, song.title, song.singer, true,
            song.id, nextIdx, currentSongState.album.value
        )
        SongPlayer.playSong(song.url, applicationContext)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop playback + tear the service down when the app is swiped away.
        SongPlayer.pause()
        stopSelf()
    }

    override fun onDestroy() {
        SongPlayer.onPlayerSwapped = null
        SpotifyWebPlayer.onStateChanged = null
        webPlayer?.release()
        webPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
