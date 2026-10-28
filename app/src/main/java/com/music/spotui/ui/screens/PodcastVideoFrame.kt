package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Forward
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
import androidx.compose.ui.res.painterResource
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

/** Activity presentation state. PiP and the app share SongPlayer's MediaSession. */
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

internal object PodcastVideoStreams {
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, PodcastVideoQuality>>()

    suspend fun isReadable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)")
                .header("Range", "bytes=0-1023").build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { result ->
                val valid = result.code == 200 || result.code == 206
                val readable = valid && result.body?.source()?.request(1L) == true
                Log.d("SpotuiPodcastVideo", "video stream probe status=${result.code} readable=$readable")
                readable
            }
        }.getOrElse { false }
    }

    /** Resolve one small, silent hover preview with a short-lived stream URL cache.
     * No high-resolution probing, audio decoder or use of the active MediaSession. */
    suspend fun resolvePreview(videoId: String): PodcastVideoQuality? {
        val now = System.currentTimeMillis()
        previewCache[videoId]?.takeIf { now - it.first in 0L..600_000L }
            ?.let { return it.second }
        val options = resolve(videoId, previewOnly = true)
        val selected = options.filter { !it.muxed }
            .sortedBy { kotlin.math.abs(it.height - 360) }
            .firstOrNull { isReadable(it.url) }
        if (selected != null) previewCache[videoId] = now to selected
        return selected
    }

    /** Preserve the best supported rendition per resolution; the first client
     *  sometimes provides only 360p progressive URLs while later clients have
     *  720p/1080p adaptive video-only tracks. All sources are resolved off-main. */
    suspend fun resolve(videoId: String, previewOnly: Boolean = false): List<PodcastVideoQuality> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(if (previewOnly) 12_000L else 16_000L) {
            val bestByHeight = LinkedHashMap<Int, PodcastVideoQuality>()
            val clients = listOf(YouTubeClient.WEB, YouTubeClient.ANDROID_VR_NO_AUTH, YouTubeClient.MOBILE)
            for (client in clients) {
                val response = runCatching { YouTube.player(videoId, client = client).getOrNull() }
                    .getOrNull() ?: continue
                if (response.playabilityStatus.status != "OK") continue
                val muxed = response.streamingData?.formats.orEmpty()
                val videoFormats = response.streamingData?.adaptiveFormats.orEmpty()
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
                // A thumbnail only needs a low-resolution video; avoid HD probes on hover.
                if (previewOnly && bestByHeight.keys.any { it <= 480 }) break
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

/** Application-scoped video surface coordinator. Does not own a decoder or clock.
 * The one ExoPlayer in SongPlayer owns audio, video, seek, PiP and MediaSession. */
@OptIn(UnstableApi::class)
object PodcastVideoSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var activeVideoId by mutableStateOf<String?>(null)
        private set
    /** Activity-wide UI state: rotation and a change between mini / expanded player
     * must never destroy the fullscreen dialog or restart its decoder. */
    var fullscreen by mutableStateOf(false)
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
    var enteringPictureInPicture by mutableStateOf(false)
        private set

    private var resolveJob: Job? = null
    private var videoClockJob: Job? = null
    private var surfaceHandoffJob: Job? = null
    private var appContext: Context? = null
    private var attachedView: PlayerView? = null
    private val visibleTargets = LinkedHashMap<PlayerView, Int>()
    private var isForeground = true

    fun isActive(videoId: String?): Boolean = videoId != null && activeVideoId == videoId

    fun beginPipHandoff() {
        enteringPictureInPicture = true
        fullscreen = false
    }
    fun finishPipHandoff() {
        enteringPictureInPicture = false
        updateSurface()
    }

    fun setEnabled(context: Context, videoId: String, enabled: Boolean) {
        if (!enabled) { stop(); return }
        if (activeVideoId == videoId && (player != null || isLoading)) return
        stop()
        activeVideoId = videoId
        appContext = context.applicationContext
        isLoading = true
        resolveJob = scope.launch {
            val streams = runCatching { PodcastVideoStreams.resolve(videoId) }
                .onFailure { Log.w("SpotuiPodcastVideo", "Video lookup failed", it) }
                .getOrNull().orEmpty()
            if (activeVideoId != videoId) return@launch
            isLoading = false
            if (streams.isEmpty()) {
                error = "Este episodio no ofrece vídeo compatible"
                return@launch
            }
            qualities = streams
            val metered = runCatching {
                (appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager)?.isActiveNetworkMetered ?: true
            }.getOrDefault(true)
            val preferred = if (metered) 480 else 720
            val initial = streams.firstOrNull { it.height == preferred }
                ?: streams.filter { it.height in 480..preferred }.maxByOrNull { it.height }
                ?: streams.firstOrNull { it.height > preferred }
                ?: streams.maxByOrNull { it.height } ?: return@launch
            // A 403 when opening a Googlevideo URL is common even when InnerTube
            // advertised it. Do not hand a rejected URL to the main MediaSession.
            val candidates = (listOf(initial) + streams.filter { it != initial })
                .filter { !it.muxed }
            val working = candidates.firstOrNull { PodcastVideoStreams.isReadable(it.url) }
            if (working == null) {
                error = "YouTube no ofrece un stream de vídeo accesible para este episodio"
                return@launch
            }
            // Audio may still be preparing when the user taps Video.
            var enabled = false
            repeat(30) {
                if (activeVideoId != videoId || enabled) return@repeat
                enabled = applyQuality(working)
                if (!enabled) delay(200L)
            }
            if (!enabled && activeVideoId == videoId) error = "No se pudo combinar el vídeo con el audio actual"
        }
    }

    /**
     * Audio and video share the one ExoPlayer / MediaSession in SongPlayer.
     * Media3 merges two DASH-style tracks of the SAME video ID and drives
     * them with a single timeline. This is never a title-based search.
     */
    private fun applyQuality(quality: PodcastVideoQuality): Boolean {
        val context = appContext ?: return false
        if (quality.muxed) return false // never decode a second audio track
        if (!SongPlayer.enablePodcastVideo(context, quality.url, quality.mimeType, quality.muxed)) {
            return false
        }
        player = SongPlayer.exoPlayer
        error = null
        selectedQuality = quality.label
        updateSurface()
        return true
    }

    fun selectQuality(height: Int) {
        val target = qualities.firstOrNull { it.height == height } ?: return
        if (target.label == selectedQuality) return
        resolveJob?.cancel()
        isLoading = true
        resolveJob = scope.launch {
            val valid = PodcastVideoStreams.isReadable(target.url)
            isLoading = false
            if (!valid || !applyQuality(target)) {
                error = "No se pudo abrir la calidad de vídeo seleccionada"
            }
        }
    }

    fun stopIfDifferent(currentPodcastId: String?) {
        if (activeVideoId != null && activeVideoId != currentPodcastId) stop()
    }

    fun stop() {
        resolveJob?.cancel(); resolveJob = null
        videoClockJob?.cancel(); videoClockJob = null
        surfaceHandoffJob?.cancel(); surfaceHandoffJob = null
        attachedView?.player = null
        attachedView = null
        visibleTargets.clear()
        // The player belongs to PlaybackService. Never release it, mute it or
        // pause it from a video-only UI lifecycle callback.
        if (player === SongPlayer.exoPlayer) SongPlayer.disablePodcastVideo()
        player = null

        activeVideoId = null
        fullscreen = false
        isLoading = false
        error = null
        qualities = emptyList()
        selectedQuality = ""
        appContext = null
        enteringPictureInPicture = false
    }

    fun sync(foreground: Boolean) {
        isForeground = foreground
        // Background playback is owned by PlaybackService. A hidden video
        // surface must not pause the podcast's soundtrack.
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
        surfaceHandoffJob?.cancel()
        if (visibleTargets.isNotEmpty()) updateSurface()
        else surfaceHandoffJob = scope.launch {
            delay(220L)
            updateSurface()
        }
    }

    private fun updateSurface() {
        val target = visibleTargets.entries.maxByOrNull { it.value }?.key
        val sharedPlayer = player ?: return
        if (target !== attachedView) {
            PlayerView.switchTargetView(sharedPlayer, attachedView, target)
            attachedView = target
        }
        // Disable video decoder/bandwidth when no UI is showing the picture;
        // never disable or release the audio track.
        SongPlayer.enablePodcastVideoTrack(target != null)
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
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !active -> Unit
            error != null -> {
                if (!compact) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = onUseAudio) { Text(uiLocalized("Continuar solo con audio")) }
                }
            }
            PodcastVideoSession.fullscreen || PodcastVideoSession.enteringPictureInPicture -> Unit
            else -> VideoSurface(
                Modifier.fillMaxSize(), priority = if (compact) 0 else surfacePriority
            )
        }
        if (active && !compact && error == null && !PodcastVideoSession.fullscreen) {
            IconButton(
                onClick = { PodcastVideoSession.fullscreen = true },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Default.Fullscreen,
                    contentDescription = uiLocalized("Vídeo a pantalla completa"), tint = Color.White)
            }
        }
    }
}

/** Hosted ONCE at the activity root, not inside a portrait/landscape player branch.
 * Recomposition on rotation cannot close or relaunch this fullscreen player. */
@Composable
fun PodcastFullscreenOverlay() {
    val videoId = PodcastVideoSession.activeVideoId ?: return
    if (!PodcastVideoSession.fullscreen || PodcastVideoPipState.isInPictureInPicture) return

    val activity = LocalContext.current.spotuiActivity()
    var positionMs by remember(videoId) { mutableLongStateOf(0L) }
    var durationMs by remember(videoId) { mutableLongStateOf(0L) }
    var audioPlaying by remember(videoId) { mutableStateOf(false) }
    var controlsVisible by remember(videoId) { mutableStateOf(true) }
    var scrubbing by remember(videoId) { mutableStateOf(false) }
    var scrubFraction by remember(videoId) { mutableFloatStateOf(0f) }
    var qualityMenuOpen by remember(videoId) { mutableStateOf(false) }
    var interactionCount by remember(videoId) { mutableIntStateOf(0) }
    var tapDirection by remember(videoId) { mutableIntStateOf(0) }
    var tapSequence by remember(videoId) { mutableIntStateOf(0) }
    val glass = Color(0xFF24272B).copy(alpha = 0.76f)
    val lime = Color(0xFF1ED760)

    fun showControls() { controlsVisible = true; interactionCount++ }
    fun skip(ms: Long) {
        val current = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
        val duration = SongPlayer.getDuration()
        val next = (current + ms).coerceAtLeast(0L)
        SongPlayer.seekTo(if (duration > 0) next.coerceAtMost(duration) else next)
        showControls()
    }

    LaunchedEffect(controlsVisible, interactionCount, scrubbing, qualityMenuOpen) {
        if (controlsVisible && !scrubbing && !qualityMenuOpen) {
            delay(3_500L)
            controlsVisible = false
        }
    }
    LaunchedEffect(tapSequence) {
        if (tapSequence > 0) {
            delay(700L)
            tapDirection = 0
        }
    }
    LaunchedEffect(videoId) {
        while (PodcastVideoSession.isActive(videoId) && PodcastVideoSession.fullscreen) {
            positionMs = SongPlayer.getCurrentPosition().coerceAtLeast(0L)
            durationMs = SongPlayer.getDuration().coerceAtLeast(0L)
            audioPlaying = SongPlayer.isPlaying()
            delay(250L)
        }
    }

    Dialog(
        onDismissRequest = { PodcastVideoSession.fullscreen = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Fullscreen is restricted to this dialog. Restore app system bars on exit.
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(view, activity) {
            // The DialogWindowProvider may be several parents above LocalView.
            var dialogWindow: android.view.Window? = null
            var node: android.view.View? = view
            while (node != null && dialogWindow == null) {
                if (node is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = node.window
                    break
                }
                val parent = node.parent
                if (parent is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = parent.window
                    break
                }
                node = parent as? android.view.View
            }
            dialogWindow?.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            dialogWindow?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false) }
            activity?.let {
                androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
            onDispose {
                dialogWindow?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                activity?.let {
                    androidx.core.view.WindowInsetsControllerCompat(it.window, it.window.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Media3's FIT resize mode maximizes 16:9 inside any orientation
            // without cropping; no safeDrawingPadding shrinks the video image.
            VideoSurface(Modifier.fillMaxSize(), priority = 3)

            // The transparent gesture layer sits ABOVE AndroidView to receive
            // single and double taps. Buttons and slider are on top of it.
            Box(
                Modifier.fillMaxSize().pointerInput(videoId) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interactionCount++
                        },
                        onDoubleTap = { offset ->
                            val backwards = offset.x < size.width / 2f
                            skip(if (backwards) -15_000L else 15_000L)
                            tapDirection = if (backwards) -1 else 1
                            tapSequence++
                        }
                    )
                }
            )

            AnimatedVisibility(
                visible = tapDirection != 0,
                enter = fadeIn(tween(130)), exit = fadeOut(tween(270)),
                modifier = Modifier.align(
                    if (tapDirection < 0) Alignment.CenterStart else Alignment.CenterEnd
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp)
                        .background(glass, RoundedCornerShape(32.dp))
                        .padding(horizontal = 19.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = if (tapDirection < 0) R.drawable.ic_replay_15 else R.drawable.ic_forward_15),
                        contentDescription = null, tint = lime, modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("15 s", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(180)), exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Readable floating controls, not an opaque controls panel.
                    Box(
                        Modifier.fillMaxWidth().height(110.dp)
                            .background(Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = .57f), Color.Transparent)
                            ))
                            .align(Alignment.TopCenter)
                    )
                    Row(
                        Modifier.align(Alignment.TopEnd)
                            .padding(top = 14.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box {
                            IconButton(
                                onClick = { qualityMenuOpen = true; showControls() },
                                modifier = Modifier.background(glass, CircleShape)
                            ) {
                                Icon(Icons.Default.Settings, "Calidad de vídeo", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = qualityMenuOpen,
                                onDismissRequest = { qualityMenuOpen = false },
                                modifier = Modifier.background(Color(0xFF24272B))
                            ) {
                                PodcastVideoSession.qualities.forEach { quality ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                quality.label + if (quality.label == PodcastVideoSession.selectedQuality) " ✓" else "",
                                                color = Color.White
                                            )
                                        },
                                        onClick = {
                                            PodcastVideoSession.selectQuality(quality.height)
                                            qualityMenuOpen = false
                                            showControls()
                                        }
                                    )
                                }
                            }
                        }
                        if (activity != null && PodcastVideoSession.player != null) {
                            IconButton(
                                onClick = {
                                    activity.enterPodcastPictureInPicture()
                                },
                                modifier = Modifier.background(glass, CircleShape)
                            ) {
                                Icon(Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture-in-Picture", tint = Color.White)
                            }
                        }
                        IconButton(
                            onClick = { PodcastVideoSession.fullscreen = false },
                            modifier = Modifier.background(glass, CircleShape)
                        ) {
                            Icon(Icons.Default.Close,
                                contentDescription = "Salir de pantalla completa", tint = Color.White)
                        }
                    }

                    Row(
                        Modifier.align(Alignment.Center)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(26.dp)
                    ) {
                        IconButton(onClick = { skip(-15_000L) },
                            modifier = Modifier.size(58.dp).background(glass, CircleShape)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_replay_15),
                                contentDescription = "Retroceder 15 segundos",
                                tint = Color.White, modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = { SongPlayer.togglePlay(); showControls() },
                            modifier = Modifier.size(74.dp)
                                .background(Color.White.copy(alpha = 0.94f), CircleShape)
                        ) {
                            Icon(
                                if (audioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (audioPlaying) "Pausar" else "Reproducir",
                                tint = Color.Black, modifier = Modifier.size(38.dp)
                            )
                        }
                        IconButton(onClick = { skip(15_000L) },
                            modifier = Modifier.size(58.dp).background(glass, CircleShape)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_forward_15),
                                contentDescription = "Avanzar 15 segundos",
                                tint = Color.White, modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .75f))
                            ))
                            .navigationBarsPadding()
                            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)
                    ) {
                        val validDuration = durationMs > 0L
                        val fraction = if (scrubbing) scrubFraction else if (validDuration)
                            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                        Slider(
                            value = fraction,
                            onValueChange = { scrubFraction = it; scrubbing = true; showControls() },
                            onValueChangeFinished = {
                                if (validDuration) SongPlayer.seekTo((scrubFraction * durationMs).toLong())
                                scrubbing = false
                                showControls()
                            },
                            enabled = validDuration,
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = lime, activeTrackColor = lime,
                                inactiveTrackColor = Color.White.copy(alpha = .35f)
                            )
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatPodcastTime(if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs),
                                color = Color.White, fontSize = 12.sp)
                            Text(formatPodcastTime(durationMs),
                                color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
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
