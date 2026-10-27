package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.alternativeStreamKey
import com.music.spotui.data.preferences.clearAlternativeStream
import com.music.spotui.data.preferences.getAlternativeStream
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.data.preferences.setLocalAlternativeStream
import com.music.spotui.data.preferences.setYouTubeAlternativeStream
import com.music.spotui.di.Palette
import com.music.spotui.di.RepeatMode
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val artistImageCache = java.util.concurrent.ConcurrentHashMap<String, String>()
private val artistIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

@OptIn(
    ExperimentalGlideComposeApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun PlayerScreen(
    navController: NavController,
    embedded: Boolean = false,
    onCollapse: () -> Unit = { navController.navigateUp() },
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val density = LocalDensity.current
    val screenHeight = with(density) {
        val dpHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        if (dpHeight.value > 0f) dpHeight.toPx() else 2000f
    }
    val coroutineScope = rememberCoroutineScope()

    // offsetY represents the current translation offset of the player screen.
    // It starts at screenHeight (so the screen initially renders fully off-screen)
    // and animates up to 0f.
    var offsetY by remember(embedded) { mutableFloatStateOf(if (embedded) 0f else screenHeight) }
    val animatable = remember { Animatable(screenHeight) }

    var animationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Track whether the entrance animation has completed so the auto-dismiss
    // safety-net doesn't fire on the initial off-screen state.
    var hasAppeared by remember { mutableStateOf(false) }

    suspend fun slideTo(targetValue: Float, velocity: Float = 0f) {
        try {
            animatable.snapTo(offsetY)
            animatable.animateTo(
                targetValue = targetValue,
                initialVelocity = velocity,
                animationSpec = if (targetValue == 0f || targetValue == screenHeight)
                    tween(300) else spring()
            ) {
                offsetY = this.value
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Animation was cancelled — offsetY is wherever the Animatable stopped.
        }
    }

    fun cancelRunningAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    fun launchAnimation(targetValue: Float, velocity: Float = 0f) {
        cancelRunningAnimation()
        animationJob = coroutineScope.launch {
            slideTo(targetValue, velocity)
        }
    }

    // ── Safety net: if offsetY ever reaches the bottom after the player has
    //    opened, dismiss regardless of how it got there. ──
    LaunchedEffect(embedded, screenHeight) {
        // Observe the animation boundary without restarting a coroutine on every
        // 60-Hz offset update (which caused avoidable UI-thread churn).
        snapshotFlow {
            !embedded && hasAppeared && offsetY >= screenHeight - 1f
        }.collect { shouldClose ->
            if (shouldClose) navController.navigateUp()
        }
    }

    // ── Configure dialog window for edge-to-edge ──
    // `decorFitsSystemWindows = false` in DialogProperties does NOT actually
    // make a Compose Navigation dialog draw behind system bars (known unfixed
    // issue).  The proven workaround is to copy the Activity window's
    // LayoutParams onto the dialog window and resize the dialog's parent view
    // to fill the screen — see https://stackoverflow.com/a/75768025
    val view = androidx.compose.ui.platform.LocalView.current
    if (!embedded) androidx.compose.runtime.SideEffect {
        // Walk up the view tree to find the dialog window.
        var dialogWindow: android.view.Window? = null
        var v: android.view.View? = view
        while (v != null) {
            if (v is androidx.compose.ui.window.DialogWindowProvider) {
                dialogWindow = v.window
                break
            }
            val parent = v.parent
            if (parent is android.view.View) {
                v = parent
            } else {
                if (parent is androidx.compose.ui.window.DialogWindowProvider) {
                    dialogWindow = parent.window
                    break
                }
                break
            }
        }
        // Get the Activity window through the context (works even inside a dialog).
        val activityWindow = generateSequence<android.content.Context>(view.context) { ctx ->
            (ctx as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<android.app.Activity>().firstOrNull()?.window

        if (activityWindow != null && dialogWindow != null) {
            // Copy the Activity's window attributes (which already have
            // edge-to-edge configured) onto the dialog window.
            val attrs = android.view.WindowManager.LayoutParams()
            attrs.copyFrom(activityWindow.attributes)
            attrs.type = dialogWindow.attributes.type
            attrs.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            attrs.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
            dialogWindow.attributes = attrs
            dialogWindow.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Resize the dialog's parent view to fill the screen.
            val parentView = view.parent as? android.view.View
            parentView?.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Make bars transparent.
            dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
            dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        if (hasAppeared) {
            offsetY = 0f
            animatable.snapTo(0f)
        }
    }

    // Animate the player sliding up when first opened
    LaunchedEffect(Unit) {
        if (embedded) {
            offsetY = 0f
            animatable.snapTo(0f)
        } else {
            // Full-screen dialog slides in; embedded tablet panel stays fixed.
            offsetY = screenHeight * 0.85f
            slideTo(0f)
        }
        hasAppeared = true
    }

    // Function to handle sliding down the player and popping the backstack
    val dismissPlayer: () -> Unit = {
        if (embedded) onCollapse() else launchAnimation(screenHeight)
    }

    // Intercept hardware system back press to slide player down smoothly
    BackHandler(enabled = true) {
        dismissPlayer()
    }

    // Create nested scroll connection to handle drag gestures
    val nestedScrollConnection = remember(screenHeight) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                // Only cancel ongoing slide animations when the USER is physically
                // dragging.  Fling-driven scroll events must not interfere.
                if (source == NestedScrollSource.Drag) {
                    cancelRunningAnimation()
                }
                // If the player is currently offset (offsetY > 0) and the user
                // drags up (delta < 0), consume the drag to slide the player back
                // up towards 0.
                if (offsetY > 0f && delta < 0f) {
                    val newOffset = (offsetY + delta).coerceIn(0f, screenHeight)
                    offsetY = newOffset
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (source == NestedScrollSource.Drag) {
                    cancelRunningAnimation()
                }
                // Unconsumed downward scroll (delta > 0) — translate the sheet
                // down ONLY when the user is physically dragging OR the sheet is
                // already partially offset.  During a fling, if the list just
                // reached its top, the leftover velocity must NOT start dragging
                // the sheet — it should stop here.
                if (delta > 0f && (source == NestedScrollSource.Drag || offsetY > 0f)) {
                    offsetY = (offsetY + delta).coerceIn(0f, screenHeight)
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // When the drag is released, if the player is offset, animate it
                // to either 0f (open) or screenHeight (dismiss).
                if (offsetY > 0f) {
                    val targetValue = when {
                        available.y < -500f -> 0f
                        available.y > 500f -> screenHeight
                        else -> if (offsetY > screenHeight * 0.25f) screenHeight else 0f
                    }
                    val job = coroutineScope.launch {
                        slideTo(targetValue, available.y)
                    }
                    animationJob = job
                    try {
                        job.join()
                    } catch (_: Exception) {
                    }
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // After the fling fully completes, if the sheet ended up partially
                // offset (e.g. a drag-then-fling that didn't trigger onPreFling's
                // snap logic), settle it now.
                if (offsetY > 0f) {
                    val targetValue = if (offsetY > screenHeight * 0.25f) screenHeight else 0f
                    val job = coroutineScope.launch {
                        slideTo(targetValue, available.y)
                    }
                    animationJob = job
                    try {
                        job.join()
                    } catch (_: Exception) {
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val songTitle by playerViewModel.currentSongTitle
    val songSinger by playerViewModel.currentSongSinger
    val songCoverUri by playerViewModel.currentSongCoverUri
    val songPlayingState by playerViewModel.currentSongPlayingState
    val songId by playerViewModel.currentSongId

    // Reconcile play/pause state immediately when opening the full screen player, ensuring
    // the play/pause button always accurately reflects whether audio is playing in SongPlayer.
    LaunchedEffect(Unit) {
        playerViewModel.syncWithPlayer()
    }
    val context = LocalContext.current
    val isLiked = remember(songId) {
        mutableStateOf(isSongLiked(context, songId.toString()))
    }
    var showMenu by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showSavedIn by remember { mutableStateOf(false) }
    var showArtistSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    if (showMenu) {
        PlayerOptionsSheet(
            navController = navController,
            playerViewModel = playerViewModel,
            context = context,
            isLiked = isLiked,
            onDismiss = { showMenu = false },
            onOpenQueue = { showQueueSheet = true }
        )
    }

    if (showSavedIn) {
        playerViewModel.queue.value.firstOrNull { it.id == songId }?.let { track ->
            com.music.spotui.ui.components.SavedInSheet(
                song = track,
                context = context,
                onDismiss = { showSavedIn = false },
                onLikedChanged = { isLiked.value = it },
            )
        } ?: run { showSavedIn = false }
    }

    if (showArtistSheet) {
        ArtistsSheet(
            artistNames = songSinger.split(",").map { it.trim() }.filter { it.isNotBlank() },
            artistIds = playerViewModel.currentArtistIds.value.split(",").map { it.trim() },
            context = context,
            onDismiss = { showArtistSheet = false },
            navController = navController,
        )
    }


    var songProgress by remember {
        mutableStateOf(
            maxOf(
                0f,
                SongPlayer.getCurrentPosition().toFloat()
            )
        )
    }
    // State is updated by the playback ticker below. Do not write to
    // mutableStateOf during composition: that forces another recomposition and
    // can make the tablet panel visibly stutter.
    var songDurationText by remember(songId) { mutableStateOf("0:00") }
    var songProgressText by remember(songId) { mutableStateOf("0:00") }

    //playerViewModel.updateSongState(songCoverUri, songTitle, songSinger, songPlayingState)


    var dominentColor by remember {
        mutableStateOf(Color(AppBackground.toArgb()))
    }
    var showDevicesSheet by remember { mutableStateOf(false) }
    // Run artwork color extraction only when the cover changes, not after every
    // progress tick / recomposition (especially important in the tablet pane).
    LaunchedEffect(songCoverUri) {
        if (songCoverUri.isNotBlank()) {
            val targetCover = songCoverUri
            Palette().extractSecondColorFromCoverUrl(context, targetCover) { color ->
                if (playerViewModel.currentSongCoverUri.value == targetCover) {
                    dominentColor = color
                }
            }
        }
    }

    val songsResponse by playerViewModel.songs.collectAsState()
    val shuffle by playerViewModel.shuffleState
    val repeat by playerViewModel.repeatState

    val songs = if (songsResponse is Response.Success) {
        (songsResponse as Response.Success).data
    } else {
        emptyList<SongsModel>()
    }

    // The queue is whatever list the user actually started playing (album tracks,
    // search results, liked songs) — stored when the song was tapped. Falling back
    // to the global top-tracks feed used to crash / be empty (it's rate-limited).
    val queueSongs by playerViewModel.queue

    // ── Now-playing swipe pager ──
    // Index of the playing track in the queue (fallback to 0 so the pager is valid
    // even before the queue/current id line up).
    val currentIndex = queueSongs.indexOfFirst { it.id == songId }
        .let { if (it >= 0) it else 0 }
    val artworkPagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(0, (queueSongs.size - 1).coerceAtLeast(0)),
        pageCount = { queueSongs.size.coerceAtLeast(1) },
    )
    var wasUserDragged by remember { mutableStateOf(false) }

    LaunchedEffect(artworkPagerState) {
        artworkPagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> wasUserDragged = true
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> wasUserDragged = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {}
            }
        }
    }

    // External track changes (auto-advance, prev/next buttons, queue edits) → snap the
    // pager to the new track. Guard on settled state so we don't fight an in-progress swipe.
    LaunchedEffect(currentIndex, queueSongs.size) {
        if (currentIndex in 0 until queueSongs.size &&
            artworkPagerState.currentPage != currentIndex &&
            !artworkPagerState.isScrollInProgress
        ) {
            artworkPagerState.scrollToPage(currentIndex)
        }
    }
    // User settled the pager on a different page via touch drag → play that track. Compare against the
    // live current id to avoid a replay feedback loop.
    LaunchedEffect(artworkPagerState) {
        snapshotFlow { artworkPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (wasUserDragged) {
                    wasUserDragged = false
                    val currentQueue = playerViewModel.queue.value
                    val currentIdx = currentQueue.indexOfFirst { it.id == playerViewModel.currentSongId.value }
                        .let { if (it >= 0) it else 0 }
                    if (page != currentIdx && page in currentQueue.indices) {
                        playerViewModel.playSongAt(currentQueue, page, context)
                        currentQueue.getOrNull(page)?.let { target ->
                            isLiked.value = isSongLiked(context, target.id.toString())
                        }
                    }
                }
            }
    }

    // Warm the stream cache for the adjacent tracks so next/previous start instantly.
    LaunchedEffect(songId, queueSongs) {
        val idx = queueSongs.indexOfFirst { it.id == songId }
        if (idx >= 0) {
            queueSongs.getOrNull(idx + 1)?.let { SongPlayer.prefetch(it.url, context) }
            queueSongs.getOrNull(idx - 1)?.let { SongPlayer.prefetch(it.url, context) }
        }
    }

    // Load the current track's Spotify Canvas (full-screen looping video background).
    LaunchedEffect(songId, queueSongs) {
        val track = queueSongs.firstOrNull { it.id == songId }
        // Downloaded tracks are meant for offline use — skip the Canvas video
        // (which needs network to stream) and always show the squared artwork.
        val downloaded = track != null &&
            com.music.spotui.data.preferences.isDownloaded(context, track.id.toString())
        playerViewModel.loadCanvas(if (downloaded) "" else track?.spotifyTrackId.orEmpty())
    }




    LaunchedEffect(songId, songPlayingState) {
        while (true) {
            // Keep playing state continuously reconciled with physical audio engine state
            playerViewModel.syncWithPlayer()

            val dur = SongPlayer.getDuration()
            songDurationText = if (dur < 0) "0:00" else playerViewModel.formatDuration(dur)

            val pos = SongPlayer.getCurrentPosition()
            songProgress = pos.toFloat()
            songProgressText = if (pos < 0) "0:00" else playerViewModel.formatDuration(pos)

            if (songPlayingState) {
                delay(300L)
            } else {
                if (dur > 0) {
                    delay(2000L)
                } else {
                    delay(300L)
                }
            }
        }
    }


    val currentEpisode = queueSongs.firstOrNull { it.id == songId }
        ?.takeIf { it.url.startsWith("yt_ep:") }
    val podcastVideoId = remember(currentEpisode?.url) {
        currentEpisode?.url?.removePrefix("yt_ep:")?.substringBefore('|')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
    // Video mode is owned by the playback session, never by this composition.
    // Minimize, tablet -> full-screen, or back navigation must not reset it.
    val showPodcastVideo = PodcastVideoSession.isActive(podcastVideoId)
    val togglePodcastVideo: () -> Unit = {
        podcastVideoId?.let { id ->
            PodcastVideoSession.setEnabled(context, id, !PodcastVideoSession.isActive(id))
        }
    }
    val podcastChapters = remember(currentEpisode?.description) {
        parsePodcastChapters(currentEpisode?.description.orEmpty())
    }
    var showPodcastChapters by remember(podcastVideoId) { mutableStateOf(false) }
    if (showPodcastChapters && podcastChapters.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showPodcastChapters = false },
            containerColor = Color(0xFF202020)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(uiLocalized("Capítulos"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                ) {
                    items(podcastChapters.size) { index ->
                        val chapter = podcastChapters[index]
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                SongPlayer.seekTo(chapter.timeMs)
                                showPodcastChapters = false
                            }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(chapter.timeFormatted, color = Color(0xFF1ED760),
                                fontSize = 13.sp, modifier = Modifier.width(76.dp))
                            Text(chapter.title, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
    val canvasUrl = playerViewModel.canvasUrl.value.takeIf { currentEpisode == null }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.nestedScroll(nestedScrollConnection))
            .pointerInput(embedded) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val anyPressed = event.changes.any { it.pressed }
                        if (anyPressed && !embedded) {
                            if (animationJob != null) {
                                cancelRunningAnimation()
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                translationY = if (embedded) 0f else offsetY
                alpha = if (embedded) 1f else (1f - (offsetY / screenHeight)).coerceIn(0f, 1f)
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(dominentColor, Color.Black),
                    startY = 100f
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        if (canvasUrl != null && com.music.spotui.ui.visibility.UiVisibility.isVisible) {
            // Spotify Canvas: the looping video fills the whole now-playing screen
            // edge-to-edge behind the controls (the "immersive" treatment), with a
            // scrim on top so the title, slider and buttons stay readable.
            CanvasVideo(
                url = canvasUrl,
                modifier = Modifier.fillMaxSize(),
                onError = { playerViewModel.clearCanvas() },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.50f),
                                Color.Black.copy(alpha = 0.10f),
                                Color.Black.copy(alpha = 0.35f),
                                Color.Black.copy(alpha = 0.80f),
                            )
                        )
                    )
            )
        }
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = !embedded && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        androidx.compose.foundation.lazy.LazyColumn(
            // Limit control spread on wide tablets while leaving immersive
            // artwork / video filling the entire background.
            modifier = Modifier
                .widthIn(max = 1180.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left side: Artwork / Pager
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .padding(12.dp)
                        ) {
                            if (showPodcastVideo && podcastVideoId != null) {
                                PodcastVideoFrame(
                                    videoId = podcastVideoId,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(10.dp)),
                                    onUseAudio = { PodcastVideoSession.stop() },
                                    surfacePriority = if (embedded) 1 else 2
                                )
                            } else if (queueSongs.isEmpty()) {
                                GlideImage(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .alpha(1f),
                                    model = queueSongs.firstOrNull { it.id == songId }?.let {
                                        com.music.spotui.ui.notification.PodcastCover.preferredUri(context, it)
                                    } ?: songCoverUri,
                                    contentScale = ContentScale.Crop,
                                    contentDescription = ""
                                )
                            } else {
                                HorizontalPager(
                                    state = artworkPagerState,
                                    modifier = Modifier.aspectRatio(1f),
                                ) { page ->
                                    GlideImage(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(10.dp))
                                            .alpha(1f),
                                        model = queueSongs.getOrNull(page)?.let {
                                            com.music.spotui.ui.notification.PodcastCover.preferredUri(context, it)
                                        } ?: songCoverUri,
                                        contentScale = ContentScale.Crop,
                                        contentDescription = ""
                                    )
                                }
                            }
                        }

                        // Right side: TopBar + Controls + Connect Row
                        Column(
                            verticalArrangement = Arrangement.SpaceEvenly,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                        ) {
                            PlayerTopBar(
                                navController = navController,
                                onMenuClick = { showMenu = true },
                                contextName = playerViewModel.currentSongAlbum.value,
                                onBackClick = { dismissPlayer() },
                                onFullscreenClick = if (embedded) { { navController.navigate(com.music.spotui.ui.navigation.Routes.Player.route) } } else null,
                                podcastVideoEnabled = showPodcastVideo,
                                onPodcastVideoToggle = if (podcastVideoId != null) {
                                    togglePodcastVideo
                                } else null,
                            )

                            PlayerInfo(
                                songTitle, songSinger, songId, context, isLiked,
                                source = SongPlayer.currentSource,
                                quality = SongPlayer.currentQuality,
                                isResolving = playerViewModel.isResolving.value,
                                resolveStatus = playerViewModel.resolveStatus.value,
                                resolveError = playerViewModel.resolveError.value,
                                resolveDetailNote = playerViewModel.resolveDetailNote.value,
                                onArtistClick = {
                                    val currentPlayingSong = queueSongs.firstOrNull { it.id == songId }
                                    val trackId = currentPlayingSong?.spotifyTrackId.orEmpty()
                                    playerViewModel.goToArtist(trackId, songSinger) { route ->
                                        navController.navigate(route)
                                    }
                                },
                                spotifyTrackId = queueSongs.firstOrNull { it.id == songId }?.spotifyTrackId.orEmpty(),
                                onShowSavedIn = { showSavedIn = true },
                                isExplicit = queueSongs.firstOrNull { it.id == songId }?.explicit == true,
                            )

                            Column(modifier = Modifier.fillMaxWidth()) {
                                var isDragging by remember { mutableStateOf(false) }
                                var dragValue by remember { mutableStateOf(0f) }
                                val liveFraction = SongPlayer.getDuration().toFloat().let { dur ->
                                    if (dur > 0f) (SongPlayer.getCurrentPosition()
                                        .toFloat() / dur).coerceIn(0f, 1f) else 0f
                                }
                                CustomSlider(
                                    value = if (isDragging) dragValue else liveFraction,
                                    onValueChange = { newValue ->
                                        isDragging = true
                                        dragValue = newValue
                                    },
                                    onValueChangeFinished = {
                                        val dur = SongPlayer.getDuration()
                                        if (dur > 0) SongPlayer.seekTo((dragValue * dur).toLong())
                                        isDragging = false
                                        if (!songPlayingState) {
                                            SongPlayer.play()
                                            playerViewModel.updateSongState(
                                                playerViewModel.currentSongCoverUri.value,
                                                playerViewModel.currentSongTitle.value,
                                                playerViewModel.currentSongSinger.value,
                                                true,
                                                playerViewModel.currentSongId.value,
                                                playerViewModel.currentSongIndex.value,
                                                playerViewModel.currentSongAlbum.value
                                            )
                                        }
                                    },
                                    valueRange = 0f..1f,
                                    steps = 0,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    colors = null
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isDragging) {
                                            val dur = SongPlayer.getDuration()
                                            if (dur > 0) playerViewModel.formatDuration((dragValue * dur).toLong()) else "0:00"
                                        } else songProgressText,
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = songDurationText,
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            PlayerFull(
                                songPlayingState,
                                playerViewModel,
                                context,
                                isLiked,
                                shuffle,
                                repeat,
                                queueSongs
                            )

                            PlayerConnectRow(
                                navController = navController,
                                context = context,
                                currentTrack = queueSongs.firstOrNull { it.id == playerViewModel.currentSongId.value },
                                onOpenDevices = { showDevicesSheet = true },
                                onOpenQueue = { showQueueSheet = true }
                            )
                        }
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .fillMaxWidth()
                            .then(if (embedded) Modifier else Modifier.navigationBarsPadding()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!embedded) Spacer(modifier = Modifier.statusBarsPadding())
                        PlayerTopBar(
                            navController = navController,
                            onMenuClick = { showMenu = true },
                            contextName = playerViewModel.currentSongAlbum.value,
                            onBackClick = { dismissPlayer() },
                            onFullscreenClick = if (embedded) { { navController.navigate(com.music.spotui.ui.navigation.Routes.Player.route) } } else null,
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            if (showPodcastVideo && podcastVideoId != null) {
                                PodcastVideoFrame(
                                    videoId = podcastVideoId,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(10.dp)),
                                    onUseAudio = { PodcastVideoSession.stop() },
                                    surfacePriority = if (embedded) 1 else 2
                                )
                            } else if (queueSongs.isEmpty()) {
                                GlideImage(
                                    modifier = Modifier
                                        .sizeIn(maxWidth = 385.dp, maxHeight = 385.dp)
                                        .aspectRatio(1f)
                                        .padding(20.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .alpha(if (canvasUrl != null) 0f else 1f),
                                    model = queueSongs.firstOrNull { it.id == songId }?.let {
                                        com.music.spotui.ui.notification.PodcastCover.preferredUri(context, it)
                                    } ?: songCoverUri,
                                    contentScale = ContentScale.Crop,
                                    contentDescription = ""
                                )
                            } else {
                                HorizontalPager(
                                    state = artworkPagerState,
                                    modifier = Modifier
                                        .sizeIn(maxWidth = 385.dp, maxHeight = 385.dp)
                                        .aspectRatio(1f),
                                ) { page ->
                                    GlideImage(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(20.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .alpha(if (canvasUrl != null) 0f else 1f),
                                        model = queueSongs.getOrNull(page)?.let {
                                            com.music.spotui.ui.notification.PodcastCover.preferredUri(context, it)
                                        } ?: songCoverUri,
                                        contentScale = ContentScale.Crop,
                                        contentDescription = ""
                                    )
                                }
                            }
                        }

                        if (podcastVideoId != null) {
                            // One aligned control group for audio/video and chapters, matching
                            // the same podcast actions on phone and tablet.
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Surface(
                                    onClick = togglePodcastVideo,
                                    color = Color.Black.copy(alpha = 0.42f),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Text(
                                        text = if (showPodcastVideo) uiLocalized("Switch to audio") else uiLocalized("Switch to video"),
                                        color = Color.White, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                                if (podcastChapters.isNotEmpty()) {
                                    androidx.compose.material3.Surface(
                                        onClick = { showPodcastChapters = true },
                                        color = Color.Black.copy(alpha = 0.42f),
                                        shape = RoundedCornerShape(24.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null,
                                                tint = Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(uiLocalized("Capítulos"), color = Color.White, fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            PlayerInfo(
                                songTitle, songSinger, songId, context, isLiked,
                                source = SongPlayer.currentSource,
                                quality = SongPlayer.currentQuality,
                                isResolving = playerViewModel.isResolving.value,
                                resolveStatus = playerViewModel.resolveStatus.value,
                                resolveError = playerViewModel.resolveError.value,
                                resolveDetailNote = playerViewModel.resolveDetailNote.value,
                                onArtistClick = {
                                    val currentPlayingSong = queueSongs.firstOrNull { it.id == songId }
                                    val trackId = currentPlayingSong?.spotifyTrackId.orEmpty()
                                    playerViewModel.goToArtist(trackId, songSinger) { route ->
                                        navController.navigate(route)
                                    }
                                },
                                spotifyTrackId = queueSongs.firstOrNull { it.id == songId }?.spotifyTrackId.orEmpty(),
                                onShowSavedIn = { showSavedIn = true },
                                isExplicit = queueSongs.firstOrNull { it.id == songId }?.explicit == true,
                            )

                            var isDragging by remember { mutableStateOf(false) }
                            var dragValue by remember { mutableStateOf(0f) }
                            val liveFraction = SongPlayer.getDuration().toFloat().let { dur ->
                                if (dur > 0f) (SongPlayer.getCurrentPosition()
                                    .toFloat() / dur).coerceIn(0f, 1f) else 0f
                            }
                            CustomSlider(
                                value = if (isDragging) dragValue else liveFraction,
                                onValueChange = { newValue ->
                                    isDragging = true
                                    dragValue = newValue
                                },
                                onValueChangeFinished = {
                                    val dur = SongPlayer.getDuration()
                                    if (dur > 0) SongPlayer.seekTo((dragValue * dur).toLong())
                                    isDragging = false
                                    if (!songPlayingState) {
                                        SongPlayer.play()
                                        playerViewModel.updateSongState(
                                            playerViewModel.currentSongCoverUri.value,
                                            playerViewModel.currentSongTitle.value,
                                            playerViewModel.currentSongSinger.value,
                                            true,
                                            playerViewModel.currentSongId.value,
                                            playerViewModel.currentSongIndex.value,
                                            playerViewModel.currentSongAlbum.value
                                        )
                                    }
                                },
                                valueRange = 0f..1f,
                                steps = 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp, 12.dp, 16.dp, 0.dp),
                                colors = null
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(25.dp, 0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (isDragging) {
                                        val dur = SongPlayer.getDuration()
                                        if (dur > 0) playerViewModel.formatDuration((dragValue * dur).toLong()) else "0:00"
                                    } else songProgressText,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = songDurationText,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            PlayerFull(
                                songPlayingState,
                                playerViewModel,
                                context,
                                isLiked,
                                shuffle,
                                repeat,
                                queueSongs
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PlayerConnectRow(
                            navController = navController,
                            context = context,
                            currentTrack = queueSongs.firstOrNull { it.id == playerViewModel.currentSongId.value },
                            onOpenDevices = { showDevicesSheet = true },
                            onOpenQueue = { showQueueSheet = true }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            item {
                InlineLyrics(
                    title = songTitle,
                    artist = songSinger,
                    album = playerViewModel.currentSongAlbum.value,
                    accentColor = dominentColor,
                    onExpand = { showLyrics = true },
                )
            }
        }

        if (showLyrics) {
            LyricsScreen(
                title = songTitle,
                artist = songSinger,
                album = playerViewModel.currentSongAlbum.value,
                accentColor = dominentColor,
                onClose = { showLyrics = false }
            )
        }

        if (showDevicesSheet) {
            com.music.spotui.ui.components.DevicesSheet(
                context = context,
                onDismiss = { showDevicesSheet = false }
            )
        }

        if (showQueueSheet) {
            QueueSheet(
                navController = navController,
                onDismiss = { showQueueSheet = false }
            )
        }
    }
}


@Composable
fun PlayerTopBar(
    navController: NavController,
    onMenuClick: () -> Unit,
    contextName: String = "",
    onBackClick: () -> Unit,
    onFullscreenClick: (() -> Unit)? = null,
    podcastVideoEnabled: Boolean = false,
    onPodcastVideoToggle: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onFullscreenClick != null) {
                androidx.compose.material3.IconButton(onClick = onFullscreenClick) {
                    // Spotify-style expand-pane control: an outlined screen with
                    // a side bar and chevron, not the application's launcher logo.
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(26.dp)) {
                        val stroke = 2.dp.toPx()
                        val w = size.width
                        val h = size.height
                        drawRoundRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke),
                            size = androidx.compose.ui.geometry.Size(w - 2f * stroke, h - 2f * stroke),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                        )
                        drawLine(Color.White,
                            start = androidx.compose.ui.geometry.Offset(w * .35f, stroke),
                            end = androidx.compose.ui.geometry.Offset(w * .35f, h - stroke),
                            strokeWidth = stroke)
                        drawLine(Color.White,
                            start = androidx.compose.ui.geometry.Offset(w * .72f, h * .30f),
                            end = androidx.compose.ui.geometry.Offset(w * .53f, h * .50f),
                            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(Color.White,
                            start = androidx.compose.ui.geometry.Offset(w * .53f, h * .50f),
                            end = androidx.compose.ui.geometry.Offset(w * .72f, h * .70f),
                            strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
            androidx.compose.material3.IconButton(onClick = onBackClick) {
                Icon(painter = painterResource(id = R.drawable.ic_down),
                    tint = Color.White, contentDescription = uiLocalized("Minimizar reproductor"))
            }
        }

        // Spotify shows the source context here (album/playlist), not a generic label.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.playing_from),
                color = Color(0xFFB3B3B3),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = contextName.ifBlank { stringResource(R.string.now_playing) },
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (onPodcastVideoToggle != null) 135.dp else 200.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onPodcastVideoToggle != null) {
                Text(
                    text = if (podcastVideoEnabled) uiLocalized("Audio") else uiLocalized("Video"),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(onClick = onPodcastVideoToggle)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(7.dp))
            }
            Icon(
                imageVector = Icons.Default.MoreVert,
                tint = Color.White,
                modifier = Modifier
                    .size(23.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onMenuClick() },
                contentDescription = ""
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerInfo(
    songTitle: String,
    songSinger: String,
    songId: Int,
    context: Context,
    isLiked: MutableState<Boolean>,
    source: String = "",
    quality: String = "",
    isResolving: Boolean = false,
    resolveStatus: String = "",
    resolveError: String? = null,
    resolveDetailNote: String? = null,
    onArtistClick: (() -> Unit)? = null,
    spotifyTrackId: String = "",
    onShowSavedIn: (() -> Unit)? = null,
    isExplicit: Boolean = false,
) {

    var snackbarMessage by remember {
        mutableStateOf("")
    }
    var snackbarVisible by remember {
        mutableStateOf(false)
    }
    var showStreamDetailDialog by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(snackbarVisible) {
        delay(1500)
        snackbarVisible = false
    }

    if (showStreamDetailDialog) {
        AlertDialog(
            onDismissRequest = { showStreamDetailDialog = false },
            title = {
                Text(uiLocalized("Stream Resolution Info"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(uiLocalized("Engine / Source:"), color = Color(0xFFB3B3B3), fontSize = 13.sp, modifier = Modifier.width(115.dp))
                        Text(if (source.isBlank()) "Standard" else source, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (quality.isNotBlank()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(uiLocalized("Audio Quality:"), color = Color(0xFFB3B3B3), fontSize = 13.sp, modifier = Modifier.width(115.dp))
                            Text(quality, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (!resolveDetailNote.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(uiLocalized("Status Note:"), color = Color(0xFFB3B3B3), fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            resolveDetailNote,
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22FFB74D))
                                .padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(uiLocalized("Resolution Trace Log:"), color = Color(0xFFB3B3B3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0C0C10))
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Column {
                                val logs = com.music.spotui.di.SongPlayer.resolutionLogs.toList()
                                if (logs.isEmpty()) {
                                    Text(uiLocalized("No live trace logs captured for current playback session."), color = Color.Gray, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                } else {
                                    logs.forEach { line ->
                                        val textColor = when {
                                            line.contains("✓") -> Color(0xFF81C784)
                                            line.contains("✗") -> Color(0xFFE57373)
                                            line.contains("Fallback") || line.contains("Exhausted") -> Color(0xFFFFB74D)
                                            else -> Color(0xFFD1D1D6)
                                        }
                                        Text(line, color = textColor, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    TextButton(onClick = {
                        val logs = com.music.spotui.di.SongPlayer.resolutionLogs.toList()
                        val text = logs.joinToString("\n").ifBlank { "No logs available" }
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                        Toast.makeText(context, "Resolution logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text(uiLocalized("Copy Logs"), color = AppPalette)
                    }
                    TextButton(onClick = { showStreamDetailDialog = false }) {
                        Text(uiLocalized("Close"), color = AppPalette)
                    }
                }
            },
            containerColor = Color(0xFF141418),
            titleContentColor = Color.White,
            textContentColor = Color.White,
        )
    }

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(25.dp, 10.dp)
    ) {

        if (snackbarVisible) {
            Snackbar(showMessage = snackbarMessage)
        } else {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .width(270.dp)
            ) {
//                        GlideImage(
//                            modifier = Modifier.size(60.dp),
//                            model = albumSongs[song].coverUri,
//                            contentScale = ContentScale.Crop,
//                            contentDescription = ""
//                        )
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isExplicit) {
                            com.music.spotui.ui.components.ExplicitBadge(size = 11.sp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                            text = songTitle,
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        text = songSinger,
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = if (onArtistClick != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onArtistClick() } else Modifier,
                    )
                    val isResolvingState = isResolving && resolveStatus.isNotBlank()
                    val hasError = !resolveError.isNullOrBlank()
                    val displaySource =
                        if (isResolvingState) "Resolving" else if (hasError) "Error" else source
                    if (displaySource.isNotBlank()) {
                        // Source badge: green = real Spotify; other colors = not Spotify
                        // (Lossless via SpotiFLAC's Tidal/Qobuz/Amazon/Deezer mirrors, or YouTube).
                        val badgeColor = when {
                            hasError -> Color(0xFFFF6B6B)
                            isResolvingState -> Color(0xFF3DABFF)
                            source == "Spotify" -> Color(0xFF1ED760)
                            source.startsWith("Lossless") -> Color(0xFFFFC862)
                            source.startsWith("Deezer") || source == "Deezer" -> Color(0xFFA238FF)
                            source == "Downloaded" -> Color(0xFF9C9C9C)
                            else -> Color(0xFFFF6B6B)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .clickable { showStreamDetailDialog = true },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Text(
                                text = when {
                                    hasError -> resolveError!!
                                    isResolvingState -> resolveStatus
                                    else -> {
                                        source + (if (quality.isNotBlank()) " • $quality" else "")
                                    }
                                },
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
            }

            Icon(
                modifier = Modifier
                    .size(26.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (isLiked.value && onShowSavedIn != null) {
                                // Already saved — second tap opens the Spotify-style
                                // "Saved in" sheet (Liked Songs + playlists) instead of
                                // silently unliking.
                                onShowSavedIn()
                                return@combinedClickable
                            }
                            if (isLiked.value) {
                                removeLikedSongId(context, songId.toString())
                                snackbarMessage = "Removed from Liked Songs"
                            } else {
                                addLikedSongId(context, songId.toString())
                                snackbarMessage = "Added to Liked Songs"
                            }
                            snackbarVisible = true
                            isLiked.value = isSongLiked(context, songId.toString())
                            // Mirror the like to the real Spotify account.
                            com.music.spotui.data.api.SpotifySync.setTrackSaved(
                                context,
                                spotifyTrackId,
                                isLiked.value
                            )
                        },
                        onLongClick = { onShowSavedIn?.invoke() },
                    ),
                painter = if (isLiked.value) {
                    painterResource(id = R.drawable.added)
                } else {
                    painterResource(id = R.drawable.ic_add)
                },
                tint = if (isLiked.value) {
                    Color(0xFF1ED760) // Spotify green for the added-to-library tick.
                } else {
                    Color.White
                },
                contentDescription = ""
            )
        }
    }


}

@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: Any? = null, // kept for API compat; unused
) {
    var isDragging by remember { mutableStateOf(false) }

    // Track height grows slightly while dragging (Spotify micro-animation)
    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 5.dp else 3.dp,
        animationSpec = tween(durationMillis = 150),
        label = "trackHeight"
    )
    // Thumb alpha: invisible at rest, pops in when dragging
    val thumbAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "thumbAlpha"
    )

    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .height(36.dp) // generous vertical touch target
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val mapped =
                        valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start)
                    onValueChange(mapped)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val newFraction =
                            (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val mapped =
                            valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start)
                        onValueChange(mapped)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val trackHeightPx = with(density) { trackHeight.toPx() }
        val thumbRadiusPx = with(density) { 6.dp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
        ) {
            val trackY = size.height / 2f
            val thumbX = fraction * size.width

            // Inactive (background) track
            drawLine(
                color = Color(0xFF535353),
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = trackHeightPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Active (played) track
            drawLine(
                color = Color.White,
                start = Offset(0f, trackY),
                end = Offset(thumbX, trackY),
                strokeWidth = trackHeightPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Thumb dot — only visible while dragging
            if (thumbAlpha > 0f) {
                drawCircle(
                    color = Color.White.copy(alpha = thumbAlpha),
                    radius = thumbRadiusPx,
                    center = Offset(thumbX, trackY)
                )
            }
        }
    }
}

@Composable
fun PlayerEndInfo() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Icon(
            modifier = Modifier
                .size(22.dp),
            painter = painterResource(id = R.drawable.ic_devices),
            tint = Color.White,
            contentDescription = ""
        )
        Icon(
            modifier = Modifier
                .size(16.dp),
            painter = painterResource(id = R.drawable.ic_share),
            tint = Color.White,
            contentDescription = ""
        )
    }
}

@Composable
fun PlayerFull(
    songPlayingState: Boolean,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    shuffle: Boolean,
    repeat: RepeatMode,
    queueSongs: List<SongsModel>
) {


    val podcastEpisode = queueSongs.firstOrNull { it.id == playerViewModel.currentSongId.value }
        ?.url?.startsWith("yt_ep:") == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        if (podcastEpisode) {
            Icon(
                painter = painterResource(id = R.drawable.ic_player_back),
                tint = Color.White,
                contentDescription = uiLocalized("Episodio anterior"),
                modifier = Modifier.size(25.dp).clickable {
                    playerViewModel.playPreviousSong(queueSongs, context)
                }
            )
        } else {
        Icon(
            modifier = Modifier
                .size(25.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (shuffle) {
                        playerViewModel.updateShuffleState(false)
                    } else {
                        playerViewModel.updateShuffleState(true)
                    }

                },
            tint = if (shuffle) {
                Color(0xFF1ED760)
            } else {
                Color.White
            },
            painter = painterResource(id = R.drawable.ic_player_shuffle),
            contentDescription = ""
        )
        }
        if (podcastEpisode) {
            androidx.compose.material3.Text(
                text = uiLocalized("↶15"),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.clickable {
                    SongPlayer.seekTo((SongPlayer.getCurrentPosition() - 15_000L).coerceAtLeast(0L))
                },
            )
        } else {
        Icon(
            modifier = Modifier
                .size(35.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // The queue itself is already in shuffled order when shuffle
                    // is on (reordered once at toggle) — never re-shuffle per tap.
                    playerViewModel.playPreviousSong(queueSongs, context)
                    isLiked.value =
                        isSongLiked(context, playerViewModel.currentSongId.value.toString())
                },
            tint = Color.White,
            painter = painterResource(id = R.drawable.ic_player_back),
            contentDescription = ""
        )
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                // requiredSize forces an exact 64×64 square even if the parent Column
                // constrains height — .size() alone let it get squished into an ellipse.
                .requiredSize(64.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable {
                    if (songPlayingState) {
                        SongPlayer.pause()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            false,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    } else {
                        SongPlayer.play()
                        playerViewModel.updateSongState(
                            playerViewModel.currentSongCoverUri.value,
                            playerViewModel.currentSongTitle.value,
                            playerViewModel.currentSongSinger.value,
                            true,
                            playerViewModel.currentSongId.value,
                            playerViewModel.currentSongIndex.value,
                            playerViewModel.currentSongAlbum.value
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val isLocatingOrBuffering =
                playerViewModel.isResolving.value || playerViewModel.isBuffering.value
            if (isLocatingOrBuffering) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = Color.Black,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    modifier = Modifier
                        .size(30.dp),
                    tint = Color.Black,
                    painter = if (songPlayingState)
                        painterResource(id = R.drawable.ic_playing)
                    else
                        painterResource(id = R.drawable.play_svgrepo_com),
                    contentDescription = ""
                )
            }
        }

        if (podcastEpisode) {
            androidx.compose.material3.Text(
                text = uiLocalized("15↷"),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.clickable {
                    val duration = SongPlayer.getDuration()
                    val target = SongPlayer.getCurrentPosition() + 15_000L
                    SongPlayer.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                },
            )
        } else {
        Icon(
            modifier = Modifier
                .size(35.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {

                    playerViewModel.playNextSongs(queueSongs, context)
                    isLiked.value =
                        isSongLiked(context, playerViewModel.currentSongId.value.toString())
                },
            tint = Color.White,
            painter = painterResource(id = R.drawable.ic_player_skip),
            contentDescription = ""
        )
        }
        if (podcastEpisode) {
            Icon(
                painter = painterResource(id = R.drawable.ic_player_skip),
                tint = Color.White,
                contentDescription = uiLocalized("Episodio siguiente"),
                modifier = Modifier.size(25.dp).clickable {
                    playerViewModel.playNextSongs(queueSongs, context)
                }
            )
        } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(width = 32.dp, height = 40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    val nextRepeat = when (repeat) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    playerViewModel.updateRepeatState(nextRepeat)
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    modifier = Modifier.size(20.dp),
                    tint = if (repeat != RepeatMode.OFF) {
                        Color(0xFF1ED760)
                    } else {
                        Color.White
                    },
                    painter = painterResource(id = R.drawable.ic_repeat),
                    contentDescription = uiLocalized("Repeat")
                )
                if (repeat == RepeatMode.ONE) {
                    Text(
                        text = uiLocalized("1"),
                        color = Color(0xFF1ED760),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(y = (-1).dp)
                    )
                }
            }
            if (repeat != RepeatMode.OFF) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
modifier = Modifier
                        .size(4.dp)
                        .background(Color(0xFF1ED760), shape = CircleShape)
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }        }

    }
}

@Composable
fun PlayerEndInfo(onOpenDevices: () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Icon(
            modifier = Modifier
                .size(22.dp)
                .clickable { onOpenDevices() },
            painter = painterResource(id = R.drawable.ic_devices),
            tint = Color.White,
            contentDescription = uiLocalized("Devices")
        )
        Icon(
            modifier = Modifier
                .size(16.dp),
            painter = painterResource(id = R.drawable.ic_share),
            tint = Color.White,
            contentDescription = ""
        )
    }
}

/** The current audio output route name for the Connect indicator (BT name if
 *  connected, else Headphones / This device). */
private fun currentAudioRoute(context: Context): String {
    return com.music.spotui.ui.utils.AudioDeviceHelper.getCurrentAudioRouteName(context)
}

@Composable
fun PlayerConnectRow(
    navController: NavController,
    context: Context,
    currentTrack: SongsModel?,
    onOpenDevices: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
) {
    DisposableEffect(context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                com.music.spotui.ui.utils.AudioDeviceHelper.updateRouteName(context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                com.music.spotui.ui.utils.AudioDeviceHelper.updateRouteName(context)
            }
        }
        am.registerAudioDeviceCallback(callback, null)
        com.music.spotui.ui.utils.AudioDeviceHelper.updateRouteName(context)
        onDispose {
            am.unregisterAudioDeviceCallback(callback)
        }
    }

    val routeName by com.music.spotui.ui.utils.AudioDeviceHelper.currentRouteNameState
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 25.dp, vertical = 4.dp),
    ) {
        // Device / Spotify Connect indicator (green, like the official app).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenDevices() }
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_devices),
                tint = Color(0xFF1ED760),
                modifier = Modifier.size(18.dp),
                contentDescription = uiLocalized("Device"),
            )
            Text(
                text = routeName,
                color = Color(0xFF1ED760),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .widthIn(max = 170.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_share),
                tint = Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        val link = currentTrack?.spotifyTrackId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "https://open.spotify.com/track/$it" }
                            ?: "${currentTrack?.title ?: ""} ${currentTrack?.singer ?: ""}".trim()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        }
                        context.startActivity(Intent.createChooser(send, "Share"))
                    },
                contentDescription = uiLocalized("Share"),
            )
            Spacer(modifier = Modifier.width(22.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.List,
                tint = Color.White,
                modifier = Modifier
                    .size(23.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpenQueue() },
                contentDescription = uiLocalized("Queue"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun ArtistsSheet(
    artistNames: List<String>,
    artistIds: List<String> = emptyList(),
    context: Context,
    onDismiss: () -> Unit,
    navController: NavController,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val artistImages = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val resolvedIds = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }

    LaunchedEffect(artistNames, artistIds) {
        artistNames.forEachIndexed { index, name ->
            val id = artistIds.getOrElse(index) { "" }
            if (id.isNotBlank()) {
                val cachedUrl = artistImageCache[id]
                if (!cachedUrl.isNullOrBlank()) {
                    artistImages[id] = cachedUrl
                } else {
                    if (com.music.spotui.data.api.SpotifyTokenProvider.ensureToken(context)) {
                        com.metrolist.spotify.Spotify.artist(id).getOrNull()?.let { artist ->
                            val url = artist.images.firstOrNull()?.url.orEmpty()
                            artistImageCache[id] = url
                            artistImages[id] = url
                        }
                    }
                }
            } else if (name.isNotBlank()) {
                val cachedId = artistIdCache[name]
                val cachedUrl = artistImageCache[cachedId ?: name]
                if (!cachedUrl.isNullOrBlank()) {
                    artistImages[name] = cachedUrl
                    if (!cachedId.isNullOrBlank()) resolvedIds[name] = cachedId
                } else {
                    if (com.music.spotui.data.api.SpotifyTokenProvider.ensureToken(context)) {
                        com.metrolist.spotify.Spotify.search(name, types = listOf("artist"), limit = 1).getOrNull()
                            ?.artists?.items?.firstOrNull()?.let { artist ->
                                val url = artist.images.firstOrNull()?.url.orEmpty()
                                artistIdCache[name] = artist.id
                                artistImageCache[name] = url
                                artistImageCache[artist.id] = url
                                artistImages[name] = url
                                resolvedIds[name] = artist.id
                            }
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = uiLocalized("Artists"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            artistNames.forEachIndexed { index, name ->
                val directId = artistIds.getOrElse(index) { "" }
                val effectiveId = directId.ifBlank { resolvedIds[name].orEmpty() }
                val imageUrl = if (directId.isNotBlank()) {
                    artistImages[directId].orEmpty()
                } else {
                    artistImages[name].orEmpty()
                }
                var following by remember(effectiveId) {
                    mutableStateOf(
                        effectiveId.isNotBlank() && com.music.spotui.data.preferences.isArtistFollowed(
                            context,
                            effectiveId
                        )
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    if (imageUrl.isNotBlank()) {
                        GlideImage(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                            model = imageUrl,
                            contentScale = ContentScale.Crop,
                            contentDescription = name,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF333333)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                tint = Color.Gray,
                                modifier = Modifier.size(24.dp),
                                contentDescription = name,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onDismiss()
                                navController.navigate(
                                    com.music.spotui.ui.navigation.artistRoute(name, effectiveId)
                                )
                            },
                    )
                    if (effectiveId.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .border(
                                    1.dp,
                                    if (following) Color.Transparent else Color.Gray,
                                    RoundedCornerShape(20.dp),
                                )
                                .background(
                                    if (following) Color(0xFF1DB954) else Color.Transparent,
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable {
                                    following = !following
                                    if (following) {
                                        com.music.spotui.data.preferences.addFollowedArtist(
                                            context,
                                            effectiveId,
                                            name
                                        )
                                    } else {
                                        com.music.spotui.data.preferences.removeFollowedArtist(
                                            context,
                                            effectiveId
                                        )
                                    }
                                    com.music.spotui.data.api.SpotifySync.setArtistFollowed(
                                        context,
                                        effectiveId,
                                        following
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = if (following) uiLocalized("Following") else uiLocalized("Follow"),
                                color = if (following) Color(0xFF191414) else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun PlayerOptionsSheet(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    context: Context,
    isLiked: MutableState<Boolean>,
    onDismiss: () -> Unit,
    onOpenQueue: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSleep by remember { mutableStateOf(false) }
    var showSavedIn by remember { mutableStateOf(false) }
    var showAlternativeStream by remember { mutableStateOf(false) }
    var showYouTubeSearch by remember { mutableStateOf(false) }
    val altSearchViewModel: com.music.spotui.ui.viewmodel.AlternativeSearchViewModel =
        hiltViewModel()

    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(250L)
        }
    }
    val sleepTimerEnd = SongPlayer.sleepTimerEndAt
    val remainingMillis = (sleepTimerEnd - currentTime).coerceAtLeast(0L)
    val remainingSeconds = remainingMillis / 1000L
    val minutesLeft = (remainingSeconds + 59) / 60

    val title by playerViewModel.currentSongTitle
    val singer by playerViewModel.currentSongSinger
    val cover by playerViewModel.currentSongCoverUri
    val album by playerViewModel.currentSongAlbum
    val songId by playerViewModel.currentSongId
    val currentQueue by playerViewModel.queue
    // The full track model (spotify id, real album, stream url) — the state above
    // only carries display strings, and `album` is the *context* name (playlist…).
    val currentSong = currentQueue.firstOrNull { it.id == songId }
    var downloaded by remember(songId) {
        mutableStateOf(
            com.music.spotui.data.preferences.isDownloaded(
                context,
                songId.toString()
            )
        )
    }
    var downloadingNow by remember(songId) {
        mutableStateOf(
            currentSong != null && SongPlayer.isDownloading(
                currentSong.url
            )
        )
    }
    val alternativeKey = currentSong?.let { alternativeStreamKey(it) }.orEmpty()
    var currentAlternative by remember(songId, alternativeKey) {
        mutableStateOf(alternativeKey.takeIf { it.isNotBlank() }
            ?.let { getAlternativeStream(context, it) })
    }
    val localFileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val song = currentSong ?: return@rememberLauncherForActivityResult
            val picked = uri ?: return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            setLocalAlternativeStream(
                context,
                alternativeKey,
                picked,
                picked.lastPathSegment.orEmpty()
            )
            // Keep any downloaded file for offline use. Alternate playback bypasses it.
            SongPlayer.registerAlternativeKeys(listOf(song.url to alternativeKey))
            currentAlternative = getAlternativeStream(context, alternativeKey)
            Toast.makeText(context, "Alternative stream set to local file", Toast.LENGTH_SHORT).show()
            SongPlayer.invalidateSongCache(song, context, reloadIfPlaying = true, clearAltStream = false)
        }

    if (showSavedIn && currentSong != null) {
        com.music.spotui.ui.components.SavedInSheet(
            song = currentSong,
            context = context,
            onDismiss = { showSavedIn = false; onDismiss() },
            onLikedChanged = { isLiked.value = it },
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Color(0xFF1D1D1D),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            var downwardDrag = 0f
            Box(Modifier.fillMaxWidth()
                .pointerInput(onDismiss) {
                    detectVerticalDragGestures(
                        onDragStart = { downwardDrag = 0f },
                        onDragEnd = { if (downwardDrag > 60f) onDismiss() },
                        onVerticalDrag = { change, amount ->
                            downwardDrag += amount
                            change.consume()
                        },
                    )
                }
                .clickable { onDismiss() }
                .padding(top = 9.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF666666)))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.86f).dp)
                .then(if (!showYouTubeSearch && !showAlternativeStream)
                    Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
        ) {
            if (showYouTubeSearch) {
                YouTubeSearchView(
                    viewModel = altSearchViewModel,
                    songTitle = title,
                    songArtist = singer,
                    enabled = currentSong != null,
                    onBack = { showYouTubeSearch = false },
                    onUseVideoId = { videoId ->
                        val song = currentSong ?: return@YouTubeSearchView
                        setYouTubeAlternativeStream(context, alternativeKey, videoId)
                        SongPlayer.registerAlternativeKeys(listOf(song.url to alternativeKey))
                        currentAlternative = getAlternativeStream(context, alternativeKey)
                        showYouTubeSearch = false
                        showAlternativeStream = false
                        Toast.makeText(
                            context,
                            "Alternative stream set to YouTube",
                            Toast.LENGTH_SHORT
                        ).show()
                        SongPlayer.invalidateSongCache(song, context, reloadIfPlaying = true, clearAltStream = false)
                        onDismiss()
                    },
                )
            } else if (showAlternativeStream) {
                AlternativeStreamEditor(
                    currentAlternative = currentAlternative,
                    enabled = currentSong != null,
                    onBack = { showAlternativeStream = false },
                    onUseYouTube = { text ->
                        val song = currentSong ?: return@AlternativeStreamEditor
                        val videoId = SongPlayer.videoIdFromYouTubeLink(text)
                        if (videoId == null) {
                            Toast.makeText(
                                context,
                                "Paste a YouTube video link or video ID",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            setYouTubeAlternativeStream(context, alternativeKey, videoId)
                            SongPlayer.registerAlternativeKeys(listOf(song.url to alternativeKey))
                            currentAlternative = getAlternativeStream(context, alternativeKey)
                            Toast.makeText(
                                context,
                                "Alternative stream set to YouTube",
                                Toast.LENGTH_SHORT
                            ).show()
                            SongPlayer.invalidateSongCache(song, context, reloadIfPlaying = true, clearAltStream = false)
                        }
                    },
                    onPickLocal = {
                        localFileLauncher.launch(arrayOf("audio/*"))
                    },
                    onClear = {
                        val song = currentSong ?: return@AlternativeStreamEditor
                        clearAlternativeStream(context, alternativeKey)
                        currentAlternative = null
                        Toast.makeText(context, "Alternative stream cleared", Toast.LENGTH_SHORT).show()
                        SongPlayer.invalidateSongCache(song, context, reloadIfPlaying = true, clearAltStream = true)
                    },
                    onOpenYouTubeSearch = { showYouTubeSearch = true },
                )
            } else if (!showSleep) {
                // ── Now-playing header ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 22.dp)
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        model = cover,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            singer,
                            color = Color(0xFFB3B3B3),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0xFF343434))

                PlayerMenuRow(
                    icon = Icons.Outlined.Share,
                    label = uiLocalized("Share")
                ) {
                    val shareText = currentSong?.spotifyTrackId?.takeIf { it.isNotBlank() }
                        ?.let { "https://open.spotify.com/track/$it" }
                        ?: "Listening to $title by $singer"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    context.startActivity(Intent.createChooser(send, "Share"))
                    onDismiss()
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.AddCircle,
                    label = uiLocalized("Add to playlist"),
                    enabled = currentSong != null,
                    trailingArrow = true,
                ) {
                    showSavedIn = true
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.PlaylistAdd,
                    label = uiLocalized("Add to queue"),
                    enabled = currentSong != null
                ) {
                    currentSong?.let { playerViewModel.addToQueue(it) }
                    onDismiss()
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.QueueMusic,
                    label = uiLocalized("View queue")
                ) {
                    onDismiss()
                    onOpenQueue()
                }
                // Use the track's REAL album (currentSongAlbum is the playing
                // context — a playlist name would resolve to garbage).
                val realAlbum = currentSong?.album?.ifBlank { null } ?: album
                PlayerMenuRow(
                    icon = Icons.Outlined.Album,
                    label = uiLocalized("Go to album"),
                    enabled = realAlbum.isNotBlank()
                ) {
                    onDismiss()
                    navController.navigate(albumRoute(realAlbum, singer))
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.People,
                    label = uiLocalized("Go to artist"),
                    enabled = singer.isNotBlank()
                ) {
                    onDismiss()
                    playerViewModel.goToArtist(
                        currentSong?.spotifyTrackId.orEmpty(),
                        singer
                    ) { route ->
                        navController.navigate(route)
                    }
                }
                PlayerMenuRow(
                    icon = if (downloaded) Icons.Default.CheckCircle else ImageVector.vectorResource(
                        R.drawable.ic_download
                    ),
                    iconTint = if (downloaded) Color(AppPalette.toArgb()) else Color.White,
                    label = when {
                        downloaded -> "Remove download"
                        downloadingNow -> "Downloading…"
                        else -> "Download"
                    },
                    enabled = currentSong != null && !downloadingNow,
                ) {
                    val song = currentSong ?: return@PlayerMenuRow
                    if (downloaded) {
                        val removed = com.music.spotui.data.preferences.removeDownload(
                            context,
                            song.id.toString()
                        )
                        if (removed) downloaded = false
                        else android.widget.Toast.makeText(context, "Could not delete audio file", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        downloadingNow = true
                        SongPlayer.downloadSong(song, context) { ok ->
                            downloadingNow = false
                            downloaded = ok
                        }
                    }
                }
                PlayerMenuRow(
                    icon = if (isLiked.value) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    iconTint = if (isLiked.value) Color(AppPalette.toArgb()) else Color.White,
                    label = if (isLiked.value) "Remove from Liked Songs" else "Add to Liked Songs"
                ) {
                    if (isLiked.value) {
                        removeLikedSongId(context, songId.toString())
                    } else {
                        addLikedSongId(context, songId.toString())
                    }
                    isLiked.value = isSongLiked(context, songId.toString())
                    // Mirror the like to the real Spotify account.
                    com.music.spotui.data.api.SpotifySync.setTrackSaved(
                        context, currentSong?.spotifyTrackId.orEmpty(), isLiked.value
                    )
                    onDismiss()
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.PlayArrow,
                    iconTint = if (currentAlternative != null) Color(AppPalette.toArgb()) else Color.White,
                    label = if (currentAlternative == null) "Alternative stream" else "Alternative stream set",
                    enabled = currentSong != null,
                    trailingArrow = true,
                ) {
                    showAlternativeStream = true
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.Refresh,
                    label = uiLocalized("Invalidate cache"),
                    enabled = currentSong != null,
                ) {
                    val song = currentSong ?: return@PlayerMenuRow
                    onDismiss()
                    SongPlayer.invalidateSongCache(song, context, reloadIfPlaying = true)
                }
                PlayerMenuRow(
                    icon = Icons.Outlined.Notifications,
                    label = stringResource(R.string.sleep_timer),
                    subtitle = if (minutesLeft > 0) "$minutesLeft min left" else null,
                    trailingArrow = true
                ) { showSleep = true }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { showSleep = false },
                        contentDescription = uiLocalized("Back"),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.sleep_timer),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (remainingSeconds > 0) {
                        val minutes = remainingSeconds / 60
                        val seconds = remainingSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            color = Color(AppPalette.toArgb()),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A2A2A))
                val options = listOf(
                    "Off" to 0L,
                    "5 minutes" to 5L,
                    "15 minutes" to 15L,
                    "30 minutes" to 30L,
                    "45 minutes" to 45L,
                    "1 hour" to 60L
                )
                options.forEach { (label, minutesOption) ->
                    PlayerMenuRow(icon = Icons.Default.Notifications, label = label) {
                        SongPlayer.setSleepTimer(minutesOption * 60_000L)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AlternativeStreamEditor(
    currentAlternative: com.music.spotui.data.preferences.AlternativeStream?,
    enabled: Boolean,
    onBack: () -> Unit,
    onUseYouTube: (String) -> Unit,
    onPickLocal: () -> Unit,
    onClear: () -> Unit,
    onOpenYouTubeSearch: () -> Unit,
) {
    var youtubeText by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                uiLocalized("Alternative stream"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (currentAlternative != null && currentAlternative.isYouTube) {
            val context = LocalContext.current
            val videoId = currentAlternative.value
            val thumbnailUrl = "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF282828))
                    .clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=$videoId")
                        )
                        context.startActivity(intent)
                    }
                    .padding(10.dp),
            ) {
                GlideImage(
                    model = thumbnailUrl,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiLocalized("YouTube alternative"),
                        color = Color(AppPalette.toArgb()),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = videoId,
                        color = Color(0xFFB3B3B3),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    tint = Color(0xFFB3B3B3),
                    modifier = Modifier.size(18.dp),
                    contentDescription = uiLocalized("Open on YouTube"),
                )
            }
        } else if (currentAlternative != null && currentAlternative.isLocal) {
            Text(
                text = "Current: local file ${currentAlternative.label.ifBlank { currentAlternative.value }}",
                color = Color(AppPalette.toArgb()),
                fontSize = 13.sp,
                maxLines = 2,
            )
        } else {
            Text(
                text = uiLocalized("No alternative stream set"),
                color = Color(0xFFB3B3B3),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = youtubeText,
            onValueChange = { youtubeText = it },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            label = { Text(uiLocalized("YouTube link or video ID"), color = Color(0xFFB3B3B3)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = enabled && youtubeText.isNotBlank(),
                onClick = { onUseYouTube(youtubeText) }) {
                Text(
                    uiLocalized("Use YouTube"),
                    color = if (enabled && youtubeText.isNotBlank()) AppPalette else Color.Gray
                )
            }
        }
        PlayerMenuRow(
            icon = Icons.Default.Search,
            label = uiLocalized("Search YouTube"),
            enabled = enabled,
            trailingArrow = true,
        ) {
            onOpenYouTubeSearch()
        }
        PlayerMenuRow(
            icon = Icons.Default.Add,
            label = uiLocalized("Use local audio file"),
            enabled = enabled,
        ) {
            onPickLocal()
        }
        PlayerMenuRow(
            icon = Icons.Default.CheckCircle,
            label = uiLocalized("Clear alternative stream"),
            enabled = enabled && currentAlternative != null,
            iconTint = Color(0xFFE57373),
        ) {
            onClear()
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun YouTubeSearchView(
    viewModel: com.music.spotui.ui.viewmodel.AlternativeSearchViewModel,
    songTitle: String,
    songArtist: String,
    enabled: Boolean,
    onBack: () -> Unit,
    onUseVideoId: (String) -> Unit,
) {
    LaunchedEffect(songTitle, songArtist) {
        viewModel.initQuery(songTitle, songArtist)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    BackHandler { viewModel.stopPreview(); onBack() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { viewModel.stopPreview(); onBack() },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                uiLocalized("Search YouTube"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.updateQuery(it) },
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            label = { Text(uiLocalized("Search query"), color = Color(0xFFB3B3B3)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        when {
            viewModel.isSearching -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = AppPalette,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            viewModel.error != null -> {
                Text(
                    text = viewModel.error!!,
                    color = Color(0xFFE57373),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            viewModel.searchResults.isEmpty() && viewModel.searchQuery.isNotBlank() -> {
                Text(
                    text = uiLocalized("No results found"),
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            else -> {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(
                        count = viewModel.searchResults.size,
                        key = { "${viewModel.searchResults[it].id}_$it" },
                    ) { index ->
                        val song = viewModel.searchResults[index]
                        YouTubeSearchResultRow(
                            song = song,
                            isPreviewing = viewModel.previewingVideoId == song.id,
                            isResolving = viewModel.previewingVideoId == song.id && viewModel.isResolvingPreview,
                            enabled = enabled,
                            onPreview = { viewModel.preview(song) },
                            onSelect = {
                                viewModel.stopPreview()
                                onUseVideoId(song.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun YouTubeSearchResultRow(
    song: com.metrolist.innertube.models.SongItem,
    isPreviewing: Boolean,
    isResolving: Boolean = false,
    enabled: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
) {
    val durationText = song.duration?.let { dur ->
        val min = dur / 60
        val sec = dur % 60
        "%d:%02d".format(min, sec)
    }.orEmpty()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        GlideImage(
            model = song.thumbnail,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artists.joinToString(", ") { it.name },
                    color = Color(0xFFB3B3B3),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (durationText.isNotBlank()) {
                    Text(
                        text = " · $durationText",
                        color = Color(0xFFB3B3B3),
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        if (isResolving) {
            androidx.compose.material3.CircularProgressIndicator(
                color = AppPalette,
                modifier = Modifier
                    .size(28.dp)
                    .padding(4.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = if (isPreviewing) Icons.Default.Pause else Icons.Default.PlayArrow,
                tint = if (isPreviewing) AppPalette else Color(0xFFB3B3B3),
                modifier = Modifier
                    .size(32.dp)
                    .clickable(enabled = enabled) { onPreview() }
                    .padding(4.dp),
                contentDescription = uiLocalized("Preview"),
            )
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Default.Check,
            tint = Color(0xFFB3B3B3),
            modifier = Modifier
                .size(32.dp)
                .clickable(enabled = enabled) { onSelect() }
                .padding(4.dp),
            contentDescription = uiLocalized("Use this"),
        )
    }
}

@Composable
fun PlayerMenuRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    iconTint: Color = Color(0xFFB8B8B8),
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = icon,
            tint = if (enabled) iconTint else Color.Gray,
            modifier = Modifier.size(26.dp),
            contentDescription = null
        )
        Spacer(modifier = Modifier.width(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) Color.White else Color.Gray,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        if (trailingArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp),
                contentDescription = null
            )
        }
    }
}

/**
 * Plays a Spotify Canvas clip: a short, muted, looping video filling the
 * now-playing background. Uses a dedicated ExoPlayer (separate from the audio
 * engine) released when the composable leaves. Falls back to nothing if the URL fails.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CanvasVideo(url: String, modifier: Modifier = Modifier, onError: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnError by androidx.compose.runtime.rememberUpdatedState(onError)

    // Canvas is decorative; decoding an additional Opus/AAC audio track at volume
    // zero still allocates a codec and buffer queues. Disable audio at the track
    // selector instead, leaving SongPlayer as the only audio renderer.
    val exo = remember(url) {
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                .build()
        }
        androidx.media3.exoplayer.ExoPlayer.Builder(context.applicationContext)
            .setTrackSelector(selector)
            .build()
            .apply {
                setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
                prepare()
            }
    }
    val playerView = remember(exo) {
        androidx.media3.ui.PlayerView(context).apply {
            player = exo
            useController = false
            controllerAutoShow = false
            setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_NEVER)
            setArtworkDisplayMode(androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_OFF)
            setDefaultArtwork(null)
            hideController()
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    DisposableEffect(exo, lifecycleOwner) {
        var released = false
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                currentOnError?.invoke()
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (released) return@LifecycleEventObserver
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // Disconnect the compositor before the Activity SurfaceView
                    // disappears. The media decoder should not race Surface teardown.
                    exo.pause()
                    playerView.player = null
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // ON_RESUME may be dispatched before MainActivity updates
                    // UiVisibility; restore the surface based on lifecycle itself.
                    playerView.player = exo
                    exo.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        exo.addListener(listener)
        onDispose {
            released = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            exo.removeListener(listener)
            exo.pause()
            // Detach BEFORE terminating its playback looper/MediaCodec.
            playerView.player = null
            exo.stop()
            exo.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(modifier = modifier, factory = { playerView })
}
