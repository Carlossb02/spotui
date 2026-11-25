package com.music.spotui.ui.components

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.preferences.SubscribedPodcastPref
import com.music.spotui.data.preferences.isArtistFollowed
import com.music.spotui.data.preferences.addFollowedArtist
import com.music.spotui.data.preferences.removeFollowedArtist
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.preferences.PodcastResumeStore
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.screens.PodcastVideoStreams
import com.music.spotui.ui.screens.CanvasVideo
import com.music.spotui.util.ExpiringLruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun CollectionStoriesButton(cover: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)
        .semantics { contentDescription = "Historias" }) {
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
                if (cover.isBlank()) Icon(Icons.Default.GraphicEq, null, Modifier.size(15.dp), tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CollectionStories(
    title: String, cover: String, songs: List<SongsModel>, accent: Color,
    navController: NavController, podcast: PodcastModel?, onDismiss: () -> Unit,
) {
    val clips by produceState<List<SongsModel>?>(null) {
        value = withContext(Dispatchers.Default) { collectionStoryItems(songs) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101014)), contentAlignment = Alignment.Center) {
            val available = clips
            if (available.isNullOrEmpty()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (available == null) CircularProgressIndicator() else Text("No hay fragmentos disponibles.", color = Color.White)
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            } else {
                CollectionStoryPager(title, cover, available, songs, accent, navController, podcast, onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CollectionStoryPager(
    title: String, cover: String, clips: List<SongsModel>, songs: List<SongsModel>,
    accent: Color, navController: NavController, podcast: PodcastModel?, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pages = if (clips.size > 1) Int.MAX_VALUE else 1
    val pager = rememberPagerState(initialPage = (pages / 2 / clips.size) * clips.size) { pages }
    val audioCache = remember { ExpiringLruCache<String, String>(8, 4 * 60_000L) }
    val player = remember {
        ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 12_000, 350, 700).build(),
        ).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var muted by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<SongsModel?>(null) }
    var savedIn by remember { mutableStateOf<SongsModel?>(null) }
    var canvasUrl by remember { mutableStateOf<String?>(null) }
    var hasVideo by remember { mutableStateOf(false) }
    var audioFallback by remember { mutableStateOf<MediaSource?>(null) }
    var status by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var playing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var restore by remember { mutableStateOf<(() -> Unit)?>(null) }
    val page = pager.settledPage
    val active = clips[page % clips.size]
    val playPreview = foreground && !pager.isScrollInProgress && !paused && !measuring && menu == null && savedIn == null
    val latestDismiss by rememberUpdatedState(onDismiss)

    suspend fun audioFor(song: SongsModel): String? = audioCache.get(song.url)
        ?: resolveStoryContent(10_000L) {
            withContext(Dispatchers.IO) { SongPlayer.resolveCollectionStory(song, context.applicationContext) }
        }?.also { audioCache.put(song.url, it) }

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
    LaunchedEffect(playPreview) { player.playWhenReady = playPreview }
    LaunchedEffect(page, foreground) {
        canvasUrl = null
        if (!foreground || PodcastResumeStore.isEpisode(active.url)) return@LaunchedEffect
        val id = SpotifySync.cleanTrackId(active.spotifyTrackId)
        if (id.matches(Regex("[A-Za-z0-9]{22}"))) {
            canvasUrl = resolveStoryContent(5_000L) {
                withContext(Dispatchers.IO) { Api(context.applicationContext).getCanvasUrl(id) }
            }
        }
    }
    LaunchedEffect(page) {
        player.stop()
        player.clearMediaItems()
        progress = 0f
        failure = false
        hasVideo = false
        audioFallback = null
        val song = active
        val (audio, video) = coroutineScope {
            val videoId = song.url.takeIf { it.startsWith("yt_ep:") }
                ?.removePrefix("yt_ep:")?.substringBefore('|')?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            val preview = videoId?.let { id -> async {
                resolveStoryContent(2_500L) { PodcastVideoStreams.resolvePreview(id)?.url }
            } }
            val url = audioFor(song)
            val videoUrl = if (url == null) null else preview?.let { withTimeoutOrNull(500L) { it.await() } }
            preview?.cancel()
            url to videoUrl
        }
        if (audio.isNullOrBlank()) {
            failure = true
            return@LaunchedEffect
        }
        val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        fun source(url: String) = factory.createMediaSource(MediaItem.fromUri(url))
        measuring = true
        player.playWhenReady = false
        player.setMediaSource(source(audio))
        player.prepare()
        val actualDuration = withTimeoutOrNull(4_000L) {
            while (player.duration <= 0 && !failure) delay(50)
            player.duration.takeIf { it > 0 }
        }
        if (actualDuration == null || !player.isCurrentMediaItemSeekable) {
            player.stop()
            measuring = false
            failure = true
            return@LaunchedEffect
        }
        val start = storyStartPosition(actualDuration)
        fun clip(media: MediaSource): MediaSource = ClippingMediaSource.Builder(media)
            .setStartPositionMs(start).setEndPositionMs(start + STORY_DURATION_MS).build()
        if (restore == null) restore = SongPlayer.pauseForCollectionStories()
        if (video != null) audioFallback = clip(source(audio))
        hasVideo = video != null
        player.setMediaSource(if (video == null) clip(source(audio)) else clip(MergingMediaSource(true, source(audio), source(video))))
        player.prepare()
        measuring = false
        delay(10_000L)
        if (status == Player.STATE_BUFFERING) {
            player.stop()
            failure = true
        }
    }
    LaunchedEffect(page, playing) {
        if (playing && clips.size > 1) {
            delay(500)
            audioFor(clips[(page + 1) % clips.size])
        }
    }
    LaunchedEffect(page, foreground, playing) {
        while (playing && foreground) {
            progress = (player.currentPosition / (player.duration.takeIf { it > 0 } ?: STORY_DURATION_MS).toFloat())
                .coerceIn(0f, 1f)
            delay(150)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VerticalPager(pager, modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().fillMaxHeight()) { index ->
            val song = clips[index % clips.size]
            val selected = index == page
            val artwork = song.coverUri.ifBlank { cover }
            val video = canvasUrl.takeIf { selected }
            val shareLink = remember(song) { storyLink(song) }
            Box(Modifier.fillMaxSize().background(Color(0xFF191820))) {
                if (video != null) {
                    CanvasVideo(video, Modifier.fillMaxSize(), playing = playPreview, onError = { canvasUrl = null })
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        accent.copy(alpha = .8f), Color(0xFF292431), Color(0xFF15131B),
                    ))))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    Color.Black.copy(alpha = .52f), Color.Transparent, Color.Black.copy(alpha = .86f),
                ))))
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = latestDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar historias", tint = Color.White)
                        }
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                        IconButton(onClick = { paused = !paused }) {
                            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                if (paused) "Reanudar fragmento" else "Pausar fragmento", tint = Color.White)
                        }
                        IconButton(onClick = { muted = !muted }) {
                            Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                if (muted) "Activar sonido" else "Silenciar", tint = Color.White)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            selected && hasVideo -> StoryVideo(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            video == null && artwork.isNotBlank() -> GlideImage(artwork, null,
                                Modifier.fillMaxWidth(.82f).aspectRatio(1f).clip(RoundedCornerShape(19.dp)),
                                contentScale = ContentScale.Crop)
                        }
                        if (selected && !failure && status != Player.STATE_READY && status != Player.STATE_ENDED) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(song.singer.ifBlank { song.album }, color = Color.White, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (selected) StoryFollowButton(song, podcast)
                        if (shareLink != null) IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLink)
                            context.startActivity(Intent.createChooser(intent, "Compartir"))
                        }) { Icon(Icons.Default.Share, "Compartir", tint = Color.White) }
                        IconButton(onClick = { menu = song }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Color.White) }
                    }
                    if (selected && failure) {
                        Text("No se pudo reproducir el fragmento. Desliza para ver otro.", color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    val saved = if (selected) rememberTrackSaved(song, false) else false
                    val label = if (PodcastResumeStore.isEpisode(song.url)) "Escuchar episodio" else "Escuchar canción"
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .14f))
                        .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = label) {
                            restore = null
                            player.pause()
                            SongPlayer.playFromCollectionStory(context, songs, song, title)
                            latestDismiss()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            GlideImage(artwork, null, Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (song.explicit) ExplicitBadge()
                                    Text(song.singer, color = Color.White.copy(alpha = .75f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        LibrarySaveIcon(saved = saved) { savedIn = song }
                    }
                    Canvas(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(18.dp)) {
                        val y = center.y
                        drawLine(Color.White.copy(alpha = .23f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
                        if (selected && progress > 0f) drawLine(Color.White, Offset(0f, y),
                            Offset(size.width * progress, y), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
    menu?.let { song -> SongOptionsSheet(song, navController, context, onDismiss = { menu = null }) }
    savedIn?.let { song -> SavedInSheet(song = song, context = context, onDismiss = { savedIn = null }) }
}

@Composable
private fun StoryFollowButton(song: SongsModel, podcast: PodcastModel?) {
    val context = LocalContext.current
    val artistId = song.artistIds.substringBefore(',').trim().takeIf {
        !PodcastResumeStore.isEpisode(song.url) && it.matches(Regex("[A-Za-z0-9]{22}"))
    }
    if (podcast == null && artistId == null) return
    var following by remember(podcast?.id, artistId) {
        mutableStateOf(if (podcast != null) SubscribedPodcastPref.isPodcastSubscribed(context, podcast.id)
            else isArtistFollowed(context, checkNotNull(artistId)))
    }
    OutlinedButton(onClick = {
        if (podcast != null) {
            following = SubscribedPodcastPref.toggleSubscribedPodcast(context, podcast)
        } else if (artistId != null) {
            following = !following
            if (following) addFollowedArtist(context, artistId, song.singer.substringBefore(','))
            else removeFollowedArtist(context, artistId)
            SpotifySync.setArtistFollowed(context, artistId, following)
        }
    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Text(if (following) "Siguiendo" else "Seguir", color = Color.White, maxLines = 1)
    }
}

private fun storyLink(song: SongsModel): String? = when {
    song.url.startsWith("yt_ep:") -> song.url.removePrefix("yt_ep:").substringBefore('|')
        .takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let { "https://www.youtube.com/watch?v=$it" }
    song.url.startsWith("episode:") -> song.url.removePrefix("episode:")
        .takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }?.let { "https://open.spotify.com/episode/$it" }
    else -> SpotifySync.cleanTrackId(song.spotifyTrackId)
        .takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }?.let { "https://open.spotify.com/track/$it" }
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
