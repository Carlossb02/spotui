package com.music.spotui.ui.screens

import com.music.spotui.util.uiLocalized

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person


import com.music.spotui.ui.components.AppSearchBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.music.spotui.data.preferences.downloadsVersion
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
import com.music.spotui.data.preferences.DownloadRestorePref
import com.music.spotui.data.preferences.DownloadSortOption
import com.music.spotui.data.preferences.getDownloadedSongs
import com.music.spotui.data.preferences.getDownloadsSortOption
import com.music.spotui.data.preferences.isDownloadsSortDescending
import com.music.spotui.data.preferences.setDownloadsSortOption
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.ui.components.SwipeToPlayNextWrapper

fun DownloadSortOption.getDescriptiveLabel(isDescending: Boolean): String {
    return when (this) {
        DownloadSortOption.DATE -> if (isDescending) "Date added (newest to oldest)" else "Date added (oldest to newest)"
        DownloadSortOption.TITLE -> if (isDescending) "Title (Z to A)" else "Title (A to Z)"
        DownloadSortOption.ARTIST -> if (isDescending) "Artist (Z to A)" else "Artist (A to Z)"
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalGlideComposeApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DownloadsScreen(navController: NavController) {

    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()

    // Observe both the saved catalogue and the COMPLETE active/pending queue.
    // Pref reading, sorting and artwork loading never run on the UI thread.
    var songs by remember { mutableStateOf(emptyList<com.music.spotui.data.entity.SongsModel>()) }
    var restorePending by remember { mutableStateOf(emptyList<com.music.spotui.data.entity.SongsModel>()) }
    val downloadRevision by downloadsVersion.collectAsState()
    val inProgress by SongPlayer.downloadQueue.collectAsState()
    var showSortSheet by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var currentSort by remember { mutableStateOf(getDownloadsSortOption(context)) }
    var isDescending by remember { mutableStateOf(isDownloadsSortDescending(context)) }

    LaunchedEffect(downloadRevision, currentSort, isDescending) {
        val snapshot = withContext(Dispatchers.IO) {
            getDownloadedSongs(context) to DownloadRestorePref.pending(context)
        }
        songs = snapshot.first
        restorePending = snapshot.second
    }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    // filter downloads by simple title/singer string match
    val displayedSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        it.singer.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val accent = Color(0xFF1DB954)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.padding(16.dp, 0.dp),
                    navigationIcon = {
                        Icon(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { navController.navigateUp() },
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
        ) {
            val scrollState = androidx.compose.foundation.rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(AppBackground.toArgb()))
                        .verticalScroll(scrollState)
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.5f),
                                    Color(AppBackground.toArgb())
                                ),
                                startY = -100f,
                            ),
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(modifier = Modifier.padding(25.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(accent.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                                contentDescription = "",
                                tint = accent,
                                modifier = Modifier.size(90.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.padding(5.dp))
                    Text(
                        modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                        text = "Downloaded",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                        text = "${songs.size} songs • available offline",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // ── Search bar for downloads ──
                AppSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.padding(20.dp, 8.dp),
                    placeholder = uiLocalized("Search downloaded songs"),
                )

                // ── Sort and Clear all action ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 0.dp, 20.dp, 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (songs.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF2A2A30))
                                .clickable { showSortSheet = true }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = currentSort.getDescriptiveLabel(isDescending),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = uiLocalized("Sort Options"),
                                tint = Color.White,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(start = 4.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if ((songs.isNotEmpty() || restorePending.isNotEmpty()) && searchQuery.isBlank()) {
                        Text(
                            text = uiLocalized("Clear all"),
                            color = Color(0xFFE57373),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF1A1A20))
                                .clickable {
                                    showClearConfirmDialog = true
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }

                // ── Full download queue: active + waiting, with per-track cancel ──
                if (inProgress.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${uiLocalized("Download queue")} (${inProgress.size})",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = uiLocalized("Cancel all pending"),
                            color = Color(0xFFE57373),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                inProgress.forEach { SongPlayer.cancelDownload(it.song.url) }
                            }.padding(6.dp),
                        )
                    }
                }
                inProgress.forEach { task ->
                    val song = task.song
                    val pct = task.progress
                    val queued = task.stage == SongPlayer.DownloadStage.QUEUED
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        GlideImage(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            model = song.coverUri,
                            failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop,
                            contentDescription = "",
                        )
                        Column(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (song.explicit) {
                                    com.music.spotui.ui.components.ExplicitBadge()
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    text = song.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            if (queued) {
                                Text(uiLocalized("Waiting to download"), color = Color.Gray, fontSize = 12.sp)
                            } else {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { pct.coerceIn(0, 100) / 100f },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = accent,
                                    trackColor = Color(0xFF333333),
                                )
                            }
                        }
                        Text(
                            text = if (queued) uiLocalized("Queued") else "$pct%",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "${uiLocalized("Cancel download")}: ${song.title}",
                            tint = Color(0xFFB3B3B3),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(32.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { SongPlayer.cancelDownload(song.url) }
                                .padding(6.dp),
                        )
                    }
                }

                // Metadata restored from a backup is not mistaken for available offline audio.
                // Users explicitly choose when to redownload (avoids unexpected mobile data use).
                val waitingForRestore = restorePending.filter { saved ->
                    songs.none { it.id == saved.id } && inProgress.none { it.song.id == saved.id }
                }
                if (waitingForRestore.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recover from backup (${waitingForRestore.size})", color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Download all", color = accent, fontSize = 13.sp,
                            modifier = Modifier.clickable { SongPlayer.downloadAll(waitingForRestore, context) }.padding(6.dp))
                    }
                    Text("Your backup saved these songs, not the audio files. Download again to listen offline.",
                        color = Color.Gray, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                }
                waitingForRestore.forEach { song ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlideImage(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            model = song.coverUri, failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop, contentDescription = "",
                        )
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(song.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                            Text(song.singer, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                        Text("Download", color = accent, fontSize = 12.sp,
                            modifier = Modifier.clickable { SongPlayer.downloadSong(song, context) }.padding(8.dp))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove from recovery list",
                            tint = Color.LightGray,
                            modifier = Modifier.size(30.dp).clickable {
                                uiScope.launch(Dispatchers.IO) {
                                    DownloadRestorePref.removePending(context, song.id.toString())
                                }
                            }.padding(5.dp),
                        )
                    }
                }

                if (songs.isEmpty() && inProgress.isEmpty() && waitingForRestore.isEmpty()) {
                    Text(
                        text = uiLocalized("No downloads yet. Tap ⋯ on a track and choose Download."),
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                } else if (displayedSongs.isEmpty() && inProgress.isEmpty() && waitingForRestore.isEmpty()) {
                    Text(
                        text = "${uiLocalized("No matches found for")} \"$searchQuery\"",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    repeat(displayedSongs.size) { index ->
                        val song = displayedSongs[index]
                        val currentColor = if (song.id == playerViewModel.currentSongId.value)
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
                                            playerViewModel.updateQueue(displayedSongs.drop(index), isManualEdit = true)
                                            playerViewModel.updateSongState(
                                                song.coverUri, song.title, song.singer,
                                                true, song.id, 0, "Downloaded"
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
                                Column(
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .width(280.dp)
                                ) {
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
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        com.music.spotui.ui.components.DownloadedSongIndicator(song.id)
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
                }

                Spacer(modifier = Modifier.height(220.dp))
            }

            com.music.spotui.ui.components.FastScrollbarForScrollState(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )

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
                            DownloadSortOption.entries.forEach { option ->
                                val isSelected = option == currentSort
                                 val icon = when (option) {
                                     DownloadSortOption.DATE -> Icons.Default.DateRange
                                     DownloadSortOption.TITLE -> Icons.AutoMirrored.Filled.List
                                     DownloadSortOption.ARTIST -> Icons.Default.Person
                                 }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (currentSort == option) {
                                                isDescending = !isDescending
                                            } else {
                                                currentSort = option
                                                isDescending = (option == DownloadSortOption.DATE)
                                            }
                                            setDownloadsSortOption(context, currentSort, isDescending)
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
                                        text = if (isSelected) option.getDescriptiveLabel(isDescending) else option.getDescriptiveLabel(option == DownloadSortOption.DATE),
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
                if (showClearConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirmDialog = false },
                        title = { Text(text = uiLocalized("Clear all downloads?"), color = Color.White, fontWeight = FontWeight.Bold) },
                        text = { Text(text = uiLocalized("Are you sure you want to remove all downloaded songs? This action cannot be undone."), color = Color(0xFFB3B3B3)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearConfirmDialog = false
                                uiScope.launch {
                                    val n = withContext(Dispatchers.IO) {
                                        com.music.spotui.data.preferences.clearAllDownloads(context)
                                    }
                                    android.widget.Toast.makeText(
                                        context, "Removed $n download${if (n == 1) "" else "s"}",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }) {
                                Text(com.music.spotui.util.AppLang.get(context, "clear"), color = Color(0xFFE57373))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirmDialog = false }) {
                                Text(com.music.spotui.util.AppLang.get(context, "cancel"), color = Color.White)
                            }
                        },
                        containerColor = Color(0xFF1A1A1A),
                        titleContentColor = Color.White,
                        textContentColor = Color(0xFFB3B3B3),
                    )
                }
            }
        }
    }
}
