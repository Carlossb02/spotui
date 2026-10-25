package com.music.spotui.ui.screens

import androidx.activity.compose.BackHandler
import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.preferences.LikedSongsSortOption
import com.music.spotui.data.preferences.getLikedSongsSortOption
import com.music.spotui.data.preferences.isLikedSongsSortDescending
import com.music.spotui.data.preferences.setLikedSongsSortOption
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.CollectionPullSearch
import com.music.spotui.ui.components.CollectionSortRow
import com.music.spotui.ui.components.CollectionSortSheet
import com.music.spotui.ui.components.collectionSortLabel
import com.music.spotui.ui.components.rememberCollectionSearchState
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.components.SwipeToPlayNextWrapper
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LikedSongsViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LikedSongsScreen(navController: NavController) {

    val likedSongsViewModel: LikedSongsViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val songsResp by likedSongsViewModel.songs.collectAsState()
    val context = LocalContext.current

    val songs = (songsResp as? Response.Success)?.data.orEmpty()

    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) {
            SongPlayer.prefetchList(songs.map { it.url }, context)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var currentSort by remember { mutableStateOf(getLikedSongsSortOption(context)) }
    var isDescending by remember { mutableStateOf(isLikedSongsSortDescending(context)) }
    var showSortSheet by remember { mutableStateOf(false) }

    val filteredSongs = remember(songs, searchQuery, currentSort, isDescending) {
        val filtered = if (searchQuery.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.singer.contains(searchQuery, ignoreCase = true) ||
                        it.album.contains(searchQuery, ignoreCase = true)
            }
        }
        when (currentSort) {
            LikedSongsSortOption.DATE -> if (isDescending) filtered else filtered.reversed()
            LikedSongsSortOption.TITLE -> if (isDescending) filtered.sortedByDescending { it.title.lowercase() } else filtered.sortedBy { it.title.lowercase() }
            LikedSongsSortOption.ARTIST -> if (isDescending) filtered.sortedByDescending { it.singer.lowercase() } else filtered.sortedBy { it.singer.lowercase() }
            LikedSongsSortOption.ALBUM -> if (isDescending) filtered.sortedByDescending { it.album.lowercase() } else filtered.sortedBy { it.album.lowercase() }
        }
    }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = {
                if (!com.music.spotui.data.preferences.isSongLiked(context, sel.id.toString())) {
                    likedSongsViewModel.removeLocally(sel.id)
                }
                menuSong = null
            },
        )
    }

    var snackbarMessage by remember { mutableStateOf("") }
    var snackbarVisible by remember { mutableStateOf(false) }
    LaunchedEffect(snackbarVisible) {
        if (snackbarVisible) {
            kotlinx.coroutines.delay(1500)
            snackbarVisible = false
        }
    }

    val likedColor = Color(0xFF5038A0)

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxSize().background(Color(AppBackground.toArgb()))
    ) {
        if (songsResp is Response.Loading) {
            Loader()
            return@Surface
        }

        val likedBackground = remember(likedColor) {
            Brush.verticalGradient(
                colors = listOf(likedColor, Color(AppBackground.toArgb()), Color(AppBackground.toArgb())),
                startY = 0f, endY = 2100f
            )
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val searchReveal = rememberCollectionSearchState(listState, "liked_songs")
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        Scaffold(
            modifier = Modifier.fillMaxSize().background(likedBackground),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier
                        .padding(horizontal = 12.dp),
                    navigationIcon = {
                        Icon(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (searchReveal.expanded || searchQuery.isNotBlank()) {
                                        searchQuery = ""
                                        searchReveal.close()
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    } else navController.navigateUp()
                                }
                                .padding(9.dp),
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "",
                            tint = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = { Text(text = "") }
                )
            }
        ) { innerPadding ->
            // The keyboard Back action only hides the IME. A second Back exits
            // search and restores the artwork, exactly like the close button.
            BackHandler(enabled = (searchReveal.expanded || searchQuery.isNotBlank()) && !keyboardVisible) {
                searchQuery = ""
                searchReveal.close()
                keyboardController?.hide()
                focusManager.clearFocus()
            }
            LaunchedEffect(keyboardVisible) {
                if (keyboardVisible) listState.scrollToItem(0)
            }
            // Keep the search field in place; collapse only the expensive artwork/
            // metadata header while the IME is visible, leaving room for results.
            searchReveal.onDismissByScroll = {
                keyboardController?.hide()
                focusManager.clearFocus()
                searchQuery = ""
            }
            Box(modifier = Modifier.fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())) {
                LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 138.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(searchReveal.nestedScrollConnection)
                ) {
                    item(key = "liked_search_reveal") {
                        CollectionPullSearch(
                            state = searchReveal,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "Buscar en canciones que te gustan"
                        )
                    }
                    if (!keyboardVisible && !searchReveal.expanded && searchQuery.isBlank()) {
                    item(key = "liked_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(440.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Spacer(modifier = Modifier.padding(25.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(230.dp)
                                        .background(
                                            brush = Brush.linearGradient(
                                                colors = listOf(Color(0xFF8E6FE0), Color(0xFF3B2A82)),
                                            )
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Favorite,
                                        contentDescription = "",
                                        tint = Color.White,
                                        modifier = Modifier.size(90.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.padding(5.dp))
                            Text(
                                modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                                text = "Canciones que te gustan",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                                text = "${songs.size} canciones",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .padding(20.dp, 0.dp)
                            ) {
                                var likedDownloaded by remember(songs) {
                                    mutableStateOf(songs.isNotEmpty() && SongPlayer.allDownloaded(songs, context))
                                }

                                if (snackbarVisible) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        Snackbar(showMessage = snackbarMessage)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (songs.isNotEmpty()) {
                                            Icon(
                                                imageVector = if (likedDownloaded)
                                                    Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                                tint = if (likedDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        if (!likedDownloaded) {
                                                            SongPlayer.downloadAll(songs, context)
                                                            snackbarMessage = "Downloading ${songs.size} tracks…"
                                                            snackbarVisible = true
                                                        }
                                                    },
                                                contentDescription = "Download liked songs",
                                            )
                                            Spacer(modifier = Modifier.width(18.dp))
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_queue_add),
                                                tint = Color.White,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        playerViewModel.addAllToQueue(songs)
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "${songs.size} track(s) added to queue",
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                    },
                                                contentDescription = "Add to queue",
                                            )
                                            Spacer(modifier = Modifier.width(18.dp))
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_player_shuffle),
                                                tint = Color.White,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        likedSongsViewModel.startShuffled(songs)?.let { first ->
                                                            likedSongsViewModel.updateSongState(
                                                                first.coverUri,
                                                                first.title,
                                                                first.singer,
                                                                true,
                                                                first.id,
                                                                0,
                                                                "Liked Songs",
                                                            )
                                                            SongPlayer.playSong(first.url, context, "song/${first.id}")
                                                        }
                                                    },
                                                contentDescription = "Shuffle play",
                                            )
                                        }
                                    }
                                }
                                if (songs.isNotEmpty()) {
                                    val playing = likedSongsViewModel.currentSongPlayingState.value
                                    val currentInList = songs.any { it.id == likedSongsViewModel.currentSongId.value }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(100.dp))
                                            .background(Color(0xFF1ED760))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                when {
                                                    currentInList -> likedSongsViewModel.setPlaying(!playing)
                                                    else -> {
                                                        likedSongsViewModel.updateQueue(songs)
                                                        likedSongsViewModel.updateSongState(
                                                            songs[0].coverUri,
                                                            songs[0].title,
                                                            songs[0].singer,
                                                            true,
                                                            songs[0].id,
                                                            0,
                                                            "Liked Songs"
                                                        )
                                                        SongPlayer.playSong(songs[0].url, context, "song/${songs[0].id}")
                                                    }
                                                }
                                            }
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(25.dp),
                                            tint = Color.Black,
                                            painter = painterResource(
                                                id = if (currentInList && playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com,
                                            ),
                                            contentDescription = if (currentInList && playing) "Pause" else "Play"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    }

                    item(key = "liked_sort") {
                        if (songs.isNotEmpty()) {
                            CollectionSortRow(
                                label = collectionSortLabel(currentSort.name, isDescending),
                                onClick = { showSortSheet = true }
                            )
                        }
                    }

                    if (filteredSongs.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No matches found for \"$searchQuery\"",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        itemsIndexed(filteredSongs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                            val currentColor = if (song.id == likedSongsViewModel.currentSongId.value)
                                Color(AppPalette.toArgb()) else Color.White

                            SwipeToPlayNextWrapper(
                                onPlayNext = {
                                    playerViewModel.playNext(song)
                                    android.widget.Toast.makeText(
                                        context,
                                        "${song.title} will play next",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AppBackground)
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onLongClick = { menuSong = song },
                                            onClick = {
                                                likedSongsViewModel.updateQueue(filteredSongs.drop(index))
                                                likedSongsViewModel.updateSongState(
                                                    song.coverUri,
                                                    song.title,
                                                    song.singer,
                                                    true,
                                                    song.id,
                                                    0,
                                                    "Liked Songs"
                                                )
                                                SongPlayer.playSong(song.url, context, "song/${song.id}")
                                            },
                                        )
                                        .padding(20.dp, 8.dp)
                                ) {
                                    GlideImage(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        model = song.coverUri,
                                        failure = placeholder(R.drawable.placeholder),
                                        contentScale = ContentScale.Crop,
                                        contentDescription = ""
                                    )
                                    Column(modifier = Modifier.padding(start = 12.dp).width(280.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (song.explicit) {
                                                com.music.spotui.ui.components.ExplicitBadge()
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = song.title,
                                                color = currentColor,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            text = song.singer,
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(220.dp)) }
                }

                com.music.spotui.ui.components.FastScrollbarForLazyList(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )

if (showSortSheet) {
            CollectionSortSheet(
                selected = currentSort.name,
                descending = isDescending,
                onDismiss = { showSortSheet = false },
                onSelected = { optionName ->
                    val selectedOption = LikedSongsSortOption.valueOf(optionName)
                    val nextDescending = if (currentSort == selectedOption) !isDescending
                        else selectedOption.name == "DATE"
                    currentSort = selectedOption
                    isDescending = nextDescending
                    setLikedSongsSortOption(context, selectedOption, nextDescending)
                    showSortSheet = false
                }
            )
        }
            }
        }
    }
}
