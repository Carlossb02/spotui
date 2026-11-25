package com.music.spotui.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.PodcastResumeStore
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.screens.PodcastVideoStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun CollectionStoriesButton(cover: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Box(Modifier.size(29.dp, 38.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.offset(x = 4.dp, y = 2.dp).size(24.dp, 31.dp)
                .background(Color.White.copy(alpha = .25f), RoundedCornerShape(7.dp)))
            Box(Modifier.offset(x = (-2).dp).size(24.dp, 33.dp)
                .clip(RoundedCornerShape(7.dp)).background(Color(0xFF28303B))
                .border(1.3.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center) {
                if (cover.isNotBlank()) {
                    GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .27f)))
                }
                Icon(Icons.Default.GraphicEq, "Historias", Modifier.size(15.dp), tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CollectionStories(
    title: String, cover: String, songs: List<SongsModel>, accent: Color, onDismiss: () -> Unit,
) {
    var stories by remember { mutableStateOf<List<SongsModel>?>(null) }
    LaunchedEffect(Unit) { stories = withContext(Dispatchers.Default) { collectionStoryItems(songs) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val clips = stories
        if (clips.isNullOrEmpty()) {
            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF151821)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (clips == null) CircularProgressIndicator() else Text("No hay fragmentos disponibles.", color = Color.White)
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            }
        } else {
            CollectionStoryPager(title, cover, clips, songs, accent, onDismiss)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CollectionStoryPager(
    title: String, cover: String, clips: List<SongsModel>, songs: List<SongsModel>,
    accent: Color, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pager = rememberPagerState { clips.size }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var muted by remember { mutableStateOf(false) }
    var hasVideo by remember { mutableStateOf(false) }
    var audioFallback by remember { mutableStateOf<MediaSource?>(null) }
    var status by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var playing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var restore by remember { mutableStateOf<(() -> Unit)?>(null) }
    val page = pager.settledPage
    val latestDismiss by rememberUpdatedState(onDismiss)

    DisposableEffect(player, lifecycle) {
        val events = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { status = playbackState }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) {
                val backup = audioFallback
                audioFallback = null
                hasVideo = false
                if (backup != null) {
                    player.setMediaSource(backup)
                    player.prepare()
                } else {
                    failure = true
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> { foreground = false; player.pause() }
                else -> Unit
            }
        }
        player.addListener(events)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(events)
            player.release()
            restore?.invoke()
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(foreground, pager.isScrollInProgress) {
        player.playWhenReady = foreground && !pager.isScrollInProgress
    }
    LaunchedEffect(page) {
        player.stop()
        player.clearMediaItems()
        progress = 0f
        failure = false
        hasVideo = false
        audioFallback = null
        val song = clips[page]
        val audio = resolveStoryContent(12_000L) {
            withContext(Dispatchers.IO) { SongPlayer.resolveCollectionStory(song, context.applicationContext) }
        }
        if (audio.isNullOrBlank()) {
            failure = true
            return@LaunchedEffect
        }
        val videoId = song.url.takeIf { it.startsWith("yt_ep:") }
            ?.removePrefix("yt_ep:")?.substringBefore('|')?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val video = videoId?.let { resolveStoryContent(2_500L) { PodcastVideoStreams.resolvePreview(it)?.url } }
        val start = storyStartPosition(song.durationMs, PodcastResumeStore.isEpisode(song.url))
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        fun source(url: String) = factory.createMediaSource(MediaItem.fromUri(url))
        fun clip(media: MediaSource): MediaSource = ClippingMediaSource.Builder(media)
            .setStartPositionMs(start).setEndPositionMs(start + STORY_DURATION_MS).build()
        if (restore == null) restore = SongPlayer.pauseForCollectionStories()
        if (video != null) audioFallback = clip(source(audio))
        hasVideo = video != null
        player.setMediaSource(if (video == null) clip(source(audio)) else clip(MergingMediaSource(true, source(audio), source(video))))
        player.playWhenReady = foreground && !pager.isScrollInProgress
        player.prepare()
        delay(10_000L)
        if (status == Player.STATE_BUFFERING) {
            player.stop()
            failure = true
        }
    }
    LaunchedEffect(status, page, foreground, playing) {
        if (status == Player.STATE_ENDED && foreground) {
            if (page < clips.lastIndex && !pager.isScrollInProgress) pager.animateScrollToPage(page + 1)
            else if (page == clips.lastIndex && !pager.isScrollInProgress) latestDismiss()
        }
        while (playing && foreground) {
            progress = (player.currentPosition / (player.duration.takeIf { it > 0 } ?: STORY_DURATION_MS).toFloat())
                .coerceIn(0f, 1f)
            delay(150)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D1017)).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().fillMaxHeight().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = latestDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar historias", tint = Color.White) }
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${page + 1} / ${clips.size}", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        if (muted) "Activar sonido" else "Silenciar", tint = Color.White)
                }
            }
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(10.dp)) {
                val gap = 4.dp.toPx()
                val segment = ((size.width - gap * (clips.size - 1)) / clips.size).coerceAtLeast(1f)
                for (i in clips.indices) {
                    val x = (segment + gap) * i
                    val y = center.y
                    drawLine(Color.White.copy(alpha = .23f), Offset(x, y), Offset(x + segment, y), 2.dp.toPx(), StrokeCap.Round)
                    val fraction = if (i < page) 1f else if (i == page) progress else 0f
                    if (fraction > 0f) drawLine(accent, Offset(x, y), Offset(x + segment * fraction, y), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            VerticalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), pageSpacing = 10.dp) { index ->
                val song = clips[index]
                Column(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)).background(
                        Brush.verticalGradient(listOf(accent.copy(alpha = .42f), Color(0xFF161D2A), Color(0xFF141923))),
                    ).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (index == page && hasVideo) {
                            StoryVideo(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
                        } else {
                            GlideImage(song.coverUri.ifBlank { cover }, null,
                                Modifier.fillMaxWidth(.86f).aspectRatio(1f).clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Crop)
                        }
                        if (index == page && !failure && status != Player.STATE_READY && status != Player.STATE_ENDED) {
                            CircularProgressIndicator(Modifier.size(34.dp), color = Color.White)
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.singer, color = Color(0xFFC5CBDB), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (index == page && failure) Text("No se pudo cargar el fragmento. Desliza para ver otro.", color = Color.LightGray)
                        TextButton(onClick = {
                            restore = null
                            player.pause()
                            SongPlayer.playFromCollectionStory(context, songs, song, title)
                            latestDismiss()
                        }) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (PodcastResumeStore.isEpisode(song.url)) "Escuchar episodio" else "Escuchar canción",
                                color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun resolveStoryContent(timeoutMs: Long, resolve: suspend () -> String?): String? =
    withTimeoutOrNull(timeoutMs) {
        try {
            resolve()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.d("CollectionStories", "Preview source unavailable", failure)
            null
        }
    }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun StoryVideo(player: Player, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(context) { PlayerView(context).apply { useController = false } }
    DisposableEffect(view, player) {
        view.player = player
        onDispose { view.player = null }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
