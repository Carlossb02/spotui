package com.music.spotui.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.music.spotui.data.preferences.PodcastResumeEntry
import com.music.spotui.data.preferences.PodcastResumeStore
import com.music.spotui.ui.screens.PodcastVideoStreams
import kotlinx.coroutines.delay

/** At most one muted decoder, including mouse/long-press previews. Main-thread only. */
private object PodcastPreviewOwner {
    var active by mutableStateOf<Any?>(null)
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinuePodcastPreview(
    entry: PodcastResumeEntry,
    modifier: Modifier = Modifier,
    radius: Dp = 9.dp,
    onTap: () -> Unit = {},
    onLongPress:(()->Unit)?=null,
    autoPreview: Boolean = false,
    previewAllowed: Boolean = true,
) {
    val context = LocalContext.current
    val videoId = remember(entry.episode.url) {
        entry.episode.url.takeIf { it.startsWith("yt_ep:") }
            ?.removePrefix("yt_ep:")?.substringBefore('|')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
    var hovered by remember(entry.episode.url) { mutableStateOf(false) }
    var previewPlayer by remember(entry.episode.url) { mutableStateOf<ExoPlayer?>(null) }
    var resolving by remember(entry.episode.url) { mutableStateOf(false) }
    var unavailable by remember(entry.episode.url) { mutableStateOf(false) }
    var frameReady by remember(entry.episode.url) { mutableStateOf(false) }
    val visible = com.music.spotui.ui.visibility.UiVisibility.isVisible
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val lease = remember(entry.episode.url) { Any() }
    val wantsPreview = visible && foreground && previewAllowed && !unavailable && (autoPreview || hovered)
    LaunchedEffect(videoId, wantsPreview) {
        if (!wantsPreview || videoId == null) return@LaunchedEffect
        // Let touch scrolling settle before reserving a decoder or making a request.
        delay(550L)
        PodcastPreviewOwner.active = lease
        try { kotlinx.coroutines.awaitCancellation() }
        finally { if (PodcastPreviewOwner.active === lease) PodcastPreviewOwner.active = null }
    }
    val shouldPreview = wantsPreview && PodcastPreviewOwner.active === lease
    val progress=remember(entry.episode.url) {PodcastResumeStore.observeEpisode(entry.episode.url)}

    // The LaunchedEffect below owns creation and release of its exact player.

    LaunchedEffect(videoId, shouldPreview, visible) {
        if (!visible || !shouldPreview || videoId == null || unavailable) return@LaunchedEffect
        while (com.music.spotui.di.SongPlayer.isPreparingTrack()) delay(200L)
        resolving = true
        val stream = try {
            PodcastVideoStreams.warmPreview(videoId)
                ?: PodcastVideoStreams.resolvePreview(videoId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            resolving = false
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("PodcastHoverPreview", "Video lookup failed", failure)
            null
        }
        resolving = false
        if (stream == null) { unavailable = true; return@LaunchedEffect }
        frameReady = false
        val previewHttp = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(6_000)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 4_000, 200, 400)
            .build()
        val player = try { ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context.applicationContext, previewHttp)
            ))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(false)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                false
            )
            .build() } catch(failure:RuntimeException) {
                unavailable=true
                return@LaunchedEffect
            }
        try {
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(480, 360)
                .build()
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { frameReady = true }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.w("PodcastHoverPreview", "Stream unavailable", error)
                    unavailable = true
                }
            })
            previewPlayer = player
            player.setMediaItem(
                MediaItem.Builder().setUri(stream.url).setMimeType(stream.mimeType).build(),
                (progress.value?.positionMs?:entry.positionMs).coerceAtLeast(0L)
            )
            player.prepare()
            player.playWhenReady = true
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                player.playWhenReady = false
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("PodcastHoverPreview", "Could not play video preview", failure)
        } finally {
            frameReady = false
            previewPlayer = null
            player.release()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .pointerInput(entry.episode.url) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> {
                                if (event.changes.any { change -> change.type == PointerType.Mouse }) {
                                    hovered = true
                                }
                            }
                            PointerEventType.Exit -> hovered = false
                        }
                    }
                }
            }

    ) {
        if (previewPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(com.music.spotui.R.layout.spotui_podcast_video_view, null, false) as PlayerView).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        useController = false
                        isClickable = false
                        isFocusable = false
                        player = previewPlayer
                    }
                },
                update = { if(it.player!==previewPlayer)it.player=previewPlayer },
                onRelease={it.player=null},
            )
        }

        if (!frameReady) {
            EpisodeArtwork(
                entry.episode,
                modifier = Modifier.fillMaxSize(),
                radius = radius,
                imageOverride = videoId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" },
                showProgress=false,
            )
        }

        if (!frameReady && !resolving) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(45.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.56f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Reproducir episodio",
                    tint = Color.White,
                    modifier = Modifier.size(31.dp)
                )
            }
        }

        if (resolving) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(25.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }

        Box(Modifier.matchParentSize().combinedClickable(
            onClick = onTap, onLongClick = { if(onLongPress!=null)onLongPress()else {unavailable=false;hovered=!hovered} }
        ))


        if (frameReady || resolving) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.VolumeOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(com.music.spotui.util.uiLocalized("Vista previa"), color = Color.White, fontSize = 10.sp)
            }
        }
    }
}
