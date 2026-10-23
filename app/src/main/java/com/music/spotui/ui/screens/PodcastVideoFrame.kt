package com.music.spotui.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.MainActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.*
import kotlin.math.abs
import java.util.Locale

/** Activity presentation state, not a second media session. A PiP window uses the same
 * muted video ExoPlayer and retains the podcast audio in SongPlayer/MediaSession. */
object PodcastVideoPipState {
    var isInPictureInPicture by mutableStateOf(false)
        internal set
}

/** The only video surface rendered while the activity is in system PiP. */
@Composable
fun PodcastPipContent(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black)) {
        VideoSurface(Modifier.fillMaxSize(), priority = 10)
    }
}

private fun Context.spotuiActivity(): MainActivity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is MainActivity) return context
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    return context as? MainActivity
}

private object PodcastVideoStreams {
    suspend fun resolve(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(22_000L) {
            val clients = listOf(
                YouTubeClient.WEB,
                YouTubeClient.ANDROID_VR_NO_AUTH,
                YouTubeClient.MOBILE
            )
            for (client in clients) {
                val response = YouTube.player(videoId, client = client).getOrNull() ?: continue
                if (response.playabilityStatus.status != "OK") continue
                val formats = (response.streamingData?.adaptiveFormats.orEmpty() +
                    response.streamingData?.formats.orEmpty())
                    .filter { it.width != null && it.height != null && it.mimeType.startsWith("video/") }
                    .distinctBy { it.itag }
                    .sortedWith(
                        compareByDescending<PlayerResponse.StreamingData.Format> {
                            // Prefer a supported efficient AVC/MP4 representation.
                            it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1")
                        }.thenByDescending { it.height?.coerceAtMost(720) ?: 0 }
                            .thenBy { if ((it.height ?: 0) > 720) it.height ?: 0 else 0 }
                    )
                for (format in formats) {
                    val resolved = format.url?.takeIf { it.startsWith("https://") }
                        ?: runCatching { NewPipeExtractor.getStreamUrl(format, videoId) }.getOrNull()
                    if (!resolved.isNullOrBlank() && resolved.startsWith("https://")) {
                        return@withTimeoutOrNull resolved to
                            if (format.mimeType.startsWith("video/webm")) MimeTypes.VIDEO_WEBM
                            else MimeTypes.VIDEO_MP4
                    }
                }
            }
            // Alternate stream extraction uses the existing NewPipe backend.
            // It contains actual video-only streams, not just thumbnails.
            val fallback = runCatching { YouTube.getNewPipeStreamUrls(videoId) }.getOrDefault(emptyList())
            fallback.firstOrNull { it.first in setOf(137, 136, 135, 134, 133, 22, 18) }
                ?.second?.takeIf { it.startsWith("https://") }?.let { it to MimeTypes.VIDEO_MP4 }
        }
    }
}

/**
 * Application-scoped, muted video companion. Audio and MediaSession remain owned by
 * SongPlayer. Unlike a composition-owned ExoPlayer, this keeps a single decoder,
 * position and source across tablet collapse, mini-player and full-screen transitions.
 * All entry points run on the main thread (the same thread as Compose and ExoPlayer).
 */
@OptIn(UnstableApi::class)
object PodcastVideoSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var activeVideoId by mutableStateOf<String?>(null)
        private set
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var resolveJob: Job? = null
    private var attachedView: PlayerView? = null
    // A mini-player can remain composed behind the full-screen navigation dialog.
    // Retain its target and hand the surface back automatically on dismissal.
    private val visibleTargets = LinkedHashMap<PlayerView, Int>()

    fun isActive(videoId: String?): Boolean = videoId != null && activeVideoId == videoId

    fun setEnabled(context: Context, videoId: String, enabled: Boolean) {
        if (!enabled) {
            stop()
            return
        }
        if (activeVideoId == videoId && (player != null || isLoading)) return
        stop()
        activeVideoId = videoId
        isLoading = true
        error = null
        val appContext = context.applicationContext
        resolveJob = scope.launch {
            val stream = runCatching { PodcastVideoStreams.resolve(videoId) }
                .onFailure { Log.w("SpotuiPodcastVideo", "Could not resolve video", it) }
                .getOrNull()
            if (activeVideoId != videoId) return@launch
            isLoading = false
            if (stream == null) {
                error = "Este episodio no ofrece vídeo compatible"
                return@launch
            }
            try {
                player = ExoPlayer.Builder(appContext).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.Builder().setUri(Uri.parse(stream.first))
                        .setMimeType(stream.second).build())
                    addListener(object : Player.Listener {
                        override fun onPlayerError(exception: PlaybackException) {
                            Log.w("SpotuiPodcastVideo", "Video decode/stream error", exception)
                            if (activeVideoId == videoId) error = "Error al reproducir vídeo"
                        }
                    })
                    playWhenReady = false
                    prepare()
                    val position = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
                    if (position > 0) seekTo(position)
                }
            } catch (ex: Exception) {
                Log.w("SpotuiPodcastVideo", "Video decoder initialization failed", ex)
                error = "No se ha podido abrir el vídeo"
            }
        }
    }

    fun stopIfDifferent(currentPodcastId: String?) {
        if (activeVideoId != null && activeVideoId != currentPodcastId) stop()
    }

    fun stop() {
        resolveJob?.cancel()
        resolveJob = null
        val old = player
        attachedView?.player = null
        attachedView = null
        visibleTargets.clear()
        player = null
        old?.release()
        activeVideoId = null
        error = null
        isLoading = false
    }

    fun sync(foreground: Boolean) {
        val video = player ?: return
        if (activeVideoId == null || error != null) return
        val audioPosition = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
        val playing = foreground && SongPlayer.isPlaying()
        if (video.playbackState != Player.STATE_IDLE && video.playbackState != Player.STATE_ENDED) {
            if (abs(video.currentPosition - audioPosition) > if (playing) 800L else 200L) {
                video.seekTo(audioPosition)
            }
        }
        if (video.playWhenReady != playing) video.playWhenReady = playing
    }

    fun attach(view: PlayerView, priority: Int) {
        visibleTargets[view] = priority
        updateSurface()
    }

    fun detach(view: PlayerView) {
        visibleTargets.remove(view)
        updateSurface()
    }

    private fun updateSurface() {
        val target = visibleTargets.entries.maxByOrNull { it.value }?.key
        if (target === attachedView) return
        val video = player ?: return
        PlayerView.switchTargetView(video, attachedView, target)
        attachedView = target
    }
}

/** Display surface only: creating or destroying this composable never restarts the video. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(modifier: Modifier = Modifier, priority: Int = 1) {
    val context = LocalContext.current
    val player = PodcastVideoSession.player
    if (player == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    val view = remember(context) {
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    DisposableEffect(view, player) {
        PodcastVideoSession.attach(view, priority)
        onDispose { PodcastVideoSession.detach(view) }
    }
    AndroidView(factory = { view }, modifier = modifier.background(Color.Black))
}

@Composable
fun PodcastVideoFrame(
    videoId: String,
    modifier: Modifier = Modifier,
    onUseAudio: () -> Unit,
    compact: Boolean = false,
    surfacePriority: Int = 1,
) {
    val error = PodcastVideoSession.error
    val active = PodcastVideoSession.isActive(videoId)
    var fullscreen by remember(videoId) { mutableStateOf(false) }
    val activity = LocalContext.current.spotuiActivity()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !active -> Unit
            error != null -> {
                if (!compact) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = onUseAudio) { Text("Continuar solo con audio") }
                }
            }
            fullscreen -> Unit // The single video surface is attached to the fullscreen dialog.
            else -> VideoSurface(Modifier.fillMaxSize(), priority = if (compact) 0 else surfacePriority)
        }
        if (active && !compact && error == null) {
            IconButton(
                onClick = { fullscreen = true },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            ) { Icon(Icons.Default.Fullscreen, contentDescription = "Vídeo a pantalla completa", tint = Color.White) }
        }
    }
    if (fullscreen && active && !PodcastVideoPipState.isInPictureInPicture) {
        // Control the *audio* session: the video decoder is muted and only mirrors
        // the original episode. Never seek/pause the secondary decoder directly.
        var positionMs by remember(videoId) { mutableLongStateOf(0L) }
        var durationMs by remember(videoId) { mutableLongStateOf(0L) }
        var audioPlaying by remember(videoId) { mutableStateOf(false) }
        var scrubbing by remember(videoId) { mutableStateOf(false) }
        var scrubFraction by remember(videoId) { mutableFloatStateOf(0f) }
        var controlsVisible by remember(videoId) { mutableStateOf(true) }
        var interactionCount by remember(videoId) { mutableIntStateOf(0) }
        fun refreshControls() { controlsVisible = true; interactionCount++ }
        // Time out only after the LAST user interaction; scrubbing never hides controls.
        LaunchedEffect(controlsVisible, interactionCount, scrubbing) {
            if (controlsVisible && !scrubbing) {
                delay(3_000L)
                controlsVisible = false
            }
        }
        LaunchedEffect(fullscreen, active, videoId) {
            while (fullscreen && active) {
                positionMs = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
                durationMs = SongPlayer.getDuration().coerceAtLeast(0L)
                audioPlaying = SongPlayer.isPlaying()
                delay(350L)
            }
        }
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // BoxWithConstraints uses BOTH available dimensions and safe drawing
            // insets, so 16:9 episodes never overflow tall/narrow devices.
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(),
                contentAlignment = Alignment.Center
            ) {
                val videoSize = PodcastVideoSession.player?.videoSize
                val videoRatio = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                    (videoSize.width.toFloat() / videoSize.height.toFloat() *
                        videoSize.pixelWidthHeightRatio).coerceIn(0.35f, 3.5f)
                } else 16f / 9f
                val fittedWidth = minOf(maxWidth, maxHeight * videoRatio)
                val fittedHeight = fittedWidth / videoRatio
                Box(
                    modifier = Modifier.width(fittedWidth).height(fittedHeight),
                    contentAlignment = Alignment.Center
                ) {
                    // Reattach the ONE persistent ExoPlayer surface at priority 3.
                    // The session and playback position survive dialog dismissal.
                    VideoSurface(Modifier.fillMaxSize(), priority = 3)
                }
                // A transparent layer above AndroidView catches taps even when
                // PlayerView consumes MotionEvents. Buttons sit above this layer.
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        controlsVisible = !controlsVisible
                        interactionCount++
                    }
                )
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(animationSpec = tween(180)),
                    exit = fadeOut(animationSpec = tween(200)),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (activity != null && PodcastVideoSession.player != null) {
                            TextButton(
                                onClick = {
                                    refreshControls()
                                    // Dismiss the separate Dialog window before Android
                                    // switches the Activity into its PiP task window.
                                    fullscreen = false
                                    activity.window.decorView.post {
                                        activity.enterPodcastPictureInPicture()
                                    }
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(20.dp))
                            ) { Text("PiP", color = Color.White) }
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = { fullscreen = false },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.68f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar vídeo a pantalla completa", tint = Color.White)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(animationSpec = tween(180)),
                    exit = fadeOut(animationSpec = tween(200)),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f), Color.Black)
                        ))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val validDuration = durationMs > 0L
                    val fraction = if (scrubbing) scrubFraction else if (validDuration)
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    else 0f
                    Slider(
                        value = fraction,
                        onValueChange = { scrubFraction = it; scrubbing = true; refreshControls() },
                        onValueChangeFinished = {
                            if (validDuration) SongPlayer.seekTo((scrubFraction * durationMs).toLong())
                            refreshControls()
                            scrubbing = false
                        },
                        enabled = validDuration,
                        modifier = Modifier.fillMaxWidth().height(35.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatPodcastTime(if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs),
                            color = Color.White, fontSize = 12.sp)
                        Text(formatPodcastTime(durationMs), color = Color.White, fontSize = 12.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { refreshControls(); SongPlayer.seekTo((SongPlayer.getCurrentPosition() - 15_000L).coerceAtLeast(0L)) }) {
                            Text("↶15", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                        IconButton(
                            onClick = { refreshControls(); SongPlayer.togglePlay() },
                            modifier = Modifier.size(56.dp).background(Color.White, CircleShape)
                        ) {
                            Icon(if (audioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (audioPlaying) "Pausar episodio" else "Reanudar episodio",
                                tint = Color.Black, modifier = Modifier.size(32.dp))
                        }
                        TextButton(onClick = {
                            refreshControls()
                            val end = SongPlayer.getDuration()
                            val next = SongPlayer.getCurrentPosition() + 15_000L
                            SongPlayer.seekTo(if (end > 0L) next.coerceAtMost(end) else next)
                        }) {
                            Text("15↷", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                    }
                }
                }
            }
        }
    }
}

private fun formatPodcastTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds / 60L) % 60L, seconds % 60L)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
