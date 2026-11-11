package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.data.preferences.PodcastResumeEntry
import com.music.spotui.data.preferences.PodcastResumeStore
import com.music.spotui.util.uiLocalized

/** Static artwork/text and the live progress footer have separate composition scopes. */
@Composable
fun RecentPodcastCard(entry:PodcastResumeEntry,autoPreview:Boolean,previewAllowed:Boolean,
    onPlay:()->Unit,onShow:()->Unit,onAdd:()->Unit,onOptions:()->Unit,onSeen:()->Unit) {
    val episode=entry.episode
    var expanded by remember(episode.url){mutableStateOf(false)}
    val width=(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp-48).dp.coerceIn(240.dp,328.dp)
    Column(Modifier.width(width).clip(RoundedCornerShape(12.dp)).background(Color(0xFF181818)).padding(12.dp)) {
        ContinuePodcastPreview(entry=entry,modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f),radius=8.dp,
            autoPreview=autoPreview,previewAllowed=previewAllowed,onTap=onPlay,onLongPress=onOptions)
        Spacer(Modifier.height(12.dp))
        Text(episode.title,color=Color.White,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.Bold,
            maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().clickable(onClick=onPlay))
        Text(episode.album.ifBlank {episode.singer},color=Color(0xFFB3B3B3),fontSize=13.sp,maxLines=1,
            overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().clickable(onClick=onShow).padding(top=5.dp,bottom=12.dp))
        RecentEpisodeProgress(entry)
        Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onAdd,modifier=Modifier.size(40.dp)) {
                Icon(Icons.Outlined.AddCircleOutline,uiLocalized("Add to queue"),tint=Color(0xFFB3B3B3),modifier=Modifier.size(24.dp))
            }
            EpisodeDownloadButton(episode=episode)
            Box {
                IconButton(onClick={expanded=true},modifier=Modifier.size(40.dp)) {
                    Icon(Icons.Default.MoreVert,uiLocalized("More options"),tint=Color(0xFFB3B3B3))
                }
                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                    DropdownMenuItem(text={Text(uiLocalized("Mark as played"))},onClick={expanded=false;onSeen()},
                        leadingIcon={Icon(Icons.Outlined.CheckCircle,null,tint=Color(0xFF1ED760))})
                    DropdownMenuItem(text={Text(uiLocalized("Episode options"))},onClick={expanded=false;onOptions()})
                }
            }
            Spacer(Modifier.weight(1f))
            FilledIconButton(onClick=onPlay,modifier=Modifier.size(44.dp),shape=CircleShape,
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF1ED760),contentColor=Color.Black)) {
                Icon(Icons.Default.PlayArrow,uiLocalized("Resume playback"),modifier=Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun RecentEpisodeProgress(initial:PodcastResumeEntry) {
    val flow=remember(initial.episode.url){PodcastResumeStore.observeEpisode(initial.episode.url)}
    val entry by flow.collectAsState()
    val current=entry?:initial
    val minutes=((current.durationMs-current.positionMs).coerceAtLeast(0)+59_999)/60_000
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(progress={current.fraction},modifier=Modifier.weight(1f).height(4.dp).clip(CircleShape),
            color=Color(0xFF1ED760),trackColor=Color(0xFF404040))
        if(current.durationMs>0)Text("$minutes ${uiLocalized("min remaining")}",color=Color(0xFFB3B3B3),fontSize=11.sp)
    }
}
