package com.music.spotui.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.LibraryEntry

internal enum class LibraryPlaylistAction {
    DOWNLOAD, ADD, TRACKS, NAME, DETAILS, REMOVE, QUEUE, COPY, COLLABORATORS, PRIVACY
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
internal fun LibraryPlaylistActions(
    entry: LibraryEntry, pinned: Boolean, onTogglePin: () -> Unit,
    onDismiss: () -> Unit, onAction: (LibraryPlaylistAction) -> Unit,
) {
    val context = LocalContext.current
    val url = spotifyPlaylistUrl(entry.linkedSpotifyPlaylistId.ifBlank { entry.spotifyId })
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF202020),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GlideImage(model = entry.coverUri, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(entry.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.subtitle, color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = Color.White.copy(alpha = .12f))
            if (url != null) MenuRow(Icons.Default.Share, "Compartir") {
                onDismiss()
                try {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url.toString())
                    }, "Compartir lista"))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "No hay una app para compartir", Toast.LENGTH_SHORT).show()
                }
            }
            MenuRow(Icons.Default.Download, "Descargar") { onAction(LibraryPlaylistAction.DOWNLOAD) }
            MenuRow(Icons.Default.AddCircleOutline, "Añadir a esta lista") { onAction(LibraryPlaylistAction.ADD) }
            MenuRow(Icons.Default.List, "Editar canciones") { onAction(LibraryPlaylistAction.TRACKS) }
            MenuRow(Icons.Default.Edit, "Cambiar nombre") { onAction(LibraryPlaylistAction.NAME) }
            MenuRow(Icons.Default.Image, if (url == null) "Descripción y portada" else "Descripción") {
                onAction(LibraryPlaylistAction.DETAILS)
            }
            MenuRow(Icons.Default.Delete, "Quitar de Biblioteca") { onAction(LibraryPlaylistAction.REMOVE) }
            MenuRow(Icons.Default.PushPin, if (pinned) "Desfijar lista" else "Fijar lista") {
                onTogglePin()
                onDismiss()
            }
            MenuRow(Icons.Default.PlaylistAdd, "Añadir a la cola") { onAction(LibraryPlaylistAction.QUEUE) }
            MenuRow(Icons.Default.Add, "Añadir a otra lista") { onAction(LibraryPlaylistAction.COPY) }
            if (url != null) {
                MenuRow(Icons.Default.GroupAdd, "Colaboradores en Spotify") { onAction(LibraryPlaylistAction.COLLABORATORS) }
                MenuRow(Icons.Default.Lock, "Privacidad en Spotify") { onAction(LibraryPlaylistAction.PRIVACY) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFB8B8B8))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
