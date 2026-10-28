package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.dataOrNull
import com.music.spotui.data.api.isLoading
import androidx.compose.material3.CircularProgressIndicator
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.SavedInSheet
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.components.SwipeToPlayNextWrapper
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.components.AppSearchBar
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.viewmodel.SearchViewModel


@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchScreen(navController: NavController, searchFocusTrigger: Int = 0) {
    val searchViewModel: SearchViewModel = hiltViewModel()
    val resultsState by searchViewModel.results.collectAsState()

    // Results are live search hits; render progressively as partial items arrive.
    val searchResults = resultsState.dataOrNull ?: SearchResults()
    val isLoading = resultsState.isLoading

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        SumUpSearchScreen(
            navController = navController,
            results = searchResults,
            isLoading = isLoading,
            searchViewModel = searchViewModel,
            searchFocusTrigger = searchFocusTrigger
        )
    }
}


@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun SumUpSearchScreen(
    navController: NavController,
    results: SearchResults,
    isLoading: Boolean = false,
    searchViewModel: SearchViewModel,
    searchFocusTrigger: Int = 0,
) {
    val context = LocalContext.current

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var text by remember {
        mutableStateOf("")
    }
    // Recents are the *items opened from results* (songs/artists/albums), not the
    // typed queries, and only appear once the user taps into the search bar.
    var isTextFieldFocused by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    LaunchedEffect(isTextFieldFocused) {
        if (isTextFieldFocused) {
            searchActive = true
        }
    }

    androidx.activity.compose.BackHandler(enabled = isTextFieldFocused || searchActive || text.isNotEmpty()) {
        isTextFieldFocused = false
        searchActive = false
        focusManager.clearFocus()
        if (text.isNotEmpty()) {
            text = ""
            searchViewModel.search("")
        }
    }

    var recents by remember {
        mutableStateOf(com.music.spotui.data.preferences.getRecentItems(context))
    }

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }
    val recordRecent: (com.music.spotui.data.preferences.RecentItem) -> Unit = { item ->
        com.music.spotui.data.preferences.addRecentItem(context, item)
        recents = com.music.spotui.data.preferences.getRecentItems(context)
    }
    var selectedCategory by remember { mutableStateOf("All") }

    // Live Spotify search results for the current query.
    val searchedList = results.songs
    // One relevance-mixed list (shows, episodes, songs, artists, albums interleaved)
    // exactly like Spotify search.
    val mixed = remember(results, text) { mixSearchResults(results, text) }
    val filteredMixed = remember(mixed, selectedCategory) {
        when (selectedCategory) {
            "Podcasts" -> mixed.filter { it is SearchRow.Show || it is SearchRow.Episode }
            "Songs" -> mixed.filter { it is SearchRow.Song }
            "Artists" -> mixed.filter { it is SearchRow.Artist }
            "Albums" -> mixed.filter { it is SearchRow.Album }
            "Playlists" -> mixed.filter { it is SearchRow.Playlist }
            else -> mixed
        }
    }

    // Warm the stream cache for the top search hits so tapping a result plays
    // (near-)instantly instead of resolving YouTube on the tap.
    LaunchedEffect(searchedList) {
        if (searchedList.isNotEmpty()) {
            SongPlayer.prefetchList(searchedList.map { it.url }, context, count = 3)
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(AppBackground.toArgb()))
                .statusBarsPadding()

        ) {
            item {
                SearchTopBar()
            }
            stickyHeader {
                SearchStickyBar(
                    text = text,
                    isLoading = isLoading && text.isNotBlank(),
                    onFocusChange = { isTextFieldFocused = it },
                    searchFocusTrigger = searchFocusTrigger,
                ) {
                    text = it
                    searchViewModel.search(it)
                }
            }

            if (text.isBlank()) {
                if ((isTextFieldFocused || searchActive) && recents.isNotEmpty()) {
                    // ── Recent searches: the items the user opened (Spotify-style),
                    // shown only once the search bar is focused ──
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp, 16.dp, 16.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.recent_searches),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                uiLocalized("Clear"),
                                color = Color(0xFFB3B3B3),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    com.music.spotui.data.preferences.clearRecentItems(context)
                                    recents = emptyList()
                                },
                            )
                        }
                    }
                    items(recents.size) { i ->
                        val item = recents[i]
                        RecentItemRow(
                            item = item,
                            onClick = {
                                when (item.type) {
                                    "song" -> {
                                        val songUrl = item.songUrl.ifBlank {
                                            SongPlayer.buildSpotifyPlayQuery(
                                                item.spotifyTrackId,
                                                item.name,
                                                item.singer
                                            )
                                        }.let { savedUrl ->
                                            if (
                                                item.spotifyTrackId.isNotBlank() &&
                                                !savedUrl.startsWith("spotify:track:")
                                            ) {
                                                SongPlayer.buildSpotifyPlayQuery(
                                                    item.spotifyTrackId,
                                                    item.name,
                                                    item.singer
                                                )
                                            } else {
                                                savedUrl
                                            }
                                        }
                                        val song = SongsModel(
                                            item.songId, item.name, item.songAlbum, item.singer,
                                            item.image, songUrl, item.spotifyTrackId,
                                            explicit = item.explicit,
                                            durationMs = item.durationMs,
                                        )
                                        searchViewModel.startRadioFromSong(song)
                                        searchViewModel.updateSongState(
                                            song.coverUri,
                                            song.title,
                                            song.singer,
                                            true,
                                            song.id,
                                            0,
                                            song.album
                                        )
                                        SongPlayer.playSong(song.url, context, "song/${song.id}")
                                    }

                                    "artist" -> navController.navigate(
                                        artistRoute(
                                            item.name,
                                            item.key.takeIf { it != item.name }.orEmpty()
                                        )
                                    )

                                    "album" -> navController.navigate(
                                        albumRoute(
                                            item.name,
                                            item.singer
                                        )
                                    )

                                    "show" -> navController.navigate(showRoute(item.key, item.name))
                                    "playlist" -> navController.navigate(playlistRoute(item.key, item.name))
                                }
                            },
                            onRemove = {
                                com.music.spotui.data.preferences.removeRecentItem(context, item)
                                recents = com.music.spotui.data.preferences.getRecentItems(context)
                            },
                            onLongClick = {
                                if (item.type == "song") {
                                    menuSong = SongsModel(
                                        id = item.songId,
                                        title = item.name,
                                        album = item.songAlbum,
                                        singer = item.singer,
                                        coverUri = item.image,
                                        url = item.songUrl,
                                        spotifyTrackId = item.spotifyTrackId,
                                        explicit = item.explicit,
                                        durationMs = item.durationMs,
                                    )
                                }
                            },
                        )
                    }
                } else {
                    // ── Spotify-style "Browse all" category grid ──
                    item {
                        // Real Spotify opens a genre *catalogue* (a page of curated
                        // playlists) rather than running a keyword song search.
                        BrowseAllSection { genre, title ->
                            navController.navigate(categoryRoute(genre, title))
                        }
                    }
                }
            } else {
                item {
                    val categories = listOf("All", "Podcasts", "Songs", "Artists", "Albums", "Playlists")
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categories.size) { cIdx ->
                            val cat = categories[cIdx]
                            val isSelected = (cat == selectedCategory)
                            Surface(
                                color = if (isSelected) Color(0xFF1ED760) else Color(0xFF2A2A30),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.clickable { selectedCategory = cat }
                            ) {
                                Text(
                                    text = cat,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                items(filteredMixed.size) { i ->
                    when (val row = filteredMixed[i]) {
                        is SearchRow.Show -> SearchShowRow(row.show) {
                            recordRecent(
                                com.music.spotui.data.preferences.RecentItem(
                                    type = "show",
                                    key = row.show.id,
                                    name = row.show.name,
                                    singer = row.show.publisher,
                                    image = row.show.coverUri,
                                )
                            )
                            navController.navigate(showRoute(row.show.id, row.show.name))
                        }

                        is SearchRow.Episode -> SearchSongRow(
                            song = row.episode,
                            songList = results.episodes,
                            searchViewModel = searchViewModel,
                            onPlayed = {
                                recordRecent(row.episode.toRecentItem())
                            },
                            onLongClick = {
                                menuSong = row.episode
                            },
                        )

                        is SearchRow.Song -> SearchSongRow(
                            song = row.song,
                            songList = searchedList,
                            searchViewModel = searchViewModel,
                            onPlayed = {
                                recordRecent(row.song.toRecentItem())
                            },
                            onLongClick = {
                                menuSong = row.song
                            },
                        )

                        is SearchRow.Artist -> SearchArtistRow(row.artist) {
                            recordRecent(
                                com.music.spotui.data.preferences.RecentItem(
                                    type = "artist",
                                    key = row.artist.id.ifBlank { row.artist.name },
                                    name = row.artist.name,
                                    image = row.artist.coverUri,
                                )
                            )
                            navController.navigate(artistRoute(row.artist.name, row.artist.id))
                        }

                        is SearchRow.Playlist -> SearchPlaylistRow(row.playlist) {
                            recordRecent(
                                com.music.spotui.data.preferences.RecentItem(
                                    type = "playlist",
                                    key = row.playlist.spotifyId,
                                    name = row.playlist.name,
                                    singer = row.playlist.subtitle,
                                    image = row.playlist.coverUri,
                                )
                            )
                            navController.navigate(playlistRoute(row.playlist.spotifyId, row.playlist.name))
                        }

                        is SearchRow.Album -> SearchAlbumRow(row.album) {
                            recordRecent(
                                com.music.spotui.data.preferences.RecentItem(
                                    type = "album",
                                    key = row.album.name,
                                    name = row.album.name,
                                    singer = row.album.artists,
                                    image = row.album.coverUri,
                                )
                            )
                            navController.navigate(albumRoute(row.album.name, row.album.artists))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(160.dp))
            }

        }
        com.music.spotui.ui.components.FastScrollbarForLazyList(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/** Search categories share one similarity ranking, not separate blocks by content type. */
sealed class SearchRow {
    data class Show(val show: com.music.spotui.data.entity.PodcastModel) : SearchRow()
    data class Episode(val episode: SongsModel) : SearchRow()
    data class Song(val song: SongsModel) : SearchRow()
    data class Artist(val artist: com.music.spotui.data.entity.ArtistsModel) : SearchRow()
    data class Album(val album: com.music.spotui.data.entity.AlbumsModel) : SearchRow()
    data class Playlist(val playlist: com.music.spotui.data.entity.LibraryEntry) : SearchRow()
}

/** Exact/prefix/token similarity first, then a small artist/track boost. Playlists compete
 * for their actual rank instead of being appended below all albums and episodes. */
internal fun searchSimilarity(query: String, title: String, categoryBoost: Int): Int {
    fun normalized(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ").trim()
    val q = normalized(query)
    val t = normalized(title)
    if (q.isEmpty() || t.isEmpty()) return categoryBoost
    val tokens = q.split(' ').filter { it.isNotBlank() }
    val matches = tokens.count { token -> t.split(' ').any { it == token || it.startsWith(token) } }
    val quality = when {
        t == q -> 200
        t.startsWith("$q ") || t.startsWith(q) -> 164
        t.contains(" $q ") || t.endsWith(" $q") -> 145
        t.contains(q) -> 123
        matches == tokens.size -> 100
        else -> (matches * 80 / tokens.size).coerceAtMost(80)
    }
    return quality + categoryBoost
}

private fun mixSearchResults(results: SearchResults, query: String): List<SearchRow> {
    val scored = buildList {
        results.artists.forEach { add(SearchRow.Artist(it)) }
        results.songs.forEach { add(SearchRow.Song(it)) }
        results.albums.forEach { add(SearchRow.Album(it)) }
        results.playlists.forEach { add(SearchRow.Playlist(it)) }
        results.shows.forEach { add(SearchRow.Show(it)) }
        results.episodes.forEach { add(SearchRow.Episode(it)) }
    }
    return scored.withIndex().sortedWith(
        compareByDescending<IndexedValue<SearchRow>> { indexed ->
            when (val r = indexed.value) {
                is SearchRow.Artist -> searchSimilarity(query, r.artist.name, 36)
                is SearchRow.Song -> searchSimilarity(query, r.song.title, 32)
                is SearchRow.Album -> searchSimilarity(query, r.album.name, 18)
                is SearchRow.Playlist -> searchSimilarity(query, r.playlist.name, 10)
                is SearchRow.Show -> searchSimilarity(query, r.show.name, 16)
                is SearchRow.Episode -> searchSimilarity(query, r.episode.title, 12)
            }
        }.thenBy { it.index }
    ).map { it.value }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SearchPlaylistRow(
    playlist: com.music.spotui.data.entity.LibraryEntry,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        GlideImage(
            model = playlist.coverUri,
            contentDescription = playlist.name,
            contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(playlist.name, color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Text(playlist.subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Maps a tapped search-result song to a persisted recent item. */
private fun SongsModel.toRecentItem() = com.music.spotui.data.preferences.RecentItem(
    type = "song",
    key = spotifyTrackId.ifBlank { url },
    name = title,
    singer = singer,
    image = coverUri,
    songId = id,
    songAlbum = album,
    songUrl = url,
    spotifyTrackId = spotifyTrackId,
    explicit = explicit,
    durationMs = durationMs,
)

/** A recent item row (song/artist/album/show the user opened), with remove (x). */
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun RecentItemRow(
    item: com.music.spotui.data.preferences.RecentItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(16.dp, 8.dp),
    ) {
        GlideImage(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(if (item.type == "artist") 100.dp else 6.dp)),
            model = item.image,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp),
        ) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val subtitle = when (item.type) {
                "song" -> "Song • ${item.singer}"
                "artist" -> "Artist"
                "album" -> "Album • ${item.singer}"
                "show" -> "Podcast" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
                else -> ""
            }
            Text(
                text = subtitle,
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        Icon(
            imageVector = Icons.Default.Close,
            tint = Color(0xFFB3B3B3),
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onRemove() },
            contentDescription = uiLocalized("Remove"),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun SearchSongRow(
    song: SongsModel,
    songList: List<SongsModel>,
    searchViewModel: SearchViewModel,
    onPlayed: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var isLiked by remember { mutableStateOf(isSongLiked(context, song.id.toString())) }
    var showSavedIn by remember { mutableStateOf(false) }
    if (showSavedIn) {
        SavedInSheet(
            song = song,
            context = context,
            onDismiss = { showSavedIn = false },
            onLikedChanged = { isLiked = it },
        )
    }
    val likeState = searchViewModel.likeState.value
    LaunchedEffect(likeState) { isLiked = isSongLiked(context, song.id.toString()) }
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val currentPlayingIndicatorColor =
        if (song.id == searchViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White

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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(AppBackground)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = onLongClick,
                    onClick = {
                        onPlayed()
                        // Start a radio from the tapped track (queue = this song + Spotify
                        // recommendations) rather than queuing the whole search list.
                        searchViewModel.startRadioFromSong(song)
                        searchViewModel.updateSongState(
                            song.coverUri,
                            song.title,
                            song.singer,
                            true,
                            song.id,
                            0,
                            song.album,
                        )
                        SongPlayer.playSong(song.url, context, "song/${song.id}")
                    },
                )
                .padding(16.dp, 8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(280.dp),
            ) {
                if (com.music.spotui.data.preferences.PodcastResumeStore.isEpisode(song.url)) {
                    com.music.spotui.ui.components.EpisodeArtwork(
                        episode = song,
                        modifier = Modifier.padding(end = 10.dp).size(48.dp),
                        radius = 6.dp
                    )
                } else {
                    GlideImage(
                        modifier = Modifier.padding(end = 10.dp).size(48.dp).clip(RoundedCornerShape(6.dp)),
                        model = song.coverUri, contentScale = ContentScale.Crop,
                        failure = placeholder(R.drawable.placeholder),
                        loading = placeholder(R.drawable.placeholder),
                        contentDescription = ""
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (song.explicit) {
                            com.music.spotui.ui.components.ExplicitBadge()
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = song.title,
                            color = currentPlayingIndicatorColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.music.spotui.ui.components.DownloadedSongIndicator(song.id)
                        Text(
                            text = "Song • ${song.singer}",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
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
                            if (isLiked) removeLikedSongId(context, song.id.toString())
                            else addLikedSongId(context, song.id.toString())
                            isLiked = isSongLiked(context, song.id.toString())
                            searchViewModel.updateLikeState(!searchViewModel.likeState.value)
                        },
                        onLongClick = { showSavedIn = true },
                    ),
                painter = if (isLiked) painterResource(id = R.drawable.added) else painterResource(
                    id = R.drawable.ic_add
                ),
                tint = if (isLiked) Color.White else Color.Gray,
                contentDescription = "",
            )
        }
    }
}

@Composable
fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp),
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchShowRow(show: com.music.spotui.data.entity.PodcastModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = show.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(
                text = show.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = uiLocalized("Podcast") + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""),
                color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchArtistRow(artist: com.music.spotui.data.entity.ArtistsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(100.dp)),
            model = artist.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(
                text = artist.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = uiLocalized("Artist"),
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchAlbumRow(album: com.music.spotui.data.entity.AlbumsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = album.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(
                text = album.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Album • ${album.artists}",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/** Spotify's "Browse all" categories: name + tile colour + the search query it runs. */
private val browseCategories: List<Triple<String, Color, String>> = listOf(
    Triple("Music", Color(0xFFDC148C), "Top hits"),
    Triple("Podcasts", Color(0xFF1E3264), "Podcast"),
    Triple("Made For You", Color(0xFF8768A8), "Discover weekly"),
    Triple("New Releases", Color(0xFFE8115B), "New releases"),
    Triple("Pop", Color(0xFF8D67AB), "Pop"),
    Triple("Hip-Hop", Color(0xFF477D95), "Hip hop"),
    Triple("Rock", Color(0xFFE61E32), "Rock"),
    Triple("Latin", Color(0xFFE1118C), "Latin"),
    Triple("Country", Color(0xFFD84000), "Country"),
    Triple("R&B", Color(0xFFBA5D07), "R&B"),
    Triple("K-Pop", Color(0xFF148A08), "K-pop"),
    Triple("Indie", Color(0xFF608108), "Indie"),
    Triple("Dance/Electronic", Color(0xFF056952), "Electronic dance"),
    Triple("Metal", Color(0xFF777777), "Metal"),
    Triple("Chill", Color(0xFF1E3264), "Chill"),
    Triple("Charts", Color(0xFF8C1932), "Top charts"),
    Triple("Workout", Color(0xFF777777), "Workout"),
    Triple("Jazz", Color(0xFF503750), "Jazz"),
)

@Composable
fun BrowseAllSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.browse_all),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp),
        )
        browseCategories.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { (name, color, query) ->
                    BrowseCategoryTile(name, color, query, Modifier.weight(1f)) {
                        onCategoryClick(
                            query,
                            name
                        )
                    }
                }
                // Keep a half-width spacer if the last row has a single tile.
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    query: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Cover of the category's top playlist, shown tilted in the corner like
    // Spotify web's browse tiles. Empty until resolved (cached per session).
    val cover by androidx.compose.runtime.produceState(initialValue = "", key1 = query) {
        value = com.music.spotui.data.api.BrowseTileImages.coverFor(context, query)
    }
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        if (cover.isNotBlank()) {
            GlideImage(
                model = cover,
                contentScale = ContentScale.Crop,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(64.dp)
                    .offset(x = 16.dp, y = 10.dp)
                    .rotate(25f)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun SearchTopBar() {
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(text = uiLocalized("Search"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        //Icon(imageVector = Icons.Default.Person, contentDescription = "", tint = Color.White)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchStickyBar(
    text: String,
    isLoading: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    searchFocusTrigger: Int = 0,
    onTextChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var lastHandledTrigger by remember { mutableStateOf(searchFocusTrigger) }

    // When the search tab is re-tapped, focus the text field and show the keyboard.
    LaunchedEffect(searchFocusTrigger) {
        if (searchFocusTrigger > 0 && searchFocusTrigger != lastHandledTrigger) {
            lastHandledTrigger = searchFocusTrigger
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    AppSearchBar(
        query = text,
        onQueryChange = onTextChange,
        modifier = Modifier.padding(10.dp),
        placeholder = stringResource(R.string.search_placeholder),
        focusRequester = focusRequester,
        onFocusChange = onFocusChange,
        isLoading = isLoading,
    )
}
