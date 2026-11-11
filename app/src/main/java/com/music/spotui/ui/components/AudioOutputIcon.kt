package com.music.spotui.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.music.spotui.ui.utils.AudioDeviceHelper
import com.music.spotui.ui.utils.PlaybackDeviceKind
import com.music.spotui.ui.utils.playbackDeviceKind

@Composable
fun PlaybackDeviceIcon(kind:PlaybackDeviceKind,tint:Color,modifier:Modifier=Modifier,contentDescription:String?=null) {
    val icon=when(kind){
        PlaybackDeviceKind.PHONE->com.music.spotui.ui.utils.PlaybackDeviceIcons.Phone
        PlaybackDeviceKind.TABLET->com.music.spotui.ui.utils.PlaybackDeviceIcons.Tablet
        PlaybackDeviceKind.HEADPHONES->com.music.spotui.ui.utils.PlaybackDeviceIcons.Headphones
        PlaybackDeviceKind.SPEAKER->com.music.spotui.ui.utils.PlaybackDeviceIcons.Speaker
        PlaybackDeviceKind.OTHER->Icons.Default.DevicesOther
    }
    Icon(icon,contentDescription=contentDescription,tint=tint,modifier=modifier)
}

/** Shared by mini, full-screen and tablet players. */
@Composable
fun AudioOutputIcon(modifier:Modifier=Modifier,contentDescription:String?=null) {
    val route by AudioDeviceHelper.currentRouteKindState
    val cloud by com.music.spotui.sync.CloudPlayback.status.collectAsState()
    val account by com.music.spotui.sync.GoogleDeviceSync.status.collectAsState()
    val owner=account.devices.firstOrNull {it.id==cloud.ownerId}
    val kind=if(cloud.remote && owner!=null)playbackDeviceKind(owner.kind,owner.name)else route
    val active=if(cloud.remote)owner?.online==true else kind==PlaybackDeviceKind.HEADPHONES || kind==PlaybackDeviceKind.SPEAKER
    PlaybackDeviceIcon(kind,if(active)Color(0xFF1ED760)else Color(0xFFB3B3B3),modifier,contentDescription)
}
