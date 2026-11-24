package com.music.spotui.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.DisposableEffect
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.utils.AudioDeviceHelper
import com.music.spotui.ui.screens.PodcastVideoFrame
import com.music.spotui.ui.screens.PodcastVideoSession
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.viewmodel.PlayerViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect

@Composable
fun Loader(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = Color(0xFF1ED760),
            strokeWidth = 3.dp
        )
    }
}

@Composable
fun ExplicitBadge(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = 10.sp,
) {
    val label=com.music.spotui.util.uiLocalized("Explicit content")
    Box(modifier=modifier.size(14.dp).background(Color(0xFFB3B3B3),RoundedCornerShape(2.dp))
        .semantics {contentDescription=label},contentAlignment=Alignment.Center) {
    Text(
        text = "E",
        color = Color(0xFF121212),
        fontSize = size,
        lineHeight = size,
        fontWeight = FontWeight.Bold,
        style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),
    )
    }
}

@Composable
private fun MiniOutputRouteButton(onClick: () -> Unit, size: Dp = 36.dp) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        AudioOutputIcon(
            contentDescription = "Seleccionar dispositivo de salida",
            modifier = Modifier.size(19.dp)
        )
    }
}

private val miniPlayerArtworkColors = com.music.spotui.util.ExpiringLruCache<String, Color>(32, 30 * 60_000L)

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    navController: NavHostController,
    onExpandPlayer: (() -> Unit)? = null,
    tabletLayout: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val miniPlayerViewModel : PlayerViewModel = hiltViewModel()
    val songTitle by miniPlayerViewModel.currentSongTitle
    val songSinger by miniPlayerViewModel.currentSongSinger
    val songCoverUri by miniPlayerViewModel.currentSongCoverUri
    val songPlayingState by miniPlayerViewModel.currentSongPlayingState
    val songId by miniPlayerViewModel.currentSongId
    val songIndex by miniPlayerViewModel.currentSongIndex
    val songAlbum by miniPlayerViewModel.currentSongAlbum
    val isCurrentSongExplicit = miniPlayerViewModel.queue.value.firstOrNull { it.id == songId }?.explicit == true

    // Keep MiniPlayer playing state in sync with physical SongPlayer audio engine
    LaunchedEffect(Unit) {
        miniPlayerViewModel.syncWithPlayer()
    }

    var swipeOffsetX by remember { mutableFloatStateOf(0f) }

    var songProgress by remember { mutableFloatStateOf(0f) }
    // Poll the playback engine on a coroutine rather than writing Compose
    // state during composition. This avoids redundant recompositions and
    // keeps the mini-player responsive even when the song is paused.
    LaunchedEffect(songId, songPlayingState) {
        while (true) {
            val duration = SongPlayer.getDuration().toFloat()
            songProgress = if (duration > 0f) {
                (SongPlayer.getCurrentPosition().toFloat() / duration).coerceIn(0f, 1f)
            } else 0f
            delay(if (songPlayingState) 600L else 1200L)
        }
    }

    val context = LocalContext.current

    var isLiked by remember {
        mutableStateOf(false)
    }
    val likeState = miniPlayerViewModel.likeState.value
    LaunchedEffect(likeState, songId) {
        isLiked = isSongLiked(context, songId.toString())
    }
    val currentTrack = miniPlayerViewModel.queue.value.firstOrNull { it.id == songId }
    val currentArtwork = currentTrack?.let { rememberSongArtwork(it) }
        .orEmpty().ifBlank { songCoverUri }
    val neutralBackground = Color(0xFF212121)
    var artworkColor by remember(currentArtwork) {
        mutableStateOf(miniPlayerArtworkColors.get(currentArtwork) ?: neutralBackground)
    }
    DisposableEffect(context, currentArtwork) {
        var active = true
        val cancel = if (currentArtwork.isNotBlank() && miniPlayerArtworkColors.get(currentArtwork) == null) {
            Palette().extractFirstColorFromImageUrl(context, currentArtwork) { color ->
                if (active) {
                    miniPlayerArtworkColors.put(currentArtwork, color)
                    artworkColor = color
                }
            }
        } else null
        onDispose {
            active = false
            cancel?.invoke()
        }
    }
    val isSaved = rememberTrackSaved(currentTrack, isLiked)
    var showSavedIn by remember { mutableStateOf(false) }
    var showDevicesSheet by remember { mutableStateOf(false) }
    // Reuse the full player's output selector. A route update is event-driven,
    // not a high-frequency Bluetooth poll on every recomposition.
    DisposableEffect(context) {
        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                AudioDeviceHelper.updateRouteName(context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                AudioDeviceHelper.updateRouteName(context)
            }
        }
        manager.registerAudioDeviceCallback(callback, null)
        AudioDeviceHelper.updateRouteName(context)
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    if (showDevicesSheet) {
        DevicesSheet(context = context, onDismiss = { showDevicesSheet = false })
    }
    if (showSavedIn && currentTrack != null) {
        SavedInSheet(
            song = currentTrack,
            context = context,
            onDismiss = { showSavedIn = false },
            onLikedChanged = {
                isLiked = it
                miniPlayerViewModel.updateLikeState(!likeState)
            },
        )
    }


    val expand = rememberUpdatedState { onExpandPlayer?.invoke() ?: navController.navigate(Routes.Player.route) }
    val gesture = Modifier.pointerInput(songId, tabletLayout) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var axis = 0
            var dx = 0f
            var dy = 0f
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    dx = change.position.x - down.position.x
                    dy = change.position.y - down.position.y
                    if (!change.pressed) {
                        released = true
                        if (axis != 0) change.consume()
                        break
                    }
                    if (axis == 0) {
                        val x = kotlin.math.abs(dx)
                        val y = kotlin.math.abs(dy)
                        axis = when {
                            x > viewConfiguration.touchSlop && x > y * 1.3f -> 1
                            y > viewConfiguration.touchSlop && y > x * 1.3f -> 2
                            else -> 0
                        }
                    }
                    if (axis == 1) {
                        change.consume()
                        swipeOffsetX = (dx * .55f).coerceIn(-64.dp.toPx(), 64.dp.toPx())
                    } else if (axis == 2 && !tabletLayout && dy < 0f) {
                        change.consume()
                    }
                }
                if (released && axis == 1 && kotlin.math.abs(dx) >= 48.dp.toPx() &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                    val queue = miniPlayerViewModel.queue.value
                    if (dx < 0) miniPlayerViewModel.playNextSongs(queue, context)
                    else miniPlayerViewModel.playPreviousSong(queue, context)
                } else if (released && axis == 2 && !tabletLayout && dy <= -36.dp.toPx()) {
                    expand.value()
                }
            } finally {
                swipeOffsetX = 0f
            }
        }
    }
    val artworkOffset by androidx.compose.animation.core.animateFloatAsState(
        swipeOffsetX, animationSpec = spring(stiffness = 650f), label = "miniPlayerSwipe"
    )
    if (tabletLayout) {
        val podcastVideoId = currentTrack?.url?.takeIf { it.startsWith("yt_ep:") }
            ?.removePrefix("yt_ep:")?.substringBefore('|')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val videoActive = PodcastVideoSession.isActive(podcastVideoId)
        Column(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(artworkColor)
                .then(gesture)
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth()
                    .clickable { onExpandPlayer?.invoke() ?: navController.navigate(Routes.Player.route) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Spotify tablet: a true 16:9 video preview, edge to edge,
                // rather than the phone's square album thumbnail.
                if (videoActive && podcastVideoId != null) {
                    PodcastVideoFrame(
                        videoId = podcastVideoId,
                        compact = true,
                        modifier = Modifier.padding(start = 7.dp, top = 5.dp, bottom = 5.dp)
                            .width(88.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp)),
                        onUseAudio = {
                            podcastVideoId?.let { PodcastVideoSession.setEnabled(context, it, false) }
                        }
                    )
                } else {
                    val coverModifier = if (podcastVideoId != null) {
                        Modifier.padding(start = 7.dp, top = 5.dp, bottom = 5.dp)
                            .width(88.dp).fillMaxHeight()
                    } else Modifier.padding(start = 7.dp).size(50.dp)
                    if (currentTrack != null && com.music.spotui.data.preferences.PodcastResumeStore.isEpisode(currentTrack.url)) {
                        EpisodeArtwork(episode = currentTrack, modifier = coverModifier, radius = 7.dp)
                    } else {
                        GlideImage(
                            model = currentArtwork,
                            contentDescription = "Carátula de reproducción",
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder),
                            modifier = coverModifier.clip(RoundedCornerShape(7.dp))
                        )
                    }
                }
                Column(Modifier.weight(1f).graphicsLayer { translationX = artworkOffset }.padding(start = 11.dp, end = 6.dp)) {
                    Text(songTitle, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = Color.White)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        if(isCurrentSongExplicit)ExplicitBadge()
                        Text(songSinger, modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
                            fontSize = 12.sp, color = Color.LightGray, maxLines = 1)
                    }
                }
                MiniOutputRouteButton(onClick = { showDevicesSheet = true })
                // Separate, bounded hit targets: never overlap the title or play.
                androidx.compose.material3.IconButton(
                    onClick = { showSavedIn = true }, modifier = Modifier.size(42.dp)
                ) {
                    Icon(if (isSaved) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                        contentDescription = "Guardar canción", tint = if (isSaved) Color(0xFF1ED760) else Color.White,
                        modifier = Modifier.size(23.dp))
                }
                androidx.compose.material3.IconButton(
                    onClick = { if (songPlayingState) SongPlayer.pause() else SongPlayer.play() },
                    modifier = Modifier.size(43.dp)
                ) {
                    Icon(if (songPlayingState) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (songPlayingState) "Pausar" else "Reproducir",
                        tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(5.dp))
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.DarkGray)) {
                Box(Modifier.fillMaxWidth(songProgress.coerceIn(0f, 1f))
                    .height(2.dp).background(Color.White))
            }
        }
        return
    }

    Column(
        modifier = modifier
            .padding(13.dp, 0.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(artworkColor)
            .padding(8.dp, 0.dp)

    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                //.background(Color.Green)
                .padding(0.dp, 2.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onExpandPlayer?.invoke() ?: navController.navigate(Routes.Player.route)
                }
                .then(gesture)
        ){

            Row(horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
                    .graphicsLayer {
                        translationX = artworkOffset
                    }
            ) {
                val podcastVideoId = currentTrack?.url?.takeIf { it.startsWith("yt_ep:") }
                    ?.removePrefix("yt_ep:")?.substringBefore('|')
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                if (PodcastVideoSession.isActive(podcastVideoId)) {
                    PodcastVideoFrame(
                        videoId = podcastVideoId!!,
                        compact = true,
                        modifier = Modifier.padding(start = 5.dp, end = 10.dp)
                            .width(75.dp).height(42.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        onUseAudio = {
                            podcastVideoId?.let { PodcastVideoSession.setEnabled(context, it, false) }
                        }
                    )
                } else {
                    if (currentTrack != null && com.music.spotui.data.preferences.PodcastResumeStore.isEpisode(currentTrack.url)) {
                        EpisodeArtwork(episode = currentTrack,
                            modifier = Modifier.padding(end = 10.dp).size(42.dp), radius = 6.dp)
                    } else {
                        GlideImage(
                            modifier = Modifier.padding(end = 10.dp).size(42.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            model = currentArtwork,
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder),
                            contentDescription = ""
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = songTitle, color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE))
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    if(isCurrentSongExplicit)ExplicitBadge()
                    Text(text = songSinger, color = Color.LightGray, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE))
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(105.dp)
            ) {
                MiniOutputRouteButton(onClick = { showDevicesSheet = true }, size = 29.dp)


                // Plus = save; green check = already saved. A second tap opens the
                // "Saved in" sheet (Liked Songs + playlists) instead of unliking.
                if (isSaved) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        tint = Color(0xFF1ED760),
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showSavedIn = true },
                        contentDescription = "Saved",
                    )
                } else {
                    Icon(
                        modifier = Modifier
                            .size(22.dp)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    addLikedSongId(context, songId.toString())
                                    isLiked = true
                                    miniPlayerViewModel.updateLikeState(!likeState)
                                    // Mirror the like to the real Spotify account.
                                    com.music.spotui.data.api.SpotifySync.setTrackSaved(
                                        context, currentTrack?.spotifyTrackId.orEmpty(), true)
                                },
                                onLongClick = { showSavedIn = true },
                            ),
                        painter = painterResource(id = R.drawable.ic_add),
                        tint = Color.White,
                        contentDescription = "Add",
                    )
                }


                val isLocatingOrBuffering = miniPlayerViewModel.isResolving.value || miniPlayerViewModel.isBuffering.value
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (isLocatingOrBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            painter = if (songPlayingState)
                                painterResource(id = R.drawable.ic_playing)
                            else
                                painterResource(id = R.drawable.play_svgrepo_com)
                            ,
                            contentDescription = "",
                            tint = Color.White,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (songPlayingState) {
                                        SongPlayer.pause()
                                        miniPlayerViewModel.updateSongState(
                                            songCoverUri,
                                            songTitle,
                                            songSinger,
                                            false,
                                            songId,
                                            songIndex,
                                            songAlbum
                                        )
                                    } else {
                                        SongPlayer.play()
                                        miniPlayerViewModel.updateSongState(
                                            songCoverUri,
                                            songTitle,
                                            songSinger,
                                            true,
                                            songId,
                                            songIndex,
                                            songAlbum
                                        )
                                    }
                                }
                        )
                    }
                }
            }


        }


        CustomSlider(
            value = songProgress,
            onValueChange = { newValue ->
                val dur = SongPlayer.getDuration()
                if (dur > 0) SongPlayer.seekTo((newValue * dur).toLong())
            },
            valueRange = 0f..1f,
            steps = 0,
            modifier = Modifier.fillMaxWidth()
        )
    }



}


@Composable
fun CustomSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: Any? = null, // kept for API compat; unused
) {
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .height(14.dp) // compact touch target
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val mapped = valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start)
                    onValueChange(mapped)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val newFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val mapped = valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start)
                    onValueChange(mapped)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            val trackHeightPx = with(density) { 2.dp.toPx() }
            val trackY = size.height / 2f
            val thumbX = fraction * size.width

            // Inactive track
            drawLine(
                color = Color(0xFF535353),
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = trackHeightPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Active track
            drawLine(
                color = Color.White,
                start = Offset(0f, trackY),
                end = Offset(thumbX, trackY),
                strokeWidth = trackHeightPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}


@Composable
fun Snackbar(showMessage : String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ){
        Text(
            fontWeight = FontWeight.W500,
            fontSize = 14.sp,
            color = Color.Black,
            text = showMessage
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToPlayNextWrapper(
    onPlayNext: () -> Unit,
    content: @Composable () -> Unit
) {
    var hasFired by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd && !hasFired) {
                hasFired = true
                onPlayNext()
            }
            false
        }
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.Settled) {
            hasFired = false
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val revealed = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
                            .coerceIn(0f, size.width)
                        if (revealed > 0f) clipRect(right = revealed) { this@drawWithContent.drawContent() }
                    }
                    .background(Color(0xFF1DB954)) // Spotify Green
                    .padding(horizontal = 24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_queue_add),
                    contentDescription = "Play next",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) {
        content()
    }
}

/**
 * Modular dark-themed search bar used across all screens in the app.
 */
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "What do you want to listen to?",
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    height: Dp = 52.dp,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    val internalFocusRequester = remember { FocusRequester() }
    val effectiveFocusRequester = focusRequester ?: internalFocusRequester

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF383838), RoundedCornerShape(10.dp))
            .height(height)
            .background(Color(0xFF242424))
            .padding(horizontal = 12.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_search_big),
            tint = Color(0xFFB3B3B3),
            contentDescription = "Search",
            modifier = Modifier
                .size(22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    effectiveFocusRequester.requestFocus()
                }
        )

        TextField(
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onFocusChange != null) {
                        Modifier.onFocusChanged { onFocusChange(it.isFocused) }
                    } else {
                        Modifier
                    }
                )
                .focusRequester(effectiveFocusRequester),
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle.Default.copy(
                fontSize = 15.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium
            ),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedPlaceholderColor = Color(0xFFB3B3B3),
                unfocusedPlaceholderColor = Color(0xFFB3B3B3),
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color(0xFF1ED760)
            ),
            singleLine = true,
            placeholder = {
                Text(
                    text = placeholder,
                    color = Color(0xFFB3B3B3),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start
                )
            }
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp),
                color = Color(0xFF1ED760),
                strokeWidth = 2.dp
            )
        }

        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear",
                tint = Color(0xFFB3B3B3),
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (onClear != null) {
                            onClear()
                        } else {
                            onQueryChange("")
                        }
                    }
            )
        }
    }
}
