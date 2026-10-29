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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
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

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinuePodcastPreview(
    entry: PodcastResumeEntry,
    modifier: Modifier = Modifier,
    radius: Dp = 9.dp,
    onTap: () -> Unit = {},
    autoPreview: Boolean = false,
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
    val shouldPreview = visible && (autoPreview || hovered)
    val currentEpisode by remember(entry.episode.url) {
        PodcastResumeStore.observeEpisode(entry.episode.url)
    }.collectAsState()
    val current = currentEpisode ?: entry

    DisposableEffect(entry.episode.url) {
        onDispose { previewPlayer?.release(); previewPlayer = null }
    }

    LaunchedEffect(videoId, shouldPreview, visible) {
        if (!visible || !shouldPreview || videoId == null || unavailable) return@LaunchedEffect
        delay(150L)
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
        val player = ExoPlayer.Builder(context.applicationContext)
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
            .build()
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
                current.positionMs.coerceAtLeast(0L)
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
            .combinedClickable(onClick = onTap, onLongClick = { hovered = !hovered })
    ) {
        if (previewPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(com.music.spotui.R.layout.spotui_podcast_video_view, null, false) as PlayerView).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player = previewPlayer
                    }
                },
                update = { it.player = previewPlayer }
            )
        }

        if (!frameReady) {
            EpisodeArtwork(
                entry.episode,
                modifier = Modifier.fillMaxSize(),
                radius = radius,
                imageOverride = videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
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

        if (current.fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(current.fraction)
                        .background(Color(0xFF1ED760))
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.70f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VolumeOff,
                contentDescription = "Vista previa sin sonido",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(9.dp))
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
