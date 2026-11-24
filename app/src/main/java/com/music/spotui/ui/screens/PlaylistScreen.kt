package com.music.spotui.ui.screens

import androidx.compose.ui.draw.shadow
import com.music.spotui.ui.components.scrollingCollectionBackground
import com.music.spotui.util.uiLocalized

import androidx.activity.compose.BackHandler
import android.content.Context
import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.music.spotui.data.api.PlaylistAccess
import com.music.spotui.data.api.PlaylistAccessInfo
import com.music.spotui.ui.components.PlaylistSpotifyManagementSection
import com.music.spotui.ui.components.PlaylistSpotifyManagementSheet
import com.music.spotui.ui.components.PlaylistSpotifyMenuItems
import com.music.spotui.ui.components.openSpotifyPlaylist
import com.music.spotui.ui.components.openSpotifyPlaylistTab
import com.music.spotui.ui.components.spotifyPlaylistUrl
import com.music.spotui.data.preferences.LocalPlaylistPref
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.CollectionPullSearch
import com.music.spotui.ui.components.CollectionSortRow
import com.music.spotui.ui.components.CollectionSortSheet
import com.music.spotui.ui.components.collectionSortLabel
import com.music.spotui.ui.components.rememberCollectionSearchState
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.components.SwipeToPlayNextWrapper
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.data.preferences.PlaylistSortOption
import com.music.spotui.data.preferences.getPlaylistSortOption
import com.music.spotui.data.preferences.isPlaylistSortDescending
import com.music.spotui.data.preferences.setPlaylistSort
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.viewmodel.PlaylistViewModel

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(navController: NavController, playlistId: String, playlistName: String = "") {

    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val songsResp by playlistViewModel.songs.collectAsState()
    val playlistResp by playlistViewModel.playlist.collectAsState()
    val context = LocalContext.current
    val tabletCollectionLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600 &&
        LocalConfiguration.current.screenWidthDp >= 840

    val syncViewToken=remember {java.util.UUID.randomUUID().toString()}
    DisposableEffect(syncViewToken) {
        com.music.spotui.sync.GoogleDeviceSync.libraryVisible(syncViewToken,true)
        onDispose {com.music.spotui.sync.GoogleDeviceSync.libraryVisible(syncViewToken,false)}
    }

    // Trigger dinámico para refrescar la UI automáticamente cuando una descarga termina
    val downloadRefreshTrigger by com.music.spotui.data.preferences.downloadsVersion.collectAsState()

    val isLocalPlaylist = playlistId.startsWith("local_pl_")
    val linkedSpotifyId = if (isLocalPlaylist) {
        val links = remember(context, playlistId) { LocalPlaylistPref.observeSyncedSpotifyId(context, playlistId) }
        val link by links.collectAsState(initial = null)
        link
    } else null
    val profilePlaylistId = if (isLocalPlaylist) linkedSpotifyId.orEmpty() else playlistId
    var playlistAccess by remember(profilePlaylistId) { mutableStateOf(PlaylistAccessInfo()) }
    LaunchedEffect(playlistId) { playlistViewModel.loadPlaylist(playlistId) }
    var refreshProfile by remember(profilePlaylistId) { mutableStateOf(0) }
    LaunchedEffect(context, profilePlaylistId, refreshProfile) {
        if (profilePlaylistId.isNotBlank()) {
            if (refreshProfile == 0) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PlaylistAccess.cached(context, profilePlaylistId)
                }?.let { playlistAccess = it }
            }
            playlistAccess = PlaylistAccess.load(context, profilePlaylistId)
        }
    }
    val spotifyMirrorStatuses by com.music.spotui.data.api.LocalSpotifyMirror.statuses.collectAsState()
    val spotifyMirrorStatus = spotifyMirrorStatuses[playlistId]
    val canEditPlaylist = isLocalPlaylist || playlistAccess.canEdit
    val externalReadOnly = !isLocalPlaylist && !canEditPlaylist
    var favoritePlaylist by remember(playlistId) {
        mutableStateOf(com.music.spotui.data.preferences.FavoritePlaylistsPref.isFavorite(context, playlistId))
    }
    val belongsToPlaylist = playlistViewModel.snapshotBelongsTo(playlistId)
    val songs=if (belongsToPlaylist) (songsResp as? Response.Success)?.data.orEmpty() else emptyList()
    val playlist = (playlistResp as? Response.Success)?.data?.takeIf { belongsToPlaylist }
        ?: AlbumsModel(
            id = playlistId.hashCode() and 0x7fffffff,
            artists = "",
            coverUri = "",
            name = playlistName,
            time = "",
        )

    val spotifyUrl = remember(profilePlaylistId) { spotifyPlaylistUrl(profilePlaylistId) }
    val visualCover = if (profilePlaylistId.isNotBlank()) {
        playlistAccess.coverUri.ifBlank { playlist.coverUri }
    } else playlist.coverUri
    val spotifyDescription = remember(playlistAccess.description, playlistId, linkedSpotifyId) {
        if (linkedSpotifyId.isNullOrBlank()) ""
        else androidx.core.text.HtmlCompat.fromHtml(playlistAccess.description,
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            .replace("Spotui-ID: " + playlistId, "").trim()
    }
    var showSpotifyManagement by remember(profilePlaylistId) { mutableStateOf(false) }
    var manageInSpotify by remember(profilePlaylistId) { mutableStateOf<PlaylistSpotifyManagementSection?>(null) }
    var awaitingSpotifyReturn by remember(profilePlaylistId) { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, playlistId, profilePlaylistId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && awaitingSpotifyReturn) {
                awaitingSpotifyReturn = false
                refreshProfile++
                if (isLocalPlaylist) {
                    com.music.spotui.data.api.LocalSpotifyMirror.refresh(context, playlistId)
                } else {
                    com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(playlistId)
                    playlistViewModel.reloadPlaylist(playlistId)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val openInSpotify: () -> Unit = {
        spotifyUrl?.let { awaitingSpotifyReturn = openSpotifyPlaylist(context, it) }
    }
    if (showSpotifyManagement || manageInSpotify != null) {
        PlaylistSpotifyManagementSheet(
            initialSection = manageInSpotify,
            onDismiss = {
                showSpotifyManagement = false
                manageInSpotify = null
            },
            onOpenSpotify = openInSpotify,
            onOpenTab = { spotifyUrl?.let { awaitingSpotifyReturn = openSpotifyPlaylistTab(context, it) } },
        )
    }

    val togglePlaylistFavorite: () -> Unit = {
        favoritePlaylist = com.music.spotui.data.preferences.FavoritePlaylistsPref.toggle(
            context, playlistId, playlist.name.ifBlank { playlistName }, playlist.coverUri,
            playlistAccess.ownerName.ifBlank { playlist.artists }
        )
        android.widget.Toast.makeText(context,
            if (favoritePlaylist) "Playlist añadida a tu biblioteca" else "Playlist quitada de tu biblioteca",
            android.widget.Toast.LENGTH_SHORT).show()
    }


    LaunchedEffect(songs, playlist, songsResp, playlistResp) {
        if (songsResp is Response.Success && playlistResp is Response.Success && songs.isNotEmpty() && playlist != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollectionAsync(
                context = context,
                id = playlistId,
                name = playlist.name,
                coverUri = playlist.coverUri,
                artists = playlist.artists,
                isPlaylist = true,
                songs = songs
            )
            }
        }
    }



    var searchQuery by remember(playlistId) { mutableStateOf("") }
    var currentSort by remember(playlistId) { mutableStateOf(getPlaylistSortOption(context, playlistId)) }
    var isDescending by remember(playlistId) { mutableStateOf(isPlaylistSortDescending(context, playlistId)) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showPlaylistActions by remember { mutableStateOf(false) }
    var showCopyPlaylist by remember { mutableStateOf(false) }
    var showAddMusic by remember { mutableStateOf(false) }
    if (showAddMusic && canEditPlaylist) {
        com.music.spotui.ui.components.PlaylistAddMusicSheet(
            playlistId = playlistId,
            onDismiss = { showAddMusic = false },
            onAdded = {
                com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(playlistId)
                playlistViewModel.reloadPlaylist(playlistId)
            }
        )
    }

    if (showCopyPlaylist) {
        com.music.spotui.ui.components.PlaylistCopySheet(
            context = context,
            sourceName = playlist.name.ifBlank { playlistName },
            songs = songs,
            onDismiss = { showCopyPlaylist = false },
        )
    }

    val filteredSongs = remember(songs, searchQuery, currentSort, isDescending) {
        val filtered = if (searchQuery.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.singer.contains(searchQuery, ignoreCase = true)
            }
        }

        when (currentSort) {
            PlaylistSortOption.DATE -> if (isDescending) filtered.reversed() else filtered
            PlaylistSortOption.TITLE -> if (isDescending) filtered.sortedByDescending { it.title.lowercase() } else filtered.sortedBy { it.title.lowercase() }
            PlaylistSortOption.ARTIST -> if (isDescending) filtered.sortedByDescending { it.singer.lowercase() } else filtered.sortedBy { it.singer.lowercase() }
            PlaylistSortOption.ALBUM -> if (isDescending) filtered.sortedByDescending { it.album.lowercase() } else filtered.sortedBy { it.album.lowercase() }
        }
    }

    // Keys are stable across insertions and deletions; O(n) even for long playlists.
    val stableSongKeys=remember(filteredSongs){com.music.spotui.ui.utils.stableSongKeys(filteredSongs)}

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog && isLocalPlaylist && linkedSpotifyId == "") {
        val currentName = playlist.name.ifBlank { playlistName }
        var newPlaylistNameInput by remember(currentName) { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Color(0xFF282828),
            title = { Text(uiLocalized("Rename Playlist"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                TextField(
                    value = newPlaylistNameInput,
                    onValueChange = { newPlaylistNameInput = it },
                    placeholder = { Text(uiLocalized("Playlist name"), color = Color.Gray) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF383838),
                        unfocusedContainerColor = Color(0xFF383838),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF1ED760),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
            },
            confirmButton = {
                Text(
                    uiLocalized("Save"),
                    color = Color(0xFF1ED760),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable {
                            val name = newPlaylistNameInput.trim()
                            if (name.isNotBlank()) {
                                com.music.spotui.data.preferences.LocalPlaylistPref.renamePlaylist(context, playlistId, name)
                                com.music.spotui.data.api.Api.HomeCache.library = null
                                com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(playlistId)
                                playlistViewModel.reloadPlaylist(playlistId)
                            }
                            showRenameDialog = false
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    uiLocalized("Cancel"),
                    color = Color.Gray,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { showRenameDialog = false }
                        .padding(8.dp)
                )
            }
        )
    }

    if (showDeleteDialog && isLocalPlaylist) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF282828),
            title = { Text(uiLocalized("Delete Playlist"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(uiLocalized("Are you sure you want to delete this playlist?"), color = Color.LightGray) },
            confirmButton = {
                Text(
                    uiLocalized("Delete"),
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable {
                            com.music.spotui.data.preferences.LocalPlaylistPref.deletePlaylist(context, playlistId)
                            com.music.spotui.data.api.Api.HomeCache.library = null
                            com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(playlistId)
                            showDeleteDialog = false
                            navController.navigateUp()
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    uiLocalized("Cancel"),
                    color = Color.Gray,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { showDeleteDialog = false }
                        .padding(8.dp)
                )
            }
        )
    }

    val playlistArtists = playlist.artists
    val playlistArtistList = remember(playlistArtists) {
        playlistArtists.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    var showArtistSheet by remember { mutableStateOf(false) }
    if (showArtistSheet) {
        ArtistsSheet(
            artistNames = playlistArtistList,
            context = context,
            onDismiss = { showArtistSheet = false },
            navController = navController,
        )
    }

    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            currentPlaylistId = playlistId,
            canEditCurrentPlaylist = canEditPlaylist,
            onSongRemovedFromPlaylist = {
                com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(playlistId)
                playlistViewModel.reloadPlaylist(playlistId)
            },
            onDismiss = { menuSong = null },
        )
    }

    val listState=androidx.compose.runtime.key(playlistId) {androidx.compose.foundation.lazy.rememberLazyListState()}
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxSize().background(Color(AppBackground.toArgb()))
    ) {
        if (songsResp is Response.Loading && playlistResp is Response.Loading) {
            Loader()
            return@Surface
        }

        var snackbarMessage by remember { mutableStateOf("") }
        var snackbarVisible by remember { mutableStateOf(false) }
        LaunchedEffect(snackbarVisible) {
            if (snackbarVisible) {
                kotlinx.coroutines.delay(1500)
                snackbarVisible = false
            }
        }

        var dominentColor by remember { mutableStateOf(Color(AppBackground.toArgb())) }
        LaunchedEffect(visualCover) {
            Palette().extractSecondColorFromCoverUrl(context = context, visualCover) { color ->
                dominentColor = color
            }
        }


        com.music.spotui.ui.components.VisibleSongPreload(listState, remember(filteredSongs) { filteredSongs.mapIndexed { i, song -> stableSongKeys[i] to song } })
    val collapsedTitle = com.music.spotui.ui.components.rememberCollectionTitleState(listState, "playlist_header")
        val searchReveal = rememberCollectionSearchState(listState, playlistId)
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        Scaffold(
            modifier = Modifier.fillMaxSize().scrollingCollectionBackground(
                topColor = dominentColor, baseColor = Color(AppBackground.toArgb()),
                listState = listState, headerKey = "playlist_header", coverUri = visualCover),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.fillMaxWidth(),
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
                        containerColor = if (collapsedTitle.collapsed) com.music.spotui.ui.components.collectionToolbarColor(dominentColor, AppBackground) else Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = { if (collapsedTitle.collapsed) Text(text = playlist.name.ifBlank { playlistName },
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                    actions = {
                        androidx.compose.material3.IconButton(
                            onClick = { showPlaylistActions = true },
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = uiLocalized("Más opciones de la lista"),
                                tint = Color.White)
                        }
                    }
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
            LaunchedEffect(keyboardVisible,searchReveal.expanded) {
                if (keyboardVisible && searchReveal.expanded) listState.scrollToItem(0)
            }
            // Keep the search field in place; collapse only the expensive artwork/
            // metadata header while the IME is visible, leaving room for results.
            searchReveal.onDismissByScroll = {
                keyboardController?.hide()
                focusManager.clearFocus()
                searchQuery = ""
            }
            com.music.spotui.ui.components.CollectionContentViewport(modifier = Modifier.fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())) {
                LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = com.music.spotui.ui.components.LocalCollectionInset.current,
                        end = com.music.spotui.ui.components.LocalCollectionInset.current,
                        bottom = 138.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(searchReveal.nestedScrollConnection)
                ) {
                    item(key = "playlist_reveal_search") {
                        CollectionPullSearch(
                            state = searchReveal,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = uiLocalized("Buscar en la lista")
                        )
                    }
                    if (!searchReveal.expanded && searchQuery.isBlank()) {
                    item(key = "playlist_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (tabletCollectionLayout) 260.dp else 360.dp)
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            // Both handheld and tablet playlist headers share
                            // Spotify's centered artwork, with metadata left-aligned
                            // beneath it. The tablet's content pane is narrower when
                            // the companion player is visible.
                            Spacer(modifier = Modifier.height(if (tabletCollectionLayout) 14.dp else 24.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.runtime.key(playlistId, visualCover) {
                                    GlideImage(
                                        modifier = Modifier
                                            .size(com.music.spotui.ui.components.collectionCoverSize())
                                            .shadow(16.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)),
                                        model = visualCover,
                                        contentScale = ContentScale.Crop,
                                        failure = placeholder(R.drawable.placeholder),
                                        contentDescription = "Carátula de ${playlist.name}",
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(17.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 20.dp).then(collapsedTitle.titleModifier)
                            ) {
                                Text(
                                    text = playlist.name.ifBlank { playlistName },
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            if (spotifyDescription.isNotBlank()) {
                                Text(spotifyDescription, color = Color.LightGray, fontSize = 13.sp, maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 6.dp))
                            }
                            Spacer(modifier = Modifier.height(13.dp))
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLocalPlaylist && linkedSpotifyId == "") {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null,
                                        tint = Color(0xFF1ED760), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Lista de Spotui", color = Color.White,
                                        fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                } else {
                                    // Only identities reported by Spotify are shown. Unknown
                                    // collaborators are never represented with fake profiles.
                                    val profiles = buildList {
                                        if (playlistAccess.ownerName.isNotBlank()) add(
                                            playlistAccess.ownerName to playlistAccess.ownerImage
                                        )
                                        playlistAccess.contributors.forEach {
                                            add(it.displayName to it.imageUrl)
                                        }
                                    }.distinctBy { it.first }.take(4)
                                    if (profiles.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy((-7).dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            profiles.forEach { (profileName, photo) ->
                                                Box(Modifier.size(29.dp).border(2.dp, Color(0xFF202020), CircleShape)
                                                    .clip(CircleShape).background(Color(0xFF494949)),
                                                    contentAlignment = Alignment.Center) {
                                                    if (!photo.isNullOrBlank()) {
                                                        GlideImage(model = photo, contentDescription = profileName,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize().clip(CircleShape))
                                                    } else {
                                                        Text(profileName.take(1).uppercase(), color = Color.White,
                                                            fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                    }
                                    Text(
                                        text = (listOf(playlistAccess.ownerName.ifBlank { if (isLocalPlaylist) "" else playlist.artists }) +
                                            playlistAccess.contributors.map { it.displayName })
                                            .filter { it.isNotBlank() }.distinct().joinToString(" · "),
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(7.dp))
                            Text(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                text = (if (songs.size == 1) "1 canción" else "${songs.size} canciones") +
                                    if (!isLocalPlaylist && playlist.time.isNotBlank()) " • ${playlist.time}" else "",
                                color = Color(0xFFB3B3B3),
                                fontSize = 12.sp,
                            )
                            if (isLocalPlaylist && (linkedSpotifyId == "" ||
                                spotifyMirrorStatus?.phase == com.music.spotui.data.api.LocalSpotifyMirror.Phase.FAILED)) {
                                val syncLabel = when (spotifyMirrorStatus?.phase) {
                                    com.music.spotui.data.api.LocalSpotifyMirror.Phase.SYNCING -> "Sincronizando con Spotify"
                                    else -> spotifyMirrorStatus?.details ?: "Pendiente de sincronizar con Spotify"
                                }
                                Text(syncLabel, color = Color.White.copy(alpha = .7f), fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { com.music.spotui.data.api.LocalSpotifyMirror.retry(context, playlistId) }
                                        .padding(horizontal = 20.dp, vertical = 6.dp))
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            if (externalReadOnly) {
                            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
if (externalReadOnly) {
                                            com.music.spotui.ui.components.LibrarySaveIcon(
                                                saved = favoritePlaylist, onClick = { togglePlaylistFavorite() },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
}
Spacer(Modifier.height(8.dp))
}
val playing = playlistViewModel.currentSongPlayingState.value
val currentInList = songs.any { it.id == playlistViewModel.currentSongId.value }
com.music.spotui.ui.components.CollectionActionBar(
 coverUri = visualCover,
 accent = dominentColor,
 sortMode = currentSort.name, sortDescending = isDescending,
 modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
 enabled = songs.isNotEmpty(), playing = currentInList && playing,
 download = { com.music.spotui.ui.components.CollectionDownloadButton(songs, "Descargar") { complete ->
                                                com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollectionAsync(
                                                    context = context,
                                                    id = playlistId,
                                                    name = playlist.name,
                                                    coverUri = playlist.coverUri,
                                                    artists = playlist.artists,
                                                    isPlaylist = true,
                                                    songs = songs
                                                )
                                                if (!complete) {
                                                    SongPlayer.downloadAll(songs, context)
                                                } else {
                                                    snackbarMessage = "Playlist added to offline library"
                                                    snackbarVisible = true
                                                }
                                            } },
 onSort = { showSortSheet = true },
 onQueue = { playerViewModel.addAllToQueue(songs) },
 onShuffle = {
                                        playlistViewModel.startShuffled(songs, sourceName = playlist.name)?.let { first ->
                                            playlistViewModel.updateSongState(
                                                first.coverUri,
                                                first.title,
                                                first.singer,
                                                true,
                                                first.id,
                                                0,
                                                playlist.name,
                                            )
                                            SongPlayer.playSong(first.url, context, "song/${first.id}")
                                        }
                                     },
 onPlay = {
                                                when {
                                                    currentInList -> playlistViewModel.setPlaying(!playing)
                                                    filteredSongs.isNotEmpty() -> {
                                                        playlistViewModel.updateQueue(filteredSongs, sourceName = playlist.name)
                                                        playlistViewModel.updateSongState(
                                                            filteredSongs[0].coverUri,
                                                            filteredSongs[0].title,
                                                            filteredSongs[0].singer,
                                                            true,
                                                            filteredSongs[0].id,
                                                            0,
                                                            playlist.name
                                                        )
                                                        SongPlayer.playSong(filteredSongs[0].url, context, "song/${filteredSongs[0].id}")
                                                    }
                                                }
                                             }
)
                        }
                    }

                    }



                    item(key = "playlist_sort") {
                        // Local playlists already have a visible Spotify-style 'Ordenar' pill.
                        if (songs.isNotEmpty() && (searchReveal.expanded || searchQuery.isNotBlank())) {
                            CollectionSortRow(
                                label = collectionSortLabel(currentSort.name, isDescending),
                                onClick = { showSortSheet = true }
                            )
                        }
                    }

                    if (!canEditPlaylist && !playlistId.startsWith("local_pl_")) item(key = "playlist_read_only") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, null, tint = Color(0xFF1ED760),
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Copiar o añadir canciones a otra lista",
                                color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.clickable { showCopyPlaylist = true })
                        }
                    }
                    if (canEditPlaylist) item(key = "add_music_to_playlist") {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showAddMusic = true }
                                .padding(horizontal = 20.dp, vertical = 8.dp).height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = .08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Default.Add,
                                    contentDescription = null, tint = Color.White,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.add_music_to_playlist),
                                color = Color.White, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    itemsIndexed(filteredSongs, key = { index, _ -> stableSongKeys[index] }) { index, song ->
                        val currentColor = if (song.id == playlistViewModel.currentSongId.value)
                            Color(0xFF1ED760) else Color.White

                        // Forzar recomposición si el trigger de descargas cambia

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
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onLongClick = { menuSong = song },
                                        onClick = {
                                            playlistViewModel.updateQueue(filteredSongs, startSongId = song.id, sourceName = playlist.name)
                                            playlistViewModel.updateSongState(
                                                song.coverUri,
                                                song.title,
                                                song.singer,
                                                true,
                                                song.id,
                                                0,
                                                playlist.name
                                            )
                                            SongPlayer.playSong(song.url, context, "song/${song.id}")
                                        },
                                    )
                                    .padding(20.dp, 8.dp)
                            ) {
                                com.music.spotui.ui.components.PlayingTrackCover(song)
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = song.title,
                                            color = currentColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        com.music.spotui.ui.components.DownloadedSongIndicator(song.id)
                                                com.music.spotui.ui.components.SongExplicitBadge(song,resolveMissing=playlistId.startsWith("local_pl_"))
                                        com.music.spotui.ui.components.EditedMetadataIndicator(song)
                                        Text(
                                            text = song.singer,
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                androidx.compose.material3.IconButton(
                                    onClick = { menuSong = song },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.MoreVert,
                                        contentDescription = "Opciones de ${song.title}",
                                        tint = Color(0xFFB3B3B3))
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(220.dp)) }
                }
                com.music.spotui.ui.components.FastScrollbarForLazyList(
                    state = listState,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                )
            }
        }
        if (showPlaylistActions) {
            ModalBottomSheet(
                onDismissRequest = { showPlaylistActions = false },
                containerColor = Color(0xFF202020)
            ) {
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
                    Text(uiLocalized("Opciones de la lista"), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 18.dp))
                    spotifyUrl?.let { url ->
                        PlaylistSpotifyMenuItems(url, playlistAccess.isOwner,
                            onDismiss = { showPlaylistActions = false },
                            onOpenSpotify = openInSpotify,
                            onManage = { showSpotifyManagement = true },
                        )
                    }
                    if (externalReadOnly) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showPlaylistActions = false
                                togglePlaylistFavorite()
                            }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (favoritePlaylist) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null, tint = if (favoritePlaylist) Color(0xFF1ED760) else Color.White)
                            Spacer(Modifier.width(20.dp))
                            Text(if (favoritePlaylist) "Quitar de tu biblioteca" else "Guardar en tu biblioteca",
                                color = Color.White, fontSize = 16.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            showCopyPlaylist = true
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                        Spacer(Modifier.width(20.dp))
                        Text(uiLocalized("Copiar o añadir a otra lista"), color = Color.White, fontSize = 16.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            showSortSheet = true
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(20.dp))
                        Text(uiLocalized("Ordenar canciones"), color = Color.White, fontSize = 16.sp)
                    }
                    if (filteredSongs.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            playlistViewModel.addAllToQueue(filteredSongs)
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(20.dp))
                            Text(uiLocalized("Añadir canciones a la cola"), color = Color.White, fontSize = 16.sp)
                        }
                    }
                    if (spotifyUrl != null && playlistAccess.isOwner) {
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            manageInSpotify = PlaylistSpotifyManagementSection.PRIVACY
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(20.dp))
                            Text("Privacidad en Spotify", color = Color.White, fontSize = 16.sp)
                        }
                    }
                    if ((isLocalPlaylist && linkedSpotifyId == "") || (spotifyUrl != null && playlistAccess.isOwner)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            if (spotifyUrl != null) manageInSpotify = PlaylistSpotifyManagementSection.DETAILS
                            else showRenameDialog = true
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(20.dp))
                            Text(if (spotifyUrl != null) "Cambiar nombre en Spotify" else uiLocalized("Cambiar nombre"),
                                color = Color.White, fontSize = 16.sp)
                        }
                    }
                    if (isLocalPlaylist || (spotifyUrl != null && playlistAccess.isOwner)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            if (isLocalPlaylist) showDeleteDialog = true
                            else manageInSpotify = PlaylistSpotifyManagementSection.DELETE
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF7070))
                            Spacer(Modifier.width(20.dp))
                            Text(if (isLocalPlaylist) uiLocalized("Eliminar lista") else "Eliminar en Spotify",
                                color = Color(0xFFFF7070), fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
        if (showSortSheet) {
            CollectionSortSheet(
                selected = currentSort.name,
                descending = isDescending,
                onDismiss = { showSortSheet = false },
                onSelected = { optionName ->
                    val selectedOption = PlaylistSortOption.valueOf(optionName)
                    val nextDescending = if (currentSort == selectedOption) !isDescending
                        else selectedOption.name == "DATE"
                    currentSort = selectedOption
                    isDescending = nextDescending
                    setPlaylistSort(context, playlistId, selectedOption, nextDescending)
                    showSortSheet = false
                }
            )
        }
    }
}
