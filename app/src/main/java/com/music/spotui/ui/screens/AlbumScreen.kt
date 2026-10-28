package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.TextStyle
import com.music.spotui.data.preferences.AlbumSortOption
import com.music.spotui.data.preferences.getAlbumSortOption
import com.music.spotui.data.preferences.isAlbumSortDescending
import com.music.spotui.data.preferences.setAlbumSortOption
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedAlbumId
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isAlbumLiked
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedAlbumId
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.music.spotui.ui.components.CollectionPullSearch
import com.music.spotui.ui.components.CollectionSortRow
import com.music.spotui.ui.components.rememberCollectionSearchState
import com.music.spotui.ui.components.LikedSongsScreen
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.SavedInSheet
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.AlbumViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.components.SwipeToPlayNextWrapper
import kotlinx.coroutines.delay


@Composable
fun AlbumScreen(navController: NavController, albumName: String, artist: String = "") {
    val albumViewModel : AlbumViewModel = hiltViewModel()
    val songs by albumViewModel.songs.collectAsState()
    val albums by albumViewModel.albums.collectAsState()

    // Load this album's actual tracks from Spotify (by name, disambiguated by artist).
    LaunchedEffect(albumName, artist) {
        albumViewModel.loadAlbumSongs(albumName, artist)
    }

    val context = LocalContext.current




    Log.d("check", albumName.toString())

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        val albumsResponse = (albums as? Response.Success)?.data.orEmpty()
        val songsResponse = (songs as? Response.Success)?.data.orEmpty()

        when {
            albums is Response.Loading && songs is Response.Loading -> {
                Log.d("homeMain", "loading..-albums")
                Loader()
            }

            else -> {
                Log.d("homeMain", "albums ready")
                if (albumName == "Liked Songs"){
                    LikedSongsScreen(albumsResponse, songsResponse, navController, context)
                }
                else{
                    SumUpAlbumScreen(navController = navController,albumViewModel, albumsResponse, songsResponse, albumName, context, artist)
                }
            }
        }
    }

}
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SumUpAlbumScreen(
    navController: NavController,
    albumViewModel: AlbumViewModel,
    albums: List<AlbumsModel>,
    songs: List<SongsModel>,
    albumName: String,
    context: Context,
    artist: String = ""
) {
    val tabletCollectionLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600 &&
        LocalConfiguration.current.screenWidthDp >= 840
    val playerViewModel: PlayerViewModel = hiltViewModel()
    // `songs` is already this album's track list (loaded by AlbumViewModel).
    val albumSongs: List<SongsModel> = songs

    // Warm the stream cache for the first few tracks so the first tap plays
    // (near-)instantly instead of resolving YouTube on the tap.
    LaunchedEffect(albumSongs) {
        if (albumSongs.isNotEmpty()) {
            SongPlayer.prefetchList(albumSongs.map { it.url }, context)
        }
    }

    val albumByName : Map<String, List<AlbumsModel>> = albums.groupBy { it.name }
    // The album may not be in the cached new-releases list (e.g. opened from
    // search) — fall back to a model built from the album's first track.
    val album : List<AlbumsModel> = albumByName[albumName]
        ?: listOf(
            AlbumsModel(
                id = albumName.hashCode() and 0x7fffffff,
                artists = albumSongs.firstOrNull()?.singer ?: artist,
                coverUri = albumSongs.firstOrNull()?.coverUri ?: "",
                name = albumName,
                time = "",
            )
        )

    val currentAlbum = album.firstOrNull()
    LaunchedEffect(albumSongs, currentAlbum) {
        if (albumSongs.isNotEmpty() && currentAlbum != null) {
            com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollection(
                context = context,
                id = "album:$albumName|$artist",
                name = albumName,
                coverUri = currentAlbum.coverUri,
                artists = currentAlbum.artists,
                isPlaylist = false,
                songs = albumSongs
            )
        }
    }

    var dominentColor by remember {
        mutableStateOf(Color(AppBackground.toArgb()))
    }
    Palette().extractSecondColorFromCoverUrl(context = context, album[0].coverUri){ color ->
        dominentColor = color
    }

    var isAlbumLiked by remember { mutableStateOf( isAlbumLiked(context, album[0].id.toString())) }

    var snackbarMessage by remember {
        mutableStateOf("")
    }
    var snackbarVisible by remember {
        mutableStateOf(false)
    }

    var searchQuery by remember(albumName) { mutableStateOf("") }
    var currentSort by remember(albumName) { mutableStateOf(getAlbumSortOption(context, albumName)) }
    var isDescending by remember(albumName) { mutableStateOf(isAlbumSortDescending(context, albumName)) }
    var showSortSheet by remember { mutableStateOf(false) }

    val filteredSongs = remember(albumSongs, searchQuery, currentSort, isDescending) {
        val filtered = if (searchQuery.isBlank()) {
            albumSongs
        } else {
            albumSongs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.singer.contains(searchQuery, ignoreCase = true)
            }
        }
        when (currentSort) {
            AlbumSortOption.DEFAULT -> if (isDescending) filtered.reversed() else filtered
            AlbumSortOption.TITLE -> if (isDescending) filtered.sortedByDescending { it.title.lowercase() } else filtered.sortedBy { it.title.lowercase() }
            AlbumSortOption.ARTIST -> if (isDescending) filtered.sortedByDescending { it.singer.lowercase() } else filtered.sortedBy { it.singer.lowercase() }
            AlbumSortOption.DURATION -> if (isDescending) filtered.sortedByDescending { it.durationMs } else filtered.sortedBy { it.durationMs }
        }
    }

    val albumArtists = album[0].artists.ifBlank { albumSongs.firstOrNull()?.singer ?: artist }
    val albumArtistList = remember(albumArtists) {
        albumArtists.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    var showArtistSheet by remember { mutableStateOf(false) }
    if (showArtistSheet) {
        ArtistsSheet(
            artistNames = albumArtistList,
            context = context,
            onDismiss = { showArtistSheet = false },
            navController = navController,
        )
    }

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }
    LaunchedEffect(snackbarVisible) {
        delay(1500)
        snackbarVisible = false
    }



    Log.d("color", dominentColor.toString())
    val listState = rememberLazyListState()
    val searchReveal = rememberCollectionSearchState(listState, albumName)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = (searchReveal.expanded || searchQuery.isNotBlank()) && !keyboardVisible) {
        searchQuery = ""
        searchReveal.close()
        keyboard?.hide()
        focusManager.clearFocus()
    }
    searchReveal.onDismissByScroll = {
        searchQuery = ""
        keyboard?.hide()
        focusManager.clearFocus()
    }
    LaunchedEffect(keyboardVisible) { if (keyboardVisible) listState.scrollToItem(0) }
    val albumBackground = remember(dominentColor) {
        Brush.verticalGradient(
            colors = listOf(dominentColor, Color(AppBackground.toArgb()), Color(AppBackground.toArgb())),
            startY = 0f, endY = 2100f
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().background(albumBackground),
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.padding(horizontal = 12.dp),
                navigationIcon = {
                    Icon(
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                            if (searchReveal.expanded || searchQuery.isNotBlank()) {
                                searchQuery = ""
                                searchReveal.close()
                                keyboard?.hide()
                                focusManager.clearFocus()
                            } else navController.navigateUp()
                        }.padding(9.dp),
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "",
                        tint = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                ),
                title = {
                    Text(text = "")
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding())) {
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 138.dp),
                modifier = Modifier.fillMaxSize()
                    .nestedScroll(searchReveal.nestedScrollConnection)
            ) {
                item(key = "album_pull_search") {
                    CollectionPullSearch(
                        state = searchReveal, query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = uiLocalized("Buscar en el álbum")
                    )
                }
                item(key = "album_content") {
                    Column {
            if (!keyboardVisible && !searchReveal.expanded && searchQuery.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = if (tabletCollectionLayout) 260.dp else 440.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Identical image alignment, dimensions and spacing to the playlist header.
                    Spacer(modifier = Modifier.height(if (tabletCollectionLayout) 14.dp else 24.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GlideImage(
                            modifier = Modifier.size(if (tabletCollectionLayout) 212.dp else 230.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            model = album[0].coverUri,
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder),
                            contentDescription = uiLocalized("Carátula del álbum")
                        )
                    }
                    Spacer(modifier = Modifier.height(17.dp))
                    Text(
                        text = albumName, color = Color.White, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(13.dp))
                    Text(
                        text = albumArtists, color = Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp).clickable {
                            if (albumArtistList.size == 1) navController.navigate(artistRoute(albumArtistList[0]))
                            else if (albumArtistList.size > 1) showArtistSheet = true
                        }
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = "${albumSongs.size} canciones" +
                            if (album[0].time.isNotBlank()) " • ${album[0].time}" else "",
                        color = Color(0xFFB3B3B3), fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                            .padding(horizontal = 20.dp)
                    ) {
                        if (snackbarVisible) {
                            Box(modifier = Modifier.weight(1f)) {
                                Snackbar(showMessage = snackbarMessage)
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    painter = if (isAlbumLiked) painterResource(R.drawable.added)
                                        else painterResource(R.drawable.ic_add),
                                    contentDescription = uiLocalized("Guardar álbum"),
                                    tint = if (isAlbumLiked) Color(AppPalette.toArgb()) else Color.White,
                                    modifier = Modifier.size(27.dp).clickable {
                                        if (isAlbumLiked) {
                                            removeLikedAlbumId(context, album[0].id.toString())
                                            snackbarMessage = "Removed from Library"
                                        } else {
                                            addLikedAlbumId(context, album[0].id.toString())
                                            snackbarMessage = "Added to Library"
                                        }
                                        isAlbumLiked = isAlbumLiked(context, album[0].id.toString())
                                        snackbarVisible = true
                                    }
                                )
                                if (albumSongs.isNotEmpty()) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_queue_add),
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp).clickable {
                                            playerViewModel.addAllToQueue(albumSongs)
                                            android.widget.Toast.makeText(context,
                                                "${albumSongs.size} track(s) added to queue",
                                                android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        contentDescription = uiLocalized("Add to queue")
                                    )
                                    var albumDownloaded by remember(albumSongs) {
                                        mutableStateOf(SongPlayer.allDownloaded(albumSongs, context))
                                    }
                                    Icon(
                                        imageVector = if (albumDownloaded) Icons.Default.CheckCircle
                                            else ImageVector.vectorResource(R.drawable.ic_download),
                                        tint = if (albumDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                        modifier = Modifier.size(24.dp).clickable {
                                            com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollection(
                                                context = context, id = "album:$albumName|$artist",
                                                name = albumName, coverUri = album[0].coverUri,
                                                artists = album[0].artists, isPlaylist = false,
                                                songs = albumSongs
                                            )
                                            if (!albumDownloaded) {
                                                SongPlayer.downloadAll(albumSongs, context)
                                                snackbarMessage = "Downloading ${albumSongs.size} tracks…"
                                            } else snackbarMessage = "Album added to offline library"
                                            snackbarVisible = true
                                        },
                                        contentDescription = uiLocalized("Download album")
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_player_shuffle),
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp).clickable {
                                            albumViewModel.startShuffled(albumSongs)?.let { first ->
                                                albumViewModel.updateSongState(
                                                    first.coverUri, first.title, first.singer, true,
                                                    first.id, 0, albumName)
                                                SongPlayer.playSong(first.url, context, "song/${first.id}")
                                            }
                                        },
                                        contentDescription = uiLocalized("Shuffle play")
                                    )
                                }
                            }
                        }
                        if (albumSongs.isNotEmpty()) {
                            val playing = albumViewModel.currentSongPlayingState.value
                            val currentInList = albumSongs.any { it.id == albumViewModel.currentSongId.value }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(100.dp))
                                    .background(Color(0xFF1ED760)).clickable {
                                        if (currentInList) albumViewModel.setPlaying(!playing)
                                        else {
                                            val first = filteredSongs.firstOrNull() ?: albumSongs.first()
                                            albumViewModel.updateQueue(filteredSongs.ifEmpty { albumSongs })
                                            albumViewModel.updateSongState(
                                                first.coverUri, first.title, first.singer, true,
                                                first.id, 0, albumName)
                                            SongPlayer.playSong(first.url, context, "song/${first.id}")
                                        }
                                    }
                            ) {
                                Icon(
                                    modifier = Modifier.size(25.dp), tint = Color.Black,
                                    painter = painterResource(
                                        if (currentInList && playing) R.drawable.ic_playing
                                        else R.drawable.play_svgrepo_com),
                                    contentDescription = if (currentInList && playing) "Pause" else "Play"
                                )
                            }
                        }
                    }
                }
            }

            if (albumSongs.isNotEmpty()) {
                CollectionSortRow(
                    label = currentSort.getDescriptiveLabel(isDescending),
                    onClick = { showSortSheet = true }
                )

                if (filteredSongs.isEmpty() && searchQuery.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${uiLocalized("No matches found for")} \"$searchQuery\"",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    repeat(filteredSongs.size) { songIdx ->
                        val targetSong = filteredSongs[songIdx]

                        var isLiked by remember(targetSong.id) {
                            mutableStateOf(isSongLiked(context, targetSong.id.toString()))
                        }
                        var showSavedIn by remember { mutableStateOf(false) }
                        if (showSavedIn) {
                            SavedInSheet(
                                song = targetSong,
                                context = context,
                                onDismiss = { showSavedIn = false },
                                onLikedChanged = { isLiked = it },
                            )
                        }
                        val likeState = albumViewModel.likeState.value
                        LaunchedEffect(likeState) {
                            isLiked = isSongLiked(context, targetSong.id.toString())
                        }
                        val songId = targetSong.id

                        val currentPlayingIndicatorColor = if (songId == albumViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White

                        SwipeToPlayNextWrapper(
                            onPlayNext = {
                                playerViewModel.playNext(targetSong)
                                android.widget.Toast.makeText(
                                    context,
                                    "${targetSong.title} will play next",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppBackground)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onLongClick = { menuSong = targetSong },
                                        onClick = {
                                            albumViewModel.updateQueue(filteredSongs.drop(songIdx))
                                            albumViewModel.updateSongState(
                                                targetSong.coverUri,
                                                targetSong.title,
                                                targetSong.singer,
                                                true,
                                                targetSong.id,
                                                0,
                                                albumName
                                            )
                                            SongPlayer.playSong(targetSong.url, context, "song/${targetSong.id}")
                                        },
                                    )
                                    .padding(20.dp, 8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.width(200.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (targetSong.explicit) {
                                                com.music.spotui.ui.components.ExplicitBadge()
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = targetSong.title,
                                                color = currentPlayingIndicatorColor,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            com.music.spotui.ui.components.DownloadedSongIndicator(targetSong.id)
                                            Text(
                                                text = targetSong.singer,
                                                color = Color.Gray,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Icon(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                if (isLiked) {
                                                    removeLikedSongId(context, songId.toString())
                                                } else {
                                                    addLikedSongId(context, songId.toString())
                                                }
                                                isLiked = isSongLiked(context, songId.toString())
                                                albumViewModel.updateLikeState(!albumViewModel.likeState.value)
                                            },
                                            onLongClick = { showSavedIn = true },
                                        ),
                                    painter = if (isLiked) {
                                        painterResource(id = R.drawable.added)
                                    } else {
                                        painterResource(id = R.drawable.ic_add)
                                    },
                                    tint = if (isLiked) {
                                        Color.White
                                    } else {
                                        Color.Gray
                                    },
                                    contentDescription = ""
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(220.dp))
                    } // album content column
                } // album content item
            } // lazy album list
        com.music.spotui.ui.components.FastScrollbarForLazyList(
            state = listState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
        )
    }

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
                        text = uiLocalized("Sort by"),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 12.dp)
                    )
                    androidx.compose.material3.HorizontalDivider(color = Color(0xFF2A2A2A))
                    Spacer(modifier = Modifier.height(4.dp))
                    AlbumSortOption.entries.forEach { option ->
                        val isSelected = option == currentSort
                        val icon = when (option) {
                            AlbumSortOption.DEFAULT -> Icons.Default.DateRange
                            AlbumSortOption.TITLE -> Icons.AutoMirrored.Filled.List
                            AlbumSortOption.ARTIST -> Icons.Default.Person
                            AlbumSortOption.DURATION -> Icons.Default.Menu
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (currentSort == option) {
                                        isDescending = !isDescending
                                    } else {
                                        currentSort = option
                                        isDescending = (option != AlbumSortOption.DEFAULT)
                                    }
                                    setAlbumSortOption(context, albumName, currentSort, isDescending)
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
                                text = if (isSelected) option.getDescriptiveLabel(isDescending) else option.getDescriptiveLabel(option != AlbumSortOption.DEFAULT),
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
