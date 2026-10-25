package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import com.music.spotui.data.preferences.PinnedLibraryPref
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.TextStyle
import com.music.spotui.data.preferences.LibrarySortOption
import com.music.spotui.data.preferences.getLibrarySortOption
import com.music.spotui.data.preferences.isLibrarySortDescending
import com.music.spotui.data.preferences.setLibrarySortOption
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.music.spotui.data.preferences.downloadsVersion
import com.music.spotui.data.preferences.getDownloadedSongIds
import com.music.spotui.data.preferences.OfflineCollectionsPref
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
import com.bumptech.glide.integration.compose.placeholder
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.R
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.LocalPlaylistPref
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.viewmodel.LibraryFilterType
import com.music.spotui.ui.viewmodel.LibraryViewModel

private data class DownloadedLibrarySnapshot(
    val songIds: Set<String>,
    val collectionKeys: Set<String>,
)

private fun downloadedLibraryMatch(entry: LibraryEntry, snapshot: DownloadedLibrarySnapshot): Boolean {
    if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) return true
    if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) return snapshot.songIds.isNotEmpty()
    if (entry.isLocal) return true
    val cleanId = Api.cleanId(entry.spotifyId)
    return "id:$cleanId" in snapshot.collectionKeys ||
        "name:${entry.isPlaylist}:${entry.name}".lowercase() in snapshot.collectionKeys
}

@Composable
fun LibraryFilterChips(
    selectedFilter: LibraryFilterType,
    isDownloadedOnly: Boolean,
    onFilterSelected: (LibraryFilterType) -> Unit,
    onToggleDownloaded: () -> Unit,
    onClearFilters: () -> Unit
) {
    val isAnyFilterActive = selectedFilter != LibraryFilterType.ALL || isDownloadedOnly

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isAnyFilterActive) {
            item {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                        .clickable { onClearFilters() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = uiLocalized("Clear filters"),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        item {
            LibraryChipItem(
                label = stringResource(R.string.playlists),
                isSelected = selectedFilter == LibraryFilterType.PLAYLISTS,
                onClick = { onFilterSelected(LibraryFilterType.PLAYLISTS) }
            )
        }

        item {
            LibraryChipItem(
                label = stringResource(R.string.artists),
                isSelected = selectedFilter == LibraryFilterType.ARTISTS,
                onClick = { onFilterSelected(LibraryFilterType.ARTISTS) }
            )
        }

        item {
            LibraryChipItem(
                label = stringResource(R.string.albums),
                isSelected = selectedFilter == LibraryFilterType.ALBUMS,
                onClick = { onFilterSelected(LibraryFilterType.ALBUMS) }
            )
        }

        item {
            LibraryChipItem(
                label = uiLocalized("Podcasts"),
                isSelected = selectedFilter == LibraryFilterType.PODCASTS,
                onClick = { onFilterSelected(LibraryFilterType.PODCASTS) }
            )
        }

        item {
            LibraryChipItem(
                label = stringResource(R.string.downloaded),
                isSelected = isDownloadedOnly,
                onClick = { onToggleDownloaded() }
            )
        }
    }
}

@Composable
private fun LibraryChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) Color(0xFF1ED760) else Color(0xFF2A2A2A)
    val textColor = if (isSelected) Color.Black else Color.White

    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {

    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val entries by libraryViewModel.entries.collectAsState()
    val account by libraryViewModel.account.collectAsState()
    val selectedFilter by libraryViewModel.selectedFilter.collectAsState()
    val isDownloadedOnly by libraryViewModel.isDownloadedOnly.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val downloadsRevision by downloadsVersion.collectAsState()
    // Build the offline index once on an IO dispatcher, never while composing each library tile.
    val downloadedSnapshot by produceState<DownloadedLibrarySnapshot?>(
        initialValue = null, key1 = isDownloadedOnly, key2 = downloadsRevision
    ) {
        value = if (isDownloadedOnly) withContext(Dispatchers.IO) {
            val ids = getDownloadedSongIds(context)
            DownloadedLibrarySnapshot(ids, OfflineCollectionsPref.getDownloadedCollectionKeys(context, ids))
        } else null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                libraryViewModel.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var pinnedIds by remember { mutableStateOf(PinnedLibraryPref.get(context)) }
    val togglePin: (String) -> Unit = { id -> pinnedIds = PinnedLibraryPref.toggle(context, id) }
    var gridView by remember { mutableStateOf(isLibraryGridView(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        var playlistNameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF282828),
            title = { Text(uiLocalized("Create Local Playlist"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                TextField(
                    value = playlistNameInput,
                    onValueChange = { playlistNameInput = it },
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
                    uiLocalized("Create"),
                    color = Color(0xFF1ED760),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable {
                            val name = playlistNameInput.trim().ifBlank { "My Playlist" }
                            val created = LocalPlaylistPref.createPlaylist(context, name)
                            Api.HomeCache.library = null
                            libraryViewModel.load()
                            showCreateDialog = false
                            navController.navigate(playlistRoute(created.id, created.name))
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
                        .clickable { showCreateDialog = false }
                        .padding(8.dp)
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        var searchQuery by remember { mutableStateOf("") }
        var isSearchVisible by remember { mutableStateOf(false) }
        var currentSort by remember { mutableStateOf(getLibrarySortOption(context)) }
        var isDescending by remember { mutableStateOf(isLibrarySortDescending(context)) }
        var showSortSheet by remember { mutableStateOf(false) }

        // Spotify-style fixed Library header: avatar, title, search, create.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(Color(0xFF333333))
                    .clickable { navController.navigate(Routes.Settings.route) },
                contentAlignment = Alignment.Center
            ) {
                val avatar = (account as? Response.Success)?.data?.imageUrl.orEmpty()
                if (avatar.isNotBlank()) AccountAvatar(avatar, 36.dp)
                else Icon(Icons.Default.Person, contentDescription = uiLocalized("Account"), tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(text = stringResource(R.string.your_library), fontSize = 23.sp,
                fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(43.dp).clip(CircleShape)
                .clickable { isSearchVisible = !isSearchVisible },
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_search_big), contentDescription = uiLocalized("Search Library"),
                    tint = if (isSearchVisible) Color(0xFF1ED760) else Color.White, modifier = Modifier.size(22.dp))
            }
            Box(modifier = Modifier.size(43.dp).clip(CircleShape)
                .clickable { showCreateDialog = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = uiLocalized("Create Playlist"), tint = Color.White,
                    modifier = Modifier.size(27.dp))
            }
        }

        // Library Filter Chips bar
        LibraryFilterChips(
            selectedFilter = selectedFilter,
            isDownloadedOnly = isDownloadedOnly,
            onFilterSelected = { libraryViewModel.setFilterType(it) },
            onToggleDownloaded = { libraryViewModel.toggleDownloadedOnly() },
            onClearFilters = { libraryViewModel.clearFilters() }
        )

        // Expandable Search & Sort Controls Bar
        AnimatedVisibility(
            visible = isSearchVisible || searchQuery.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 4.dp, 16.dp, 8.dp)
            ) {
                // Search Input Field
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .height(36.dp)
                        .background(Color(0xFF2A2A2A))
                        .padding(horizontal = 10.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search_big),
                        tint = Color.Gray,
                        contentDescription = uiLocalized("Search Library"),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle.Default.copy(
                            fontSize = 13.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(Color(0xFF1ED760)),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = uiLocalized("Search in library"),
                                        color = Color.Gray,
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = uiLocalized("Clear search"),
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))


            }
        }

        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { showSortSheet = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                    tint = Color(0xFFB3B3B3), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = when (currentSort) {
                        LibrarySortOption.RECENTS -> uiLocalized("Recently added")
                        LibrarySortOption.TITLE -> uiLocalized("Title")
                        LibrarySortOption.CREATOR -> uiLocalized("Artist / Creator")
                    }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.size(38.dp).clip(CircleShape)
                .clickable {
                    gridView = !gridView
                    setLibraryGridView(context, gridView)
                }, contentAlignment = Alignment.Center) {
                Icon(painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                    contentDescription = if (gridView) uiLocalized("Show as list") else uiLocalized("Show as grid"),
                    tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }

        val followedArtists by libraryViewModel.followedArtists.collectAsState()
        when (entries) {
            is Response.Loading -> LibrarySkeleton(PaddingValues(0.dp))
            is Response.Success -> {
                val rawEntries = (entries as Response.Success).data
                val filteredEntries = remember(rawEntries, selectedFilter, isDownloadedOnly, downloadedSnapshot, searchQuery, currentSort, isDescending, pinnedIds) {
                    val filtered = rawEntries.filter { entry ->
                        val matchesCategory = when (selectedFilter) {
                            LibraryFilterType.ALL -> true
                            LibraryFilterType.PLAYLISTS -> entry.isPlaylist
                            LibraryFilterType.ALBUMS -> !entry.isPlaylist && !entry.isPodcast && entry.spotifyId != Api.HomeCache.LIKED_SONGS_ID && entry.spotifyId != Api.HomeCache.DOWNLOADS_ID
                            LibraryFilterType.ARTISTS -> false
                            LibraryFilterType.PODCASTS -> entry.isPodcast
                        }
                        val matchesDownload = if (isDownloadedOnly) {
                            downloadedSnapshot?.let { downloadedLibraryMatch(entry, it) } ?: false
                        } else entry.spotifyId != Api.HomeCache.DOWNLOADS_ID
                        val matchesSearch = if (searchQuery.isBlank()) true else (entry.name.contains(searchQuery, ignoreCase = true) || entry.artists.contains(searchQuery, ignoreCase = true) || entry.subtitle.contains(searchQuery, ignoreCase = true))
                        matchesCategory && matchesDownload && matchesSearch
                    }
                    val sorted = when (currentSort) {
                        LibrarySortOption.RECENTS -> if (isDescending) filtered else filtered.reversed()
                        LibrarySortOption.TITLE -> if (isDescending) filtered.sortedByDescending { it.name.lowercase() } else filtered.sortedBy { it.name.lowercase() }
                        LibrarySortOption.CREATOR -> if (isDescending) filtered.sortedByDescending { it.artists.ifBlank { it.subtitle }.lowercase() } else filtered.sortedBy { it.artists.ifBlank { it.subtitle }.lowercase() }
                    }
                    // Stable partition; pinned entries retain the selected sort within their group.
                    sorted.filter { it.spotifyId in pinnedIds } + sorted.filterNot { it.spotifyId in pinnedIds }
                }
                val filteredArtists = remember(followedArtists, selectedFilter, isDownloadedOnly, searchQuery, currentSort, isDescending) {
                    if (isDownloadedOnly || selectedFilter == LibraryFilterType.PLAYLISTS || selectedFilter == LibraryFilterType.ALBUMS || selectedFilter == LibraryFilterType.PODCASTS) {
                        emptyList()
                    } else {
                        val searchFiltered = if (searchQuery.isBlank()) followedArtists else followedArtists.filter { it.name.contains(searchQuery, ignoreCase = true) }
                        when (currentSort) {
                            LibrarySortOption.RECENTS -> if (isDescending) searchFiltered else searchFiltered.reversed()
                            LibrarySortOption.TITLE, LibrarySortOption.CREATOR -> if (isDescending) searchFiltered.sortedByDescending { it.name.lowercase() } else searchFiltered.sortedBy { it.name.lowercase() }
                        }
                    }
                }

                if (gridView) {
                    LibraryGridScreen(
                        padding = PaddingValues(0.dp),
                        entries = filteredEntries,
                        followedArtists = filteredArtists,
                        navController = navController,
                        pinnedIds = pinnedIds,
                        onTogglePin = togglePin,
                        onClearFilters = { libraryViewModel.clearFilters() }
                    )
                } else {
                    SumUpLibraryScreen(
                        padding = PaddingValues(0.dp),
                        entries = filteredEntries,
                        followedArtists = filteredArtists,
                        navController = navController,
                        pinnedIds = pinnedIds,
                        onTogglePin = togglePin,
                        onClearFilters = { libraryViewModel.clearFilters() }
                    )
                }
            }
            else -> Box(modifier = Modifier.padding(20.dp, 100.dp)) { Snackbar(showMessage = "Couldn't load your library") }
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
                    HorizontalDivider(color = Color(0xFF2A2A2A))
                    Spacer(modifier = Modifier.height(4.dp))
                    LibrarySortOption.entries.forEach { option ->
                        val isSelected = option == currentSort
                        val icon = when (option) {
                            LibrarySortOption.RECENTS -> Icons.Default.DateRange
                            LibrarySortOption.TITLE -> Icons.AutoMirrored.Filled.List
                            LibrarySortOption.CREATOR -> Icons.Default.Person
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (currentSort == option) {
                                        isDescending = !isDescending
                                    } else {
                                        currentSort = option
                                        isDescending = (option == LibrarySortOption.RECENTS)
                                    }
                                    setLibrarySortOption(context, currentSort, isDescending)
                                    showSortSheet = false
                                }
                                .padding(16.dp, 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFF1ED760) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(18.dp))
                            Text(
                                text = if (isSelected) option.getDescriptiveLabel(isDescending) else option.getDescriptiveLabel(option == LibrarySortOption.RECENTS),
                                color = if (isSelected) Color(0xFF1ED760) else Color.White,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = if (isDescending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = Color(0xFF1ED760),
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

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AccountAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    GlideImage(
        modifier = Modifier.size(size).clip(CircleShape),
        model = url,
        contentScale = ContentScale.Crop,
        contentDescription = ""
    )
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SumUpLibraryScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
    onClearFilters: () -> Unit = {}
) {
    if (entries.isEmpty() && followedArtists.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = uiLocalized("No items found"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiLocalized("Try clearing your active filters or adding more items to your library."),
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1ED760))
                    .clickable { onClearFilters() }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = uiLocalized("Clear filters"),
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF101010))
        ) {
        item { Spacer(modifier = Modifier.height(10.dp)) }
        items(entries) { entry ->
            var pinMenuOpen by remember(entry.spotifyId) { mutableStateOf(false) }
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { openLibraryEntry(entry, navController) },
                        onLongClick = { pinMenuOpen = true }
                    )
            ) {
                if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF006450), Color(0xFF00382B))
                                )
                            ),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download),
                            contentDescription = uiLocalized("Downloaded"),
                            tint = Color(0xFF1ED760),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID && entry.coverUri.isBlank()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF450AF5), Color(0xFF8E8E93))
                                )
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = uiLocalized("Liked Songs"),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                        model = entry.coverUri,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        loading = placeholder(R.drawable.placeholder),
                        contentDescription = ""
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(text = entry.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (entry.spotifyId in pinnedIds) {
                            Icon(Icons.Filled.PushPin, contentDescription = uiLocalized("Fijado"),
                                tint = Color(0xFF1ED760), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        if (entry.isLocal) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = uiLocalized("Local Storage"),
                                tint = Color(0xFF1ED760),
                                modifier = Modifier
                                    .size(14.dp)
                                    .padding(end = 3.dp)
                            )
                        }
                        Text(text = entry.subtitle, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                DropdownMenu(expanded = pinMenuOpen, onDismissRequest = { pinMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (entry.spotifyId in pinnedIds) uiLocalized("Unpin from Your Library") else uiLocalized("Pin to Your Library")) },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = Color(0xFF1ED760)) },
                        onClick = { pinMenuOpen = false; onTogglePin(entry.spotifyId) }
                    )
                }
            }
        }
        // ── Artists the user follows on Spotify ──
        if (followedArtists.isNotEmpty()) {
            item {
                Text(
                    text = uiLocalized("Artists you follow"),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp),
                )
            }
            items(followedArtists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = uiLocalized("Artist"), color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(160.dp)) }
    }
    com.music.spotui.ui.components.FastScrollbarForLazyList(
        state = listState,
        modifier = Modifier
            .padding(padding)
            .align(Alignment.CenterEnd)
    )
}
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    if (entry.isPodcast) navController.navigate(showRoute(entry.spotifyId, entry.name))
    else if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
    else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) navController.navigate(Routes.Downloads.route)
    else if (entry.isPlaylist) navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    else navController.navigate(albumRoute(entry.name, entry.artists))
}

/**
 * Grid layout of the same library content: 3 columns of square covers with the
 * title/subtitle underneath, like Spotify's "Grid" library view. Followed
 * artists render as circles under their own full-width header.
 */
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryGridScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
    onClearFilters: () -> Unit = {}
) {
    if (entries.isEmpty() && followedArtists.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = uiLocalized("No items found"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiLocalized("Try clearing your active filters or adding more items to your library."),
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1ED760))
                    .clickable { onClearFilters() }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = uiLocalized("Clear filters"),
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = if (androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600)
            GridCells.Adaptive(minSize = 154.dp) else GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF101010)),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 130.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(entries) { entry ->
            var pinMenuOpen by remember(entry.spotifyId) { mutableStateOf(false) }
            Column(
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { openLibraryEntry(entry, navController) },
                    onLongClick = { pinMenuOpen = true }
                )
            ) {
                DropdownMenu(expanded = pinMenuOpen, onDismissRequest = { pinMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (entry.spotifyId in pinnedIds) uiLocalized("Unpin from Your Library") else uiLocalized("Pin to Your Library")) },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null, tint = Color(0xFF1ED760)) },
                        onClick = { pinMenuOpen = false; onTogglePin(entry.spotifyId) }
                    )
                }
                if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF006450), Color(0xFF00382B))
                                )
                            ),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download),
                            contentDescription = uiLocalized("Downloaded"),
                            tint = Color(0xFF1ED760),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID && entry.coverUri.isBlank()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF450AF5), Color(0xFF8E8E93))
                                )
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = uiLocalized("Liked Songs"),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else {
                    GlideImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                        model = entry.coverUri,
                        contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        loading = placeholder(R.drawable.placeholder),
                        contentDescription = ""
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.spotifyId in pinnedIds) {
                        Icon(Icons.Filled.PushPin, contentDescription = uiLocalized("Fijado"),
                            tint = Color(0xFF1ED760), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    if (entry.isLocal) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = uiLocalized("Local Storage"),
                            tint = Color(0xFF1ED760),
                            modifier = Modifier
                                .size(12.dp)
                                .padding(end = 3.dp)
                        )
                    }
                    Text(text = entry.subtitle, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (followedArtists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = uiLocalized("Artists you follow"),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(followedArtists) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = artist.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(text = uiLocalized("Artist"), color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LibrarySkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF101010))
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        repeat(8) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
            ) {
                Box(modifier = Modifier.size(55.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E1E1E)))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Box(modifier = Modifier.height(14.dp).width(160.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.height(11.dp).width(90.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                }
            }
        }
    }
}

@Composable
private fun AccountRow(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(20.dp, 16.dp)
    )
}
