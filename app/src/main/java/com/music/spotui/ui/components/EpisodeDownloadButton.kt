package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.downloadsVersion
import com.music.spotui.data.preferences.isDownloaded
import com.music.spotui.data.preferences.removeDownload
import com.music.spotui.di.SongPlayer
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares the player's queue and offline store; leaving the screen never cancels a download. */
@Composable
fun EpisodeDownloadButton(episode: SongsModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by downloadsVersion.collectAsState()
    val downloaded = remember(context, episode.id, version) { isDownloaded(context, episode.id.toString()) }
    val entryFlow = remember(episode.url) {
        SongPlayer.downloadQueue.map { queue -> queue.firstOrNull { it.song.url == episode.url } }.distinctUntilChanged()
    }
    val entry by entryFlow.collectAsState(initial = SongPlayer.downloadQueue.value.firstOrNull { it.song.url == episode.url })
    var showMenu by remember(episode.url) { mutableStateOf(false) }
    var removing by remember(episode.url) { mutableStateOf(false) }
    val downloadLabel = uiLocalized("Descargar episodio")
    val downloadedLabel = uiLocalized("Descargado")
    val cancelLabel = uiLocalized("Cancelar descarga")
    val removeLabel = uiLocalized("Eliminar descarga")
    val errorLabel = uiLocalized("No se pudo eliminar la descarga")
    val green = Color(0xFF1ED760)
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(enabled = !removing, onClick = {
            when {
                entry != null -> SongPlayer.cancelDownload(episode.url)
                downloaded -> showMenu = true
                else -> SongPlayer.downloadSong(episode, context.applicationContext)
            }
        }) {
            when {
                entry != null -> Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    val task = entry
                    if (task?.stage == SongPlayer.DownloadStage.QUEUED) {
                        CircularProgressIndicator(Modifier.fillMaxSize(), color = green, strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(progress = { ((task?.progress ?: 0) / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxSize(), color = green, strokeWidth = 2.dp,
                            trackColor = Color(0xFF434343))
                    }
                    Icon(Icons.Default.Close, cancelLabel, tint = green, modifier = Modifier.size(15.dp))
                }
                downloaded -> Box(Modifier.size(23.dp).background(green, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowDownward, downloadedLabel, tint = Color(0xFF121212), modifier = Modifier.size(18.dp))
                }
                else -> Icon(Icons.Outlined.DownloadForOffline, downloadLabel, tint = Color(0xFFB3B3B3), modifier = Modifier.size(26.dp))
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text(removeLabel) }, onClick = {
                showMenu = false
                removing = true
                scope.launch {
                    try {
                        val removed = withContext(Dispatchers.IO) { runCatching { removeDownload(context, episode.id.toString()) }.getOrDefault(false) }
                        if (!removed) android.widget.Toast.makeText(context, errorLabel, android.widget.Toast.LENGTH_SHORT).show()
                    } finally { removing = false }
                }
            })
        }
    }
}
