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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.music.spotui.ui.components.CollectionPullSearch
import com.music.spotui.ui.components.rememberCollectionSearchState
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
    val listState = rememberLazyListState()
    val searchReveal = rememberCollectionSearchState(listState, showId)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    searchReveal.onDismissByScroll = {
        searchQuery = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }
    LaunchedEffect(keyboardVisible) {
        if (keyboardVisible) listState.scrollToItem(0)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(searchReveal.nestedScrollConnection)
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
            if (episodes.isNotEmpty()) {
                item(key = "podcast_pull_search") {
                    CollectionPullSearch(
                        state = searchReveal,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "Buscar en episodios"
                    )
                }
            }
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

            // Hide the show header while typing so search results remain visible.
            if (!keyboardVisible) item {
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
                        model = show?.let {
                            com.music.spotui.ui.notification.PodcastCover.preferredShowUri(
                                context, it.id, resolvedCover.orEmpty()
                            )
                        } ?: resolvedCover,
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
                                            com.music.spotui.ui.notification.PodcastCover.preserve(context, firstEp)
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
            if (episodesState is Response.Error) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = (episodesState as Response.Error).error,
                            color = Color(0xFFB3B3B3),
                            fontSize = 13.sp,
                            maxLines = 3,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Reintentar episodios",
                            color = Color(0xFF1ED760),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { vm.retryShow(context, showId, showName) }.padding(12.dp)
                        )
                    }
                }
            }

            if (episodes.isNotEmpty()) {
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
                items(displayedEpisodes.size, key = { i -> displayedEpisodes[i].url }) { i ->
                    val ep = displayedEpisodes[i]
                    val chapters = remember(ep.description) { parsePodcastChapters(ep.description) }
                    // No renderizar descripciones erróneamente almacenadas como fecha.
                    val releaseDate = remember(ep.releaseDate) {
                        com.music.spotui.providers.PodcastProvider.validatedReleaseDate(ep.releaseDate).orEmpty()
                    }

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
                                .clipToBounds()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onLongClick = { menuSong = ep },
                                    onClick = {
                                        vm.updateQueue(filteredEpisodes)
                                        vm.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, i, ep.album)
                                        com.music.spotui.ui.notification.PodcastCover.preserve(context, ep)
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
                                    model = com.music.spotui.ui.notification.PodcastCover.preferredUri(context, ep)
                                        .ifBlank { show?.coverUri.orEmpty() },
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

                                    // Compact podcast preview: always sanitized and visually capped.
                                    val previewText = remember(ep.description) { buildEpisodePreview(ep.description) }
                                    if (previewText.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(38.dp)
                                                .clipToBounds(),
                                            contentAlignment = Alignment.TopStart
                                        ) {
                                            Text(
                                                text = previewText,
                                                color = Color(0xFFB3B3B3),
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    // Meta Row: Duration Badge & Quick Play
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (releaseDate.isNotBlank()) {
                                                Text(
                                                    text = releaseDate,
                                                    color = Color(0xFFB3B3B3),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.widthIn(max = 120.dp)
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
                                                    com.music.spotui.ui.notification.PodcastCover.preserve(context, ep)
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

                            // Chapters: compact horizontal chips to avoid giant episode cards.
                            if (chapters.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Surface(
                                        color = Color(0x1422C55E),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = "${chapters.size} capítulos",
                                            color = Color(0xFF1ED760),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Pulsa un bloque para saltar",
                                        color = Color(0xFF8E8E93),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(chapters.take(3).size) { cIdx ->
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
                                                    com.music.spotui.ui.notification.PodcastCover.preserve(context, ep)
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
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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
                                    if (chapters.size > 3) {
                                        item {
                                            Surface(
                                                color = Color(0xFF202026),
                                                shape = RoundedCornerShape(16.dp)
                                            ) {
                                                Text(
                                                    text = "+${chapters.size - 3} más",
                                                    color = Color(0xFFB3B3B3),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
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
                                .padding(top = 20.dp, bottom = 42.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                color = Color(0xFF1ED760),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .clickable { vm.loadMoreEpisodes() }
                            ) {
                                Text(
                                    text = "Cargar más episodios",
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

            // Reserve room for the mini-player, system navigation and large font scales.
            item { Spacer(Modifier.height(230.dp)) }
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

internal data class PodcastChapter(
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

internal fun parsePodcastChapters(description: String): List<PodcastChapter> {
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

private fun buildEpisodePreview(description: String): String {
    if (description.isBlank()) return ""

    // The provider can include raw YouTube descriptions with ads, links and chapter lists.
    // Pick the first editorial paragraph, not the first arbitrary string (e.g. "Publi").
    val chapterLine = Regex("""^\s*(?:\[?\d{1,2}:\d{2}(?::\d{2})?\]?\s*[-:·•\s]+).*$""")
    val promotionLine = Regex(
        """(?i)^(?:[-*#\s]*)?(?:publi(?:cidad)?|patrocinad[oa]|sponsor(?:ed)?|ad(?:vertisement)?|sígueme|s[ií]guenos|suscr[ií]bete|hazte miembro|hazte socio|código descuento|codigo descuento|descuento|colaboraci[oó]n comercial|tienda|merch|mail para|contacto|mis libros?|mi libro|el libro|nuevo libro|redes sociales|instagram|facebook|twitter|tiktok|canal de yt|web oficial|jordi wild web|realizaci[oó]n y edici[oó]n|momentos clave|cap[ií]tulos|marcas de tiempo)\b.*$"""
    )
    val url = Regex("""(?i)(?:https?://|www\.)\S+""")
    val normalized = description
        .replace(Regex("""(?i)<br\s*/?>|</p>"""), "\n")
        .replace(Regex("""<[^>]*>"""), " ")
        .replace("\u2028", "\n")
        .replace("\u2029", "\n")

    val excerpt = normalized.lineSequence()
        .map { url.replace(it, " ").replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotBlank() }
        .filterNot { chapterLine.matches(it) || promotionLine.matches(it) }
        .filter { line -> line.count { it.isLetter() } >= 35 }
        .firstOrNull()
        ?: return ""

    return if (excerpt.length <= 200) excerpt
    else excerpt.take(197).trimEnd('.', ',', ';', ':', ' ') + "..."
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
