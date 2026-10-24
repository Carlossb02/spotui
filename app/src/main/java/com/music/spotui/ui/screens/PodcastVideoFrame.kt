package com.music.spotui.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.music.spotui.R
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

/** A concrete quality choice for one video-only stream of the same podcast. */
data class PodcastVideoQuality(
    val height: Int,
    val url: String,
    val mimeType: String,
    val muxed: Boolean = false,
) {
    val label: String get() = "${height}p"
}

private object PodcastVideoStreams {
    /** Preserve the best supported rendition per resolution; the first client
     *  sometimes provides only 360p progressive URLs while later clients have
     *  720p/1080p adaptive video-only tracks. All sources are resolved off-main. */
    suspend fun resolve(videoId: String): List<PodcastVideoQuality> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(16_000L) {
            val bestByHeight = LinkedHashMap<Int, PodcastVideoQuality>()
            val clients = listOf(YouTubeClient.WEB, YouTubeClient.ANDROID_VR_NO_AUTH, YouTubeClient.MOBILE)
            for (client in clients) {
                val response = runCatching { YouTube.player(videoId, client = client).getOrNull() }
                    .getOrNull() ?: continue
                if (response.playabilityStatus.status != "OK") continue
                val muxed = response.streamingData?.formats.orEmpty()
                val videoFormats = (response.streamingData?.adaptiveFormats.orEmpty() + muxed)
                    .filter { it.width != null && it.height != null && it.mimeType.startsWith("video/") }
                    // Prefer hardware-decoded H.264, then the muxed copy at the
                    // same resolution. Sort by height before limiting candidates.
                    .sortedWith(compareByDescending<PlayerResponse.StreamingData.Format> {
                        it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1")
                    }.thenByDescending { it in muxed }.thenByDescending { it.height ?: 0 })
                    .distinctBy { it.height }
                    .filter { (it.height ?: 0) in 144..2160 }
                    .take(12)
                for (format in videoFormats) {
                    val height = format.height ?: continue
                    if (bestByHeight.containsKey(height)) continue
                    val url = format.url?.takeIf { it.startsWith("https://") }
                        ?: runCatching { NewPipeExtractor.getStreamUrl(format, videoId) }.getOrNull()
                    if (!url.isNullOrBlank() && url.startsWith("https://")) {
                        bestByHeight[height] = PodcastVideoQuality(
                            height, url,
                            if (format.mimeType.startsWith("video/webm")) MimeTypes.VIDEO_WEBM
                                else MimeTypes.VIDEO_MP4,
                            muxed = format in muxed
                        )
                    }
                }
                // Stop network probing as soon as we can offer HD + fallback.
                if (bestByHeight.keys.any { it >= 720 } && bestByHeight.keys.any { it <= 480 }) break
            }
            if (bestByHeight.isEmpty()) {
                val fallback = runCatching { YouTube.getNewPipeStreamUrls(videoId) }.getOrDefault(emptyList())
                for ((itag, url) in fallback) {
                    val height = when (itag) {
                        137 -> 1080; 136 -> 720; 135 -> 480; 134 -> 360; 133 -> 240
                        22 -> 720; 18 -> 360; else -> continue
                    }
                    if (url.startsWith("https://")) bestByHeight.putIfAbsent(
                        height, PodcastVideoQuality(height, url, MimeTypes.VIDEO_MP4,
                            muxed = itag == 18 || itag == 22)
                    )
                }
            }
            bestByHeight.values.sortedByDescending { it.height }
        } ?: emptyList()
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
    // This is the SAME ExoPlayer instance already owned by SongPlayer/MediaSession.
    // Never allocate, mute, seek, or release a second decoder here.
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var qualities by mutableStateOf<List<PodcastVideoQuality>>(emptyList())
        private set
    var selectedQuality by mutableStateOf("")
        private set
    // Avoid attaching the intermediate mini/lateral surface when the fullscreen
    // dialog is transitioning directly into Android's PiP window.
    var enteringPictureInPicture by mutableStateOf(false)
        private set

    fun beginPipHandoff() { enteringPictureInPicture = true }
    fun finishPipHandoff() {
        enteringPictureInPicture = false
        updateSurface()
    }
    private var resolveJob: Job? = null
    private var appContext: Context? = null
    private var attachedView: PlayerView? = null
    private val visibleTargets = LinkedHashMap<PlayerView, Int>()
    // Briefly having no surface during fullscreen/mini/PiP hand-off is NOT a
    // request to disable the video track. Track disabling changes renderer
    // selection and may rebuffer the *same* ExoPlayer's podcast audio.
    private var surfaceHandoffJob: Job? = null
    private var videoTrackIdleJob: Job? = null
    private var isForeground = true
    private var videoTrackEnabled: Boolean? = null

    fun isActive(videoId: String?): Boolean = videoId != null && activeVideoId == videoId

    fun setEnabled(context: Context, videoId: String, enabled: Boolean) {
        if (!enabled) { stop(); return }
        if (activeVideoId == videoId && (player != null || isLoading)) return
        stop()
        activeVideoId = videoId
        isLoading = true
        error = null
        appContext = context.applicationContext
        resolveJob = scope.launch {
            val streams = runCatching { PodcastVideoStreams.resolve(videoId) }
                .onFailure { Log.w("SpotuiPodcastVideo", "Stream resolution failed", it) }
                .getOrNull().orEmpty()
            if (activeVideoId != videoId) return@launch
            isLoading = false
            if (streams.isEmpty()) {
                error = "Este episodio no ofrece vídeo compatible"
                return@launch
            }
            qualities = streams
            // 720p is the HD default on unmetered networks; 480p on cellular.
            // Higher available renditions (1080p etc.) remain in Settings.
            // Never limit the whole session to the first 360p muxed URL.
            val metered = runCatching {
                (appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager)?.isActiveNetworkMetered ?: true
            }.getOrDefault(true)
            val preferred = if (metered) 480 else 720
            val initial = streams.firstOrNull { it.height == preferred }
                ?: streams.filter { it.height in 480..preferred }.maxByOrNull { it.height }
                ?: streams.firstOrNull { it.height > preferred }
                ?: streams.maxByOrNull { it.height } ?: return@launch
            if (!applyQuality(initial)) {
                error = "No se puede activar el vídeo de este episodio"
            }
        }
    }

    private fun applyQuality(quality: PodcastVideoQuality): Boolean {
        val context = appContext ?: return false
        val ok = SongPlayer.enablePodcastVideo(
            context, quality.url, quality.mimeType, quality.muxed
        )
        if (!ok) return false
        selectedQuality = quality.label
        player = SongPlayer.exoPlayer
        videoTrackEnabled = null // New source resets track availability.
        updateSurface()
        return true
    }

    fun selectQuality(height: Int) {
        val target = qualities.firstOrNull { it.height == height } ?: return
        if (target.label == selectedQuality) return
        if (!applyQuality(target)) error = "No se pudo cambiar la calidad"
    }

    fun stopIfDifferent(currentPodcastId: String?) {
        if (activeVideoId != null && activeVideoId != currentPodcastId) stop()
    }

    fun stop() {
        resolveJob?.cancel()
        resolveJob = null
        surfaceHandoffJob?.cancel()
        videoTrackIdleJob?.cancel()
        attachedView?.player = null
        attachedView = null
        visibleTargets.clear()
        videoTrackEnabled = null
        player = null
        SongPlayer.disablePodcastVideo()
        activeVideoId = null
        isLoading = false
        error = null
        qualities = emptyList()
        selectedQuality = ""
        appContext = null
        enteringPictureInPicture = false
    }

    fun sync(foreground: Boolean) {
        isForeground = foreground
        updateTrackState()
    }

    fun attach(view: PlayerView, priority: Int) {
        visibleTargets[view] = priority
        surfaceHandoffJob?.cancel()
        updateSurface()
    }

    fun detach(view: PlayerView) {
        visibleTargets.remove(view)
        if (view !== attachedView) {
            updateSurface()
            return
        }
        // A destination surface generally appears a frame or two after its
        // predecessor is disposed. Retain the old target briefly rather than
        // tearing down the decoder midway through a navigation animation.
        surfaceHandoffJob?.cancel()
        if (visibleTargets.isNotEmpty()) {
            updateSurface()
        } else {
            surfaceHandoffJob = scope.launch {
                delay(220L)
                updateSurface()
            }
        }
    }

    private fun updateSurface() {
        val target = visibleTargets.entries.maxByOrNull { it.value }?.key
        val commonPlayer = player ?: return
        if (target !== attachedView) {
            // Media3 binds the *new* target before detaching the old one.
            PlayerView.switchTargetView(commonPlayer, attachedView, target)
            attachedView = target
        }
        updateTrackState()
    }

    private fun updateTrackState() {
        if (player == null) return
        val hasSurface = visibleTargets.isNotEmpty() || attachedView != null ||
            PodcastVideoPipState.isInPictureInPicture
        val shouldEnable = hasSurface && (isForeground || PodcastVideoPipState.isInPictureInPicture)
        if (shouldEnable) {
            videoTrackIdleJob?.cancel()
            videoTrackIdleJob = null
            setVideoTrackIfChanged(true)
        } else if (videoTrackEnabled != false && videoTrackIdleJob == null) {
            // Keep the selected video track throughout UI transitions; only
            // disable it once playback is genuinely offscreen for 1.5 seconds.
            videoTrackIdleJob = scope.launch {
                delay(1_500L)
                videoTrackIdleJob = null
                if ((!isForeground && !PodcastVideoPipState.isInPictureInPicture) ||
                    (visibleTargets.isEmpty() && attachedView == null)) {
                    setVideoTrackIfChanged(false)
                }
            }
        }
    }

    private fun setVideoTrackIfChanged(enabled: Boolean) {
        if (videoTrackEnabled == enabled) return
        videoTrackEnabled = enabled
        SongPlayer.enablePodcastVideoTrack(enabled)
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
        (LayoutInflater.from(context).inflate(R.layout.spotui_podcast_video_view, null, false) as PlayerView).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    // AndroidView must attach to its parent *before* Media3 receives its
    // Surface/TextureView. Otherwise ExoPlayer can render into a dead surface.
    AndroidView(
        factory = { view },
        modifier = modifier.background(Color.Black),
        update = { PodcastVideoSession.attach(it, priority) },
        onRelease = { PodcastVideoSession.detach(it) }
    )
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
    var fullscreenContentVisible by remember(videoId) { mutableStateOf(false) }
    val fullscreenTransitionScope = rememberCoroutineScope()
    fun dismissFullscreen() {
        fullscreenContentVisible = false
        fullscreenTransitionScope.launch {
            delay(230L) // Let the exit fade finish before removing the video view.
            fullscreen = false
        }
    }
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
            fullscreen || PodcastVideoSession.enteringPictureInPicture -> Unit
                // Do not briefly attach the mini/lateral view between fullscreen and PiP.
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
        LaunchedEffect(videoId) {
            // Allow the dialog window to be attached before animating the content.
            withFrameNanos { }
            fullscreenContentVisible = true
        }
        // Controls operate on the *same* Media3 A/V timeline. The surface
        // hand-off must never pause/reseek the underlying session.
        var positionMs by remember(videoId) { mutableLongStateOf(0L) }
        var durationMs by remember(videoId) { mutableLongStateOf(0L) }
        var audioPlaying by remember(videoId) { mutableStateOf(false) }
        var scrubbing by remember(videoId) { mutableStateOf(false) }
        var scrubFraction by remember(videoId) { mutableFloatStateOf(0f) }
        var controlsVisible by remember(videoId) { mutableStateOf(true) }
        var qualityMenuOpen by remember(videoId) { mutableStateOf(false) }
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
            onDismissRequest = { dismissFullscreen() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            // Keep the dialog window at a fixed size during its fade/scale.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
            AnimatedVisibility(
                visible = fullscreenContentVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(220)) + scaleIn(
                    initialScale = 0.985f,
                    animationSpec = tween(270, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(tween(210)) + scaleOut(
                    targetScale = 0.985f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
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
                    enter = fadeIn(animationSpec = tween(260)),
                    exit = fadeOut(animationSpec = tween(260)),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { qualityMenuOpen = true; refreshControls() },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.68f), CircleShape)) {
                                Icon(Icons.Default.Settings,
                                    contentDescription = "Calidad de vídeo: ${PodcastVideoSession.selectedQuality}",
                                    tint = Color.White)
                            }
                            DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
                                PodcastVideoSession.qualities.forEach { quality ->
                                    DropdownMenuItem(
                                        text = { Text(quality.label + if (quality.label == PodcastVideoSession.selectedQuality) " ✓" else "") },
                                        onClick = {
                                            PodcastVideoSession.selectQuality(quality.height)
                                            qualityMenuOpen = false
                                            refreshControls()
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (activity != null && PodcastVideoSession.player != null) {
                            IconButton(
                                onClick = {
                                    refreshControls()
                                    // Dismiss the separate Dialog window before Android
                                    // switches the Activity into its PiP task window.
                                    PodcastVideoSession.beginPipHandoff()
                                    fullscreenContentVisible = false
                                    fullscreen = false
                                    activity.window.decorView.post {
                                        activity.enterPodcastPictureInPicture()
                                    }
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.68f), CircleShape)
                            ) {
                                Icon(Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture-in-Picture", tint = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = { dismissFullscreen() },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.68f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar vídeo a pantalla completa", tint = Color.White)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(animationSpec = tween(260)),
                    exit = fadeOut(animationSpec = tween(260)),
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
            } // AnimatedVisibility
            } // Fixed-size dialog container
        }
    }
}

private fun formatPodcastTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds / 60L) % 60L, seconds % 60L)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
