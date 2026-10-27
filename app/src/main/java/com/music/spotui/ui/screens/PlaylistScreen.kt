package com.music.spotui.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Brush
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

    // Trigger dinámico para refrescar la UI automáticamente cuando una descarga termina
    var downloadRefreshTrigger by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val oldCallback = SongPlayer.onDownloadsChanged
        SongPlayer.onDownloadsChanged = {
            oldCallback?.invoke()
            downloadRefreshTrigger++
        }
        onDispose {
            SongPlayer.onDownloadsChanged = oldCallback
        }
    }

    var playlistAccess by remember(playlistId) {
        mutableStateOf(com.music.spotui.data.api.PlaylistAccessInfo(
            canEdit = playlistId.startsWith("local_pl_"),
            available = playlistId.startsWith("local_pl_"),
        ))
    }
    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylist(playlistId)
        playlistAccess = com.music.spotui.data.api.PlaylistAccess.load(context, playlistId)
    }
    val canEditPlaylist = playlistAccess.canEdit
    val externalReadOnly = !playlistId.startsWith("local_pl_") && !canEditPlaylist
    var favoritePlaylist by remember(playlistId) {
        mutableStateOf(com.music.spotui.data.preferences.FavoritePlaylistsPref.isFavorite(context, playlistId))
    }
    var addedSongsRevision by remember(playlistId) { mutableStateOf(0) }
    val originalSongs = (songsResp as? Response.Success)?.data.orEmpty()
    val localAdditions = remember(playlistId, addedSongsRevision, songsResp) {
        if (playlistId.startsWith("local_pl_")) emptyList()
        else com.music.spotui.data.preferences.PlaylistAdditionsPref.tracks(context, playlistId)
    }
    val syncedPending = remember(playlistId, addedSongsRevision, songsResp) {
        if (playlistId.startsWith("local_pl_")) emptyList()
        else com.music.spotui.data.preferences.PlaylistSyncedPendingPref.tracks(context, playlistId)
    }
    // Spotify may return stale playlist content immediately after a successful POST.
    // Show confirmed additions now, deduplicating once the remote endpoint catches up.
    val songs = originalSongs + (localAdditions + syncedPending).filterNot { extra ->
        originalSongs.any { existing -> existing.url == extra.url ||
            (extra.spotifyTrackId.isNotBlank() &&
                com.music.spotui.data.api.SpotifySync.cleanTrackId(existing.spotifyTrackId) == extra.spotifyTrackId) }
    }
    val playlist = (playlistResp as? Response.Success)?.data
        ?: AlbumsModel(
            id = playlistId.hashCode() and 0x7fffffff,
            artists = "",
            coverUri = songs.firstOrNull()?.coverUri ?: "",
            name = playlistName,
            time = "",
        )

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
            com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollection(
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

    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) {
            SongPlayer.prefetchList(songs.map { it.url }, context)
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
                addedSongsRevision++
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
    val stableSongKeys = remember(filteredSongs) {
        val occurrences = HashMap<String, Int>()
        filteredSongs.map { song ->
            val identity = song.url.ifBlank { "song-${song.id}" }
            val occurrence = occurrences[identity] ?: 0
            occurrences[identity] = occurrence + 1
            "$identity#$occurrence"
        }
    }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
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

    if (showDeleteDialog) {
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
                addedSongsRevision++
                com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(playlistId)
                playlistViewModel.reloadPlaylist(playlistId)
            },
            onDismiss = { menuSong = null },
        )
    }

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
        LaunchedEffect(playlist.coverUri) {
            Palette().extractSecondColorFromCoverUrl(context = context, playlist.coverUri) { color ->
                dominentColor = color
            }
        }

        val playlistBackground = remember(dominentColor) {
            Brush.verticalGradient(
                colors = listOf(dominentColor, Color(AppBackground.toArgb()), Color(AppBackground.toArgb())),
                startY = 0f, endY = 2100f
            )
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val searchReveal = rememberCollectionSearchState(listState, playlistId)
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        Scaffold(
            modifier = Modifier.fillMaxSize().background(playlistBackground),
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
                    title = { Text(text = "") },
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
                    item(key = "playlist_reveal_search") {
                        CollectionPullSearch(
                            state = searchReveal,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = uiLocalized("Buscar en la lista")
                        )
                    }
                    if (!keyboardVisible && !searchReveal.expanded && searchQuery.isBlank()) {
                    item(key = "playlist_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (tabletCollectionLayout) 260.dp else 440.dp)
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
                                GlideImage(
                                    modifier = Modifier
                                        .size(if (tabletCollectionLayout) 212.dp else 230.dp)
                                        .clip(RoundedCornerShape(5.dp)),
                                    model = playlist.coverUri,
                                    contentScale = ContentScale.Crop,
                                    failure = placeholder(R.drawable.placeholder),
                                    contentDescription = "Carátula de ${playlist.name}",
                                )
                            }
                            Spacer(modifier = Modifier.height(17.dp))
                            val isLocalPlaylist = playlistId.startsWith("local_pl_")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            ) {
                                Text(
                                    text = playlist.name.ifBlank { playlistName },
                                    color = Color.White,
                                    fontSize = if (tabletCollectionLayout) 30.sp else 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(13.dp))
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLocalPlaylist && playlistAccess.ownerName.isBlank()) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null,
                                        tint = Color(0xFF1ED760), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Lista local · Solo en Spotui", color = Color.White,
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
                                        text = (listOf(playlistAccess.ownerName.ifBlank { playlist.artists }) +
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
                                text = "${songs.size} canciones" +
                                    if (playlist.time.isNotBlank()) " • ${playlist.time}" else "",
                                color = Color(0xFFB3B3B3),
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .padding(20.dp, 0.dp)
                            ) {
                                var playlistDownloaded by remember(songs, downloadRefreshTrigger) {
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
                                        if (externalReadOnly) {
                                            Icon(
                                                imageVector = if (favoritePlaylist) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                tint = if (favoritePlaylist) Color(0xFF1ED760) else Color.White,
                                                contentDescription = if (favoritePlaylist) "Quitar de tu biblioteca" else "Guardar en tu biblioteca",
                                                modifier = Modifier.size(27.dp).clickable { togglePlaylistFavorite() },
                                            )
                                            Spacer(Modifier.width(18.dp))
                                        }
                                        if (songs.isNotEmpty()) {
                                            // Add all playlist tracks to the queue.
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_queue_add),
                                                tint = Color.White,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        playlistViewModel.addAllToQueue(filteredSongs)
                                                        android.widget.Toast.makeText(
                                                            context,
                                                            "${filteredSongs.size} track(s) added to queue",
                                                            android.widget.Toast.LENGTH_SHORT,
                                                        ).show()
                                                    },
                                                contentDescription = uiLocalized("Add to queue"),
                                            )
                                            Spacer(modifier = Modifier.width(18.dp))
                                            Icon(
                                                imageVector = if (playlistDownloaded)
                                                    Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                                tint = if (playlistDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        com.music.spotui.data.preferences.OfflineCollectionsPref.saveCollection(
                                                            context = context,
                                                            id = playlistId,
                                                            name = playlist.name,
                                                            coverUri = playlist.coverUri,
                                                            artists = playlist.artists,
                                                            isPlaylist = true,
                                                            songs = songs
                                                        )
                                                        if (!playlistDownloaded) {
                                                            SongPlayer.downloadAll(songs, context)
                                                            snackbarMessage = "Downloading ${songs.size} tracks…"
                                                            snackbarVisible = true
                                                        } else {
                                                            snackbarMessage = "Playlist added to offline library"
                                                            snackbarVisible = true
                                                        }
                                                    },
                                                contentDescription = uiLocalized("Download playlist"),
                                            )
                                            Spacer(modifier = Modifier.width(18.dp))
                                            // Shuffle-play: start the playlist in random order.
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_player_shuffle),
                                                tint = Color.White,
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) {
                                                        playlistViewModel.startShuffled(songs)?.let { first ->
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
                                                contentDescription = uiLocalized("Shuffle play"),
                                            )

                                        }
                                    }
                                }
                                // Always visible: pause when playing, resume when this
                                // list's track is paused, otherwise start from the top.
                                if (songs.isNotEmpty()) {
                                    val playing = playlistViewModel.currentSongPlayingState.value
                                    val currentInList = songs.any { it.id == playlistViewModel.currentSongId.value }
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
                                                    currentInList -> playlistViewModel.setPlaying(!playing)
                                                    filteredSongs.isNotEmpty() -> {
                                                        playlistViewModel.updateQueue(filteredSongs)
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

                    if (playlistId.startsWith("local_pl_")) {
                        item(key = "local_playlist_actions") {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.clip(RoundedCornerShape(24.dp))
                                        .background(Color(0xFF292929))
                                        .clickable { showRenameDialog = true }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(uiLocalized("Editar"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(
                                    modifier = Modifier.clip(RoundedCornerShape(24.dp))
                                        .background(Color(0xFF292929))
                                        .clickable { showSortSheet = true }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Menu, null, tint = Color.White, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(uiLocalized("Ordenar"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    item(key = "playlist_sort") {
                        // Local playlists already have a visible Spotify-style 'Ordenar' pill.
                        if (songs.isNotEmpty() && !playlistId.startsWith("local_pl_")) {
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
                            modifier = Modifier.fillMaxWidth()
                                .background(Color(AppBackground.toArgb()))
                                .clickable { showAddMusic = true }
                                .padding(horizontal = 17.dp, vertical = 10.dp)
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0xFF303030)),
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
                            Color(AppPalette.toArgb()) else Color.White

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
                                    .background(AppBackground)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onLongClick = { menuSong = song },
                                        onClick = {
                                            playlistViewModel.updateQueue(filteredSongs.drop(index))
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
                                GlideImage(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    model = song.coverUri,
                                    failure = placeholder(R.drawable.placeholder),
                                    contentScale = ContentScale.Crop,
                                    contentDescription = ""
                                )
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
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
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        com.music.spotui.ui.components.DownloadedSongIndicator(song.id)
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
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Text(uiLocalized("Opciones de la lista"), color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 18.dp))
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
                    if (playlistId.startsWith("local_pl_")) {
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            showRenameDialog = true
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(20.dp))
                            Text(uiLocalized("Cambiar nombre"), color = Color.White, fontSize = 16.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable {
                            showPlaylistActions = false
                            showDeleteDialog = true
                        }.padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF7070))
                            Spacer(Modifier.width(20.dp))
                            Text(uiLocalized("Eliminar lista"), color = Color(0xFFFF7070), fontSize = 16.sp)
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