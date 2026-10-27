package com.music.spotui.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.layout.ContentScale
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylist
import com.music.spotui.data.api.*
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LocalPlaylist
import com.music.spotui.data.preferences.LocalPlaylistPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spotify-style destination picker for copying a read-only playlist.
 * Create new → a full local copy that remains in Spotui only.
 * Existing destination → append only if ownership/collaboration verified.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun PlaylistCopySheet(
    context: Context,
    sourceName: String,
    songs: List<SongsModel>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var localDestinations by remember { mutableStateOf<List<LocalPlaylist>>(emptyList()) }
    var remoteDestinations by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var newName by remember { mutableStateOf("Copia de $sourceName") }
    var creating by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        localDestinations = LocalPlaylistPref.getLocalPlaylists(context)
        remoteDestinations = withContext(Dispatchers.IO) {
            if (!SpotifyTokenProvider.ensureToken(context)) return@withContext emptyList()
            val userId = Spotify.me().getOrNull()?.id.orEmpty()
            if (userId.isBlank()) return@withContext emptyList()
            val collected = mutableListOf<SpotifyPlaylist>()
            var offset = 0
            do {
                val page = Spotify.myPlaylists(limit = 50, offset = offset).getOrNull() ?: break
                collected.addAll(page.items)
                offset += page.items.size
                if (page.items.isEmpty() || offset >= page.total) break
            } while (offset < 200)
            collected.distinctBy { it.id }.filter { it.owner?.id == userId }
        }
    }

    // Collaborative playlists may be in the user's library despite another owner.
    // Verify them individually, off the UI thread, before offering an editable target.
    LaunchedEffect(Unit) {
        val userId = withContext(Dispatchers.IO) { Spotify.me().getOrNull()?.id.orEmpty() }
        if (userId.isBlank()) return@LaunchedEffect
        val all = withContext(Dispatchers.IO) { Spotify.myPlaylists(limit = 100).getOrNull()?.items.orEmpty() }
        all.filter { it.owner?.id != userId }.forEach { playlist ->
            launch {
                if (PlaylistAccess.load(context, playlist.id).canEdit &&
                    remoteDestinations.none { it.id == playlist.id }) {
                    remoteDestinations = remoteDestinations + playlist
                }
            }
        }
    }

    fun done(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        working = false
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1C)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Añadir a una lista", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 8.dp))
            Text("Copiar ${songs.size} canciones de «$sourceName»", color = Color.LightGray, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp))
            if (creating) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("Nombre de la nueva lista") }, singleLine = true,
                    enabled = !working, modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { creating = false }, enabled = !working) { Text("Cancelar") }
                    Button(onClick = {
                        if (newName.isBlank() || working) return@Button
                        working = true
                        scope.launch(Dispatchers.IO) {
                            LocalPlaylistPref.createPlaylistWithSongs(context, newName.trim(), songs)
                            Api.HomeCache.library = null
                            withContext(Dispatchers.Main) { done("Lista creada y guardada en Spotui") }
                        }
                    }, enabled = !working && newName.isNotBlank()) { Text("Crear lista") }
                }
            } else {
                Row(Modifier.fillMaxWidth().clickable(enabled = !working) { creating = true }
                    .padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = Color(0xFF1ED760), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(17.dp))
                    Text("Nueva lista", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = Color(0xFF343434))
                LazyColumn(Modifier.heightIn(max = 430.dp)) {
                    items(localDestinations, key = { "local_${it.id}" }) { destination ->
                        DestinationRow(destination.name, destination.coverUri, !working) {
                            working = true
                            scope.launch(Dispatchers.IO) {
                                songs.forEach { LocalPlaylistPref.addSongToPlaylist(context, destination.id, it) }
                                Api.HomeCache.library = null
                                withContext(Dispatchers.Main) { done("Canciones añadidas a «${destination.name}»") }
                            }
                        }
                    }
                    items(remoteDestinations, key = { "spotify_${it.id}" }) { destination ->
                        DestinationRow(destination.name, destination.images.firstOrNull()?.url, !working) {
                            working = true
                            scope.launch(Dispatchers.IO) {
                                // Server authorization is authoritative; local metadata is only a preliminary filter.
                                if (!PlaylistAccess.load(context, destination.id).canEdit) {
                                    withContext(Dispatchers.Main) { done("No tienes permiso para modificar esta lista") }
                                    return@launch
                                }
                                val uris = songs.map { SpotifySync.cleanTrackId(it.spotifyTrackId) }
                                    .filter { it.matches(Regex("[A-Za-z0-9]{22}")) }
                                    .map { "spotify:track:$it" }
                                var success = true
                                for (chunk in uris.chunked(100)) {
                                    if (Spotify.addTracksToPlaylist(destination.id, chunk).isFailure) { success = false; break }
                                }
                                Api.HomeCache.invalidatePlaylist(destination.id)
                                withContext(Dispatchers.Main) {
                                    done(if (success) "${uris.size} canciones añadidas en Spotify" else "No se pudieron añadir todas las canciones")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DestinationRow(name: String, cover: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!cover.isNullOrBlank()) GlideImage(
            model = cover, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
        ) else Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.LibraryAdd, null, tint = Color.LightGray)
        }
        Spacer(Modifier.width(15.dp))
        Text(name, color = if (enabled) Color.White else Color.Gray, fontSize = 15.sp)
    }
}
