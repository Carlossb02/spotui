package com.music.spotui.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The same shape and saved/unsaved colours for albums and playlist shortcuts. */
@Composable
fun LibrarySaveIcon(saved: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Icon(
        imageVector = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        tint = if (saved) Color(0xFF1ED760) else Color.White,
        contentDescription = if (saved) "Quitar de tu biblioteca" else "Guardar en tu biblioteca",
        modifier = Modifier.size(27.dp).clickable(enabled = enabled, onClick = onClick),
    )
}
