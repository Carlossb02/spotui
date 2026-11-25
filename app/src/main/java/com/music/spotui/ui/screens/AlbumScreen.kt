package com.music.spotui.ui.screens

import androidx.compose.ui.draw.shadow
import com.music.spotui.ui.components.scrollingCollectionBackground
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
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.launch


@Composable
fun AlbumScreen(navController: NavController, albumName: String, artist: String = "", syntheticId: String? = null) {
    val albumViewModel : AlbumViewModel = hiltViewModel()
    val songs by albumViewModel.songs.collectAsState()
    val albums by albumViewModel.albums.collectAsState()

    // Load this album's actual tracks from Spotify (by name, disambiguated by artist).
    LaunchedEffect(albumName, artist, syntheticId) {
        if (syntheticId != null) albumViewModel.loadSyntheticAlbum(syntheticId)
        else albumViewModel.loadAlbumSongs(albumName, artist)
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
    val resolvedAlbum by albumViewModel.albumDetail.collectAsState()

    val albumByName : Map<String, List<AlbumsModel>> = albums.groupBy { it.name }
    // The album may not be in the cached new-releases list (e.g. opened from
    // search) — fall back to a model built from the album's first track.
    val album : List<AlbumsModel> = resolvedAlbum?.let { listOf(it) }
        ?: albumByName[albumName]?.filter { artist.isBlank() || it.artists.equals(artist, ignoreCase = true) }?.takeIf { it.isNotEmpty() }
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
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollectionAsync(
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
    }

    var dominentColor by remember {
        mutableStateOf(Color(AppBackground.toArgb()))
    }
    LaunchedEffect(album[0].coverUri) {
        Palette().extractSecondColorFromCoverUrl(context = context, album[0].coverUri) { color ->
            dominentColor = color
        }
    }

    val albumSpotifyId = album[0].spotifyAlbumId
    var albumSaved by remember(albumSpotifyId, album[0].id) {
        mutableStateOf(com.music.spotui.data.api.AlbumLibrary.cached(context, albumSpotifyId, album[0].id))
    }
    var albumSaveKnown by remember(albumSpotifyId) { mutableStateOf(false) }
    var albumSaveBusy by remember(albumSpotifyId) { mutableStateOf(false) }
    var albumCheckRevision by remember { mutableStateOf(0) }
    var albumSaveGeneration by remember(albumSpotifyId) { mutableStateOf(0) }
    val albumSaveScope = androidx.compose.runtime.rememberCoroutineScope()
    val albumLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(albumLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) albumCheckRevision++
        }
        albumLifecycle.lifecycle.addObserver(observer)
        onDispose { albumLifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(albumSpotifyId, albumCheckRevision) {
        val generation = albumSaveGeneration
        val saved = com.music.spotui.data.api.AlbumLibrary.read(context, albumSpotifyId, album[0].id)
        if (saved != null && generation == albumSaveGeneration && !albumSaveBusy) {
            albumSaved = saved; albumSaveKnown = true
        }
    }

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

    val totalDurationSec = remember(albumSongs) { albumSongs.sumOf { it.durationMs.toLong() } / 1000L }
    val trackNumbers = remember(albumSongs) { albumSongs.mapIndexed { index, track -> track.url to index + 1 }.toMap() }
    val releaseKind = when (album[0].type.lowercase()) {
        "single" -> "Single"
        "compilation" -> uiLocalized("Compilación")
        else -> uiLocalized("Álbum")
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
    val stableSongKeys=remember(filteredSongs){com.music.spotui.ui.utils.stableSongKeys(filteredSongs)}
        val listState = rememberLazyListState()
        com.music.spotui.ui.components.VisibleSongPreload(listState, remember(filteredSongs, stableSongKeys) { filteredSongs.mapIndexed { i, song -> (stableSongKeys.getOrNull(i) ?: song.id.toString()) to song } })
    val collapsedTitle = com.music.spotui.ui.components.rememberCollectionTitleState(listState, "album_header")
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
    LaunchedEffect(keyboardVisible,searchReveal.expanded) { if (keyboardVisible && searchReveal.expanded) listState.scrollToItem(0) }

    val collectionGlass = com.music.spotui.ui.components.rememberCollectionGlassBackdrop()
    Scaffold(
        modifier = Modifier.fillMaxSize().scrollingCollectionBackground(
                topColor = dominentColor, baseColor = Color(AppBackground.toArgb()),
                listState = listState, headerKey = "album_header", coverUri = album[0].coverUri, glass = collectionGlass),
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.fillMaxWidth(),
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
                    containerColor = if (collapsedTitle.collapsed) com.music.spotui.ui.components.collectionToolbarColor(dominentColor, AppBackground) else Color.Transparent,
                    titleContentColor = Color.White,
                ),
                title = {
                    if (collapsedTitle.collapsed) Text(
                        text = albumName, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    )
                }
            )
        }
    ) { innerPadding ->
        com.music.spotui.ui.components.CollectionContentViewport(modifier = Modifier.fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding())) {
            LazyColumn(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = com.music.spotui.ui.components.LocalCollectionInset.current,
                        end = com.music.spotui.ui.components.LocalCollectionInset.current,
                        bottom = 138.dp),
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
                item(key = "album_header") {
                    Column {
            if (!searchReveal.expanded && searchQuery.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = if (tabletCollectionLayout) 260.dp else 360.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Identical image alignment, dimensions and spacing to the playlist header.
                    Spacer(modifier = Modifier.height(if (tabletCollectionLayout) 14.dp else 24.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GlideImage(
                            modifier = Modifier.size(com.music.spotui.ui.components.collectionCoverSize())
                                .shadow(16.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)),
                            model = album[0].coverUri,
                            contentScale = ContentScale.Crop,
                            failure = placeholder(R.drawable.placeholder),
                            contentDescription = uiLocalized("Carátula del álbum")
                        )
                    }
                    Spacer(modifier = Modifier.height(17.dp))
                    Text(
                        text = albumName, color = Color.White, fontSize = 32.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp).then(collapsedTitle.titleModifier)
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
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if(albumSongs.any { it.explicit })com.music.spotui.ui.components.ExplicitBadge()
                    Text(
                        text = listOfNotNull(
                            releaseKind,
                            "${albumSongs.size} ${uiLocalized("canciones")}",
                            totalDurationSec.takeIf { it > 0 }?.let { "${it / 60} min" },
                        ).joinToString(" • "),
                        color = Color(0xFFB3B3B3), fontSize = 12.sp,
                    )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
val playing = albumViewModel.currentSongPlayingState.value
val currentInList = albumSongs.any { it.id == albumViewModel.currentSongId.value }
com.music.spotui.ui.components.CollectionActionBar(
 navController = navController,
 glass = collectionGlass,
 storySongs = albumSongs, storyTitle = albumName, storyCover = album[0].coverUri,
 onShare = if (albumSpotifyId.isNotBlank()) ({
     val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
         .putExtra(android.content.Intent.EXTRA_TEXT, "https://open.spotify.com/album/$albumSpotifyId")
     context.startActivity(android.content.Intent.createChooser(intent, "Compartir álbum"))
 }) else null,
 savedControl = {
     if (albumSpotifyId.isNotBlank()) {
         com.music.spotui.ui.components.LibrarySaveIcon(
                          saved = albumSaved,
                          enabled = !albumSaveBusy && albumSpotifyId.isNotBlank(),
                          onClick = {
                              if (!albumSaveBusy) {
                                  val desiredSaved = !albumSaved
                                  albumSaveBusy = true
                                  albumSaveGeneration++
                                  albumSaveScope.launch {
                                      try {
                                          if (com.music.spotui.data.api.AlbumLibrary.setSaved(
                                                  context, albumSpotifyId, album[0].id, desiredSaved)) {
                                              albumSaved = desiredSaved
                                              albumSaveKnown = true
                                              snackbarMessage = if (albumSaved) "Álbum añadido a tu biblioteca" else "Álbum quitado de tu biblioteca"
                                          } else snackbarMessage = "No se pudo guardar el cambio."
                                          snackbarVisible = true
                                      } finally { albumSaveBusy = false }
                                  }
                              }
                          },
                      )
     }
 },
 accent = dominentColor,
 sortMode = currentSort.name, sortDescending = isDescending,
 modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
 enabled = albumSongs.isNotEmpty(), playing = currentInList && playing,
 download = { com.music.spotui.ui.components.CollectionDownloadButton(albumSongs, "Descargar") { complete ->
                                        com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollectionAsync(
                                            context = context, id = "album:$albumName|$artist",
                                            name = albumName, coverUri = album[0].coverUri,
                                            artists = album[0].artists, isPlaylist = false,
                                            songs = albumSongs
                                        )
                                        if (!complete) {
                                            SongPlayer.downloadAll(albumSongs, context)
                                            snackbarMessage = "Downloading ${albumSongs.size} tracks…"
                                        } else snackbarMessage = "Album added to offline library"
                                        snackbarVisible = true
                                    } },
 onSort = { showSortSheet = true },
 onQueue = { playerViewModel.addAllToQueue(albumSongs) },
 onShuffle = {
                                albumViewModel.startShuffled(albumSongs, sourceName = albumName)?.let { first ->
                                    albumViewModel.updateSongState(
                                        first.coverUri, first.title, first.singer, true,
                                        first.id, 0, albumName)
                                    SongPlayer.playSong(first.url, context, "song/${first.id}")
                                }
                             },
 onPlay = {
                                        if (currentInList) albumViewModel.setPlaying(!playing)
                                        else {
                                            val first = filteredSongs.firstOrNull() ?: albumSongs.first()
                                            albumViewModel.updateQueue(filteredSongs.ifEmpty { albumSongs }, sourceName = albumName)
                                            albumViewModel.updateSongState(
                                                first.coverUri, first.title, first.singer, true,
                                                first.id, 0, albumName)
                                            SongPlayer.playSong(first.url, context, "song/${first.id}")
                                        }
                                     }
)
                }
            }

                    } // album header column
                } // album header item
            if (albumSongs.isNotEmpty()) {
                item(key = "album_sort") {
                    if (searchReveal.expanded || searchQuery.isNotBlank()) CollectionSortRow(
                        label = currentSort.getDescriptiveLabel(isDescending),
                        onClick = { showSortSheet = true }
                    )
                }
                if (filteredSongs.isEmpty() && searchQuery.isNotBlank()) {
                    item(key = "album_empty_search") {
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
                    }
                } else {
                    itemsIndexed(filteredSongs, key = { index, track -> stableSongKeys.getOrNull(index) ?: track.id.toString() }) { songIdx, targetSong ->

                        var isLiked by remember(targetSong.id) {
                            mutableStateOf(isSongLiked(context, targetSong.id.toString()))
                        }
                        val isSaved = com.music.spotui.ui.components.rememberTrackSaved(targetSong, isLiked)
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

                        val currentPlayingIndicatorColor = if (songId == albumViewModel.currentSongId.value) Color(0xFF1ED760) else Color.White

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
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onLongClick = { menuSong = targetSong },
                                        onClick = {
                                            albumViewModel.updateQueue(filteredSongs, startSongId = targetSong.id, sourceName = albumName)
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
                                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                                ) {
                                    com.music.spotui.ui.components.TrackNumberOrPlaying(targetSong,
                                        (trackNumbers[targetSong.url] ?: songIdx + 1).toString(),Modifier.width(28.dp).padding(top=3.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                            com.music.spotui.ui.components.SongExplicitBadge(targetSong)
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

                                if (tabletCollectionLayout && targetSong.durationMs > 0) {
                                    Text(
                                        text = "%d:%02d".format(targetSong.durationMs / 60000, (targetSong.durationMs / 1000) % 60),
                                        color = Color.Gray, fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 18.dp),
                                    )
                                }
                                Icon(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                if (isSaved) { showSavedIn = true; return@combinedClickable }
                                                addLikedSongId(context, songId.toString())
                                                isLiked = isSongLiked(context, songId.toString())
                                                albumViewModel.updateLikeState(!albumViewModel.likeState.value)
                                            },
                                            onLongClick = { showSavedIn = true },
                                        ),
                                    painter = if (isSaved) {
                                        painterResource(id = R.drawable.added)
                                    } else {
                                        painterResource(id = R.drawable.ic_add)
                                    },
                                    tint = if (isSaved) {
                                        Color(0xFF1ED760)
                                    } else {
                                        Color.Gray
                                    },
                                    contentDescription = uiLocalized("Guardar canción")
                                )
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = uiLocalized("Más opciones"), tint = Color.Gray,
                                    modifier = Modifier.padding(start = 12.dp).size(28.dp)
                                        .clickable { menuSong = targetSong },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "album_footer") {
                Text(
                    text = albumName + " • " + albumArtists,
                    color = Color.Gray, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
            } // lazy album list
        com.music.spotui.ui.components.FastScrollbarForLazyList(
            state = listState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
        )
    }

        if (showSortSheet) com.music.spotui.ui.components.CollectionSortSheet(
 selected = currentSort.name, descending = isDescending, choices = listOf("DEFAULT" to "Orden de pistas", "TITLE" to "Título", "ARTIST" to "Artista", "DURATION" to "Duración"),
 onDismiss = { showSortSheet = false },
 onSelected = { key ->
 val option = AlbumSortOption.valueOf(key)
 isDescending = if (currentSort == option) !isDescending else option != AlbumSortOption.DEFAULT
 currentSort = option
 setAlbumSortOption(context, albumName, currentSort, isDescending)
 showSortSheet = false
 })
    }
}
