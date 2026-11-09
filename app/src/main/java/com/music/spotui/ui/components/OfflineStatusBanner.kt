package com.music.spotui.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.spotui.sync.ConnectionState

@Composable
fun OfflineStatusBanner(modifier:Modifier=Modifier) {
    val online by ConnectionState.online.collectAsState()
    AnimatedVisibility(visible=!online,modifier=modifier,enter=fadeIn()+slideInVertically {it},exit=fadeOut()+slideOutVertically {it}) {
        Surface(color=Color(0xFF333333),shape=RoundedCornerShape(12.dp),shadowElevation=6.dp,modifier=Modifier.padding(horizontal=16.dp)) {
            Row(Modifier.padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.CloudOff,null,tint=Color.White);Text("Estás sin conexión",color=Color.White)
            }
        }
    }
}
