package com.music.spotui.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
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

/** A silent hover preview, independent of the audio session/PodcastVideoSession.
 * Not resolved/decoded at all until a mouse actually hovers a YouTube episode.
 * Touch users see the still cover; moving away releases the player immediately.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinuePodcastPreview(
    entry: PodcastResumeEntry,
    modifier: Modifier = Modifier,
    radius: Dp = 9.dp,
    onTap: () -> Unit = {},
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
    val currentEpisode by remember(entry.episode.url) {
        PodcastResumeStore.observeEpisode(entry.episode.url)
    }.collectAsState()
    val current = currentEpisode ?: entry
    // A preview is a separate video-only, zero-volume decoder. Never seek, pause
    // or reconfigure the app's audio player to display a hover frame.
    DisposableEffect(entry.episode.url) {
        onDispose { previewPlayer?.release(); previewPlayer = null }
    }

    LaunchedEffect(videoId, hovered) {
        if (!hovered || videoId == null || unavailable) return@LaunchedEffect
        // Intent delay avoids needless network calls as a mouse crosses the row.
        delay(350L)
        resolving = true
        val stream = try {
            PodcastVideoStreams.resolvePreview(videoId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            resolving = false
            // A pointer leaving the tile cancels the load; that is not a 403
            // and must not permanently disable previews on the next hover.
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("PodcastHoverPreview", "Video lookup failed", failure)
            null
        }
        resolving = false
        if (stream == null) { unavailable = true; return@LaunchedEffect }
        val previewHttp = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(8_000)
        val player = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context.applicationContext, previewHttp)
            ))
            .setHandleAudioBecomingNoisy(false)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                false // Never take focus from podcast/music playback.
            )
            .build()
        try {
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.w("PodcastHoverPreview", "Stream unavailable", error)
                    unavailable = true
                    hovered = false // restore cover; do not endlessly retry a forbidden CDN URL.
                }
            })
            previewPlayer = player
            player.setMediaItem(MediaItem.Builder().setUri(stream.url).setMimeType(stream.mimeType).build())
            player.prepare()
            // A YouTube preview is an excerpt of the actual episode, not its cover image.
            player.seekTo(current.positionMs.coerceAtLeast(0L))
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
            previewPlayer = null
            player.release()
        }
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(radius))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> {
                                if (event.changes.any { it.type == PointerType.Mouse }) hovered = true
                            }
                            PointerEventType.Exit -> {
                                if (event.changes.any { it.type == PointerType.Mouse }) hovered = false
                            }
                        }
                    }
                }
            }
            // Mobile: hold the thumbnail to preview; tap to play the episode.
            .combinedClickable(onClick = onTap, onLongClick = { hovered = !hovered })
    ) {
        // A podcast's square show cover is not its YouTube 16:9 video thumbnail.
        // Use the original episode's actual video artwork when we have its ID.
        EpisodeArtwork(
            entry.episode,
            modifier = Modifier.fillMaxSize(),
            radius = radius,
            imageOverride = videoId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
        )
        if (resolving && hovered) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(25.dp),
                color = Color.White, strokeWidth = 2.dp
            )
        }
        if (previewPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player = previewPlayer
                    }
                },
                update = { it.player = previewPlayer }
            )
        }
        // Always visible progress, even when a video is playing over the cover.
        if (current.fraction > 0f) {
            Box(
                Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(current.fraction)
                    .background(Color(0xFF1ED760)))
            }
        }
        // Top-right overlay follows the YouTube hover-card visual language.
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.70f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.VolumeOff, contentDescription = "Vista previa sin sonido",
                tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Icon(Icons.Default.GraphicEq, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(17.dp))
        }
    }
}
