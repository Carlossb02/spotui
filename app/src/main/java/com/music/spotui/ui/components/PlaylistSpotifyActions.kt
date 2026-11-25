package com.music.spotui.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal fun spotifyPlaylistUrl(id: String): Uri? {
    val realId = id.trim().substringBefore('?').substringBefore('|')
        .removePrefix("https://open.spotify.com/playlist/")
        .removePrefix("spotify:playlist:").removePrefix("playlist:").removePrefix("playlist/")
    if (!realId.matches(Regex("[A-Za-z0-9]{22}"))) return null
    return Uri.parse("https://open.spotify.com/playlist/" + realId)
}

internal fun openSpotifyPlaylist(context: Context, url: Uri): Boolean {
    val official = Intent(Intent.ACTION_VIEW, url).setPackage("com.spotify.music")
    try {
        context.startActivity(official)
        return true
    } catch (_: ActivityNotFoundException) {
        // Restrict the fallback to browsers because Spotidroid also handles Spotify links.
    }
    val browser = Intent(Intent.ACTION_VIEW, url).apply {
        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    }
    return try {
        context.startActivity(browser)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No hay una app para abrir Spotify", Toast.LENGTH_SHORT).show()
        false
    }
}

@Composable
internal fun PlaylistSpotifyMenuItems(
    url: Uri,
    onDismiss: () -> Unit,
    onOpenSpotify: () -> Unit,
) {
    val context = LocalContext.current
    PlaylistExternalAction(Icons.Default.OpenInNew, "Abrir en Spotify") {
        onDismiss()
        onOpenSpotify()
    }
    PlaylistExternalAction(Icons.Default.Share, "Compartir enlace") {
        onDismiss()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url.toString())
        }
        try {
            context.startActivity(Intent.createChooser(share, "Compartir lista"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No hay una app para compartir el enlace", Toast.LENGTH_SHORT).show()
        }
    }
    PlaylistExternalAction(Icons.Default.ContentCopy, "Copiar enlace") {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Lista de Spotify", url.toString()))
        if (clipboard != null && android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }
}

@Composable
private fun PlaylistExternalAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(20.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
