package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.preferences.downloadsVersion
import com.music.spotui.data.preferences.isDownloaded

/** Spotify-style saved-for-offline glyph preceding the artist on song rows.
 * Uses one shared revision flow, so single-track and bulk downloads update all visible rows. */
@Composable
fun DownloadedSongIndicator(songId: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version by downloadsVersion.collectAsState()
    val downloaded = remember(context, songId, version) { isDownloaded(context, songId.toString()) }
    if (downloaded) {
        Box(modifier = modifier.size(14.dp).background(Color(0xFF1ED760), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowDownward, contentDescription = "Descargado",
                tint = Color(0xFF111111), modifier = Modifier.size(11.dp))
        }
    }
}
