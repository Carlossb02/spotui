package com.music.spotui.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LocalPlaylistPref
import com.music.spotui.data.preferences.PlaylistAdditionsPref
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Two explicit destinations: a Spotify catalogue track is synchronized only after a
 * successful Spotify write; a user-provided YouTube track is stored solely on-device. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun PlaylistAddMusicSheet(
    playlistId: String,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val localPlaylist = playlistId.startsWith("local_pl_")
    val searchModel: SearchViewModel = hiltViewModel()
    val searchResponse by searchModel.songs.collectAsState()
    var mode by remember { mutableStateOf("choose") }
    var query by remember { mutableStateOf("") }
    var youtubeUrl by remember { mutableStateOf("") }
    var videoId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var artwork by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF202020),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.add_music_to_playlist), fontWeight = FontWeight.Bold)
            when (mode) {
                "choose" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { mode = "spotify" }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.search_catalog))
                            Text(
                                if (localPlaylist) stringResource(R.string.saved_locally)
                                else stringResource(R.string.synced_with_spotify),
                                color = Color.LightGray,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { mode = "youtube" }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.add_from_youtube))
                            Text(stringResource(R.string.only_saved_locally), color = Color.LightGray)
                        }
                    }
                }
                "spotify" -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; searchModel.search(it) },
                        label = { Text(stringResource(R.string.search_tracks)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    val matches: List<SongsModel> = when (val result = searchResponse) {
                        is Response.Success -> result.data
                        is Response.Loading -> result.partialData.orEmpty()
                        is Response.Error -> emptyList()
                    }
                    LazyColumn(modifier = Modifier.height(350.dp)) {
                        items(matches, key = { it.id }) { song ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    if (localPlaylist) {
                                        LocalPlaylistPref.addSongToPlaylist(context, playlistId, song)
                                        onAdded(); onDismiss()
                                    } else {
                                        val spotifyTrack = SpotifySync.cleanTrackId(song.spotifyTrackId).takeIf {
                                            it.matches(Regex("[A-Za-z0-9]{22}"))
                                        }
                                        if (spotifyTrack == null) {
                                            Toast.makeText(context, R.string.track_not_spotify, Toast.LENGTH_SHORT).show()
                                        } else {
                                            busy = true
                                            SpotifySync.addTrackToPlaylist(context, playlistId, spotifyTrack) { ok ->
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    busy = false
                                                    if (ok) {
                                                        com.music.spotui.data.preferences.PlaylistSyncedPendingPref.addConfirmed(
                                                            context, playlistId, song.copy(spotifyTrackId = spotifyTrack)
                                                        )
                                                        onAdded(); onDismiss()
                                                    }
                                                    else Toast.makeText(context, R.string.spotify_add_failed, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlideImage(
                                    model = song.coverUri,
                                    contentDescription = null,
                                    modifier = Modifier.size(46.dp),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.singer, color = Color.LightGray, maxLines = 1)
                                }
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        }
                    }
                }
                "youtube" -> {
                    OutlinedTextField(
                        value = youtubeUrl, onValueChange = { youtubeUrl = it },
                        label = { Text(stringResource(R.string.youtube_link)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Button(onClick = {
                        val parsed = SongPlayer.videoIdFromYouTubeLink(youtubeUrl)
                        if (parsed == null) {
                            Toast.makeText(context, R.string.invalid_youtube_link, Toast.LENGTH_SHORT).show()
                        } else {
                            videoId = parsed
                            artwork = "https://i.ytimg.com/vi/$parsed/hqdefault.jpg"
                            title = ""
                            artist = ""
                            mode = "metadata"
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_label)) }
                }
                "metadata" -> {
                    GlideImage(
                        model = artwork, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(110.dp).background(Color.DarkGray, RoundedCornerShape(8.dp))
                    )
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.track_title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.artist)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.album)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(artwork, { artwork = it }, label = { Text(stringResource(R.string.cover_uri)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = {
                        val id = "yt_local:$videoId"
                        val track = SongsModel(
                            id = id.hashCode() and 0x7fffffff,
                            title = title.ifBlank { "YouTube · $videoId" },
                            album = album,
                            singer = artist.ifBlank { "YouTube" },
                            coverUri = artwork,
                            url = id,
                        )
                        if (localPlaylist) LocalPlaylistPref.addSongToPlaylist(context, playlistId, track)
                        else PlaylistAdditionsPref.add(context, playlistId, track)
                        onAdded(); onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_locally)) }
                }
            }
            if (mode != "choose") {
                TextButton(onClick = { mode = if (mode == "metadata") "youtube" else "choose" }) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}
