package com.music.spotui.ui.notification

import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaStyleNotificationHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.MainActivity
import com.music.spotui.R
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.RepeatMode
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.SpotifyWebPlayer
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts a [MediaSession] over the app's single ExoPlayer (owned by [SongPlayer]).
 * This is what surfaces the track in the system notification center / lock screen
 * and routes the notification's transport controls (play/pause/seek/next/prev)
 * back into playback. Next/previous are wired to the in-app queue because our
 * player only ever holds one resolved stream at a time (YouTube URLs are resolved
 * lazily per track), so we advance the queue ourselves rather than via a playlist.
 *
 * It is a [MediaLibraryService] (not just a session service) so Android Auto can
 * browse the library — Liked Songs, Downloads, playlists and albums — and start
 * playback from the car.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    lateinit var currentSongState: CurrentSongState

    @Inject
    lateinit var repository: AppRepository

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Android Auto's legacy MediaBrowser can BLOCK the main thread while
    // waiting for onGetChildren. Never complete that future on the main thread:
    // doing so deadlocks browsing, leaving only the "Now playing" screen.
    private val carBrowseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationRefreshJob: kotlinx.coroutines.Job? = null
    private var taskRemovalCheckJob: kotlinx.coroutines.Job? = null
    private var ownsPlaybackForeground = false

    companion object {
        // Media3's default notification ID, so Android never shows two media cards.
        private const val PLAYBACK_NOTIFICATION_ID = 1001
        private const val PLAYBACK_NOTIFICATION_CHANNEL = "spotui_media_playback"
        private const val ACTION_NOTIFICATION_TOGGLE = "com.music.spotui.action.NOTIFICATION_TOGGLE"
        private const val ACTION_NOTIFICATION_PREVIOUS = "com.music.spotui.action.NOTIFICATION_PREVIOUS"
        private const val ACTION_NOTIFICATION_NEXT = "com.music.spotui.action.NOTIFICATION_NEXT"
        private const val ACTION_NOTIFICATION_CLOSE = "com.music.spotui.action.NOTIFICATION_CLOSE"

        // Android Auto browse tree
        private const val ROOT = "root"
        private const val NODE_HOME = "auto_home"
        private const val NODE_PINNED = "auto_pinned"
        private const val NODE_RECENTS = "auto_recents"
        private const val NODE_BROWSE = "auto_browse"
        private const val NODE_LIBRARY = "auto_library"
        private const val NODE_PODCASTS = "auto_podcasts"
        private const val NODE_LOCAL = "auto_local"
        private const val NODE_LIKED = "liked"
        private const val NODE_DOWNLOADS = "downloads"
        private const val NODE_PLAYLISTS = "playlists"
        private const val NODE_ALBUMS = "albums"
        private const val NODE_ARTISTS = "artists"
        private const val NODE_SONGS = "songs"
    }

    /**
     * An ExoPlayer's MediaItem may arrive several seconds after the user presses Play,
     * because Spotui resolves remote streams lazily. Media3's automatic notification
     * refuses an empty timeline, so it cannot protect the service during that window.
     * The service owns ONE real MediaStyle notification for the Media3 session instead.
     * This is a real foreground MEDIA_PLAYBACK service, not a second player or a dummy
     * high-priority notification.
     */
    private fun playbackShouldOwnForeground(): Boolean =
        SongPlayer.isResolvingOrPlaying() || SongPlayer.isPreparingTrack() ||
            SongPlayer.isCrossfadeActive() || SpotifyWebPlayer.isPlaying ||
            (SongPlayer.exoPlayer?.playWhenReady == true &&
                SongPlayer.exoPlayer?.playbackState != Player.STATE_ENDED)

    private fun createPlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(
                    PLAYBACK_NOTIFICATION_CHANNEL,
                    "Reproducción de Spotui",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controles de música y podcasts en reproducción"
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun mediaAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildPlaybackNotification(session: MediaSession, foreground: Boolean): android.app.Notification {
        val isActive = SongPlayer.isResolvingOrPlaying() || SpotifyWebPlayer.isPlaying
        val title = currentSongState.title.value.ifBlank { "Spotui" }
        val artist = currentSongState.singer.value.ifBlank { "Música y podcasts" }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PLAYBACK_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_spotui_notification_monochrome)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(foreground)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_previous, "Anterior",
                mediaAction(ACTION_NOTIFICATION_PREVIOUS, 110))
            .addAction(
                if (isActive) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isActive) "Pausar" else "Reproducir",
                mediaAction(ACTION_NOTIFICATION_TOGGLE, 111)
            )
            .addAction(android.R.drawable.ic_media_next, "Siguiente",
                mediaAction(ACTION_NOTIFICATION_NEXT, 112))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar",
                mediaAction(ACTION_NOTIFICATION_CLOSE, 113))
            // MediaStyle with Media3's platform token is essential on Android 13+:
            // SystemUI builds the media controls from the live MediaSession.
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun scheduleMediaNotificationRefresh() {
        notificationRefreshJob?.cancel()
        notificationRefreshJob = serviceScope.launch {
            // Do not postpone notifications repeatedly during buffering/crossfade.
            kotlinx.coroutines.delay(70L)
            mediaSession?.let { session ->
                runCatching { updateOwnedPlaybackNotification(session) }
                    .onFailure { android.util.Log.e("SpotuiLifecycle", "media_notification_error", it) }
            }
        }
    }

    private fun updateOwnedPlaybackNotification(session: MediaSession) {
        val active = playbackShouldOwnForeground()
        val hasTrack = currentSongState.songUrl.value.isNotBlank() ||
            currentSongState.queue.value.isNotEmpty() || SpotifyWebPlayer.isPlaying
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!active && !hasTrack) {
            if (ownsPlaybackForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                ownsPlaybackForeground = false
            }
            manager.cancel(PLAYBACK_NOTIFICATION_ID)
            return
        }
        val notification = buildPlaybackNotification(session, foreground = active)
        if (active) {
            try {
                // Bind-only services may be demoted when the Activity goes away.
                // Explicit promotion keeps playback perceptible to Android's LMK.
                ServiceCompat.startForeground(
                    this, PLAYBACK_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                if (!ownsPlaybackForeground) {
                    android.util.Log.i("SpotuiLifecycle", "media_fgs_active=true")
                }
                ownsPlaybackForeground = true
            } catch (error: Exception) {
                // Background starts may be restricted by the OS. Preserve playback
                // and report the real exception rather than crashing our process.
                android.util.Log.e("SpotuiLifecycle", "media_fgs_promotion_failed", error)
                runCatching { manager.notify(PLAYBACK_NOTIFICATION_ID, notification) }
            }
        } else {
            if (ownsPlaybackForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                ownsPlaybackForeground = false
            }
            // A paused player remains visible but is no longer kept alive as an FGS.
            runCatching { manager.notify(PLAYBACK_NOTIFICATION_ID, notification) }
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Intentionally take ownership of this single notification, as allowed by
        // MediaSessionService. Calling super here would allow Media3 to demote it
        // while a YouTube URL is still resolving or a crossfade swaps players.
        runCatching { updateOwnedPlaybackNotification(session) }
            .onFailure { android.util.Log.e("SpotuiLifecycle", "onUpdateNotification failed", it) }
    }

    // Android Auto browse cache: mediaId → track, and mediaId → the list it was
    // browsed from (so playing a track queues its whole playlist/album).
    private val trackById = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()
    private val folderById = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()
    private val podcastById = java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.PodcastModel>()
    private val queueByTrackId = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
    private var webPlayer: WebMediaPlayer? = null
    private var showingWeb = false

    private val errorRetryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            ensureCurrentArtwork()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            ensureCurrentArtwork()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            scheduleMediaNotificationRefresh()
            currentSongState.updateBufferingState(playbackState == Player.STATE_BUFFERING)
            // ExoPlayer's WAKE_MODE_LOCAL is responsible for the CPU wake lock.
            // No 12-hour application-held wake locks are needed here.
            if (playbackState == Player.STATE_ENDED) {
                // Ignore a stale END from the outgoing track while a replacement
                // stream is being resolved; SongPlayer owns its failure fallback.
                if (SongPlayer.isPreparingTrack()) return
                if (SongPlayer.isCrossfadeActive()) {
                    // If the next player is already ready, hand off now instead
                    // of suppressing ENDED and waiting for a delayed fade timer.
                    if (SongPlayer.promoteReadyCrossfadeOnEnd()) return
                    val sec = SongPlayer.getSecondaryPlayerForService()
                    if (sec != null && (sec.isPlaying || sec.playbackState == Player.STATE_READY || sec.playbackState == Player.STATE_BUFFERING)) {
                        // Secondary player is actively transitioning or buffering, ignore old player STATE_ENDED
                        return
                    } else {
                        // Secondary player is missing or stalled, cancel crossfade so fallback advance can proceed
                        SongPlayer.cancelCrossfadeFromService()
                    }
                }
                errorRetryCounts.clear()
                when (currentSongState.repeat.value) {
                    RepeatMode.ONE -> {
                        val p = SongPlayer.exoPlayer
                        if (p != null) {
                            p.seekTo(0)
                            p.play()
                        } else {
                            val queue = currentSongState.queue.value
                            if (queue.isNotEmpty()) {
                                val curId = currentSongState.songId.value
                                val cur = queue.indexOfFirst { it.id == curId }
                                    .takeIf { it >= 0 }
                                    ?: queue.indexOfFirst { it.url == currentSongState.songUrl.value }.takeIf { it >= 0 }
                                    ?: currentSongState.songIndex.value.coerceIn(0, queue.size - 1)
                                val song = queue[cur]
                                SongPlayer.playSong(song.url, applicationContext, "song/${song.id}")
                            }
                        }
                    }

                    RepeatMode.ALL -> {
                        advance(forward = true)
                    }

                    RepeatMode.OFF -> {
                        val queue = currentSongState.queue.value
                        if (queue.isNotEmpty()) {
                            val curId = currentSongState.songId.value
                            val cur = queue.indexOfFirst { it.id == curId }
                                .takeIf { it >= 0 }
                                ?: queue.indexOfFirst { it.url == currentSongState.songUrl.value }.takeIf { it >= 0 }
                                ?: currentSongState.songIndex.value.coerceIn(0, queue.size - 1)
                            if (cur < queue.size - 1) {
                                advance(forward = true)
                            }
                        }
                    }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            scheduleMediaNotificationRefresh()
            android.util.Log.w("SpotuiTransition", "session underlying playWhenReady=$playWhenReady reason=$reason " +
                "state=${SongPlayer.exoPlayer?.playbackState}, crossfade=${SongPlayer.isCrossfadeActive()}")
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            android.util.Log.w("SpotuiTransition", "session underlying suppression=$playbackSuppressionReason " +
                "state=${SongPlayer.exoPlayer?.playbackState}, crossfade=${SongPlayer.isCrossfadeActive()}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            scheduleMediaNotificationRefresh()
            android.util.Log.i("SpotuiTransition", "session underlying isPlaying=$isPlaying " +
                "state=${SongPlayer.exoPlayer?.playbackState}, crossfade=${SongPlayer.isCrossfadeActive()}")
            // Mirror ExoPlayer's real play/pause state into the in-app UI so the
            // on-screen play button stays in sync when the notification controls are used.
            // Ignore transient stops/pauses from the outgoing player during an active crossfade.
            if (SongPlayer.isCrossfadeActive()) return
            if (!isPlaying && SongPlayer.isPreparingTrack()) return
            // Do not leak a manual wake lock across an entire playback session.
            if (!isPlaying) {
                PlaybackLifecycleDiagnostics.record(applicationContext,
                    "player_not_playing state=${SongPlayer.exoPlayer?.playbackState} " +
                    "ready=${SongPlayer.exoPlayer?.playWhenReady}")
            }
            if (!showingWeb) currentSongState.updatePlayingState(isPlaying)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Video is optional. The MediaSession must not skip an episode simply
            // because the selected YouTube video stream is unavailable/expired.
            if (com.music.spotui.ui.screens.PodcastVideoSession.player === SongPlayer.exoPlayer &&
                com.music.spotui.ui.screens.PodcastVideoSession.activeVideoId != null) {
                android.util.Log.w("PlaybackService", "A/V source failed; falling back to podcast audio", error)
                com.music.spotui.ui.screens.PodcastVideoSession.stop()
                return
            }
            if (SongPlayer.isCrossfadeActive()) {
                android.util.Log.w("PlaybackService", "Outgoing player failed during crossfade; checking incoming player", error)
                // If the incoming player is READY, its promotion is queued on the
                // main looper. If still buffering, SongPlayer's bounded watchdog
                // takes over instead of losing the transition silently.
                SongPlayer.promoteReadyCrossfadeOnEnd()
                return
            }
            android.util.Log.e(
                "PlaybackService",
                "Player error during playback: ${error.message}",
                error
            )
            val queue = currentSongState.queue.value
            val curId = currentSongState.songId.value
            val cur = queue.indexOfFirst { it.id == curId }
            val songUrl = if (cur >= 0) queue[cur].url else currentSongState.songUrl.value
            
            val retries = errorRetryCounts[songUrl] ?: 0
            val isIoOrSourceError = error.errorCodeName.contains("IO") ||
                    error.errorCodeName.contains("NETWORK") ||
                    error.errorCodeName.contains("TIMEOUT") ||
                    error.errorCodeName.contains("SOURCE") ||
                    error.errorCodeName.contains("UNSPECIFIED") ||
                    error.errorCodeName.contains("PARSING") ||
                    error.message?.contains("Source error", ignoreCase = true) == true ||
                    error.errorCode in 2000..3999

            if (retries < 2 && isIoOrSourceError && songUrl.isNotBlank()) {
                errorRetryCounts[songUrl] = retries + 1
                android.util.Log.w("PlaybackService", "Transient loading error for $songUrl (attempt ${retries + 1}), retrying playback instead of skipping...")
                SongPlayer.invalidateResolvedStream(songUrl)
                val mediaId = if (cur >= 0) "song/${queue[cur].id}" else currentSongState.songId.value.let { if (it != 0) "song/$it" else null }
                SongPlayer.playSong(songUrl, applicationContext, mediaId)
                return
            }
            errorRetryCounts.remove(songUrl)
            if (songUrl.isNotBlank()) {
                SongPlayer.invalidateResolvedStream(songUrl)
            }
            advance(forward = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackLifecycleDiagnostics.reportPreviousExit(this)
        PlaybackLifecycleDiagnostics.record(this, "service_created")
        SongPlayer.ensureCreated(this)

        // Order notification buttons: [repeat | prev | play/pause | next | close]
        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            init {
                setSmallIcon(R.drawable.ic_spotui_notification_monochrome)
            }

            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                val base =
                    super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                val repeatBtn = base.find { it.sessionCommand?.customAction == "ACTION_REPEAT" }
                val closeBtn = base.find { it.sessionCommand?.customAction == "ACTION_CLOSE" }
                val transport = base.filter {
                    it.sessionCommand?.customAction != "ACTION_REPEAT" &&
                            it.sessionCommand?.customAction != "ACTION_CLOSE" &&
                            it.sessionCommand?.customAction != "ACTION_NONE"
                }
                return ImmutableList.builder<CommandButton>()
                    .apply { repeatBtn?.let { add(it) } }
                    .addAll(transport)
                    .apply { closeBtn?.let { add(it) } }
                    .build()
            }
        }
        setMediaNotificationProvider(notificationProvider)
        createPlaybackNotificationChannel()
        // Diagnostics only: notification visibility can also be disabled in
        // Android app settings, independently from Media3 foreground handling.
        val notificationsEnabled =
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .areNotificationsEnabled()
        android.util.Log.i("SpotuiLifecycle", "system_notifications_enabled=$notificationsEnabled")

        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
        // Android Auto/media buttons may start this service with no Activity at all.
        // Restore the *paused* queue here so controls work after process recreation.
        if (currentSongState.queue.value.isEmpty()) {
            com.music.spotui.data.preferences.loadLastPlayback(applicationContext)?.let { (savedSong, positionMs) ->
                val savedQueue = com.music.spotui.data.preferences.loadLastQueue(applicationContext)
                val restoredQueue = savedQueue.ifEmpty { listOf(savedSong) }
                currentSongState.updateQueue(restoredQueue, isManualEdit = true)
                val idx = restoredQueue.indexOfFirst { it.id == savedSong.id }.coerceAtLeast(0)
                currentSongState.updateSongState(savedSong.coverUri, savedSong.title,
                    savedSong.singer, false, savedSong.id, idx, savedSong.album)
                SongPlayer.setRestorePoint(savedSong.url, positionMs)
            }
        }
        SongPlayer.ensureCreated(this)
        val base = SongPlayer.exoPlayer ?: return
        base.addListener(playerListener)

        // Tapping the notification opens the app (back on the Now Playing screen).
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        webPlayer = WebMediaPlayer(mainLooper, currentSongState) { forward -> advance(forward) }

        mediaSession = MediaLibrarySession.Builder(this, wrap(base), LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
        scheduleMediaNotificationRefresh()

        // The session must see a buffering/play-intent event while the current
        // stream URL is being resolved. Otherwise Media3 may demote the service
        // when the outgoing player pauses, especially under battery restrictions.
        SongPlayer.onPlaybackIntentChanged = {
            activeForwardingPlayer?.dispatchPlaybackRefresh()
            scheduleMediaNotificationRefresh()
        }

        // When a crossfade promotes a new ExoPlayer instance, re-bind the session to it
        // (runs on the main thread; setPlayer is the supported way to swap a session's player).
        SongPlayer.onPlayerCreated = { newPlayer ->
            if (!showingWeb) mediaSession?.player = wrap(newPlayer)
            newPlayer.addListener(playerListener)
            scheduleMediaNotificationRefresh()
        }
        SongPlayer.onPlayerSwapped = { oldPlayer, newPlayer ->
            android.util.Log.i("SpotuiTransition", "media session swap: " +
                "old=${oldPlayer?.playbackState} new=${newPlayer.playbackState} " +
                "newPlaying=${newPlayer.isPlaying} newWhenReady=${newPlayer.playWhenReady} " +
                "newSuppression=${newPlayer.playbackSuppressionReason}")
            oldPlayer?.removeListener(playerListener)
            if (!showingWeb) {
                val wrapped = wrap(newPlayer)
                mediaSession?.player = wrapped
                activeForwardingPlayer?.dispatchMetadataRefresh()
            }
            newPlayer.addListener(playerListener)
            playerListener.onPlaybackStateChanged(newPlayer.playbackState)
            playerListener.onIsPlayingChanged(newPlayer.isPlaying)
            // Media3 will hold its own playback wake lock after player swap.
            currentSongState.updatePlayingState(newPlayer.isPlaying || newPlayer.playWhenReady)
            scheduleMediaNotificationRefresh()
        }

        // Keep the notification's repeat icon in sync whenever the repeat mode
        // changes from ANY source (full-screen UI, notification button, etc.).
        serviceScope.launch {
            snapshotFlow { currentSongState.repeat.value }
                .distinctUntilChanged()
                .collect { mode ->
                    mediaSession?.setCustomLayout(buildCustomLayout(mode))
                }
        }

        // Cada vez que cambia la canción actual (desde la app, el coche o el crossfade),
        // asegurar que su carátula esté cargada y republicada en la sesión.
        serviceScope.launch {
            snapshotFlow { currentSongState.songId.value }
                .distinctUntilChanged()
                .collect { ensureCurrentArtwork() }
        }

        // Re-dispatch metadata and timeline when queue changes
        serviceScope.launch {
            snapshotFlow { currentSongState.queue.value }
                .distinctUntilChanged()
                .collect {
                    activeForwardingPlayer?.dispatchMetadataRefresh()
                }
        }

        // As the hidden web player streams, keep the notification in sync and swap
        // the session between the web player (during web playback) and the ExoPlayer.
        SpotifyWebPlayer.onStateChanged = {
            syncSessionPlayer()
            scheduleMediaNotificationRefresh()
            if (showingWeb) {
                webPlayer?.refresh()
                // Reflect the web player's real play/pause state into the in-app UI
                // so the on-screen icon matches after the notification's pause.
                currentSongState.updatePlayingState(SpotifyWebPlayer.isPlaying)
            }
        }

        // Register a receiver for legacy music control broadcasts used by
        // automation apps (MacroDroid, Tasker, etc.).
        val musicFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_BUTTON)
            addAction("com.android.music.musicservicecommand")
            addAction("com.android.music.play")
            addAction("com.android.music.pause")
            addAction("com.android.music.next")
            addAction("com.android.music.previous")
            addAction("com.android.music.togglepause")
            addAction("com.android.music.stop")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            mediaControlReceiver,
            musicFilter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
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

    /**
     * Receives legacy music control broadcasts from automation apps (MacroDroid,
     * Tasker) that use the "com.android.music.musicservicecommand" pattern or
     * direct action intents to control media playback.
     */
    private val mediaControlReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.d(
                "PlaybackService",
                "BroadcastReceiver: action=${intent.action} extras=${intent.extras?.keySet()}"
            )
            when (intent.action) {
                Intent.ACTION_MEDIA_BUTTON -> {
                    val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    android.util.Log.d(
                        "PlaybackService",
                        "BroadcastReceiver: ACTION_MEDIA_BUTTON keyCode=${keyEvent?.keyCode}"
                    )
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> SongPlayer.next(context)
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> SongPlayer.previous(context)
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> SongPlayer.togglePlay()
                            KeyEvent.KEYCODE_MEDIA_PLAY -> SongPlayer.play()
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> SongPlayer.pause()
                            KeyEvent.KEYCODE_MEDIA_STOP -> SongPlayer.pause()
                        }
                    }
                    if (isOrderedBroadcast) abortBroadcast()
                }

                "com.android.music.musicservicecommand" -> {
                    when (intent.getStringExtra("command")) {
                        "play" -> SongPlayer.play()
                        "pause" -> SongPlayer.pause()
                        "togglepause", "toggle" -> SongPlayer.togglePlay()
                        "next" -> SongPlayer.next(context)
                        "previous" -> SongPlayer.previous(context)
                        "stop" -> SongPlayer.pause()
                    }
                }

                "com.android.music.play" -> SongPlayer.play()
                "com.android.music.pause" -> SongPlayer.pause()
                "com.android.music.next" -> SongPlayer.next(context)
                "com.android.music.previous" -> SongPlayer.previous(context)
                "com.android.music.togglepause" -> SongPlayer.togglePlay()
                "com.android.music.stop" -> SongPlayer.pause()
            }
        }
    }

    private var activeForwardingPlayer: AutoPlayer? = null

    private fun wrap(base: Player): ForwardingPlayer =
        AutoPlayer(base).also { activeForwardingPlayer = it }

    private inner class AutoPlayer(private val base: Player) : ForwardingPlayer(base) {

        // Listeners registrados por la sesión: los guardamos para poder emitir
        // "metadatos cambiados" a mano cuando termina de cargarse la carátula.
        private val sessionListeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()
        private val listenerWrappers = java.util.concurrent.ConcurrentHashMap<Player.Listener, Player.Listener>()

        override fun getPlaybackState(): Int {
            if (SongPlayer.isCrossfadeActive()) {
                val incoming = SongPlayer.getSecondaryPlayerForService()
                if (incoming != null && incoming.playbackState == Player.STATE_READY) {
                    return Player.STATE_READY
                }
            }
            if (SongPlayer.isCrossfadeActive() || SongPlayer.isResolvingOrPlaying()) {
                val state = super.getPlaybackState()
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    return Player.STATE_BUFFERING
                }
            }
            return super.getPlaybackState()
        }

        override fun getPlayWhenReady(): Boolean {
            if (SongPlayer.isCrossfadeActive() || SongPlayer.isPreparingTrack()) return true
            return super.getPlayWhenReady()
        }

        override fun isPlaying(): Boolean {
            if (SongPlayer.isCrossfadeActive()) {
                val incoming = SongPlayer.getSecondaryPlayerForService()
                if (incoming?.isPlaying == true) return true
            }
            // Do not report false playback while resolving as physically playing;
            // PlayWhenReady + buffering express the intention to continue.
            return super.isPlaying()
        }

        override fun addListener(listener: Player.Listener) {
            sessionListeners.add(listener)
            val wrapper = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (base !== SongPlayer.exoPlayer) return
                    listener.onPlaybackStateChanged(this@AutoPlayer.playbackState)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (base !== SongPlayer.exoPlayer) return
                    listener.onIsPlayingChanged(this@AutoPlayer.isPlaying)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (base !== SongPlayer.exoPlayer) return
                    listener.onPlayWhenReadyChanged(this@AutoPlayer.playWhenReady, reason)
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    if (base !== SongPlayer.exoPlayer) return
                    listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (base === SongPlayer.exoPlayer) listener.onMediaItemTransition(mediaItem, reason)
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    if (base === SongPlayer.exoPlayer) listener.onMediaMetadataChanged(this@AutoPlayer.mediaMetadata)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (base === SongPlayer.exoPlayer && !SongPlayer.isCrossfadeActive()) {
                        listener.onPlayerError(error)
                    }
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (base === SongPlayer.exoPlayer) listener.onEvents(this@AutoPlayer, events)
                }
            }
            listenerWrappers[listener] = wrapper
            super.addListener(wrapper)
        }

        override fun removeListener(listener: Player.Listener) {
            sessionListeners.remove(listener)
            val wrapper = listenerWrappers.remove(listener)
            if (wrapper != null) {
                super.removeListener(wrapper)
            } else {
                super.removeListener(listener)
            }
        }

        /** Fuerza a la sesión (y por tanto a Android Auto) a releer los metadatos. */
        fun dispatchPlaybackRefresh() {
            val events = Player.Events(
                androidx.media3.common.FlagSet.Builder()
                    .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                    .add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                    .add(Player.EVENT_IS_PLAYING_CHANGED)
                    .build()
            )
            sessionListeners.forEach { listener ->
                listener.onPlaybackStateChanged(playbackState)
                listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                listener.onIsPlayingChanged(isPlaying)
                listener.onEvents(this, events)
            }
        }

        fun dispatchMetadataRefresh() {
            val md = mediaMetadata
            val events = Player.Events(
                androidx.media3.common.FlagSet.Builder()
                    .add(Player.EVENT_MEDIA_METADATA_CHANGED)
                    .build()
            )
            sessionListeners.forEach {
                it.onMediaMetadataChanged(md)
                it.onEvents(this, events)
            }
        }

        override fun getMediaMetadata(): MediaMetadata = enrichMetadata(super.getMediaMetadata())

        override fun getCurrentMediaItem(): MediaItem? {
            val item = super.getCurrentMediaItem() ?: return null
            return item.buildUpon()
                .setMediaMetadata(enrichMetadata(item.mediaMetadata))
                .build()
        }

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_PLAY_PAUSE)
                .add(COMMAND_SET_REPEAT_MODE)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_PLAY_PAUSE, COMMAND_SET_REPEAT_MODE -> true

            else -> super.isCommandAvailable(command)
        }

        override fun getRepeatMode(): Int = when (currentSongState.repeat.value) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }

        override fun setRepeatMode(repeatMode: Int) {
            val mode = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            currentSongState.updateRepeatState(mode)
            // Always ensure the underlying ExoPlayer repeatMode stays REPEAT_MODE_OFF.
            // Spotui manages single-track / all-track looping at the queue & PlaybackService level.
            // If ExoPlayer itself is set to REPEAT_MODE_ONE or REPEAT_MODE_ALL on a single-item
            // timeline, ExoPlayer silently loops the single item internally and NEVER emits STATE_ENDED.
            base.repeatMode = Player.REPEAT_MODE_OFF
        }

        override fun play() {
            SongPlayer.play()
        }

        override fun pause() {
            SongPlayer.pause()
        }

        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = advance(forward = true)
        override fun seekToNextMediaItem() = advance(forward = true)
        override fun seekToPrevious() = advance(forward = false)
        override fun seekToPreviousMediaItem() = advance(forward = false)
    }

    // ── Carátula del "reproduciendo ahora" (Android Auto) ─────────────────

    private var artworkRefreshJob: kotlinx.coroutines.Job? = null

    /** Canción que la app considera actual, resuelta sin depender de nombres de campos extra. */
    private fun currentSong(): SongsModel? {
        val id = currentSongState.songId.value
        return trackById["song/$id"]
            ?: currentSongState.queue.value.firstOrNull { it.id == id }
    }

    /** Completa los metadatos del item actual con título/artista y los bytes de la carátula. */
    private fun enrichMetadata(base: MediaMetadata): MediaMetadata {
        val song = currentSong() ?: return base
        val builder = base.buildUpon()
        if (base.title.isNullOrBlank()) builder.setTitle(song.title)
        if (base.artist.isNullOrBlank()) builder.setArtist(song.singer)
        if (base.albumTitle.isNullOrBlank()) builder.setAlbumTitle(song.album)
        com.music.spotui.util.ArtworkHelper.attachArtwork(
            builder, applicationContext, song.coverUri, song.id.toString(), song.url
        )
        PlaybackArtwork.get(song.id.toString())?.let {
            builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
    }

    private fun refreshNowPlayingArtwork() {
        if (showingWeb) return
        activeForwardingPlayer?.dispatchMetadataRefresh()
    }

    /**
     * Precarga la carátula de la canción actual (y las 2 siguientes) y la republica en
     * cuanto está lista. Además reenvía los metadatos 3 veces en los primeros segundos:
     * Android Auto a veces descarta la primera actualización si llega mientras cambia de pista.
     */
    private fun ensureCurrentArtwork() {
        val song = currentSong() ?: return
        val songId = song.id
        val queue = currentSongState.queue.value
        val idx = queue.indexOfFirst { it.id == songId }
        val targets = buildList {
            add(song)
            if (idx >= 0) for (i in 1..2) queue.getOrNull(idx + i)?.let { add(it) }
        }
        targets.forEach { s ->
            PlaybackArtwork.ensure(applicationContext, s.id.toString(), s.coverUri) {
                if (s.id == songId && currentSong()?.id == songId) refreshNowPlayingArtwork()
            }
        }
        artworkRefreshJob?.cancel()
        artworkRefreshJob = serviceScope.launch {
            for (d in longArrayOf(800, 2000, 4000)) {
                kotlinx.coroutines.delay(d)
                if (currentSong()?.id != songId) return@launch
                refreshNowPlayingArtwork()
            }
        }
    }

    /** Advance the in-app queue one step in the given direction and start it. */
    private fun advance(forward: Boolean) {
        if (forward) SongPlayer.next(applicationContext) else SongPlayer.previous(applicationContext)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession



    /**
     * Builds the notification custom layout: [repeat button | close button].
     * The repeat button icon reflects the current [mode].
     */
    private fun buildCustomLayout(mode: RepeatMode): ImmutableList<CommandButton> {
        val repeatIconRes = when (mode) {
            RepeatMode.OFF -> R.drawable.ic_repeat_off
            RepeatMode.ONE -> R.drawable.ic_repeat_one
            RepeatMode.ALL -> R.drawable.ic_repeat
        }
        val repeatLabel = when (mode) {
            RepeatMode.OFF -> "Repeat off"
            RepeatMode.ONE -> "Repeat one"
            RepeatMode.ALL -> "Repeat all"
        }
        val repeatButton = CommandButton.Builder()
            .setDisplayName(repeatLabel)
            .setSessionCommand(SessionCommand("ACTION_REPEAT", Bundle.EMPTY))
            .setIconResId(repeatIconRes)
            .build()

        val shuffleIconRes = if (currentSongState.shuffle.value) R.drawable.ic_player_shuffle else R.drawable.ic_player_shuffle // need to add or check if there is an inactive shuffle icon
        val shuffleLabel = if (currentSongState.shuffle.value) "Shuffle on" else "Shuffle off"
        val shuffleButton = CommandButton.Builder()
            .setDisplayName(shuffleLabel)
            .setSessionCommand(SessionCommand("ACTION_SHUFFLE", Bundle.EMPTY))
            .setIconResId(shuffleIconRes)
            .build()

        val closeButton = CommandButton.Builder()
            .setDisplayName("Close")
            .setSessionCommand(SessionCommand("ACTION_CLOSE", Bundle.EMPTY))
            .setIconResId(R.drawable.ic_close)
            .build()

        return ImmutableList.of(repeatButton, shuffleButton, closeButton)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Intercept media button events at the earliest point — BEFORE the session
         * checks command availability or calls onPlayerCommandRequest. This ensures
         * next/previous work even when MacroDroid dispatches a key event without
         * being a connected MediaController.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            android.util.Log.d(
                "PlaybackService",
                "onMediaButtonEvent: keyEvent=$keyEvent action=${intent.action}"
            )
            if (keyEvent == null) {
                android.util.Log.w(
                    "PlaybackService",
                    "onMediaButtonEvent: no KeyEvent in intent extras=${intent.extras?.keySet()}"
                )
                return false
            }
            android.util.Log.d(
                "PlaybackService",
                "onMediaButtonEvent: keyCode=${keyEvent.keyCode} action=${keyEvent.action}"
            )
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return false
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    android.util.Log.d(
                        "PlaybackService",
                        "onMediaButtonEvent: handling KEYCODE_MEDIA_NEXT"
                    )
                    SongPlayer.next(applicationContext)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    android.util.Log.d(
                        "PlaybackService",
                        "onMediaButtonEvent: handling KEYCODE_MEDIA_PREVIOUS"
                    )
                    SongPlayer.previous(applicationContext)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    android.util.Log.d(
                        "PlaybackService",
                        "onMediaButtonEvent: KEYCODE_MEDIA_PLAY_PAUSE (not intercepting)"
                    )
                }
            }
            return false
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val baseResult = super.onConnect(session, controller)
            val sessionCommands = baseResult.availableSessionCommands.buildUpon()
                .add(SessionCommand("ACTION_CLOSE", Bundle.EMPTY))
                .add(SessionCommand("ACTION_REPEAT", Bundle.EMPTY))
                .add(SessionCommand("ACTION_SHUFFLE", Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(baseResult.availablePlayerCommands)
                .setCustomLayout(buildCustomLayout(currentSongState.repeat.value))
                .build()
        }


        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "ACTION_CLOSE" -> {
                    // Never kill the whole Android process (it can corrupt ongoing
                    // writes and bypass service/session cleanup).
                    SongPlayer.stop()
                    pauseAllPlayersAndStopSelf()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                "ACTION_REPEAT" -> {
                    // Cycle OFF → ALL → ONE → OFF (matches full-screen UI)
                    val next = when (currentSongState.repeat.value) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    currentSongState.updateRepeatState(next)
                    // Update the custom layout so the notification icon reflects the new mode
                    val newLayout = buildCustomLayout(next)
                    mediaSession?.setCustomLayout(newLayout)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                "ACTION_SHUFFLE" -> {
                    currentSongState.updateShuffleState(!currentSongState.shuffle.value)
                    val newLayout = buildCustomLayout(currentSongState.repeat.value)
                    mediaSession?.setCustomLayout(newLayout)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val logoUri = "android.resource://${packageName}/${R.drawable.logo}"
            return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "spotui", logoUri), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val children = childrenOf(parentId)
            // Automotive clients are allowed to request small pages (including 1).
            // Returning the complete library regardless of page could make UI freeze.
            val from = page.toLong().coerceAtLeast(0) * pageSize.coerceAtLeast(1)
            val pageItems = if (page < 0 || pageSize <= 0 || from >= children.size) {
                emptyList()
            } else {
                children.drop(from.toInt()).take(pageSize)
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(pageItems), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            trackById[mediaId]?.let { LibraryResult.ofItem(playable(it), null) }
                ?: folderById[mediaId]?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE),
        )

        // A browsed track was tapped in the car: queue the list it came from and
        // play through our own engine (streams are resolved lazily per track, so
        // we never hand the session a playlist of URIs).
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            val song = requested?.let { trackById[it.mediaId] }
            val resolvedQueue: List<MediaItem>
            val resolvedIndex: Int
            if (song != null) {
                val queue = queueByTrackId[requested?.mediaId] ?: listOf(song)
                currentSongState.updateQueue(queue)
                val idx = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                currentSongState.updateSongState(
                    song.coverUri, song.title, song.singer, true,
                    song.id, idx, song.album,
                )
                ensureCurrentArtwork()
                SongPlayer.playSong(song.url, applicationContext, "song/${song.id}")
                resolvedQueue = queue.map { playable(it) }
                resolvedIndex = idx
            } else {
                resolvedQueue = mediaItems
                resolvedIndex = startIndex.coerceAtLeast(0)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolvedQueue, resolvedIndex, startPositionMs),
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val persisted = com.music.spotui.data.preferences
                .loadLastPlayback(applicationContext)
            val currentQueue = currentSongState.queue.value.ifEmpty {
                com.music.spotui.data.preferences.loadLastQueue(applicationContext)
                    .ifEmpty { persisted?.let { listOf(it.first) }.orEmpty() }
            }
            if (currentQueue.isNotEmpty()) {
                val curId = currentSongState.songId.value
                    .takeIf { it != 0 } ?: persisted?.first?.id
                val idx = currentQueue.indexOfFirst { it.id == curId }.coerceAtLeast(0)
                val items = currentQueue.map { playable(it) }
                val song = currentQueue[idx]
                val position = if (currentSongState.queue.value.isNotEmpty())
                    SongPlayer.getCurrentPosition().coerceAtLeast(0L)
                else persisted?.second?.coerceAtLeast(0L) ?: 0L

                if (currentSongState.queue.value.isEmpty()) {
                    currentSongState.updateQueue(currentQueue)
                    currentSongState.updateSongState(
                        song.coverUri, song.title, song.singer,
                        false, song.id, idx, song.album
                    )
                }
                if (isForPlayback) {
                    ensureCurrentArtwork()
                    if (position > 0L) SongPlayer.setRestorePoint(song.url, position)
                    SongPlayer.playSong(song.url, applicationContext, "song/${song.id}")
                }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(items, idx, position)
                )
            }
            return super.onPlaybackResumption(mediaSession, controller, isForPlayback)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            if (query.isNotBlank()) {
                try {
                    val songs = lastSuccess(repository.searchSongs(query)).orEmpty()
                    searchCache[query] = songs
                    registerTracks("search/$query", songs)
                    session.notifySearchResultChanged(browser, query, songs.size, params)
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Error in onSearch for query: $query", e)
                }
            }
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val songs = searchCache[query].orEmpty()
            val all = songs.map { playable(it) }
            val from = page.toLong().coerceAtLeast(0) * pageSize.coerceAtLeast(1)
            val items = if (page < 0 || pageSize <= 0 || from >= all.size) {
                emptyList()
            } else {
                all.drop(from.toInt()).take(pageSize)
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = try {
        // Enforce a strict fallback timeout for Android Auto responsiveness
        kotlinx.coroutines.withTimeoutOrNull(8000) {
            when {
                // Four tabs maximum is widely supported by Android Auto.
                parentId == ROOT -> listOf(
                    folder(NODE_HOME, "Inicio"),
                    folder(NODE_RECENTS, "Recientes"),
                    folder(NODE_BROWSE, "Explorar"),
                    folder(NODE_LIBRARY, "Tu biblioteca")
                )

                parentId == NODE_HOME -> listOf(
                    folder(NODE_RECENTS, "Escuchado recientemente"),
                    folder(NODE_PINNED, "Fijados"),
                    folder(NODE_LIKED, "Canciones que te gustan"),
                    folder(NODE_PLAYLISTS, "Tus playlists"),
                    folder(NODE_DOWNLOADS, "Descargas"),
                    folder(NODE_PODCASTS, "Podcasts", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
                )

                parentId == NODE_BROWSE -> listOf(
                    folder(NODE_PLAYLISTS, "Playlists"),
                    folder(NODE_ALBUMS, "Álbumes", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    folder(NODE_ARTISTS, "Artistas", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                    folder(NODE_PODCASTS, "Podcasts y programas", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
                    folder(NODE_SONGS, "Toda la música")
                )

                parentId == NODE_LIBRARY -> listOf(
                    folder(NODE_LIKED, "Canciones que te gustan"),
                    folder(NODE_PLAYLISTS, "Playlists"),
                    folder(NODE_ALBUMS, "Álbumes", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    folder(NODE_ARTISTS, "Artistas", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                    folder(NODE_PODCASTS, "Podcasts que sigues", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
                    folder(NODE_DOWNLOADS, "Descargas"),
                    folder(NODE_LOCAL, "Archivos locales"),
                    folder(NODE_SONGS, "Todas las canciones")
                )

                parentId == NODE_RECENTS -> {
                    val recent = com.music.spotui.data.preferences.getListeningHistory(applicationContext)
                        .take(75)
                        .filter { it.url.isNotBlank() }
                        .distinctBy { it.songId }
                        .map { entry ->
                            SongsModel(
                                id = entry.songId,
                                title = entry.title,
                                singer = entry.singer,
                                album = entry.album,
                                coverUri = entry.image,
                                url = entry.url
                            )
                        }
                    registerTracks(NODE_RECENTS, recent)
                }

                parentId == NODE_LOCAL -> registerTracks(
                    NODE_LOCAL,
                    com.music.spotui.data.preferences.getLocalSongs(applicationContext)
                )

                parentId == NODE_PODCASTS ->
                    com.music.spotui.data.preferences.SubscribedPodcastPref
                        .getSubscribedPodcasts(applicationContext)
                        .filter { it.id.isNotBlank() && !it.id.startsWith("itunes:") }
                        .map { show ->
                            val safeId = android.net.Uri.encode(show.id)
                            podcastById[safeId] = show
                            folder(
                                "podcast/$safeId",
                                show.name,
                                show.coverUri,
                                mediaType = MediaMetadata.MEDIA_TYPE_PODCAST
                            )
                        }

                parentId.startsWith("podcast/") -> {
                    val id = parentId.removePrefix("podcast/")
                    val show = podcastById[id]
                    if (show == null) emptyList()
                    else {
                        // Return the first YouTube page quickly. Do not wait for all
                        // podcast continuations while the driver is browsing.
                        val episodes = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                            com.music.spotui.providers.PodcastProvider
                                .getShowEpisodesFlow(show.id, show.name)
                                .first()
                        }.orEmpty()
                        registerTracks(parentId, episodes)
                    }
                }

                parentId == NODE_LIKED ->
                    registerTracks(
                        NODE_LIKED,
                        lastSuccess(repository.provideLikedSongs()).orEmpty()
                    )

                parentId == NODE_DOWNLOADS ->
                    registerTracks(
                        NODE_DOWNLOADS,
                        com.music.spotui.data.preferences.getDownloadedSongs(applicationContext),
                    )

                parentId == NODE_PINNED -> {
                    val pinned = com.music.spotui.data.preferences.PinnedLibraryPref.get(applicationContext)
                    libraryEntries().filter { it.spotifyId in pinned && !it.isPodcast }.map { entry ->
                        val mediaId = when {
                            entry.isPlaylist -> "playlist/${entry.spotifyId}"
                            else -> "album/${android.net.Uri.encode(entry.name)}/${android.net.Uri.encode(entry.artists)}"
                        }
                        folder(mediaId, entry.name, entry.coverUri)
                    }
                }

                parentId == NODE_PLAYLISTS ->
                    libraryEntries().filter {
                        it.isPlaylist && it.spotifyId != Api.LIKED_SONGS_ID && it.spotifyId != Api.DOWNLOADS_ID
                    }.map {
                        folder(
                            "playlist/${it.spotifyId}",
                            it.name,
                            it.coverUri,
                            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        )
                    }

                parentId == NODE_ALBUMS ->
                    libraryEntries().filter { !it.isPlaylist && !it.isPodcast }.map {
                        folder(
                            "album/${android.net.Uri.encode(it.name)}/${android.net.Uri.encode(it.artists)}",
                            it.name,
                            it.coverUri,
                            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        )
                    }

                parentId == NODE_ARTISTS ->
                    lastSuccess(repository.provideArtists()).orEmpty().map {
                        folder(
                            "artist/${android.net.Uri.encode(it.name)}",
                            it.name,
                            it.coverUri,
                            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                        )
                    }

                parentId == NODE_SONGS ->
                    registerTracks(
                        NODE_SONGS,
                        lastSuccess(repository.provideSongs()).orEmpty()
                    )

                parentId.startsWith("playlist/") -> {
                    val playlistId = parentId.removePrefix("playlist/")
                    val songs = if (playlistId.startsWith("local_pl_")) {
                        com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylist(applicationContext, playlistId)?.songs
                    } else {
                        lastSuccess(repository.providePlaylistSongs(playlistId))
                            ?: com.music.spotui.data.preferences.OfflineCollectionsPref.getCollection(applicationContext, playlistId)?.songs
                    }
                    registerTracks(parentId, songs.orEmpty())
                }

                parentId.startsWith("album/") -> {
                    val parts = parentId.removePrefix("album/").split('/')
                    val name = android.net.Uri.decode(parts.getOrElse(0) { "" })
                    val artist = android.net.Uri.decode(parts.getOrElse(1) { "" })
                    registerTracks(
                        parentId,
                        lastSuccess(repository.provideAlbumSongs(name, artist)).orEmpty()
                    )
                }

                parentId.startsWith("artist/") -> {
                    val artistName = android.net.Uri.decode(parentId.removePrefix("artist/"))
                    registerTracks(
                        parentId,
                        lastSuccess(repository.provideArtistSongs(artistName)).orEmpty()
                    )
                }

                else -> emptyList()
            }
        } ?: emptyList()
    } catch (e: Exception) {
        android.util.Log.e("PlaybackService", "Error retrieving children for $parentId", e)
        emptyList()
    }

    private suspend fun libraryEntries(): List<com.music.spotui.data.entity.LibraryEntry> {
        val remote = lastSuccess(repository.provideLibrary()).orEmpty()
        val localPlaylists = com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylists(applicationContext).map { pl ->
            com.music.spotui.data.entity.LibraryEntry(
                spotifyId = pl.id,
                name = pl.name,
                subtitle = "Local Playlist • ${pl.songs.size} song" + (if (pl.songs.size == 1) "" else "s"),
                coverUri = pl.coverUri,
                isPlaylist = true,
                isLocal = true,
            )
        }
        val offlineCollections = com.music.spotui.data.preferences.OfflineCollectionsPref.getOfflineCollections(applicationContext).map { col ->
            com.music.spotui.data.entity.LibraryEntry(
                spotifyId = col.id,
                name = col.name,
                subtitle = if (col.isPlaylist) "Playlist • Available offline" else "Album • ${col.artists} • Available offline",
                coverUri = col.coverUri,
                isPlaylist = col.isPlaylist,
                artists = col.artists,
            )
        }
        val merged = remote + localPlaylists + offlineCollections
        return merged.distinctBy { entry ->
            val cleanId = entry.spotifyId.removePrefix("playlist/").removePrefix("album/").trim()
            if (cleanId.isNotBlank()) cleanId else entry.name.trim().lowercase()
        }
    }

    /** Runs a paged/cached response flow to completion and keeps the final data. */
    private suspend fun <T> lastSuccess(flow: Flow<Response<T>>): T? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        var result: T? = null
        try {
            // Android Auto requires quick responses to avoid infinite loading screens.
            // An 8000ms timeout ensures we never hang the media browser thread.
            kotlinx.coroutines.withTimeoutOrNull(8000) {
                flow.collect { response ->
                    if (response is Response.Success) {
                        result = response.data
                        // Found a success emission (cached or fresh). Cancel immediately to return fast.
                        throw kotlinx.coroutines.CancellationException("Success received")
                    } else if (response is Response.Error) {
                        throw kotlinx.coroutines.CancellationException("Error received")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Expected cancellation to stop collecting
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Error in lastSuccess flow collection", e)
        }
        result
    }

    private fun registerTracks(parentId: String, songs: List<SongsModel>): List<MediaItem> {
        songs.forEach { song ->
            trackById["song/${song.id}"] = song
            queueByTrackId["song/${song.id}"] = songs
        }
        // Do not queue artwork network requests for the entire library at once:
        // Android Auto renders only a window of visible rows. Keep background
        // cover prefetch bounded; never hold browse callbacks waiting for it.
        songs.take(36).forEach { song ->
            com.music.spotui.util.ArtworkHelper.ensureDownloadedCover(applicationContext, song)
        }
        return songs.map { playable(it) }
    }

    private fun folder(
        id: String,
        title: String,
        coverUri: String = "",
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    ): MediaItem {
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        // Android Auto controls the final layout. These are presentation hints
        // understood by compatible head units (grid for albums, list for tracks).
        val styles = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        builder.setExtras(styles)
        if (coverUri.startsWith("android.resource://")) {
            builder.setArtworkUri(android.net.Uri.parse(coverUri))
        } else if (coverUri.isNotBlank()) {
            com.music.spotui.util.ArtworkHelper.ensureDownloadedCover(this, id, coverUri)
            com.music.spotui.util.ArtworkHelper.attachArtwork(
                builder, this, coverUri, id.removePrefix("song/").removePrefix("playlist/").removePrefix("album/").removePrefix("artist/")
            )
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(builder.build())
            .build().also { folderById[id] = it }
    }

    private fun playable(song: SongsModel): MediaItem {
        val builder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.singer)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(
                if (song.url.startsWith("yt_ep:") || song.url.startsWith("episode:"))
                    MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                else MediaMetadata.MEDIA_TYPE_MUSIC
            )
        com.music.spotui.util.ArtworkHelper.attachArtwork(
            builder, this, song.coverUri, song.id.toString(), song.url
        )
        PlaybackArtwork.get(song.id.toString())?.let {
            builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return MediaItem.Builder()
            .setMediaId("song/${song.id}")
            .setMediaMetadata(builder.build())
            .build()
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        // Android Auto calls its legacy browser synchronously on the service's
        // main thread. Keeping browse/database/network work off Main is essential.
        carBrowseScope.launch {
            try {
                f.set(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                f.cancel(false)
            } catch (e: Exception) {
                android.util.Log.e("SpotuiAuto", "Library browse failed", e)
                f.setException(e)
            }
        }
        return f
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PlaybackLifecycleDiagnostics.record(this, "service_start action=${intent?.action} flags=$flags")
        // Buttons are explicit PendingIntents to this existing service, not implicit
        // media broadcasts which HyperOS may defer when the UI is backgrounded.
        when (intent?.action) {
            ACTION_NOTIFICATION_TOGGLE -> {
                SongPlayer.togglePlay()
                scheduleMediaNotificationRefresh()
                return START_STICKY
            }
            ACTION_NOTIFICATION_PREVIOUS -> {
                SongPlayer.previous(applicationContext)
                scheduleMediaNotificationRefresh()
                return START_STICKY
            }
            ACTION_NOTIFICATION_NEXT -> {
                SongPlayer.next(applicationContext)
                scheduleMediaNotificationRefresh()
                return START_STICKY
            }
            ACTION_NOTIFICATION_CLOSE -> {
                taskRemovalCheckJob?.cancel()
                SpotifyWebPlayer.pause()
                SongPlayer.stop()
                currentSongState.updatePlayingState(false)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(PLAYBACK_NOTIFICATION_ID)
                pauseAllPlayersAndStopSelf()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTrimMemory(level: Int) {
        // Log memory pressure without touching the audio player or its media
        // session: an Activity stop must never dispose the playback engine.
        PlaybackLifecycleDiagnostics.record(this, "service_trim_memory level=$level " +
            "playing=${SongPlayer.isPlaying()} preparing=${SongPlayer.isPreparingTrack()}")
        // Search results and decoded artwork can be rebuilt; active song/player
        // and its queue must never be evicted when another app needs RAM.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            searchCache.clear()
            PlaybackArtwork.releaseMemoryCache()
        }
        super.onTrimMemory(level)
    }

    /**
     * HyperOS can invoke onTaskRemoved during Recents reparenting while Spotui's
     * Activity still exists. An immediate SongPlayer.stop() here previously killed
     * audio just by opening Google. Only act once Android's OWN task list confirms
     * that the Spotui task has actually disappeared, twice, after a grace period.
     * If the query is unavailable/ambiguous, favor uninterrupted playback.
     */
    private fun hasSpotuiTaskInRecents(): Boolean? = runCatching {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.appTasks.any { task ->
            val info = task.taskInfo
            info?.baseActivity?.packageName == packageName ||
                info?.topActivity?.packageName == packageName ||
                info?.baseIntent?.component?.packageName == packageName
        }
    }.onFailure {
        android.util.Log.w("SpotuiLifecycle", "Unable to inspect app tasks", it)
    }.getOrNull()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val playingOrPreparing = isPlaybackOngoing() ||
            SongPlayer.isResolvingOrPlaying() || SongPlayer.isPreparingTrack() ||
            SpotifyWebPlayer.isPlaying
        PlaybackLifecycleDiagnostics.record(this,
            "task_removed_candidate active=$playingOrPreparing taskPresent=${hasSpotuiTaskInRecents()}")

        if (!playingOrPreparing) {
            // Official Media3 behavior for genuinely idle playback.
            super.onTaskRemoved(rootIntent)
            return
        }

        taskRemovalCheckJob?.cancel()
        taskRemovalCheckJob = serviceScope.launch {
            kotlinx.coroutines.delay(1_500L)
            val firstCheck = hasSpotuiTaskInRecents()
            if (firstCheck != false) {
                PlaybackLifecycleDiagnostics.record(this@PlaybackService,
                    "task_removal_ignored firstCheck=$firstCheck")
                return@launch
            }
            kotlinx.coroutines.delay(1_500L)
            val secondCheck = hasSpotuiTaskInRecents()
            if (secondCheck != false) {
                PlaybackLifecycleDiagnostics.record(this@PlaybackService,
                    "task_removal_ignored secondCheck=$secondCheck")
                return@launch
            }
            PlaybackLifecycleDiagnostics.record(this@PlaybackService,
                "task_removal_confirmed stopping_player")
            SpotifyWebPlayer.pause()
            SongPlayer.stop()
            currentSongState.updatePlayingState(false)
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackLifecycleDiagnostics.record(this, "service_destroy " +
            "fgs=${isPlaybackOngoing()} playing=${SongPlayer.isPlaying()} " +
            "preparing=${SongPlayer.isPreparingTrack()} crossfade=${SongPlayer.isCrossfadeActive()}")
        android.util.Log.w("SpotuiTransition", "PlaybackService onDestroy, " +
            "state=${SongPlayer.exoPlayer?.playbackState} " +
            "isPlaying=${SongPlayer.isPlaying()} crossfade=${SongPlayer.isCrossfadeActive()}")
        artworkRefreshJob?.cancel()
        notificationRefreshJob?.cancel()
        taskRemovalCheckJob?.cancel()
        if (ownsPlaybackForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            ownsPlaybackForeground = false
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(PLAYBACK_NOTIFICATION_ID)
        serviceScope.cancel()
        carBrowseScope.cancel()
        runCatching { unregisterReceiver(mediaControlReceiver) }
        SongPlayer.exoPlayer?.removeListener(playerListener)
        SongPlayer.onPlayerCreated = null
        SongPlayer.onPlayerSwapped = null
        SongPlayer.onPlaybackIntentChanged = null
        SpotifyWebPlayer.onStateChanged = null
        webPlayer?.release()
        webPlayer = null
        searchCache.clear()
        folderById.clear()
        podcastById.clear()
        mediaSession?.release()
        mediaSession = null
        SongPlayer.release() // release AudioTrack, decoders and timers on service stop
        super.onDestroy()
    }
}
