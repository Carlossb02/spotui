package com.music.spotui.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.downloadRecordingKey
import com.music.spotui.data.preferences.downloadsVersion
import com.music.spotui.data.preferences.getDownloadedEntries
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val CollectionGreen = Color(0xFF1ED760)

@Composable
internal fun collectionCoverSize(): Dp = (LocalConfiguration.current.screenWidthDp.dp * .46f).coerceIn(160.dp, 220.dp)

@Composable
internal fun CollectionSortButton(label: String = "Ordenar", onClick: () -> Unit) {
    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color.White.copy(alpha = .065f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(com.music.spotui.util.uiLocalized(label), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 72.dp))
        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

@Composable
internal fun CollectionActionSurface(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .065f))
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center, content = content)
}

internal data class CollectionDownloadState(val progress: Float, val complete: Boolean, val active: Boolean)
internal fun collectionDownloadState(keys: Set<String>, saved: Set<String>, tasks: List<Pair<String, Int>>): CollectionDownloadState {
    if (keys.isEmpty()) return CollectionDownloadState(0f, false, false)
    val pending = tasks.filter { it.first in keys }.associate { it.first to it.second.coerceIn(0, 100) }
    val progress = keys.sumOf { key -> if (key in saved) 1.0 else (pending[key] ?: 0) / 100.0 } / keys.size
    val complete = keys.all { it in saved } && pending.isEmpty()
    return CollectionDownloadState(if (complete) 1f else progress.toFloat().coerceAtMost(.99f), complete, pending.isNotEmpty())
}

/** One aggregated ring, updated by real transfer progress and verified completed files. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CollectionDownloadButton(songs: List<SongsModel>, description: String, onClick: (Boolean) -> Unit) {
    val context = LocalContext.current
    val transfers by SongPlayer.downloadQueue.collectAsState()
    val pausedKeys by SongPlayer.pausedDownloads.collectAsState()
    val version by downloadsVersion.collectAsState()
    val keys = remember(songs) { songs.map { downloadRecordingKey(it.url, it.spotifyTrackId) }.toSet() }
    val saved by produceState<Set<String>>(emptySet(), version) {
        value = withContext(Dispatchers.IO) { getDownloadedEntries(context).map { downloadRecordingKey(it.first.url, it.first.spotifyTrackId) }.toSet() }
    }
    val status = remember(keys, saved, transfers) { collectionDownloadState(keys, saved, transfers.map {
        downloadRecordingKey(it.song.url, it.song.spotifyTrackId) to it.progress
    }) }
    var requested by remember(keys) { mutableStateOf(false) }
    LaunchedEffect(status.active, status.complete) { if (status.active || status.complete) requested = false }
    var menu by remember { mutableStateOf(false) }
    val paused = keys.any { it in pausedKeys }
    val fraction by androidx.compose.animation.core.animateFloatAsState(status.progress, label = "collectionDownload")
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape)
            .combinedClickable(enabled = keys.isNotEmpty(), role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    when {
                        paused -> SongPlayer.pauseDownloads(songs, false)
                        status.active || requested -> SongPlayer.pauseDownloads(songs, true)
                        !status.complete -> { requested = true; onClick(false) }
                    }
                }, onLongClick = { menu = true }), contentAlignment = Alignment.Center) {
            Icon(painter = androidx.compose.ui.res.painterResource(if (paused) com.music.spotui.R.drawable.collection_play else if (status.active) com.music.spotui.R.drawable.collection_pause else com.music.spotui.R.drawable.collection_download),
                contentDescription = if (paused) "Reanudar descargas" else if (status.active) "Pausar descargas" else description,
                tint = if (status.complete) CollectionGreen else Color.White, modifier = Modifier.size(24.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Cancelar descargas de esta colección") }, onClick = {
                    menu = false; requested = false; SongPlayer.manageCollectionDownloads(songs, context, delete = false)
                })
                DropdownMenuItem(text = { Text("Borrar descargas de esta colección") }, onClick = {
                    menu = false; requested = false; SongPlayer.manageCollectionDownloads(songs, context, delete = true)
                })
            }
        }
        if (!status.complete && (requested || status.active)) CircularProgressIndicator(
            progress = { fraction.coerceAtLeast(.015f) }, modifier = Modifier.fillMaxSize(),
            color = CollectionGreen, trackColor = Color.White.copy(alpha = .1f), strokeWidth = 2.dp, strokeCap = StrokeCap.Round)
    }
}

@Composable
internal fun CollectionSecondaryActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Box(modifier.padding(end = 20.dp).clip(androidx.compose.ui.graphics.RectangleShape)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** A single fixed contract for collection controls; screen-specific actions belong above. */
@Composable
internal fun CollectionActionBar(
    modifier: Modifier = Modifier, enabled: Boolean, playing: Boolean,
    download: @Composable () -> Unit, onSort: () -> Unit, onQueue: () -> Unit,
    onShuffle: () -> Unit, onPlay: () -> Unit,
    sortMode: String, sortDescending: Boolean,
) {
    Row(modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { download() }
            val sortIcon = when (sortMode) {
                "DEFAULT" -> com.music.spotui.R.drawable.collection_sort_track
                "TITLE" -> com.music.spotui.R.drawable.collection_sort_title
                "ARTIST" -> com.music.spotui.R.drawable.collection_sort_artist
                "ALBUM" -> com.music.spotui.R.drawable.collection_sort_album
                "DURATION" -> com.music.spotui.R.drawable.collection_sort_duration
                "POPULARITY" -> com.music.spotui.R.drawable.collection_sort_popularity
                else -> com.music.spotui.R.drawable.collection_sort_date
            }
            Column(Modifier.width(76.dp).height(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onSort),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(androidx.compose.ui.res.painterResource(sortIcon), when (sortMode) { "DEFAULT" -> "Orden de pistas"; "TITLE" -> "Título"; "ARTIST" -> "Artista"; "ALBUM" -> "Álbum"; "DURATION" -> "Duración"; "POPULARITY" -> "Popularidad"; else -> "Fecha" }, tint = Color(0xFFE0E0E0), modifier = Modifier.size(20.dp))
                    Icon(androidx.compose.ui.res.painterResource(if (sortDescending) com.music.spotui.R.drawable.collection_sort_down else com.music.spotui.R.drawable.collection_sort_up),
                        if (sortDescending) "Descendente" else "Ascendente", tint = CollectionGreen, modifier = Modifier.size(14.dp))
                }
                Text("Ordenar", color = Color(0xFFB3B3B3), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            IconButton(onClick = onQueue, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(androidx.compose.ui.res.painterResource(com.music.spotui.R.drawable.collection_queue), "Añadir a la cola", tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        IconButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(androidx.compose.ui.res.painterResource(com.music.spotui.R.drawable.collection_shuffle), "Aleatorio", tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onPlay, enabled = enabled, modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CollectionGreen, contentColor = Color.Black,
                disabledContainerColor = CollectionGreen.copy(alpha = .35f), disabledContentColor = Color.Black)) {
            Icon(androidx.compose.ui.res.painterResource(if (playing) com.music.spotui.R.drawable.collection_pause else com.music.spotui.R.drawable.collection_play), if (playing) "Pausar" else "Reproducir", modifier = Modifier.size(30.dp))
        }
    }
}
