package com.music.spotui.ui.components

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
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
    navController: NavController, podcast: PodcastModel?, shuffleSeed: Int,
    resumeUrl: String?, resumePosition: Long, resumePaused: Boolean, resumeMuted: Boolean,
    onVisitArtist: (String, Long, Boolean, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val clips by produceState<List<SongsModel>?>(null, songs) {
        if (value.isNullOrEmpty()) value = withContext(Dispatchers.Default) { collectionStoryItems(songs, kotlin.random.Random(shuffleSeed)) }
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
                CollectionStoryPager(title, cover, available, songs, accent, navController, podcast,
                    resumeUrl, resumePosition, resumePaused, resumeMuted, onVisitArtist, onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CollectionStoryPager(
    title: String, cover: String, clips: List<SongsModel>, songs: List<SongsModel>,
    accent: Color, navController: NavController, podcast: PodcastModel?,
    resumeUrl: String?, resumePosition: Long, resumePaused: Boolean, resumeMuted: Boolean,
    onVisitArtist: (String, Long, Boolean, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pages = if (clips.size > 1) Int.MAX_VALUE else 1
    val restoredIndex = clips.indexOfFirst { it.url == resumeUrl }
    val initialPage = (pages / 2 / clips.size) * clips.size + restoredIndex.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initialPage) { pages }
    var pendingPosition by remember { mutableLongStateOf(if (restoredIndex >= 0) resumePosition else 0L) }
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
    var muted by remember { mutableStateOf(restoredIndex >= 0 && resumeMuted) }
    var artistMenu by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(restoredIndex >= 0 && resumePaused) }
    var preparedPage by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf<SongsModel?>(null) }
    var savedIn by remember { mutableStateOf<SongsModel?>(null) }
    val durationCache = remember { ExpiringLruCache<String, Long>(8, 4 * 60_000L) }
    val artistPhotos = remember { ExpiringLruCache<String, String>(100, 30 * 60_000L) }
    val canvasCache = remember { ExpiringLruCache<String, String>(12, 5 * 60_000L) }
    var canvasUrl by remember { mutableStateOf<String?>(null) }
    var hasVideo by remember { mutableStateOf(false) }
    var audioFallback by remember { mutableStateOf<MediaSource?>(null) }
    var status by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var playing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var restore by remember { mutableStateOf<(() -> Unit)?>(null) }
    val page = pager.settledPage
    var audioAttempt by remember(page) { mutableIntStateOf(0) }
    val active = clips[page % clips.size]
    val canPlayPreview = foreground && !pager.isScrollInProgress && !paused && menu == null && savedIn == null && !artistMenu
    val playPreview = preparedPage == page && canPlayPreview
    val latestCanPlayPreview by rememberUpdatedState(canPlayPreview)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val contextApi = remember(context) { Api(context.applicationContext) }
    val followed by produceState(emptySet<String>(), contextApi) {
        value = withContext(Dispatchers.IO) { contextApi.storedFollowedArtists().orEmpty().map { it.id }.toSet() }
        value = withContext(Dispatchers.IO) { contextApi.getFollowedArtists().map { it.id }.toSet() }
    }
    val followChoices = remember { mutableStateMapOf<String, Boolean>() }
    val names = remember(active.singer) { active.singer.split(',').map(String::trim).filter(String::isNotBlank) }
    val metadata = remember(names, active.artistIds) {
        val ids = active.artistIds.split(',').map(String::trim)
        names.mapIndexed { i, name -> name to ids.getOrNull(i).orEmpty() }
    }
    val artists by key(active.url) { produceState(metadata, metadata) {
        value = metadata
        val id = SpotifySync.cleanTrackId(active.spotifyTrackId.ifBlank {
            active.url.substringBefore('|').takeIf { it.startsWith("spotify:track:") }.orEmpty()
        })
        if (podcast == null && (metadata.isEmpty() || metadata.any { it.second.isBlank() }) &&
            id.matches(Regex("[A-Za-z0-9]{22}"))) {
            value = withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000L) { com.metrolist.spotify.Spotify.track(id).getOrNull()?.artists }
                    .orEmpty().map { it.name to it.id.orEmpty() }.ifEmpty { metadata }
            }
        }
    } }
    fun openArtist(name: String, id: String) {
        artistMenu = false
        onVisitArtist(active.url, if (preparedPage == page) player.currentPosition else 0L, paused, muted)
        navController.navigate(com.music.spotui.ui.navigation.artistRoute(name, id))
    }

    suspend fun audioFor(song: SongsModel): String? = audioCache.get(song.url)
        ?: resolveStoryContent(10_000L) {
            withContext(Dispatchers.IO) { SongPlayer.resolveCollectionStory(song, context.applicationContext) }
        }?.also { audioCache.put(song.url, it) }

    fun trackId(song: SongsModel): String = SpotifySync.cleanTrackId(song.spotifyTrackId.ifBlank {
        song.url.substringBefore('|').takeIf { it.startsWith("spotify:track:") }.orEmpty()
    })
    suspend fun canvasFor(song: SongsModel): String? {
        val id = trackId(song)
        if (!id.matches(Regex("[A-Za-z0-9]{22}")) || PodcastResumeStore.isEpisode(song.url)) return null
        return canvasCache.get(id) ?: resolveStoryContent(9_000L) {
            withContext(Dispatchers.IO) {
                Api(context.applicationContext).getCanvasUrl(id)
                    ?: com.metrolist.spotify.Spotify.canvasUrl(id)
            }
        }?.also { canvasCache.put(id, it) }
    }
    suspend fun mediaFactory() = withContext(Dispatchers.IO) {
        ProgressiveMediaSource.Factory(StoryMediaCache.factory(context.applicationContext))
    }

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
    LaunchedEffect(page, preparedPage, playPreview) { player.playWhenReady = playPreview }
    LaunchedEffect(page, foreground) {
        canvasUrl = null
        if (foreground) canvasUrl = canvasFor(active)
    }
    LaunchedEffect(page) { artistMenu = false }
    LaunchedEffect(page, failure) {
        if (failure && audioAttempt == 0) {
            delay(400)
            audioCache.remove(active.url)
            audioAttempt++
        }
    }
    LaunchedEffect(page, audioAttempt) {
        preparedPage = null
        player.playWhenReady = false
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
                resolveStoryContent(5_000L) { PodcastVideoStreams.resolvePreview(id)?.url }
            } }
            val url = audioFor(song)
            val videoUrl = if (url == null) null else preview?.let { withTimeoutOrNull(1_600L) { it.await() } }
            preview?.cancel()
            url to videoUrl
        }
        if (audio.isNullOrBlank()) {
            failure = true
            return@LaunchedEffect
        }
        val factory = mediaFactory()
        fun source(url: String) = factory.createMediaSource(MediaItem.fromUri(url))
        val actualDuration = durationCache.get(audio) ?: run {
            player.setMediaSource(source(audio))
            player.prepare()
            withTimeoutOrNull(6_000L) {
                while ((player.duration <= 0 || !player.isCurrentMediaItemSeekable) && !failure) delay(50)
                player.duration.takeIf { it > 0 && player.isCurrentMediaItemSeekable }
            }?.also { durationCache.put(audio, it) }
        }
        if (actualDuration == null) {
            player.stop()
            failure = true
            return@LaunchedEffect
        }
        val start = storyStartPosition(actualDuration)
        fun clip(media: MediaSource): MediaSource = ClippingMediaSource.Builder(media)
            .setStartPositionMs(start).setEndPositionMs(start + STORY_DURATION_MS).build()
        if (restore == null) restore = SongPlayer.pauseForCollectionStories()
        if (video != null) audioFallback = clip(source(audio))
        hasVideo = video != null
        val target = if (page == initialPage) pendingPosition.coerceIn(
            0L, (minOf(actualDuration, STORY_DURATION_MS) - 1L).coerceAtLeast(0L)
        ) else 0L
        pendingPosition = 0L
        player.setMediaSource(
            if (video == null) clip(source(audio)) else clip(MergingMediaSource(true, source(audio), source(video))),
            target,
        )
        player.prepare()
        preparedPage = page
        player.playWhenReady = latestCanPlayPreview
        delay(10_000L)
        if (status == Player.STATE_BUFFERING) {
            player.stop()
            failure = true
        }
    }
    LaunchedEffect(page, foreground) {
        if (!foreground || clips.size < 2) return@LaunchedEffect
        val next = clips[(page + 1) % clips.size]
        coroutineScope {
            launch { canvasFor(next) }
            launch {
                val url = audioFor(next) ?: return@launch
                val factory = mediaFactory()
                val probe = ExoPlayer.Builder(context).build()
                try {
                    probe.setMediaSource(factory.createMediaSource(MediaItem.fromUri(url)))
                    probe.prepare()
                    val length = withTimeoutOrNull(6_000L) {
                        while (probe.duration <= 0 && probe.playerError == null) delay(50)
                        probe.duration.takeIf { it > 0 && probe.isCurrentMediaItemSeekable }
                    } ?: return@launch
                    durationCache.put(url, length)
                    val start = storyStartPosition(length)
                    probe.setMediaSource(ClippingMediaSource.Builder(factory.createMediaSource(MediaItem.fromUri(url)))
                        .setStartPositionMs(start).setEndPositionMs(start + STORY_DURATION_MS).build())
                    probe.prepare()
                    delay(7_000L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.d("CollectionStories", "Could not preload next story", failure)
                } finally { probe.release() }
            }
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
        VerticalPager(pager, modifier = Modifier.fillMaxSize()) { index ->
            val song = clips[index % clips.size]
            val selected = index == page
            val artwork = song.coverUri.ifBlank { cover }
            val video = canvasUrl.takeIf { selected }
            val shareLink = remember(song) { storyLink(song) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Color(0xFF191820))) {
                if (video != null) {
                    CanvasVideo(video, Modifier.fillMaxSize(), playing = playPreview, textureSurface = true,
                        onError = { canvasUrl = null })
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
                        if (selected) StoryArtistPhotos(artists, podcast, artistPhotos, ::openArtist)
                        Text(song.singer.ifBlank { song.album }, color = Color.White, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (selected) {
                            when {
                                podcast != null -> StoryPodcastFollowButton(podcast)
                                artists.size > 1 -> OutlinedButton(onClick = { artistMenu = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Text("Ver todos", color = Color.White, maxLines = 1)
                                }
                                artists.size == 1 -> StoryArtistFollowButton(artists.first(), followed, followChoices)
                            }
                        }
                        if (shareLink != null) IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLink)
                            context.startActivity(Intent.createChooser(intent, "Compartir"))
                        }) { Icon(Icons.Default.Share, "Compartir", tint = Color.White) }
                        IconButton(onClick = { menu = song }) { Icon(Icons.Default.MoreVert, "Más opciones", tint = Color.White) }
                    }
                    if (selected && failure) {
                        TextButton(onClick = { audioCache.remove(active.url); audioAttempt++ }) {
                            Text("Reintentar audio", color = Color.White)
                        }
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
                                    if (song.explicit) {
                                        ExplicitBadge()
                                        Spacer(Modifier.width(6.dp))
                                    }
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
    }
    if (artistMenu) StoryArtistsSheet(artists, followed, followChoices, artistPhotos, ::openArtist) { artistMenu = false }
    menu?.let { song -> SongOptionsSheet(song, navController, context, onDismiss = { menu = null }) }
    savedIn?.let { song -> SavedInSheet(song = song, context = context, onDismiss = { savedIn = null }) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private object StoryMediaCache {
    private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    @Synchronized fun factory(context: android.content.Context): androidx.media3.datasource.DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context)
        val store = cache ?: runCatching {
            androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "story_media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(16L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            )
        }.getOrNull()?.also { cache = it } ?: return upstream
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}

@Composable
private fun StoryArtistPhotos(artists: List<Pair<String, String>>, podcast: PodcastModel?,
    cache: ExpiringLruCache<String, String>, onOpen: (String, String) -> Unit) {
    Row(Modifier.widthIn(max = 110.dp).horizontalScroll(rememberScrollState()).padding(end = 7.dp),
        horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
        if (podcast != null) StoryArtistAvatar(podcast.name, podcast.coverUri, 28.dp)
        else artists.forEach { (name, id) ->
            StoryArtistAvatar(name, storyArtistPhoto(id, cache), 28.dp) { onOpen(name, id) }
        }
    }
}

@Composable
private fun storyArtistPhoto(id: String, cache: ExpiringLruCache<String, String>): String {
    val photo by produceState(cache.get(id).orEmpty(), id) {
        value = cache.get(id).orEmpty()
        if (value.isEmpty() && id.matches(Regex("[A-Za-z0-9]{22}"))) {
            value = resolveStoryContent(5_000L) {
                withContext(Dispatchers.IO) { com.metrolist.spotify.Spotify.artist(id).getOrNull()?.images?.firstOrNull()?.url }
            }.orEmpty().also { if (it.isNotBlank()) cache.put(id, it) }
        }
    }
    return photo
}

@Composable
@OptIn(ExperimentalGlideComposeApi::class)
private fun StoryArtistAvatar(name: String, photo: String, size: androidx.compose.ui.unit.Dp, open: (() -> Unit)? = null) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF3A353F))
        .then(if (open != null) Modifier.clickable(onClickLabel = "Abrir perfil de $name", onClick = open) else Modifier),
        contentAlignment = Alignment.Center) {
        if (photo.isNotBlank()) GlideImage(photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryArtistsSheet(artists: List<Pair<String, String>>, followed: Set<String>, choices: MutableMap<String, Boolean>,
    cache: ExpiringLruCache<String, String>, open: (String, String) -> Unit, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF211F23)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Artistas", color = Color.White, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            artists.forEach { (name, id) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StoryArtistAvatar(name, storyArtistPhoto(id, cache), 44.dp) { open(name, id) }
                    Text(name, color = Color.White, modifier = Modifier.weight(1f).clickable { open(name, id) }.padding(horizontal = 12.dp))
                    StoryArtistFollowButton(name to id, followed, choices)
                }
            }
        }
    }
}

@Composable
private fun StoryArtistFollowButton(artist: Pair<String, String>, followed: Set<String>, choices: MutableMap<String, Boolean>) {
    val (name, id) = artist
    if (!id.matches(Regex("[A-Za-z0-9]{22}"))) return
    val context = LocalContext.current
    val revision by com.music.spotui.data.preferences.followedArtistRevision.collectAsState()
    val local = remember(context, id, revision) { isArtistFollowed(context, id) }
    val pending by produceState<Boolean?>(null, id, revision) {
        value = withContext(Dispatchers.IO) { com.music.spotui.data.api.SpotifyPendingWrites.desired(context, "artist", id) }
    }
    val following = choices[id] ?: pending ?: (local || id in followed)
    StoryFollowControl(following) {
        choices[id] = !following
        if (following) removeFollowedArtist(context, id) else addFollowedArtist(context, id, name)
        SpotifySync.setArtistFollowed(context, id, !following)
    }
}

@Composable
private fun StoryPodcastFollowButton(podcast: PodcastModel) {
    val context = LocalContext.current
    val revision by SubscribedPodcastPref.revision.collectAsState()
    val following = remember(context, podcast.id, revision) { SubscribedPodcastPref.isPodcastSubscribed(context, podcast.id) }
    StoryFollowControl(following) { SubscribedPodcastPref.toggleSubscribedPodcast(context, podcast) }
}

@Composable
private fun StoryFollowControl(following: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (following) Color(0xFF206047) else Color.Black.copy(alpha = .48f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
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
    val view = remember(context) {
        android.view.LayoutInflater.from(context).inflate(com.music.spotui.R.layout.story_video_view, null) as PlayerView
    }
    DisposableEffect(view, player) {
        view.player = player
        onDispose { view.player = null }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
