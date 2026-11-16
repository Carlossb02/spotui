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
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .065f))
            .combinedClickable(enabled = keys.isNotEmpty(), role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    when {
                        paused -> SongPlayer.pauseDownloads(songs, false)
                        status.active || requested -> SongPlayer.pauseDownloads(songs, true)
                        !status.complete -> { requested = true; onClick(false) }
                    }
                }, onLongClick = { menu = true }), contentAlignment = Alignment.Center) {
            Icon(if (paused) Icons.Default.PlayArrow else if (status.active) Icons.Default.Pause else Icons.Default.Download,
                if (paused) "Reanudar descargas" else if (status.active) "Pausar descargas" else description,
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
