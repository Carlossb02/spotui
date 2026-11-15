package com.music.spotui.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.spotui.data.api.FollowedEpisode
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.*
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.viewmodel.HomeViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FollowingPodcastEpisodesContent(
    homeViewModel:HomeViewModel, navController:NavController, header:@Composable ()->Unit,
) {
    val context=LocalContext.current
    val player:PlayerViewModel=hiltViewModel()
    val feed by homeViewModel.followedEpisodes.collectAsState()
    LaunchedEffect(Unit) {
        if(!homeViewModel.followedEpisodes.value.loading)
            homeViewModel.refreshFollowedReleases(kind=ReleaseKind.PODCASTS)
    }
    var ascending by remember {mutableStateOf(context.getSharedPreferences("app_sort_preferences",0).getBoolean("following_podcasts_ascending",false))}
    val episodes=remember(feed.items,ascending) {
        if(ascending)feed.items.sortedBy {it.publishedAt} else feed.items.sortedByDescending {it.publishedAt}
    }
    var menu by remember {mutableStateOf<SongsModel?>(null)}
    var savedIn by remember {mutableStateOf<SongsModel?>(null)}
    menu?.let {song->SongOptionsSheet(song=song,navController=navController,context=context,onDismiss={menu=null})}
    savedIn?.let {song->SavedInSheet(song=song,context=context,onDismiss={savedIn=null})}
    LaunchedEffect(feed.complete,feed.loading,feed.checkedAt) {
        if(feed.complete && !feed.loading && feed.checkedAt>0)withContext(Dispatchers.IO) {
            markReleasesChecked(context,ReleaseKind.PODCASTS,feed.checkedAt)
        }
    }
    fun play(index:Int) {
        val episode=episodes[index].song
        val active=player.queue.value.getOrNull(player.currentSongIndex.value)
        if(player.currentSongId.value==episode.id && active?.url==episode.url) {
            player.setPlaying(!player.currentSongPlayingState.value)
        }else {
            player.updateQueue(episodes.map {it.song},startSongId=episode.id,sourceName="Últimos episodios")
            val selected=player.queue.value.indexOfFirst {it.url==episode.url}.coerceAtLeast(0)
            player.updateSongState(episode.coverUri,episode.title,episode.singer,true,episode.id,selected,episode.album)
            com.music.spotui.ui.notification.PodcastCover.preserve(context,episode)
            val resume=PodcastResumeStore.entries.value.firstOrNull {
                PodcastResumeStore.episodeKey(it.episode.url)==PodcastResumeStore.episodeKey(episode.url)
            }
            SongPlayer.setRestorePoint(episode.url,resume?.positionMs ?: 0)
            SongPlayer.playSong(episode.url,context,"song/${episode.id}")
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        header()
        FollowingRefreshContainer(refreshing=feed.loading,
            onRefresh={homeViewModel.refreshFollowedReleases(force=true,kind=ReleaseKind.PODCASTS)},
            modifier=Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=140.dp)) {
            item {
                Text(uiLocalized("Latest episodes"),color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(20.dp,18.dp,20.dp,12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={ascending=!ascending;context.getSharedPreferences("app_sort_preferences",0).edit()
                        .putBoolean("following_podcasts_ascending",ascending).apply()},modifier=Modifier.weight(1f)) {
                        Icon(if(ascending)Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            uiLocalized("Change order"),tint=Color.White,modifier=Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiLocalized(if(ascending)"Oldest first · Last month" else "Newest first · Last month"),
                            color=Color.White,fontWeight=FontWeight.Bold)
                    }
                }
                feed.error?.let {Text(uiLocalized(it),color=Color(0xFFB3B3B3),modifier=Modifier.padding(20.dp,8.dp))}
                if(!feed.loading && feed.error==null && episodes.isEmpty())Text(uiLocalized("No episodes published in the last month."),
                    color=Color(0xFFB3B3B3),modifier=Modifier.padding(24.dp))
            }
            itemsIndexed(episodes,key={_,item->item.song.url.substringBefore('|')}) { index,item ->
                FollowingEpisodeRow(item,player.currentSongId.value==item.song.id && player.currentSongPlayingState.value,
                    onPlay={play(index)},onMenu={menu=item.song},onSave={savedIn=item.song},
                    onShow={navController.navigate(showRoute(item.showId,item.song.album))})
            }
        }
        }
    }
}

@Composable
private fun FollowingEpisodeRow(item:FollowedEpisode,playing:Boolean,onPlay:()->Unit,onMenu:()->Unit,onSave:()->Unit,onShow:()->Unit) {
    val episode=item.song
    val context=LocalContext.current
    val saved=rememberTrackSaved(episode,false)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide=maxWidth>650.dp
        Column(Modifier.fillMaxWidth().clickable(onClick=onPlay).padding(horizontal=20.dp,vertical=16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                EpisodeArtwork(episode,Modifier.width(if(wide)180.dp else 108.dp).aspectRatio(16f/9f),radius=5.dp,showProgress=true)
                Column(Modifier.weight(1f).padding(start=14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(episode.title,color=Color.White,fontSize=if(wide)20.sp else 16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(episode.album.ifBlank {episode.singer},color=Color(0xFFB3B3B3),fontSize=14.sp,maxLines=1,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.clickable(onClick=onShow))
                }
            }
            val description=remember(episode.description) {buildEpisodePreview(episode.description).ifBlank {
                android.text.Html.fromHtml(episode.description,android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace(Regex("\\s+")," ").trim()
            }}
            if(description.isNotBlank())Text(description,color=Color(0xFFB3B3B3),fontSize=14.sp,lineHeight=21.sp,
                maxLines=2,overflow=TextOverflow.Ellipsis)
            val date=java.time.Instant.ofEpochMilli(item.publishedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM",java.util.Locale.getDefault()))
            val minutes=episode.durationMs/60_000
            val duration=if(minutes>=60)"${minutes/60} h ${minutes%60} min" else "$minutes min"
            Text(date+if(minutes>0)" · $duration" else "",color=Color.White,fontSize=13.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onMenu) {Icon(Icons.Default.MoreVert,uiLocalized("More options"),tint=Color(0xFFB3B3B3))}
                IconButton(onClick={
                    val id=episode.url.removePrefix("yt_ep:").substringBefore('|')
                    val link=when {
                        episode.url.startsWith("yt_ep:")->"https://www.youtube.com/watch?v=$id"
                        episode.url.startsWith("episode:")->"https://open.spotify.com/episode/${episode.url.removePrefix("episode:")}"
                        else->episode.url
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="text/plain";putExtra(Intent.EXTRA_TEXT,"${episode.title}\n$link")
                    },null))
                }) {Icon(Icons.Default.Share,uiLocalized("Share"),tint=Color(0xFFB3B3B3))}
                EpisodeDownloadButton(episode)
                IconButton(onClick=onSave) {Icon(if(saved)Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                    uiLocalized(if(saved)"Saved" else "Save"),tint=if(saved)PodcastNewsColor else Color(0xFFB3B3B3))}
                IconButton(onClick=onPlay,modifier=Modifier.size(44.dp).clip(CircleShape).background(Color.White)) {
                    Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,
                        uiLocalized(if(playing)"Pause" else "Play"),tint=Color.Black,modifier=Modifier.size(28.dp))
                }
            }
            HorizontalDivider(color=Color(0xFF282828))
        }
    }
}
