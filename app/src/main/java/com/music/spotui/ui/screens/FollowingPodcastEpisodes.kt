package com.music.spotui.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
    val subscriptionRevision by SubscribedPodcastPref.revision.collectAsState()
    val shows by produceState(remember(context) { SubscribedPodcastPref.getSubscribedPodcasts(context) },
        context, subscriptionRevision) {
        value = withContext(Dispatchers.IO) { SubscribedPodcastPref.getSubscribedPodcasts(context) }
    }
    LaunchedEffect(Unit) {
        if(!homeViewModel.followedEpisodes.value.loading)
            homeViewModel.refreshFollowedReleases(kind=ReleaseKind.PODCASTS)
    }
    var selectedShows by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    val showIds = remember(shows) { shows.map { it.id }.toSet() }
    val activeFilter = remember(showIds, selectedShows) { selectedShows.filter { it in showIds }.toSet() }
    val episodes = remember(feed.items, activeFilter) {
        feed.items.filter { activeFilter.isEmpty() || it.showId in activeFilter }.sortedByDescending { it.publishedAt }
    }
    if (filterOpen && shows.isNotEmpty()) PodcastFilterSheet(
        shows = shows,
        initialSelection = activeFilter,
        onDismiss = { filterOpen = false },
        onApply = {
            selectedShows = ArrayList(it)
            filterOpen = false
        },
    )
    var menu by remember {mutableStateOf<SongsModel?>(null)}
    var savedIn by remember {mutableStateOf<SongsModel?>(null)}
    menu?.let {song->SongOptionsSheet(song=song,navController=navController,context=context,onDismiss={menu=null})}
    savedIn?.let {song->SavedInSheet(song=song,context=context,onDismiss={savedIn=null})}
    LaunchedEffect(feed.items) {
        if(feed.items.isNotEmpty())withContext(Dispatchers.IO) {
            markReleasesChecked(context,ReleaseKind.PODCASTS,feed.items.maxOf { it.publishedAt })
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
            if (shows.isNotEmpty()) item(key = "followed_podcast_shows") {
                Text(uiLocalized("Following podcasts"), color = Color.White, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shows, key = { it.id }) { show ->
                        LaunchedEffect(show.id, show.coverUri) {
                            if (show.coverUri.isBlank()) withContext(Dispatchers.IO) {
                                SubscribedPodcastPref.recoverMissingCover(context.applicationContext, show)
                            }
                        }
                        val cover = remember(show.id, show.coverUri) {
                            com.music.spotui.ui.notification.PodcastCover.preferredShowUri(context, show.id, show.coverUri)
                        }
                        PodcastCatalogueCard(show.copy(coverUri = cover)) {
                            navController.navigate(showRoute(show.id, show.name))
                        }
                    }
                }
            }
            item {
                Text(uiLocalized("Latest episodes"),color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(20.dp,18.dp,20.dp,12.dp))
                if (shows.isNotEmpty()) {
                    val label = uiLocalized("Filter by podcast")
                    OutlinedButton(
                        onClick = { filterOpen = true },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (activeFilter.isEmpty()) Color(0xFF777777) else PodcastNewsColor,
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (activeFilter.isEmpty()) label else "$label (${activeFilter.size})")
                    }
                }
                feed.error?.let {Text(uiLocalized(it),color=Color(0xFFB3B3B3),modifier=Modifier.padding(20.dp,8.dp))}
                if(!feed.loading && feed.error==null && episodes.isEmpty())Text(uiLocalized(if (activeFilter.isEmpty()) "No episodes published in the last month." else "No recent episodes for the selected podcasts."),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastFilterSheet(
    shows: List<com.music.spotui.data.entity.PodcastModel>,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
) {
    var draft by remember(initialSelection) { mutableStateOf(initialSelection) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF212121),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            uiLocalized("Filter by podcast"), color = Color.White,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(shows, key = { it.id }) { show ->
                val selected = show.id in draft
                Row(
                    Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox) {
                        draft = if (it) draft + show.id else draft - show.id
                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(show.name, modifier = Modifier.weight(1f), color = Color.White)
                    Checkbox(
                        checked = selected, onCheckedChange = null,
                        colors = CheckboxDefaults.colors(
                            checkedColor = PodcastNewsColor, checkmarkColor = Color.Black,
                            uncheckedColor = Color(0xFFAAAAAA),
                        ),
                    )
                }
            }
        }
        HorizontalDivider(color = Color(0xFF3D3D3D))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = { draft = emptySet() }, enabled = draft.isNotEmpty()) {
                Text(uiLocalized("Clear filter"))
            }
            Button(
                onClick = { onApply(draft) },
                colors = ButtonDefaults.buttonColors(containerColor = PodcastNewsColor, contentColor = Color.Black),
            ) {
                Text(uiLocalized("Apply filter"), fontWeight = FontWeight.Bold)
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
            val date=com.music.spotui.data.relativePublicationTime(item.publishedAt)
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
