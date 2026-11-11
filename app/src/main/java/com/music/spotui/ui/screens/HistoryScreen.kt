package com.music.spotui.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.AppSearchBar
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class RecapPeriod { ALL, MONTH, YEAR }
private data class RecapData(val history: List<HistoryEntry> = emptyList(), val months: List<java.time.YearMonth> = emptyList()) {
    val stats = ListeningHistoryStats(history)
}

private val SpotifyGreen = Color(0xFF1ED760)
private val MutedText = Color(0xFFB3B3B3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val context = LocalContext.current
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val revision by listeningHistoryVersion.collectAsState()
    val today = remember { java.time.LocalDate.now() }
    var period by remember { mutableStateOf(RecapPeriod.ALL) }
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthValue) }
    var periodMenu by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    val request = Triple(year.takeUnless { period == RecapPeriod.ALL }, if (period == RecapPeriod.MONTH) month else null, revision)
    val data by produceState(RecapData(), context, request) {
        value = withContext(Dispatchers.IO) {
            RecapData(getListeningHistory(context, request.first, request.second, Int.MAX_VALUE), listeningHistoryMonths(context))
        }
    }
    val history = data.history
    val stats = data.stats
    val years = remember(data.months, today.year) { (data.months.map { it.year } + today.year).distinct().sortedDescending() }
    val storageError = uiLocalized("No se pudo actualizar el historial")
    fun editHistory(work: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(work) }
            if (result.isFailure) android.widget.Toast.makeText(context, storageError, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var kind by remember { mutableStateOf(HistoryKind.MUSIC) }
    var searchQuery by remember { mutableStateOf("") }
    var currentSort by remember { mutableStateOf(getHistorySortOption(context)) }
    var descending by remember { mutableStateOf(isHistorySortDescending(context)) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<HistoryEntry?>(null) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { SongOptionsSheet(song = it, navController = navController, context = context, onDismiss = { menuSong = null }) }
    fun playEntry(entry: HistoryEntry) {
        if (entry.url.isBlank()) return
        val song = entry.asSong()
        playerViewModel.updateQueue(listOf(song), sourceName = "Historial de escucha")
        playerViewModel.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
        SongPlayer.playSong(song.url, context, "song/${song.id}")
    }
    val selected = remember(stats, kind) { stats.filtered(kind) }
    val filtered = remember(selected, searchQuery, currentSort, descending) {
        val matches = selected.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.singer.contains(searchQuery, true) || it.album.contains(searchQuery, true) }
        when (currentSort) {
            HistorySortOption.DATE -> if (descending) matches.sortedByDescending { it.ts } else matches.sortedBy { it.ts }
            HistorySortOption.TITLE -> if (descending) matches.sortedByDescending { it.title.lowercase() } else matches.sortedBy { it.title.lowercase() }
            HistorySortOption.ARTIST -> if (descending) matches.sortedByDescending { it.singer.lowercase() } else matches.sortedBy { it.singer.lowercase() }
        }
    }
    val listState = rememberLazyListState()
    val inset = ((LocalConfiguration.current.screenWidthDp - 840) / 2f).coerceAtLeast(0f).dp
    Box(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = inset, end = inset, bottom = 144.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiLocalized("Volver"), tint = Color.White) }
                    Text(uiLocalized("Historial de escucha"), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) IconButton(onClick = { showClearDialog = true }) { Icon(Icons.Default.DeleteOutline, uiLocalized("Borrar historial"), tint = MutedText) }
                }
            }
            item(key = "period") {
                Row(Modifier.fillMaxWidth().padding(20.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1.35f)) {
                        TextButton(onClick = { periodMenu = true }, modifier = Modifier.fillMaxWidth().background(Color(0xFF282828), RoundedCornerShape(50))) {
                            Text(when (period) {
                                RecapPeriod.ALL -> uiLocalized("Todo el historial")
                                RecapPeriod.YEAR -> year.toString()
                                RecapPeriod.MONTH -> java.time.YearMonth.of(year, month).format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.getDefault()))
                            }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowDown, null, tint = MutedText)
                        }
                        DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                            DropdownMenuItem(text = { Text(uiLocalized("Todo el historial"), color = if (period == RecapPeriod.ALL) SpotifyGreen else Color.White) }, onClick = { period = RecapPeriod.ALL; periodMenu = false })
                            years.forEach { availableYear ->
                                HorizontalDivider(color = Color(0xFF363636))
                                DropdownMenuItem(text = { Text(availableYear.toString(), fontWeight = FontWeight.Bold, color = if (period == RecapPeriod.YEAR && year == availableYear) SpotifyGreen else Color.White) }, onClick = { year = availableYear; period = RecapPeriod.YEAR; periodMenu = false })
                                (data.months + java.time.YearMonth.of(today.year, today.monthValue)).distinct().filter { it.year == availableYear }.sortedDescending().forEach { available ->
                                    DropdownMenuItem(text = { Text(available.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.getDefault())), color = if (period == RecapPeriod.MONTH && year == available.year && month == available.monthValue) SpotifyGreen else Color.White) }, onClick = {
                                        year = available.year; month = available.monthValue; period = RecapPeriod.MONTH; periodMenu = false
                                    })
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { kindMenu = true }, modifier = Modifier.fillMaxWidth().background(Color(0xFF282828), RoundedCornerShape(50))) {
                            Text(uiLocalized(when (kind) { HistoryKind.ALL -> "Todo"; HistoryKind.MUSIC -> "Música"; HistoryKind.PODCASTS -> "Podcasts" }), color = Color.White, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowDown, null, tint = MutedText)
                        }
                        DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                            listOf(HistoryKind.MUSIC, HistoryKind.PODCASTS).forEach { option -> DropdownMenuItem(text = {
                                Text(uiLocalized(when(option) { HistoryKind.ALL -> "Todo"; HistoryKind.MUSIC -> "Música"; HistoryKind.PODCASTS -> "Podcasts" }), color = if (kind == option) SpotifyGreen else Color.White)
                            }, onClick = { kind = option; kindMenu = false }) }
                        }
                    }
                }
            }
            item(key = "recap") {
                AnimatedContent(targetState = kind, label = "history_recap") { selectedKind -> HistoryRecap(stats, selectedKind) }
            }
            if (history.isNotEmpty()) {
                item(key = "totals") {
                    Row(Modifier.fillMaxWidth().padding(20.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HistoryCountCard(uiLocalized("Música"), stats.musicPlays, Color(0xFF242424), Modifier.weight(1f)) { kind = HistoryKind.MUSIC }
                        HistoryCountCard(uiLocalized("Podcasts"), stats.podcastPlays, Color(0xFF242424), Modifier.weight(1f)) { kind = HistoryKind.PODCASTS }
                    }
                }
                if (kind != HistoryKind.PODCASTS) item(key = "minutes") {
                    Column(Modifier.fillMaxWidth().padding(20.dp, 8.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF242424)).padding(22.dp)) {
                        Icon(Icons.Default.Headphones, null, tint = Color(0xFFD7F769), modifier = Modifier.size(28.dp))
                        Text(java.text.NumberFormat.getIntegerInstance().format(stats.musicMinutes), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                        Text(uiLocalized("Minutos de música"), color = Color.White, fontWeight = FontWeight.Bold)

                    }
                }
                if (kind != HistoryKind.PODCASTS && stats.tracks.isNotEmpty()) item(key = "video_recap") {
                    HistoryHeading(uiLocalized("Tus canciones más escuchadas"))
                    val previews = rememberLazyListState()
                    val activePreview by remember { derivedStateOf {
                        val layout = previews.layoutInfo
                        layout.visibleItemsInfo.maxByOrNull { item ->
                            minOf(item.offset + item.size, layout.viewportEndOffset) - maxOf(item.offset, layout.viewportStartOffset)
                        }?.index ?: 0
                    } }
                    LazyRow(state = previews, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(stats.tracks, key = { _, rank -> rank.latest.recordingKey() }) { index, rank ->
                            HistoryCanvasCard(rank, index == activePreview && !previews.isScrollInProgress && !listState.isScrollInProgress,
                                lookup = { id -> playerViewModel.historyCanvas(id) }, onClick = { playEntry(rank.latest) })
                        }
                    }
                }
                if (kind != HistoryKind.PODCASTS && stats.artists.isNotEmpty()) {
                    item(key = "artist_title") { HistoryHeading(uiLocalized("Tus artistas más escuchados")) }
                    items(stats.artists, key = { "artist_${it.name}" }) { rank ->
                        HistoryRankCard(stats.artists.indexOf(rank) + 1, rank, SpotifyGreen, playable = false) { navController.navigate(artistRoute(rank.name)) }
                    }
                }
                if (kind != HistoryKind.MUSIC && stats.shows.isNotEmpty()) {
                    item(key = "podcast_title") { HistoryHeading(uiLocalized("Tus podcasts más escuchados")) }
                    items(stats.shows, key = { "show_${it.name}" }) { rank ->
                        HistoryRankCard(stats.shows.indexOf(rank) + 1, rank, Color(0xFFCFB0FF)) { playEntry(rank.latest) }
                    }
                }
            }
            item(key = "recent_title") {
                Row(Modifier.fillMaxWidth().padding(20.dp, 20.dp, 12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiLocalized("Escuchado recientemente"), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSortSheet = true }) { Icon(Icons.Default.Sort, uiLocalized("Ordenar"), tint = SpotifyGreen) }
                }
                AppSearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, placeholder = uiLocalized("Buscar en el historial"), modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
            }
            if (filtered.isEmpty()) item(key = "empty") {
                Text(uiLocalized(if (history.isEmpty()) "Aún no hay reproducciones." else "No hay reproducciones para este filtro."), color = MutedText, modifier = Modifier.padding(20.dp, 28.dp))
            }
            items(filtered, key = { it.storageKey() }) { entry ->
                HistoryRow(entry, onRemove = { removeTarget = entry }, onClick = { playEntry(entry) }, onLongClick = { menuSong = entry.asSong() })
            }
        }
    }
    if (showSortSheet) ModalBottomSheet(onDismissRequest = { showSortSheet = false }, containerColor = Color(0xFF202020)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            Text(uiLocalized("Ordenar historial"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            HistorySortOption.entries.forEach { option ->
                TextButton(onClick = {
                    descending = if (currentSort == option) !descending else option == HistorySortOption.DATE
                    currentSort = option
                    setHistorySortOption(context, option, descending)
                    showSortSheet = false
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(uiLocalized(when(option) { HistorySortOption.DATE -> "Fecha"; HistorySortOption.TITLE -> "Título"; HistorySortOption.ARTIST -> "Artista o podcast" }), color = if (currentSort == option) SpotifyGreen else Color.White, modifier = Modifier.weight(1f))
                    if (currentSort == option) Icon(if (descending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, tint = SpotifyGreen)
                }
            }
        }
    }
    if (showClearDialog) AlertDialog(onDismissRequest = { showClearDialog = false }, containerColor = Color(0xFF242424),
        title = { Text(uiLocalized("¿Borrar el historial?"), color = Color.White) },
        text = { Text(uiLocalized("Se eliminará tu historial de música y podcasts."), color = MutedText) },
        confirmButton = { TextButton(onClick = { editHistory { clearListeningHistory(context) }; showClearDialog = false }) { Text(uiLocalized("Borrar"), color = Color(0xFFFF8E9D)) } },
        dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text(uiLocalized("Cancelar"), color = Color.White) } })
    removeTarget?.let { entry -> AlertDialog(onDismissRequest = { removeTarget = null }, containerColor = Color(0xFF242424),
        title = { Text(uiLocalized("¿Quitar estas reproducciones?"), color = Color.White) },
        text = { Text("${entry.playCount} ${uiLocalized("reproducciones de esta canción en este mes")}", color = MutedText) },
        confirmButton = { TextButton(onClick = { editHistory { removeListeningHistory(context, entry) }; removeTarget = null }) { Text(uiLocalized("Quitar"), color = SpotifyGreen) } },
        dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(uiLocalized("Cancelar"), color = Color.White) } }) }

}

private fun HistoryEntry.asSong() = SongsModel(id = songId, title = title, album = album, singer = singer, coverUri = image, url = url, spotifyTrackId = spotifyTrackId, durationMs = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

@Composable
private fun HistoryRecap(stats: ListeningHistoryStats, kind: HistoryKind) {
    val transition = rememberInfiniteTransition(label = "recap_shapes")
    val rotation by transition.animateFloat(initialValue = -15f, targetValue = 18f, animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "recap_rotation")
    val selected = stats.filtered(kind)
    val count by animateIntAsState(selected.sumOf { it.playCount }, tween(650), label = "recap_count")
    val accent = SpotifyGreen
    Box(Modifier.fillMaxWidth().padding(20.dp, 12.dp).clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(Color(0xFF15392A), Color(0xFF1A1A1A))))) {
        Canvas(Modifier.matchParentSize().graphicsLayer { rotationZ = rotation; scaleX = 1.25f; scaleY = 1.25f }) {
            drawCircle(accent.copy(alpha = 0.22f), size.width * 0.55f, Offset(size.width * 0.95f, size.height * 0.1f))
            drawCircle(SpotifyGreen.copy(alpha = 0.10f), size.width * 0.34f, Offset(size.width * 0.8f, size.height * 1.2f))
        }
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(uiLocalized("TU ACTIVIDAD"), color = accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Spacer(Modifier.height(18.dp))
            Text(uiLocalized(when(kind) { HistoryKind.ALL -> "Tu historial"; HistoryKind.MUSIC -> "Tu música"; HistoryKind.PODCASTS -> "Tus podcasts" }), color = Color.White, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(22.dp))
            Text("$count", color = accent, fontSize = 48.sp, fontWeight = FontWeight.Black)
            Text(uiLocalized("Reproducciones"), color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HistoryCountCard(label: String, count: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(color).clickable(onClick = onClick).padding(16.dp)) {
        Text(label, color = MutedText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("$count", color = SpotifyGreen, fontSize = 36.sp, fontWeight = FontWeight.Black)
        Text(uiLocalized("reproducciones"), color = MutedText, fontSize = 12.sp)
    }
}

@Composable
private fun HistoryHeading(title: String) { Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp)) }

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HistoryRankCard(position: Int, rank: HistoryRanking, accent: Color, playable: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp, 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF202020)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(position.toString().padStart(2, '0'), color = accent, fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(42.dp))
        GlideImage(model = rank.latest.image, contentDescription = null, contentScale = ContentScale.Crop, failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder), modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(rank.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${rank.plays} ${uiLocalized("reproducciones")}", color = MutedText, fontSize = 12.sp)
        }
        Icon(if (playable) Icons.Default.PlayArrow else Icons.Default.ChevronRight, null, tint = accent, modifier = Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HistoryCanvasCard(rank: HistoryRanking, active: Boolean, lookup: suspend (String) -> String?, onClick: () -> Unit) {
    val track = rank.latest
    val canvas by produceState<String?>(null, track.spotifyTrackId, active) {
        value = null
        if (active && track.spotifyTrackId.isNotBlank()) {
            while (SongPlayer.isPreparingTrack()) delay(200L)
            value = withContext(Dispatchers.IO) { runCatching { lookup(track.spotifyTrackId) }.getOrNull() }
        }
    }
    Box(Modifier.width(190.dp).height(280.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF252525)).clickable(onClick = onClick)) {
        GlideImage(model = track.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder))
        if (active && canvas != null) com.music.spotui.ui.components.VideoCanvasBackground(
            media = com.music.spotui.ui.components.CanvasMedia(canvas!!), shouldPlay = true, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE121212)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(track.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.singer, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${rank.plays} ${uiLocalized("reproducciones")}", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
    ) {
        if (entry.isPodcast()) {
            com.music.spotui.ui.components.EpisodeArtwork(
                episode = com.music.spotui.data.entity.SongsModel(
                    id = entry.songId, title = entry.title, album = entry.album,
                    singer = entry.singer, coverUri = entry.image, url = entry.url
                ),
                modifier = Modifier.size(44.dp), radius = 4.dp
            )
        } else {
            GlideImage(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
                model = entry.image, contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                contentDescription = "",
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp),
        ) {
            Text(entry.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${entry.playCount} ${uiLocalized("reproducciones")} • ${if (entry.isPodcast()) "Podcast • " else ""}${entry.singer} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.ts))}",
                color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = uiLocalized("Remove"),
            tint = MutedText,
            modifier = Modifier
                .size(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onRemove() },
        )
    }
}
