package com.music.spotui.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.music.spotui.R
import com.music.spotui.ui.utils.AudioDeviceHelper

/** Shared by the mini, full-screen and tablet players. No change to audio routing. */
@Composable
fun AudioOutputIcon(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val bluetooth by AudioDeviceHelper.bluetoothRouteActiveState
    if (bluetooth) {
        Icon(Icons.Default.Headphones, contentDescription = contentDescription,
            tint = Color(0xFF1ED760), modifier = modifier)
    } else {
        Icon(painterResource(R.drawable.ic_devices), contentDescription = contentDescription,
            tint = Color.White, modifier = modifier)
    }
}
