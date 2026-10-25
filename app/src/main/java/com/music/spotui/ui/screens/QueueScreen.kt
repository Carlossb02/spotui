package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.music.spotui.ui.components.SongOptionsSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * The "play queue" as an overlay sheet over the full-screen player (matching Spotify).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF121212),
        contentColor = Color.White,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        QueueContent(
            navController = navController,
            onClose = onDismiss
        )
    }
}

/**
 * The "play queue" screen (standalone route fallback).
 */
@Composable
fun QueueScreen(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        QueueContent(
            navController = navController,
            onClose = { navController.navigateUp() }
        )
    }
}

/**
 * The core queue UI — the track that's playing now plus everything coming up.
 * Tapping an upcoming track jumps straight to it; dragging the handle reorders it;
 * swiping a row removes it.
 */
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueueContent(
    navController: NavController,
    onClose: () -> Unit,
) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val queue by playerViewModel.queue
    val currentId = playerViewModel.currentSongId.value
    val curIdx = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val upcoming = if (queue.isNotEmpty()) queue.drop(curIdx + 1) else emptyList()
    val current = queue.getOrNull(curIdx)

    // The gesture lives on the stable LazyColumn, NOT on a disposable row.
    // A row can scroll completely out of view while held; its pointerInput would
    // be cancelled by lazy-list recycling. The parent survives and keeps scrolling.
    var draggingId by remember { mutableIntStateOf(-1) }
    var pointerViewportY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 64.dp.toPx() }
    val edgeDistancePx = with(density) { 82.dp.toPx() }
    val handleAreaPx = with(density) { 58.dp.toPx() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val latestUpcoming by rememberUpdatedState(upcoming)
    val latestHasCurrent by rememberUpdatedState(current != null)

    val reorderAtPointer: () -> Unit = {
        val q = playerViewModel.queue.value
        val draggedIndex = q.indexOfFirst { it.id == draggingId }
        val playingIndex = q.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        if (draggedIndex > playingIndex) {
            val headerCount = (if (current != null) 1 else 0) + (if (upcoming.isNotEmpty()) 1 else 0)
            val visibleTracks = listState.layoutInfo.visibleItemsInfo.filter {
                it.index >= headerCount && it.index < headerCount + upcoming.size
            }
            val closest = visibleTracks.minByOrNull { abs((it.offset + it.size / 2f) - pointerViewportY) }
            if (closest != null) {
                val targetAbsolute = (playingIndex + 1 + closest.index - headerCount)
                    .coerceIn(playingIndex + 1, q.lastIndex)
                if (targetAbsolute != draggedIndex) playerViewModel.moveQueueItem(draggedIndex, targetAbsolute)
            }
        }
    }
    val latestReorder by rememberUpdatedState(reorderAtPointer)

    // Run every frame while near an edge, even when the user's finger stays still.
    LaunchedEffect(draggingId) {
        if (draggingId == -1) return@LaunchedEffect
        while (draggingId != -1) {
            val height = listState.layoutInfo.viewportSize.height.toFloat()
            val down = ((pointerViewportY - (height - edgeDistancePx)) / edgeDistancePx).coerceIn(0f, 1f)
            val up = ((edgeDistancePx - pointerViewportY) / edgeDistancePx).coerceIn(0f, 1f)
            val velocity = when {
                down > 0f -> 4f + 24f * down
                up > 0f -> -(4f + 24f * up)
                else -> 0f
            }
            if (velocity != 0f && listState.scrollBy(velocity) != 0f) latestReorder()
            kotlinx.coroutines.delay(16L)
        }
    }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = uiLocalized("Close"),
                tint = Color.White,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClose() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.queue), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .pointerInput(currentId, queue.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val headers = (if (latestHasCurrent) 1 else 0) + (if (latestUpcoming.isNotEmpty()) 1 else 0)
                            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                down.position.y >= it.offset && down.position.y < it.offset + it.size &&
                                    it.index >= headers && it.index < headers + latestUpcoming.size
                            }
                            // Only the right-hand reorder grip may begin a drag.
                            if (item != null && down.position.x >= size.width - handleAreaPx) {
                                val songId = latestUpcoming[item.index - headers].id
                                val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                if (slop != null) {
                                    draggingId = songId
                                    pointerViewportY = slop.position.y
                                    drag(down.id) { change ->
                                        change.consume()
                                        pointerViewportY = change.position.y
                                        latestReorder()
                                    }
                                }
                            }
                            draggingId = -1
                        }
                    }
            ) {
                current?.let {
                    item {
                        Text(
                            stringResource(R.string.now_playing),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 6.dp)
                        )
                        QueueRow(
                            song = it,
                            highlight = true,
                            onClick = {},
                            onLongClick = { menuSong = it },
                        )
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item {
                        Text(
                            uiLocalized("Next up"),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 6.dp)
                        )
                    }
                    // Use an occurrence identity independent of position. An index-based key
                    // disposes the pointerInput coroutine on every swap, ending the drag.
                    itemsIndexed(upcoming, key = { index, s ->
                        "${s.id}:${upcoming.take(index).count { it.id == s.id }}"
                    }) { _, song ->
                        val isDragging = draggingId == song.id
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    playerViewModel.removeFromQueue(song)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    contentAlignment = Alignment.CenterEnd,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF7A1F1F))
                                        .padding(horizontal = 24.dp),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = uiLocalized("Remove"), tint = Color.White)
                                }
                            },
                            gesturesEnabled = draggingId == -1,
                            modifier = Modifier.graphicsLayer {
                                alpha = if (isDragging) 0.16f else 1f
                            },
                        ) {
                            QueueRow(
                                song = song,
                                highlight = false,
                                onLongClick = { menuSong = song },
                                onClick = {
                                    val idx = queue.indexOfFirst { it.id == song.id }
                                    playerViewModel.updateSongState(
                                        song.coverUri, song.title, song.singer, true,
                                        song.id, idx.coerceAtLeast(0), song.album
                                    )
                                    SongPlayer.playSong(song.url, context, "song/${song.id}")
                                },
                                dragHandle = Modifier,
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(40.dp)) }
            }
            // Floating row stays beneath the finger even when its source item is recycled.
            if (draggingId != -1) {
                val ghost = queue.firstOrNull { it.id == draggingId }
                if (ghost != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().offset {
                            IntOffset(0, (pointerViewportY - rowHeightPx / 2f).roundToInt())
                        }.graphicsLayer { shadowElevation = 12f * density.density }
                    ) {
                        QueueRow(song = ghost, highlight = false, onClick = {}, dragHandle = Modifier)
                    }
                }
            }
            if (draggingId == -1) {
                com.music.spotui.ui.components.FastScrollbarForLazyList(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
private fun QueueRow(
    song: com.music.spotui.data.entity.SongsModel,
    highlight: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    dragHandle: Modifier? = null,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(16.dp, 8.dp)
    ) {
        GlideImage(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
            model = com.music.spotui.ui.notification.PodcastCover.preferredUri(context, song),
            contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            contentDescription = ""
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) {
                    com.music.spotui.ui.components.ExplicitBadge()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = song.title,
                    color = if (highlight) Color(AppPalette.toArgb()) else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.music.spotui.ui.components.DownloadedSongIndicator(song.id)
                Text(
                    text = song.singer,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!highlight && dragHandle != null) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = uiLocalized("Reorder"),
                tint = Color(0xFFB3B3B3),
                modifier = Modifier
                    .size(28.dp)
                    .then(dragHandle)
            )
        }
    }
}
