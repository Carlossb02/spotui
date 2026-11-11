package com.music.spotui.ui.components

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.sync.CloudPlayback
import com.music.spotui.sync.GoogleDeviceSync
import com.music.spotui.sync.SyncOptions
import com.music.spotui.ui.utils.AudioDeviceHelper
import com.music.spotui.ui.utils.AudioDeviceType
import com.music.spotui.ui.utils.PlaybackDeviceKind
import com.music.spotui.ui.utils.playbackDeviceKind
import com.music.spotui.util.uiLocalized

private val DeviceGreen=Color(0xFF1ED760)
private val DeviceSecondary=Color(0xFFB3B3B3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(context:Context,onDismiss:()->Unit) {
    val cloud by CloudPlayback.status.collectAsState()
    val account by GoogleDeviceSync.status.collectAsState()
    val options by SyncOptions.values.collectAsState()
    val playerSync=options[SyncOptions.Category.PLAYER]!=false
    val localId=cloud.localId.ifBlank {GoogleDeviceSync.localDeviceId()}
    val localName=account.devices.firstOrNull {it.id==localId}?.name ?: Build.MODEL
    val localKind=if(LocalConfiguration.current.smallestScreenWidthDp>=600)PlaybackDeviceKind.TABLET else PlaybackDeviceKind.PHONE
    var devices by remember {mutableStateOf(AudioDeviceHelper.getAvailableAudioDevices(context))}
    var permission by remember {mutableStateOf(AudioDeviceHelper.hasBluetoothPermission(context))}
    val activeRoute=devices.firstOrNull {it.isActive}
    val remoteDevices=remember(account.devices,localId) {account.devices.filter {it.id!=localId}.distinctBy {it.id}}
    val routes=remember(devices) {devices.distinctBy {it.type to it.id}}
    val owner=account.devices.firstOrNull {it.id==cloud.ownerId}
    val destinationAvailable=!cloud.remote || owner?.online==true
    val destinationTint=if(destinationAvailable)DeviceGreen else DeviceSecondary
    val ownerName=if(cloud.remote)owner?.name ?: cloud.ownerName else localName
    val ownerKind=if(cloud.remote)playbackDeviceKind(owner?.kind.orEmpty(),ownerName)
        else if(activeRoute?.type in listOf(AudioDeviceType.BLUETOOTH,AudioDeviceType.WIRED_HEADPHONES))PlaybackDeviceKind.HEADPHONES else localKind
    val listState=rememberLazyListState()
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val height=(LocalConfiguration.current.screenHeightDp*0.84f).dp
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission=it;devices=AudioDeviceHelper.getAvailableAudioDevices(context)
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState,
        sheetGesturesEnabled=false,dragHandle=null,containerColor=Color(0xFF121212),contentColor=Color.White,
        shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)) {
        // Fixed viewport and header. Only the list owns vertical flings; they cannot drag the panel.
        Column(Modifier.fillMaxWidth().height(height).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=12.dp,bottom=8.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Text(uiLocalized("Connect to a device"),color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold,
                    modifier=Modifier.weight(1f))
                IconButton(onClick=onDismiss) {Icon(Icons.Default.Close,uiLocalized("Close"),tint=DeviceSecondary)}
            }
            HorizontalDivider(color=Color(0xFF282828))
            LazyColumn(state=listState,modifier=Modifier.fillMaxWidth().weight(1f),
                contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=24.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                item(key="playback_location",contentType="summary") {
                    Row(Modifier.fillMaxWidth().background(if(destinationAvailable)Color(0xFF1C2820)else Color(0xFF242424),RoundedCornerShape(12.dp)).padding(16.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        PlaybackDeviceIcon(ownerKind,destinationTint,Modifier.size(30.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(uiLocalized(if(destinationAvailable)"Audio on" else "Last playback device"),color=DeviceSecondary,fontSize=12.sp)
                            Text(if(!cloud.remote && ownerKind==PlaybackDeviceKind.HEADPHONES)activeRoute?.name.orEmpty() else ownerName,
                                color=destinationTint,fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(uiLocalized(if(!destinationAvailable)"Offline" else if(cloud.remote)"Controlled from this device" else "This device")+
                                if(!cloud.remote && ownerKind==PlaybackDeviceKind.HEADPHONES)" · $localName" else "",
                                color=DeviceSecondary,fontSize=12.sp)
                        }
                    }
                }
                if(cloud.remote)item(key="remote_volume",contentType="volume") {
                    Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)) {
                        var volume by remember(cloud.ownerId){mutableStateOf(cloud.volume.coerceIn(0f,1f))}
                        var changing by remember(cloud.ownerId){mutableStateOf(false)}
                        LaunchedEffect(cloud.ownerId,cloud.volume,cloud.error,owner?.online) {
                            if(!changing)volume=cloud.volume.coerceIn(0f,1f)
                        }
                        Text(uiLocalized("Device volume"),color=DeviceSecondary,fontSize=12.sp)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeDown,null,tint=DeviceSecondary,modifier=Modifier.size(20.dp))
                            Slider(value=volume,onValueChange={changing=true;volume=it},
                                onValueChangeFinished={changing=false;CloudPlayback.setRemoteVolume(volume)},
                                enabled=cloud.connected && owner?.online==true && cloud.volume>=0f,
                                modifier=Modifier.weight(1f),colors=SliderDefaults.colors(thumbColor=DeviceGreen,
                                    activeTrackColor=DeviceGreen,inactiveTrackColor=Color(0xFF535353)))
                            Icon(Icons.Default.VolumeUp,null,tint=DeviceSecondary,modifier=Modifier.size(20.dp))
                        }
                    }
                }
                item(key="local_heading",contentType="heading") {DeviceSectionTitle("This device")}
                item(key="local_device",contentType="device") {
                    DeviceChoiceRow(uiLocalized("This device"),localName+" · "+uiLocalized(if(cloud.remote)"Tap to play here" else "Playback device"),
                        localKind,selected=!cloud.remote,enabled=!cloud.remote || localId.isNotBlank(),
                        pending=localId.isNotBlank() && cloud.transferring==localId,
                        onClick={if(cloud.remote)CloudPlayback.transferTo(localId)})
                }
                if(account.email.isNotBlank() && playerSync && remoteDevices.isNotEmpty()) {
                    item(key="remote_heading",contentType="heading") {DeviceSectionTitle("Other devices")}
                    items(remoteDevices,key={"remote:${it.id}"},contentType={"device"}) {device->
                        val enabled=device.online && device.id !in cloud.disabledDevices
                        val selected=device.id==cloud.ownerId && enabled
                        DeviceChoiceRow(device.name,uiLocalized(when {
                            device.id in cloud.disabledDevices->"Local playback only"
                            cloud.transferring==device.id->"Connecting…"
                            selected->"Playback device"
                            device.online->"Available"
                            else->"Offline"
                        }),playbackDeviceKind(device.kind,device.name),selected,enabled,
                            cloud.transferring==device.id,onClick={CloudPlayback.transferTo(device.id)})
                    }
                }
                item(key="outputs_heading",contentType="heading") {DeviceSectionTitle("Audio outputs on this device")}
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S && !permission)item(key="permission",contentType="permission") {
                    Column(Modifier.fillMaxWidth().background(Color(0xFF242424),RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text(uiLocalized("Grant permission to discover paired Bluetooth devices"),color=DeviceSecondary,fontSize=12.sp)
                        TextButton(onClick={permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)}) {
                            Text(uiLocalized("Grant"),color=DeviceGreen)
                        }
                    }
                }
                items(routes,key={"route:${it.type}:${it.id}"},contentType={"device"}) {route->
                    val kind=when(route.type) {
                        AudioDeviceType.SPEAKER->PlaybackDeviceKind.SPEAKER
                        AudioDeviceType.BLUETOOTH,AudioDeviceType.WIRED_HEADPHONES->PlaybackDeviceKind.HEADPHONES
                        else->PlaybackDeviceKind.OTHER
                    }
                    val selected=route.isActive && !cloud.remote
                    DeviceChoiceRow(if(route.type==AudioDeviceType.SPEAKER)uiLocalized("Device speaker") else route.name,
                        uiLocalized(when {selected->"Active audio route";route.isConnected->"Connected";else->"Paired Bluetooth Device"}),
                        kind,selected,onClick={
                            if(cloud.remote && localId.isNotBlank())CloudPlayback.transferTo(localId)
                            if(!AudioDeviceHelper.switchAudioOutput(context,route))AudioDeviceHelper.openSystemAudioSwitcher(context)
                            devices=AudioDeviceHelper.getAvailableAudioDevices(context)
                        })
                }
                cloud.error?.let {message->item(key="error",contentType="error") {
                    Text(message,color=Color(0xFFFFB4AB),fontSize=13.sp,modifier=Modifier.padding(12.dp))
                }}
                item(key="system_switcher",contentType="action") {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical=12.dp),color=Color(0xFF282828))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            AudioDeviceHelper.openSystemAudioSwitcher(context);onDismiss()
                        }.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings,null,tint=DeviceSecondary,modifier=Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(uiLocalized("System Audio Switcher"),color=Color.White,fontSize=14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceSectionTitle(text:String) {
    Text(uiLocalized(text),color=DeviceSecondary,fontSize=12.sp,fontWeight=FontWeight.SemiBold,
        modifier=Modifier.padding(start=12.dp,top=16.dp,bottom=4.dp))
}

@Composable
private fun DeviceChoiceRow(title:String,subtitle:String,kind:PlaybackDeviceKind,selected:Boolean,
    enabled:Boolean=true,pending:Boolean=false,onClick:()->Unit) {
    val tint=if(selected)DeviceGreen else if(enabled)Color.White else Color(0xFF727272)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable(enabled=enabled && !pending,onClick=onClick).heightIn(min=68.dp).padding(horizontal=12.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically) {
        PlaybackDeviceIcon(kind,tint,Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title,color=tint,fontSize=15.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,
                maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(subtitle,color=if(selected)DeviceGreen else DeviceSecondary,fontSize=12.sp,
                maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(pending)CircularProgressIndicator(Modifier.size(20.dp),color=DeviceGreen,strokeWidth=2.dp)
        else if(selected)Icon(Icons.Default.Check,uiLocalized("Playback device"),tint=DeviceGreen,modifier=Modifier.size(20.dp))
    }
}
