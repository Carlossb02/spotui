package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.StreamSelections
import com.music.spotui.util.uiLocalized

@Composable
internal fun StreamInfoDialog(song:SongsModel,onDismiss:()->Unit) {
    val context=LocalContext.current
    val revision by StreamSelections.revision.collectAsState()
    val info=remember(song.url,revision){StreamSelections.get(context,song.url)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(uiLocalized("Stream information"))},
        text={SelectionContainer {Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(song.title)
            if(info==null)Text(uiLocalized("Play this track to see its selected stream. Older downloads may not retain this information."))
            else {
                if(info.videoId.isNotBlank())Text(info.youtubeUrl,color=Color(0xFF74C7FF),modifier=Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(info.youtubeUrl)))
                })
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(info.selectedAt)))
                Text("${uiLocalized("Selection")}: ${info.mode}\n${info.title}\n${info.artist}")
                Text("${uiLocalized("Score")}: ${info.score?.toString() ?: uiLocalized("Unavailable")}\n"+
                    "${uiLocalized("Matching stage")}: ${info.stage?.toString() ?: uiLocalized("Unavailable")}\n"+
                    "${uiLocalized("Explicit content")}: ${info.explicit?.toString() ?: uiLocalized("Unknown")}")
                Text(uiLocalized("Selection criteria"))
                Text(info.criteria)
                Text(uiLocalized("The score compares candidates; it is not a percentage or an analysis of the sung words."))
            }
        }}},confirmButton={TextButton(onClick=onDismiss){Text(uiLocalized("Close"))}})
}
