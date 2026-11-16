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
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
    Icon(
        painter = androidx.compose.ui.res.painterResource(if (saved) com.music.spotui.R.drawable.collection_heart_saved else com.music.spotui.R.drawable.collection_heart),
        tint = if (saved) Color(0xFF1ED760) else Color.White,
        contentDescription = if (saved) "Quitar de tu biblioteca" else "Guardar en tu biblioteca",
        modifier = Modifier.size(24.dp),
    )
    }
}
