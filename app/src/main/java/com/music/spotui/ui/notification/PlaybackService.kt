package com.music.spotui.ui.notification

import android.app.PendingIntent
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

    // Android Auto browse cache: mediaId → track, and mediaId → the list it was
    // browsed from (so playing a track queues its whole playlist/album).
    private val trackById = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()
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
            currentSongState.updateBufferingState(playbackState == Player.STATE_BUFFERING)
            if (playbackState == Player.STATE_READY && (SongPlayer.exoPlayer?.playWhenReady == true)) {
                SongPlayer.acquireWakeLock(applicationContext, "spotui:playing", 12 * 60 * 60_000L)
            } else if ((playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) && !SongPlayer.isCrossfadeActive()) {
                SongPlayer.releaseWakeLock("spotui:playback_end")
            }
            if (playbackState == Player.STATE_ENDED) {
                if (SongPlayer.isCrossfadeActive()) {
                    // Ignore the old player's STATE_ENDED event during an active crossfade.
                    // The crossfade routine itself handles the transition and promotes the new player.
                    return
                }
                errorRetryCounts.clear()
                SongPlayer.acquireWakeLock(applicationContext, "spotui:advance", 60_000L)
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
                            } else {
                                SongPlayer.releaseWakeLock("spotui:advance")
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
                            } else {
                                SongPlayer.releaseWakeLock("spotui:advance")
                            }
                        } else {
                            SongPlayer.releaseWakeLock("spotui:advance")
                        }
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Mirror ExoPlayer's real play/pause state into the in-app UI so the
            // on-screen play button stays in sync when the notification controls are used.
            // Ignore transient stops/pauses from the outgoing player during an active crossfade.
            if (SongPlayer.isCrossfadeActive()) return
            if (isPlaying) {
                SongPlayer.acquireWakeLock(applicationContext, "spotui:playing", 12 * 60 * 60_000L)
            } else {
                SongPlayer.releaseWakeLock("spotui:paused")
            }
            if (!showingWeb) currentSongState.updatePlayingState(isPlaying)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
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
            SongPlayer.acquireWakeLock(applicationContext, "spotui:error_advance", 60_000L)
            if (songUrl.isNotBlank()) {
                SongPlayer.invalidateResolvedStream(songUrl)
            }
            advance(forward = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SongPlayer.ensureCreated(this)

        // Order notification buttons: [repeat | prev | play/pause | next | close]
        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            init {
                setSmallIcon(R.drawable.ic_spotui_notification)
            }

            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                val base =
                    super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                android.util.Log.d(
                    "PlaybackService",
                    "getMediaButtons: playerCommands=$playerCommands"
                )
                android.util.Log.d(
                    "PlaybackService",
                    "getMediaButtons: baseButtons size=${base.size}"
                )
                for (b in base) {
                    android.util.Log.d(
                        "PlaybackService",
                        "getMediaButtons: button displayName=${b.displayName}, customAction=${b.sessionCommand?.customAction}, playerCommand=${b.playerCommand}"
                    )
                }
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

        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
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


        // When a crossfade promotes a new ExoPlayer instance, re-bind the session to it
        // (runs on the main thread; setPlayer is the supported way to swap a session's player).
        SongPlayer.onPlayerCreated = { newPlayer ->
            if (!showingWeb) mediaSession?.player = wrap(newPlayer)
            newPlayer.addListener(playerListener)
        }
        SongPlayer.onPlayerSwapped = { oldPlayer, newPlayer ->
            oldPlayer?.removeListener(playerListener)
            if (!showingWeb) {
                val wrapped = wrap(newPlayer)
                mediaSession?.player = wrapped
                activeForwardingPlayer?.dispatchMetadataRefresh()
            }
            newPlayer.addListener(playerListener)
            playerListener.onPlaybackStateChanged(newPlayer.playbackState)
            playerListener.onIsPlayingChanged(newPlayer.isPlaying)
            currentSongState.updatePlayingState(newPlayer.isPlaying || newPlayer.playWhenReady)
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
            if (SongPlayer.isCrossfadeActive()) return Player.STATE_READY
            return super.getPlaybackState()
        }

        override fun getPlayWhenReady(): Boolean {
            if (SongPlayer.isCrossfadeActive()) return true
            return super.getPlayWhenReady()
        }

        override fun isPlaying(): Boolean {
            if (SongPlayer.isCrossfadeActive()) return true
            return super.isPlaying()
        }

        override fun addListener(listener: Player.Listener) {
            sessionListeners.add(listener)
            val wrapper = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if ((SongPlayer.isCrossfadeActive() || base != SongPlayer.exoPlayer) && (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE)) {
                        return
                    }
                    listener.onPlaybackStateChanged(playbackState)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if ((SongPlayer.isCrossfadeActive() || base != SongPlayer.exoPlayer) && !isPlaying) {
                        return
                    }
                    listener.onIsPlayingChanged(isPlaying)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    listener.onMediaItemTransition(mediaItem, reason)
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    listener.onMediaMetadataChanged(mediaMetadata)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    listener.onPlayerError(error)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    listener.onEvents(player, events)
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

    // ── Android Auto browse tree ──────────────────────────────────────────

    private companion object {
        const val ROOT = "root"
        const val NODE_LIKED = "liked"
        const val NODE_DOWNLOADS = "downloads"
        const val NODE_PLAYLISTS = "playlists"
        const val NODE_ALBUMS = "albums"
        const val NODE_ARTISTS = "artists"
        const val NODE_SONGS = "songs"
    }

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
                    // exit the player and kill the process directly to terminate the app cleanly
                    SongPlayer.pause()
                    android.os.Process.killProcess(android.os.Process.myPid())
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
            LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            trackById[mediaId]?.let { LibraryResult.ofItem(playable(it), null) }
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
            val currentQueue = currentSongState.queue.value
            if (currentQueue.isNotEmpty()) {
                val curId = currentSongState.songId.value
                val idx = currentQueue.indexOfFirst { it.id == curId }.coerceAtLeast(0)
                val items = currentQueue.map { playable(it) }
                val song = currentQueue[idx]
                if (isForPlayback) {
                    ensureCurrentArtwork()
                    SongPlayer.playSong(song.url, applicationContext, "song/${song.id}")
                }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        idx,
                        SongPlayer.getCurrentPosition().coerceAtLeast(0L)
                    )
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
            val items = songs.map { playable(it) }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = try {
        // Enforce a strict fallback timeout for Android Auto responsiveness
        kotlinx.coroutines.withTimeoutOrNull(8000) {
            when {
                parentId == ROOT -> listOf(
                    folder(
                        NODE_LIKED,
                        "Liked Songs",
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    ),
                    folder(
                        NODE_DOWNLOADS,
                        "Downloads",
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    ),
                    folder(
                        NODE_PLAYLISTS,
                        "Playlists",
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    ),
                    folder(
                        NODE_ALBUMS,
                        "Albums",
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                    ),
                    folder(
                        NODE_ARTISTS,
                        "Artists",
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                    ),
                    folder(
                        NODE_SONGS,
                        "All Songs",
                        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    ),
                )

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
                    libraryEntries().filter { !it.isPlaylist }.map {
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
    private suspend fun <T> lastSuccess(flow: Flow<Response<T>>): T? {
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
        return result
    }

    private fun registerTracks(parentId: String, songs: List<SongsModel>): List<MediaItem> {
        songs.forEach { song ->
            trackById["song/${song.id}"] = song
            queueByTrackId["song/${song.id}"] = songs
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
        if (coverUri.isNotBlank()) {
            com.music.spotui.util.ArtworkHelper.ensureDownloadedCover(this, id, coverUri)
            com.music.spotui.util.ArtworkHelper.attachArtwork(
                builder, this, coverUri, id.removePrefix("song/").removePrefix("playlist/").removePrefix("album/").removePrefix("artist/")
            )
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(builder.build())
            .build()
    }

    private fun playable(song: SongsModel): MediaItem {
        val builder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.singer)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
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
        serviceScope.launch {
            try {
                f.set(block())
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        return f
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop playback + tear the service down when the app is swiped away.
        SongPlayer.pause()
        stopSelf()
    }

    override fun onDestroy() {
        artworkRefreshJob?.cancel()
        serviceScope.cancel()
        runCatching { unregisterReceiver(mediaControlReceiver) }
        SongPlayer.exoPlayer?.removeListener(playerListener)
        SongPlayer.onPlayerSwapped = null
        SpotifyWebPlayer.onStateChanged = null
        webPlayer?.release()
        webPlayer = null
        searchCache.clear()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
