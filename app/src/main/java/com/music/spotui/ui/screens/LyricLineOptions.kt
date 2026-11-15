package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.preferences.*
import com.music.spotui.util.uiLocalized

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
internal fun LyricLineOptions(onDismiss:()->Unit) {
    val context=LocalContext.current
    var enabled by remember {mutableStateOf(isPlayerLyricLineEnabled(context))}
    var appearance by remember {mutableStateOf(getLyricLineAppearance(context))}
    fun save(value:LyricLineAppearance){appearance=value;setLyricLineAppearance(context,value)}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=Color(0xFF181818)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(uiLocalized("Current lyric line"),style=MaterialTheme.typography.titleLarge,color=Color.White)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(uiLocalized(if(enabled)"Enabled" else "Disabled"),color=Color.White)
                Switch(enabled,onCheckedChange={enabled=it;setPlayerLyricLineEnabled(context,it)})
            }
            if(enabled) {
                Text(uiLocalized("Position"),color=Color.White)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {LyricPosition.entries.forEach {value->
                    FilterChip(selected=appearance.position==value,onClick={save(appearance.copy(position=value))},label={Text(uiLocalized(value.label))})
                }}
                Text(uiLocalized("Size"),color=Color.White)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {LyricSize.entries.forEach {value->
                    FilterChip(selected=appearance.size==value,onClick={save(appearance.copy(size=value))},label={Text(uiLocalized(value.label))})
                }}
                Text(uiLocalized("Color and animation"),color=Color.White)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {LyricColorMode.entries.forEach {value->
                    FilterChip(selected=appearance.color==value,onClick={save(appearance.copy(color=value))},label={Text(uiLocalized(value.label))})
                }}
                if(appearance.color==LyricColorMode.CUSTOM) {
                    val hsv=remember(appearance.customColor){FloatArray(3).also {android.graphics.Color.colorToHSV(appearance.customColor,it)}}
                    var hue by remember(appearance.customColor){mutableFloatStateOf(hsv[0])}
                    var saturation by remember(appearance.customColor){mutableFloatStateOf(hsv[1])}
                    var brightness by remember(appearance.customColor){mutableFloatStateOf(hsv[2])}
                    fun argb()=android.graphics.Color.HSVToColor(floatArrayOf(hue,saturation,brightness))
                    Box(Modifier.fillMaxWidth().height(38.dp).background(Color(argb())))
                    Text(uiLocalized("Hue"),color=Color.White)
                    Slider(hue,{hue=it},valueRange=0f..360f,onValueChangeFinished={save(appearance.copy(customColor=argb()))})
                    Text(uiLocalized("Saturation"),color=Color.White)
                    Slider(saturation,{saturation=it},onValueChangeFinished={save(appearance.copy(customColor=argb()))})
                    Text(uiLocalized("Brightness"),color=Color.White)
                    Slider(brightness,{brightness=it},onValueChangeFinished={save(appearance.copy(customColor=argb()))})
                    Text(String.format("#%06X",argb() and 0xFFFFFF),color=Color.White)
                }
                if(appearance.color==LyricColorMode.VIDEO_CONTRAST)
                    Text(uiLocalized("Adapts to the video brightness while it plays."),color=Color.LightGray)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
