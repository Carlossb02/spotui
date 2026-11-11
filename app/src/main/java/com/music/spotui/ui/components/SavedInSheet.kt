package com.music.spotui.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.SavedTrackLibrary
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SavedPlaylist
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.launch

private val SpotifyGreen = Color(0xFF1ED760)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SavedInSheet(
    song: SongsModel,
    context: Context,
    onDismiss: () -> Unit,
    onLikedChanged: (Boolean) -> Unit = {},
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snapshot by SavedTrackLibrary.snapshot.collectAsState()
    var liked by remember(song.id, song.url) { mutableStateOf(isSongLiked(context, song.id.toString())) }
    var removeCandidate by remember { mutableStateOf<SavedPlaylist?>(null) }
    var confirmUnlike by remember { mutableStateOf(false) }
    val localPreview = remember(song.id, song.url) { mutableStateMapOf<String, Boolean>() }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val operations = remember(song.id, song.url) { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    val trackId = SpotifySync.cleanTrackId(song.spotifyTrackId)
    LaunchedEffect(song.id, song.url) { SavedTrackLibrary.ensureLoaded(context) }
    val localOverrides = localPreview.toMap()
    fun contains(list: SavedPlaylist) = localPreview[list.id]
        ?: SavedTrackLibrary.snapshot.value.playlists.firstOrNull { it.id == list.id }?.contains(song)
        ?: list.contains(song)
    val saved = remember(snapshot, song, localOverrides) { snapshot.playlists.filter { contains(it) } }
    val available = remember(snapshot, song, localOverrides) { snapshot.playlists.filterNot { contains(it) } }
    LaunchedEffect(snapshot) {
        localPreview.keys.toList().forEach { id ->
            if (snapshot.playlists.firstOrNull { it.id == id }?.contains(song) == localPreview[id]) localPreview.remove(id)
        }
    }

    fun toggleLiked() {
        com.music.spotui.data.api.LibraryContentMemory.register(context, song)
        liked = !liked
        if (liked) addLikedSongId(context, song.id.toString()) else removeLikedSongId(context, song.id.toString())
        SpotifySync.setTrackSaved(context, song.spotifyTrackId, liked)
        onLikedChanged(liked)
    }
    fun toggle(list: SavedPlaylist, removalConfirmed: Boolean = false) {
        if (!list.checked) return
        val contains = contains(list)
        if (contains && !removalConfirmed) { removeCandidate = list; return }
        if (list.local) {
            localPreview[list.id] = !contains
            if (contains) LocalPlaylistPref.removeSongFromPlaylist(context, list.id, song)
            else LocalPlaylistPref.addSongToPlaylist(context, list.id, song)
            Api.HomeCache.library = null
        } else if (PlaylistAdditionsPref.isManaged(context,song)) {
            localPreview[list.id] = !contains
            if (contains) PlaylistAdditionsPref.remove(context,list.id,song)
            else PlaylistAdditionsPref.add(context,list.id,song)
        } else if (trackId.isNotBlank()) {
            val operation = (operations[list.id] ?: 0) + 1
            operations[list.id] = operation
            val done: (Boolean) -> Unit = { ok -> scope.launch {
                if (ok) {
                    if (contains) PlaylistSyncedPendingPref.remove(context, list.id, trackId)
                    else PlaylistSyncedPendingPref.addConfirmed(context, list.id, song.copy(spotifyTrackId = trackId))
                } else if (operations[list.id] == operation) android.widget.Toast.makeText(context, "No se pudo guardar el cambio. Reinténtalo.", android.widget.Toast.LENGTH_SHORT).show()
            }; Unit }
            if (contains) SpotifySync.removeTrackFromPlaylist(context, list.id, trackId, done)
            else SpotifySync.addTrackToPlaylist(context, list.id, trackId, done)
        }
    }
    @Composable fun likedRow() {
        SavedInRow(uiLocalized("Canciones que te gustan"), uiLocalized("Tu colección de canciones"), liked, cover = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF4A39EA), Color(0xFF868AE1)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }, onToggle = { if (liked) confirmUnlike = true else toggleLiked() })
    }
    @Composable fun playlistRow(list: SavedPlaylist) {
        SavedInRow(list.name, when {
            list.failed -> uiLocalized("No se pudo comprobar")
            !list.checked -> uiLocalized("Tu playlist")
            list.local -> uiLocalized("Playlist de este dispositivo")
            else -> uiLocalized("Tu playlist")
        }, saved = contains(list), isLocal = list.local,
            enabled = list.checked && (list.local || trackId.isNotBlank() || PlaylistAdditionsPref.isManaged(context,song)),
            cover = {
                GlideImage(model = list.coverUri, contentDescription = list.name,
                    contentScale = ContentScale.Crop, failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder),
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
            }, onToggle = { toggle(list) })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF181818)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(uiLocalized("Guardar canción"), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(uiLocalized("Nueva playlist"), color = SpotifyGreen, modifier = Modifier.clickable { creating = !creating })
            }
            if (creating) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(uiLocalized("Nombre de la playlist")) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                TextButton(onClick = {
                    LocalPlaylistPref.createPlaylist(context, name.trim(), song)
                    Api.HomeCache.library = null
                    creating = false; name = ""
                }, enabled = name.isNotBlank(), modifier = Modifier.padding(horizontal = 12.dp)) { Text(uiLocalized("Crear"), color = SpotifyGreen) }
            }
            LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp)) {
                if (liked || saved.isNotEmpty()) {
                    item(key = "saved_header") { Text(uiLocalized("Se ha guardado en:"), color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(20.dp, 8.dp)) }
                    if (liked) item(key = "liked_saved") { likedRow() }
                    items(saved, key = { "saved_${it.id}" }) { playlistRow(it) }
                    item(key = "saved_divider") { HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = Color(0xFF363636)) }
                }
                item(key = "add_header") { Text(uiLocalized("Añadir a:"), color = Color(0xFFB3B3B3), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(20.dp, 8.dp)) }
                if (!liked) item(key = "liked_available") { likedRow() }
                items(available, key = { "available_${it.id}" }) { playlistRow(it) }
                if (snapshot.loading && snapshot.playlists.none { it.checked }) item(key = "loading") {
                    Row(Modifier.padding(20.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = SpotifyGreen, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                }
                if (snapshot.failed) item(key = "retry") {
                    TextButton(onClick = { SavedTrackLibrary.ensureLoaded(context, force = true) }) {
                        Text(uiLocalized("No se pudieron comprobar todas las listas. Reintentar"), color = SpotifyGreen)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    removeCandidate?.let { candidate ->
        AlertDialog(onDismissRequest = { removeCandidate = null }, containerColor = Color(0xFF242424),
            title = { Text(uiLocalized("¿Quitar de esta playlist?"), color = Color.White) },
            text = { Text("${song.title} · ${candidate.name}", color = Color(0xFFB3B3B3)) },
            confirmButton = { TextButton(onClick = {
                removeCandidate = null
                val current = SavedTrackLibrary.snapshot.value.playlists.firstOrNull { it.id == candidate.id }
                if (current != null && contains(current)) toggle(current, removalConfirmed = true)
            }) { Text(uiLocalized("Quitar"), color = SpotifyGreen) } },
            dismissButton = { TextButton(onClick = { removeCandidate = null }) { Text(uiLocalized("Cancelar"), color = Color.White) } })
    }
    if (confirmUnlike) AlertDialog(onDismissRequest = { confirmUnlike = false }, containerColor = Color(0xFF242424),
        title = { Text(uiLocalized("¿Quitar de Canciones que te gustan?"), color = Color.White) },
        confirmButton = { TextButton(onClick = { confirmUnlike = false; if (liked) toggleLiked() }) { Text(uiLocalized("Quitar"), color = SpotifyGreen) } },
        dismissButton = { TextButton(onClick = { confirmUnlike = false }) { Text(uiLocalized("Cancelar"), color = Color.White) } })

}

@Composable
private fun SavedInRow(
    name: String,
    subtitle: String,
    saved: Boolean,
    isLocal: Boolean = false,
    enabled: Boolean = true,
    cover: @Composable () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() }
            .padding(20.dp, 8.dp),
    ) {
        cover()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp),
        ) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLocal) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = uiLocalized("Local Storage"),
                        tint = SpotifyGreen,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 3.dp)
                    )
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (saved) {
            Icon(Icons.Default.CheckCircle, contentDescription = uiLocalized("Saved"), tint = SpotifyGreen, modifier = Modifier.size(26.dp))
        } else {
            Icon(Icons.Default.Add, contentDescription = uiLocalized("Add"), tint = Color(0xFFB3B3B3), modifier = Modifier.size(26.dp))
        }
    }
}
