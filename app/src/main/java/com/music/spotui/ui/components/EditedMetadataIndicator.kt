package com.music.spotui.ui.components

import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.CustomMetadataPref
import com.music.spotui.util.uiLocalized

@Composable
internal fun EditedMetadataIndicator(song:SongsModel) {
    val context=LocalContext.current
    var edited by remember(song){mutableStateOf(CustomMetadataPref.hasExistingEdit(context,song))}
    DisposableEffect(song) {
        val prefs=context.getSharedPreferences("CustomMetadata",0)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->edited=CustomMetadataPref.hasExistingEdit(context,song)}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    if(edited)Icon(Icons.Outlined.Edit,uiLocalized("Edited metadata"),tint=Color(0xFFB3B3B3),modifier=Modifier.size(13.dp))
}
