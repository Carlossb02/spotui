package com.music.spotui.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal fun spotifyPlaylistUrl(id: String): Uri? {
    val realId = id.removePrefix("spotify:playlist:").removePrefix("playlist:")
    if (!realId.matches(Regex("[A-Za-z0-9]{22}"))) return null
    return Uri.parse("https://open.spotify.com/playlist/" + realId)
}

internal fun openSpotifyPlaylist(context: Context, url: Uri): Boolean {
    val official = Intent(Intent.ACTION_VIEW, url).setPackage("com.spotify.music")
    try {
        context.startActivity(official)
        return true
    } catch (_: ActivityNotFoundException) {
        // Restrict the fallback to browsers because Spotui also handles Spotify links.
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

/** Opens Spotify's own site in a browser-owned tab over Spotui. */
internal fun openSpotifyPlaylistTab(context: Context, url: Uri): Boolean {
    val browserPackage = CustomTabsClient.getPackageName(context, null)
        ?: return openSpotifyPlaylist(context, url)
    val tab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
        .build()
    tab.intent.setPackage(browserPackage)
    return try {
        tab.launchUrl(context, url)
        true
    } catch (_: ActivityNotFoundException) {
        openSpotifyPlaylist(context, url)
    }
}

@Composable
internal fun PlaylistSpotifyMenuItems(
    url: Uri,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onOpenSpotify: () -> Unit,
    onManage: () -> Unit,
) {
    val context = LocalContext.current
    PlaylistExternalAction(Icons.Default.OpenInNew, "Abrir en Spotify") {
        onDismiss()
        onOpenSpotify()
    }
    if (isOwner) {
        PlaylistExternalAction(Icons.Default.Settings, "Gestionar en Spotify") {
            onDismiss()
            onManage()
        }
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

internal enum class PlaylistSpotifyManagementSection(val label: String, val icon: ImageVector, val help: String) {
    DETAILS("Nombre, descripción y portada", Icons.Default.Edit,
        "En Spotify, abre el menú de la lista y elige Editar lista. Desde ahí puedes cambiar el nombre, la descripción y la portada."),
    PRIVACY("Privacidad y perfil", Icons.Default.Lock,
        "En el menú de la lista de Spotify, elige Hacer privada o Hacer pública. Para mostrarla u ocultarla en tu perfil, usa Añadir al perfil o Quitar del perfil."),
    DELETE("Eliminar lista", Icons.Default.Delete,
        "En Spotify, abre los tres puntos de la lista y elige Eliminar lista."),
    COLLABORATORS("Colaboradores", Icons.Default.GroupAdd,
        "En Spotify, abre el menú de una lista privada y elige Invitar a colaboradores. Para quitar un permiso, toca las fotos de perfil bajo el nombre y abre el menú de esa persona."),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSpotifyManagementSheet(
    initialSection: PlaylistSpotifyManagementSection? = null,
    onDismiss: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenTab: () -> Unit,
) {
    var selected by remember(initialSection) { mutableStateOf(initialSection) }
    if (selected == null) ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF202020)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Gestionar en Spotify", color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 12.dp))
            PlaylistSpotifyManagementSection.entries.forEach { action ->
                PlaylistExternalAction(action.icon, action.label) { selected = action }
            }
        }
    }
    selected?.let { action ->
        val back = { if (initialSection == null) selected = null else onDismiss() }
        AlertDialog(
            onDismissRequest = back,
            title = { Text(action.label) },
            text = { Text(action.help) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onOpenTab()
                }) { Text("Abrir pestaña de Spotify") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDismiss()
                    onOpenSpotify()
                }) { Text("Usar app de Spotify") }
            },
        )
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
