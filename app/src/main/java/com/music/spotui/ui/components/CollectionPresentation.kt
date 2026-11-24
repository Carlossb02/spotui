package com.music.spotui.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
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

private val LocalCapsuleMetrics = staticCompositionLocalOf { collectionActionMetrics(288f) }
private val LocalCapsuleAccent = staticCompositionLocalOf { Color(0xFF75648F) }

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
    val metrics = LocalCapsuleMetrics.current
    val accent = LocalCapsuleAccent.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    val action = if (paused) "Reanudar" else if (status.active) "Pausar" else "Descargar"
    Box(Modifier.width(metrics.download.dp).height(metrics.control.dp)) {
        CollectionCapsuleBody(action,
            Modifier.fillMaxSize().liquidCollectionGlass(accent, interaction)
                .then(if (status.complete) Modifier.border(1.4.dp, CollectionGreen, shape) else Modifier)
                .combinedClickable(interactionSource = interaction, indication = null,
                    enabled = keys.isNotEmpty(), role = androidx.compose.ui.semantics.Role.Button,
                    onClick = {
                        when {
                            paused -> SongPlayer.pauseDownloads(songs, false)
                            status.active || requested -> SongPlayer.pauseDownloads(songs, true)
                            !status.complete -> { requested = true; onClick(false) }
                        }
                    }, onLongClick = { menu = true })) {
            Icon(androidx.compose.ui.res.painterResource(if (paused) com.music.spotui.R.drawable.collection_play else if (status.active) com.music.spotui.R.drawable.collection_pause else com.music.spotui.R.drawable.collection_download),
                if (paused) "Reanudar descargas" else if (status.active) "Pausar descargas" else description,
                tint = if (status.complete) CollectionGreen else Color.White, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Cancelar descargas de esta colección") }, onClick = {
                menu = false; requested = false; SongPlayer.manageCollectionDownloads(songs, context, delete = false)
            })
            DropdownMenuItem(text = { Text("Borrar descargas de esta colección") }, onClick = {
                menu = false; requested = false; SongPlayer.manageCollectionDownloads(songs, context, delete = true)
            })
        }
        if (!status.complete && (requested || status.active)) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val w = size.width - stroke; val h = size.height - stroke
            val perimeter = 2 * (w - h) + kotlin.math.PI.toFloat() * h
            drawRoundRect(CollectionGreen, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(perimeter * fraction.coerceAtLeast(.015f), perimeter))))
        }
    }
}

@Composable
private fun CollectionCapsuleBody(label: String, modifier: Modifier, icon: @Composable RowScope.() -> Unit) {
    val labels = LocalCapsuleMetrics.current.labels
    Row(modifier.padding(horizontal = if (labels) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        icon()
        if (labels) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = .35f)))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private val capsuleAccents = com.music.spotui.util.ExpiringLruCache<String, Color>(24, 30 * 60_000L)
@Composable
internal fun rememberCollectionAccent(cover: String): Color {
    val context = LocalContext.current
    var accent by remember(cover) { mutableStateOf(capsuleAccents.get(cover) ?: Color(0xFF75648F)) }
    LaunchedEffect(cover) {
        if (cover.isNotBlank() && capsuleAccents.get(cover) == null) {
            SongPlayer.awaitAudioStartup()
            com.music.spotui.di.Palette().extractSecondColorFromCoverUrl(context, cover) {
                capsuleAccents.put(cover, it); accent = it
            }
        }
    }
    return accent
}

@Composable
internal fun CollectionSecondaryActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Box(modifier.padding(end = 20.dp).clip(androidx.compose.ui.graphics.RectangleShape)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Three matching capsules; phones retain icons and accessible names without visible labels. */
@Composable
internal fun CollectionActionBar(
    modifier: Modifier = Modifier, enabled: Boolean, playing: Boolean,
    download: @Composable () -> Unit, onSort: () -> Unit, onQueue: () -> Unit,
    onShuffle: () -> Unit, onPlay: () -> Unit,
    sortMode: String, sortDescending: Boolean, accent: Color = Color(0xFF75648F), glass: CollectionGlassBackdrop,
) {
    val sortInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val queueInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    BoxWithConstraints(modifier) {
        val metrics = remember(maxWidth, tablet) { collectionActionMetrics(maxWidth.value, tablet) }
        CompositionLocalProvider(LocalCapsuleMetrics provides metrics, LocalCapsuleAccent provides accent, LocalGlassBackdrop provides glass) {
            Row(Modifier.height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(metrics.itemGap.dp), verticalAlignment = Alignment.CenterVertically) {
                    download()
                    val sortIcon = when (sortMode) {
                        "DEFAULT" -> com.music.spotui.R.drawable.collection_sort_track
                        "TITLE" -> com.music.spotui.R.drawable.collection_sort_title
                        "ARTIST" -> com.music.spotui.R.drawable.collection_sort_artist
                        "ALBUM" -> com.music.spotui.R.drawable.collection_sort_album
                        "DURATION" -> com.music.spotui.R.drawable.collection_sort_duration
                        "POPULARITY" -> com.music.spotui.R.drawable.collection_sort_popularity
                        else -> com.music.spotui.R.drawable.collection_sort_date
                    }
                    CollectionCapsuleBody("Ordenar", Modifier.width(metrics.pill.dp).height(metrics.control.dp)
                        .liquidCollectionGlass(accent, sortInteraction)
                        .clickable(interactionSource = sortInteraction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onSort)) {
                        Icon(androidx.compose.ui.res.painterResource(sortIcon), when (sortMode) { "DEFAULT" -> "Ordenar por pistas"; "TITLE" -> "Ordenar por título"; "ARTIST" -> "Ordenar por artista"; "ALBUM" -> "Ordenar por álbum"; "DURATION" -> "Ordenar por duración"; "POPULARITY" -> "Ordenar por popularidad"; else -> "Ordenar por fecha" }, tint = Color.White, modifier = Modifier.size(22.dp))
                        Icon(androidx.compose.ui.res.painterResource(if (sortDescending) com.music.spotui.R.drawable.collection_sort_down else com.music.spotui.R.drawable.collection_sort_up),
                            if (sortDescending) "Descendente" else "Ascendente", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    CollectionCapsuleBody("Añadir a la cola", Modifier.width(metrics.queue.dp).height(metrics.control.dp)
                        .liquidCollectionGlass(accent, queueInteraction)
                        .clickable(interactionSource = queueInteraction, indication = null, enabled = enabled,
                            role = androidx.compose.ui.semantics.Role.Button, onClick = onQueue)) {
                        Icon(androidx.compose.ui.res.painterResource(com.music.spotui.R.drawable.collection_queue), "Añadir a la cola", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(metrics.gap.dp))
                IconButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.size(metrics.control.dp)) {
                    Icon(androidx.compose.ui.res.painterResource(com.music.spotui.R.drawable.collection_shuffle), "Aleatorio", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onPlay, enabled = enabled, modifier = Modifier.size(metrics.play.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = CollectionGreen, contentColor = Color.Black,
                        disabledContainerColor = CollectionGreen.copy(alpha = .35f), disabledContentColor = Color.Black)) {
                    Icon(androidx.compose.ui.res.painterResource(if (playing) com.music.spotui.R.drawable.collection_pause else com.music.spotui.R.drawable.collection_play), if (playing) "Pausar" else "Reproducir", modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
