package com.music.spotui.ui.components

import com.music.spotui.util.uiLocalized
import com.music.spotui.util.localizedUiText

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.music.spotui.R
import androidx.compose.ui.res.vectorResource
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.theme.AppPalette

/**
 * Long-press context menu for a single track. Mirrors Spotify's "3-dot" sheet:
 * play next, add to queue, like/unlike, jump to artist/album, share.
 *
 * Queue operations go through [PlayerViewModel], which mutates the shared
 * (singleton) playback queue. The like toggle persists to local prefs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SongOptionsSheet(
    song: SongsModel,
    navController: NavController,
    context: Context,
    currentPlaylistId: String? = null,
    canEditCurrentPlaylist: Boolean = true,
    onSongRemovedFromPlaylist: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onOpenQueue:(()->Unit)?=null,
    onLikedChanged:((Boolean)->Unit)?=null,
    extraActions:(@Composable ()->Unit)?=null,
) {
    val language=com.music.spotui.data.preferences.SpotuiLanguage.current
    fun text(value:String)=localizedUiText(value,language)
    val revision by com.music.spotui.sync.OwnLibraryChanges.revision.collectAsState()
    val displaySong=remember(song,revision) {com.music.spotui.data.preferences.CustomMetadataPref.applyOverride(context,song)}
    var showPlaylistRemoval by remember { mutableStateOf(false) }
    val playerViewModel: com.music.spotui.ui.viewmodel.PlayerViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var liked by remember { mutableStateOf(isSongLiked(context, song.id.toString())) }
    var downloaded by remember { mutableStateOf(com.music.spotui.data.preferences.isDownloaded(context, song.id.toString())) }
    var downloadingNow by remember { mutableStateOf(com.music.spotui.di.SongPlayer.isDownloading(song.url)) }
    var downloadPct by remember { mutableStateOf(com.music.spotui.di.SongPlayer.downloadProgress(song.url)) }
    // Poll progress while a download is active so the menu row shows live "Downloading… NN%".
    androidx.compose.runtime.LaunchedEffect(downloadingNow) {
        while (downloadingNow) {
            downloadPct = com.music.spotui.di.SongPlayer.downloadProgress(song.url)
            kotlinx.coroutines.delay(300)
        }
    }

    // Spotify-style "Saved in" sheet (Liked Songs + playlists, add/remove/create).
    var showSavedIn by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    if (showSavedIn) {
        SavedInSheet(
            song = song,
            context = context,
            onDismiss = { showSavedIn = false; onDismiss() },
            onLikedChanged = { liked = it;onLikedChanged?.invoke(it) },
        )
        return
    }

    // Local origin survives title/artist edits; a Spotify track never becomes
    // editable just because its artist metadata happens to be "Unknown Artist".
    val editableLocalTrack = remember(song, context) {
        com.music.spotui.data.preferences.isUserEditableLocalTrack(context, song)
    }

    if (showEditDialog && editableLocalTrack) {
        var editTitle by remember { mutableStateOf(displaySong.title) }
        var editArtist by remember { mutableStateOf(displaySong.singer) }
        var editAlbum by remember { mutableStateOf(displaySong.album) }
        var editCover by remember { mutableStateOf(displaySong.coverUri) }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(uiLocalized("Edit Metadata"), color = Color.White) },
            containerColor = Color(0xFF242424),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiLocalized("Title"), color = Color.Gray, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF1ED760),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Text(uiLocalized("Artist"), color = Color.Gray, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = editArtist,
                        onValueChange = { editArtist = it },
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF1ED760),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Text(uiLocalized("Album"), color = Color.Gray, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = editAlbum,
                        onValueChange = { editAlbum = it },
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF1ED760),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Text(uiLocalized("Cover URI"), color = Color.Gray, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = editCover,
                        onValueChange = { editCover = it },
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF1ED760),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val finalTitle = editTitle.ifBlank { displaySong.title }
                        val finalArtist = editArtist.ifBlank { displaySong.singer }
                        com.music.spotui.data.preferences.updateLocalTrackMetadata(
                            context,
                            song,
                            finalTitle,
                            finalArtist,
                            editAlbum,
                            editCover
                        )
                        onSongRemovedFromPlaylist?.invoke()
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            com.music.spotui.util.BackupHelper.performAutoBackup(context)
                        }
                        android.widget.Toast.makeText(context, text("Metadata updated"), android.widget.Toast.LENGTH_SHORT).show()
                        showEditDialog = false
                        onDismiss()
                    }
                ) {
                    Text(uiLocalized("Save"), color = Color(0xFF1ED760))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showEditDialog = false }) {
                    Text(uiLocalized("Cancel"), color = Color.Gray)
                }
            }
        )
    }

    if (showPlaylistRemoval && !currentPlaylistId.isNullOrBlank()) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { showPlaylistRemoval = false }, containerColor = Color(0xFF242424),
            title = { Text(uiLocalized("¿Quitar de esta playlist?"), color = Color.White) },
            text = { Text(displaySong.title, color = Color(0xFFB3B3B3)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                showPlaylistRemoval = false
                    if (com.music.spotui.data.preferences.PlaylistAdditionsPref.isManaged(context,song) && !currentPlaylistId.startsWith("local_pl_")) {
                        com.music.spotui.data.preferences.PlaylistAdditionsPref.remove(context, currentPlaylistId, song)
                        onSongRemovedFromPlaylist?.invoke()
                    } else if (currentPlaylistId.startsWith("local_pl_")) {
                        com.music.spotui.data.preferences.LocalPlaylistPref.removeSongFromPlaylist(context, currentPlaylistId, song)
                        com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(currentPlaylistId)
                        com.music.spotui.data.api.Api.HomeCache.library = null
                        onSongRemovedFromPlaylist?.invoke()
                    } else {
                        val trackId = song.spotifyTrackId.ifBlank { song.id.toString() }
                        com.music.spotui.data.api.SpotifySync.removeTrackFromPlaylist(context, currentPlaylistId, trackId) { ok ->
                            com.music.spotui.data.api.Api.HomeCache.invalidatePlaylist(currentPlaylistId)
                            if (ok) {
                                com.music.spotui.data.preferences.PlaylistSyncedPendingPref.remove(
                                    context, currentPlaylistId, trackId
                                )
                                // Remote callbacks run off the UI thread.
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    onSongRemovedFromPlaylist?.invoke()
                                }
                            }
                        }
                    }
                    onDismiss()
            }) { Text(uiLocalized("Quitar"), color = Color(0xFF1ED760)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showPlaylistRemoval = false }) { Text(uiLocalized("Cancelar"), color = Color.White) } })
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Color(0xFF1D1D1D),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            var downwardDrag = 0f
            Box(Modifier.fillMaxWidth()
                .pointerInput(onDismiss) {
                    detectVerticalDragGestures(
                        onDragStart = { downwardDrag = 0f },
                        onDragEnd = { if (downwardDrag > 60f) onDismiss() },
                        onVerticalDrag = { change, amount ->
                            downwardDrag += amount
                            change.consume()
                        },
                    )
                }
                .clickable { onDismiss() }
                .padding(top = 10.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 34.dp, height = 4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xFF777777)))
            }
        },
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .86f).dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp, 14.dp, 24.dp, 20.dp)
            ) {
                GlideImage(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    model = displaySong.coverUri,
                    contentScale = ContentScale.Crop,
                    contentDescription = ""
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(displaySong.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(displaySong.singer, color = Color(0xFFB3B3B3), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = Color(0xFF353535))

            if (editableLocalTrack) {
                SongMenuRow(
                    icon = Icons.Outlined.Edit,
                    label = uiLocalized("Edit metadata"),
                ) {
                    showEditDialog = true
                }
            }

            SongMenuRow(Icons.Outlined.Share, uiLocalized("Share")) {
                val shareText = song.spotifyTrackId.takeIf { it.isNotBlank() }
                    ?.let { "https://open.spotify.com/track/$it" }
                    ?: text("Listening to %1\$s by %2\$s").format(song.title,song.singer)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(send, text("Share")))
                onDismiss()
            }
            SongMenuRow(
                Icons.Outlined.AddCircle, uiLocalized("Add to playlist"),
                enabled = true, trailingArrow = true,
            ) {
                showSavedIn = true
            }
            if (!currentPlaylistId.isNullOrBlank() && canEditCurrentPlaylist) {
                SongMenuRow(
                    icon = Icons.Outlined.RemoveCircle,
                    label = uiLocalized("Remove from this playlist"),
                    iconTint = Color(0xFFB3B3B3)
                ) {
                    showPlaylistRemoval = true
                }
            }
            SongMenuRow(Icons.Outlined.PlaylistAdd, uiLocalized("Add to queue")) {
                playerViewModel.addToQueue(song)
                onDismiss()
            }
            SongMenuRow(Icons.Outlined.QueueMusic, uiLocalized("View queue")) {
                onDismiss()
                if(onOpenQueue!=null)onOpenQueue()else navController.navigate(com.music.spotui.ui.navigation.Routes.Queue.route)
            }
            SongMenuRow(Icons.Outlined.Album, uiLocalized("Go to album"), enabled = song.album.isNotBlank(), trailingArrow = true) {
                navController.navigate(albumRoute(song.album, song.singer))
                onDismiss()
            }
            val artist = song.singer.substringBefore(",").trim()
            SongMenuRow(Icons.Outlined.People, uiLocalized("Go to artist"), enabled = artist.isNotBlank(), trailingArrow = true) {
                // Resolve the exact artist (id) from the track so the name can't
                // fuzzy-match to a different artist.
                playerViewModel.goToArtist(song.spotifyTrackId, song.singer, song = song) { route ->
                    navController.navigate(route)
                }
                onDismiss()
            }
            SongMenuRow(Icons.Outlined.PlayCircle, uiLocalized("Play next")) {
                playerViewModel.playNext(song)
                onDismiss()
            }
            SongMenuRow(
                icon = if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                label = text(if (liked) "Remove from Liked Songs" else "Add to Liked Songs"),
                iconTint = Color(0xFF1ED760),
            ) {
                com.music.spotui.data.api.LibraryContentMemory.register(context, song)
                if (liked) removeLikedSongId(context, song.id.toString())
                else addLikedSongId(context, song.id.toString())
                liked = !liked
                onLikedChanged?.invoke(liked)
                // Mirror the like to the real Spotify account.
                com.music.spotui.data.api.SpotifySync.setTrackSaved(context, song.spotifyTrackId, liked)
            }
            SongMenuRow(
                icon = if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                label = when {
                    downloaded -> text("Remove download")
                    downloadingNow -> text("Downloading…") + if(downloadPct in 1..99) " $downloadPct%" else ""
                    else -> text("Download")
                },
                iconTint = if (downloaded) Color(0xFF1ED760) else Color(0xFFB3B3B3),
                enabled = !downloadingNow,
            ) {
                if (downloaded) {
                    val removed = com.music.spotui.data.preferences.removeDownload(context, song.id.toString())
                    if (removed) downloaded = false
                    else android.widget.Toast.makeText(context, text("Could not delete audio file"), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    downloadingNow = true
                    com.music.spotui.di.SongPlayer.downloadSong(song, context) { ok ->
                        downloadingNow = false
                        downloaded = ok
                    }
                }
            }
            if (downloaded) {
                SongMenuRow(
                    icon = Icons.Outlined.Download,
                    label = uiLocalized("Export to Music"),
                ) {
                    onDismiss()
                    // Detached scope + app context so the copy + toast survive the
                    // sheet closing (a rememberCoroutineScope would be cancelled).
                    val app = context.applicationContext
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val ok = com.music.spotui.data.preferences.exportDownload(app, song)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                app,
                                text(if (ok) "Saved to Music/spotui" else "Export failed"),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            SongMenuRow(
                icon = Icons.Outlined.Refresh,
                label = uiLocalized("Invalidate cache"),
            ) {
                onDismiss()
                com.music.spotui.di.SongPlayer.invalidateSongCache(song, context, reloadIfPlaying = true, clearAltStream = false)
            }
            extraActions?.invoke()
            Spacer(modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
internal fun SongMenuRow(
    icon: ImageVector,
    label: String,
    subtitle:String?=null,
    iconTint: Color = Color(0xFFB3B3B3),
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) iconTint else Color.Gray.copy(alpha = 0.4f),
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
        Text(
            text = uiLocalized(label),
            color = if (enabled) Color.White else Color.Gray.copy(alpha = 0.4f),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if(subtitle!=null)Text(subtitle,color=Color(0xFFB3B3B3),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        if (trailingArrow) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
