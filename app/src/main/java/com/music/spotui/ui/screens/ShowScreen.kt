package com.music.spotui.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.ShowSortOption
import com.music.spotui.data.preferences.getShowSortOption
import com.music.spotui.data.preferences.isShowSortDescending
import com.music.spotui.data.preferences.setShowSortOption
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.AppSearchBar
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.components.SwipeToPlayNextWrapper
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.viewmodel.ShowViewModel

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShowScreen(navController: NavController, showId: String, showName: String = "") {
    val vm: ShowViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current

    LaunchedEffect(showId) { vm.loadShow(context, showId, showName) }

    val episodesState by vm.episodes.collectAsState()
    val show by vm.show.collectAsState()
    val isSubscribed by vm.isSubscribed.collectAsState()
    val visibleCount by vm.visibleCount.collectAsState()
    val episodes = (episodesState as? Response.Success)?.data.orEmpty()

    var searchQuery by remember(showId) { mutableStateOf("") }
    var currentSort by remember(showId) { mutableStateOf(getShowSortOption(context, showId)) }
    var isDescending by remember(showId) { mutableStateOf(isShowSortDescending(context, showId)) }
    var showSortSheet by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    var isNotificationOn by remember { mutableStateOf(false) }

    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    val filteredEpisodes = remember(episodes, searchQuery, currentSort, isDescending) {
        val filtered = if (searchQuery.isBlank()) {
            episodes
        } else {
            episodes.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.singer.contains(searchQuery, ignoreCase = true) ||
                        it.description.contains(searchQuery, ignoreCase = true)
            }
        }
        when (currentSort) {
            ShowSortOption.DATE -> if (isDescending) filtered else filtered.reversed()
            ShowSortOption.TITLE -> if (isDescending) filtered.sortedByDescending { it.title.lowercase() } else filtered.sortedBy { it.title.lowercase() }
            ShowSortOption.DURATION -> if (isDescending) filtered.sortedByDescending { it.durationMs } else filtered.sortedBy { it.durationMs }
        }
    }

    val displayedEpisodes = remember(filteredEpisodes, visibleCount) {
        filteredEpisodes.take(visibleCount)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E2A38),
                            Color(AppBackground.toArgb()),
                            Color(AppBackground.toArgb())
                        )
                    )
                )
                .statusBarsPadding(),
        ) {
            // Back Button
            item {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = Color.White,
                    contentDescription = "Volver",
                    modifier = Modifier
                        .padding(16.dp, 12.dp)
                        .size(26.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { navController.navigateUp() },
                )
            }

            // Spotify Header Section
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    // Show Cover Image
                    val resolvedCover = show?.coverUri?.takeIf { it.isNotBlank() }
                        ?: episodes.firstOrNull { it.coverUri.isNotBlank() }?.coverUri
                    val resolvedTitle = show?.name?.takeIf { it.isNotBlank() && it != "Podcast" }
                        ?: showName.takeIf { it.isNotBlank() && it != "Podcast" }
                        ?: episodes.firstOrNull()?.album?.takeIf { it.isNotBlank() && it != "Podcast" }
                        ?: "Podcast"
                    val resolvedPublisher = show?.publisher?.takeIf { it.isNotBlank() }
                        ?: episodes.firstOrNull()?.singer?.takeIf { it.isNotBlank() }
                        ?: ""

                    GlideImage(
                        model = resolvedCover,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(16.dp))

                    // Show Title
                    Text(
                        text = resolvedTitle,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (resolvedPublisher.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "De $resolvedPublisher",
                            color = Color(0xFFB3B3B3),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Action Control Row (Follow button + Notifications + Queue/Play)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Seguir / Siguiendo Capsule Button
                            Surface(
                                color = if (isSubscribed) Color(0xFF1ED760) else Color.Transparent,
                                shape = RoundedCornerShape(20.dp),
                                border = if (isSubscribed) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White),
                                modifier = Modifier.clickable {
                                    vm.toggleSubscription(context)
                                    val msg = if (vm.isSubscribed.value) "Añadido a tus podcasts seguidos" else "Eliminado de tus podcasts"
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSubscribed) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = if (isSubscribed) "Siguiendo" else "Seguir",
                                        color = if (isSubscribed) Color.Black else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Bell Icon Button
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2A2A30))
                                    .clickable {
                                        isNotificationOn = !isNotificationOn
                                        val msg = if (isNotificationOn) "Notificaciones activadas" else "Notificaciones desactivadas"
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = "Notificaciones",
                                    tint = if (isNotificationOn) Color(0xFF1ED760) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Right action buttons: Add to queue & Big Green Play Button
                        if (episodes.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_queue_add),
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            playerViewModel.addAllToQueue(episodes)
                                            android.widget.Toast.makeText(
                                                context,
                                                "${episodes.size} episodio(s) añadidos a la cola",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    contentDescription = "Añadir episodios a la cola",
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                // Spotify Big Play Button
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1ED760))
                                        .clickable {
                                            val firstEp = filteredEpisodes.firstOrNull() ?: return@clickable
                                            vm.updateQueue(filteredEpisodes)
                                            vm.updateSongState(firstEp.coverUri, firstEp.title, firstEp.singer, true, firstEp.id, 0, firstEp.album)
                                            SongPlayer.playSong(firstEp.url, context, "song/${firstEp.id}")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Reproducir",
                                        tint = Color.Black,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (episodesState is Response.Loading) {
                item { Loader() }
            }

            if (episodes.isNotEmpty()) {
                // Search Bar inside Episodes
                item {
                    AppSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        modifier = Modifier.padding(20.dp, 8.dp),
                        placeholder = "Buscar en episodios",
                    )
                }

                // Sort Action Bar
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp, 4.dp, 20.dp, 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredEpisodes.size} episodios",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF2A2A30))
                                .clickable { showSortSheet = true }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = currentSort.getDescriptiveLabel(isDescending),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Opciones de orden",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(start = 2.dp)
                            )
                        }
                    }
                }
            }

            if (filteredEpisodes.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No se encontraron coincidencias para \"$searchQuery\"",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                // Render batch of displayed episodes
                items(displayedEpisodes.size) { i ->
                    val ep = displayedEpisodes[i]
                    var expanded by remember(ep.id) { mutableStateOf(false) }
                    val chapters = remember(ep.description) { parsePodcastChapters(ep.description) }

                    SwipeToPlayNextWrapper(
                        onPlayNext = {
                            playerViewModel.playNext(ep)
                            android.widget.Toast.makeText(
                                context,
                                "${ep.title} se reproducirá a continuación",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppBackground)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onLongClick = { menuSong = ep },
                                    onClick = {
                                        vm.updateQueue(filteredEpisodes)
                                        vm.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, i, ep.album)
                                        SongPlayer.playSong(ep.url, context, "song/${ep.id}")
                                    },
                                )
                                .padding(16.dp, 12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Episode Thumbnail
                                GlideImage(
                                    model = ep.coverUri.ifBlank { show?.coverUri },
                                    contentScale = ContentScale.Crop,
                                    failure = placeholder(R.drawable.placeholder),
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentDescription = null,
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                ) {
                                    // Title
                                    Text(
                                        text = ep.title,
                                        color = if (ep.id == vm.currentSongId.value) Color(0xFF1ED760) else Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Description Excerpt
                                    if (ep.description.isNotBlank()) {
                                        val cleanDesc = ep.description.lines()
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .joinToString(" ")
                                        Text(
                                            text = cleanDesc,
                                            color = Color(0xFFB3B3B3),
                                            fontSize = 13.sp,
                                            maxLines = if (expanded) 4 else 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { expanded = !expanded }
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }

                                    // Meta Row: Duration Badge & Quick Play
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (ep.releaseDate.isNotBlank()) {
                                                Text(
                                                    text = ep.releaseDate,
                                                    color = Color(0xFFB3B3B3),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(" • ", color = Color(0xFFB3B3B3), fontSize = 12.sp)
                                            }
                                            if (ep.durationMs > 0) {
                                                Surface(
                                                    color = Color(0xFF2A2A30),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text(
                                                        text = formatDuration(ep.durationMs),
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                        }

                                        // Play Episode Circle Button
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF2A2A30))
                                                .clickable {
                                                    vm.updateQueue(filteredEpisodes)
                                                    vm.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, i, ep.album)
                                                    SongPlayer.playSong(ep.url, context, "song/${ep.id}")
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Reproducir episodio",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Chapters list
                            if (chapters.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Capítulos (${chapters.size})",
                                    color = Color(0xFF1ED760),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(chapters.size) { cIdx ->
                                        val chapter = chapters[cIdx]
                                        Surface(
                                            color = Color(0xFF2A2A30),
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.clickable {
                                                val activeSongId = vm.currentSongId.value
                                                val isSameEpisode = activeSongId == ep.id && SongPlayer.isPrepared()
                                                vm.updateQueue(filteredEpisodes)
                                                vm.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, i, ep.album)
                                                if (isSameEpisode) {
                                                    SongPlayer.seekTo(chapter.timeMs)
                                                } else {
                                                    SongPlayer.setRestorePoint(ep.url, chapter.timeMs)
                                                    SongPlayer.playSong(ep.url, context, "song/${ep.id}")
                                                }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Saltando a ${chapter.timeFormatted}: ${chapter.title}",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = chapter.timeFormatted,
                                                    color = Color(0xFF1ED760),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = chapter.title,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Color(0xFF222222))
                        }
                    }
                }

                // Spotify Green "Cargar más" Button
                if (filteredEpisodes.size > visibleCount) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                color = Color(0xFF1ED760),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .clickable { vm.loadMoreEpisodes() }
                            ) {
                                Text(
                                    text = "Cargar más",
                                    color = Color.Black,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(130.dp)) }
        }

        // Sort Options Modal Bottom Sheet
        if (showSortSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSortSheet = false },
                containerColor = Color(0xFF1A1A1A)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = "Ordenar por",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 12.dp)
                    )
                    HorizontalDivider(color = Color(0xFF2A2A2A))
                    Spacer(modifier = Modifier.height(4.dp))
                    ShowSortOption.entries.forEach { option ->
                        val isSelected = option == currentSort
                        val icon = when (option) {
                            ShowSortOption.DATE -> Icons.Default.DateRange
                            ShowSortOption.TITLE -> Icons.AutoMirrored.Filled.List
                            ShowSortOption.DURATION -> Icons.Default.Menu
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (currentSort == option) {
                                        isDescending = !isDescending
                                    } else {
                                        currentSort = option
                                        isDescending = (option == ShowSortOption.DATE)
                                    }
                                    setShowSortOption(context, showId, currentSort, isDescending)
                                    showSortSheet = false
                                }
                                .padding(16.dp, 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) Color(AppPalette.toArgb()) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(18.dp))
                            Text(
                                text = if (isSelected) option.getDescriptiveLabel(isDescending) else option.getDescriptiveLabel(option == ShowSortOption.DATE),
                                color = if (isSelected) Color(AppPalette.toArgb()) else Color.White,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = if (isDescending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = Color(AppPalette.toArgb()),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

private data class PodcastChapter(
    val title: String,
    val timeMs: Long,
    val timeFormatted: String
)

private fun parseTimeToMs(time: String): Long {
    val parts = time.split(":").map { it.toLongOrNull() ?: 0L }
    return when (parts.size) {
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        2 -> (parts[0] * 60 + parts[1]) * 1000
        else -> -1L
    }
}

private fun parsePodcastChapters(description: String): List<PodcastChapter> {
    if (description.isBlank()) return emptyList()
    val regex = Regex("(\\[?\\d{1,2}:\\d{2}(?::\\d{2})?\\]?)[-:\\s]+(.*)")
    val chapters = mutableListOf<PodcastChapter>()
    description.lineSequence().forEach { line ->
        regex.find(line.trim())?.let { match ->
            val rawTime = match.groupValues[1].removePrefix("[").removeSuffix("]")
            val titleStr = match.groupValues[2].trim()
            val timeMs = parseTimeToMs(rawTime)
            if (timeMs >= 0 && titleStr.isNotBlank() && chapters.none { it.timeMs == timeMs }) {
                chapters.add(PodcastChapter(title = titleStr, timeMs = timeMs, timeFormatted = rawTime))
            }
        }
    }
    return chapters
}

private fun formatDuration(ms: Int): String {
    if (ms <= 0) return ""
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    return if (hours > 0) {
        "$hours h $minutes min"
    } else {
        "$minutes min"
    }
}
