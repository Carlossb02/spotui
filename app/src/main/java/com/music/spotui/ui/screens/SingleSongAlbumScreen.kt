package com.music.spotui.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.libraryIdentity
import com.music.spotui.data.preferences.OfflineCollectionsPref
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.*
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.util.uiLocalized

internal fun singleSongAlbumRoute(context:Context,song:SongsModel):String {
    val id="single:"+java.security.MessageDigest.getInstance("SHA-256").digest(song.libraryIdentity().toByteArray())
        .joinToString(""){"%02x".format(it)}
    OfflineCollectionsPref.saveCollection(context,id,song.title,song.coverUri,song.singer,false,listOf(song))
    return "singleAlbum/${Uri.encode(id)}"
}

@Composable
internal fun SingleSongAlbumScreen(id:String,navController:NavController) {
    val context=LocalContext.current
    val song=remember(id){OfflineCollectionsPref.getCollection(context,id)?.songs?.singleOrNull()} ?: return
    val player:PlayerViewModel=hiltViewModel()
    var menu by remember {mutableStateOf(false)}
    if(menu)SongOptionsSheet(song,navController,context,onDismiss={menu=false})
    Column(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp)) {
        IconButton(onClick={navController.popBackStack()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,uiLocalized("Back"),tint=Color.White)}
        PlayingTrackCover(song,Modifier.fillMaxWidth().widthIn(max=360.dp).aspectRatio(1f),showPlaying=false)
        Spacer(Modifier.height(24.dp))
        Text(song.title,color=Color.White,fontWeight=FontWeight.Bold,fontSize=28.sp)
        Text(song.singer,color=Color.LightGray,fontSize=16.sp)
        Text(uiLocalized("Single")+" · 1 "+uiLocalized("song"),color=Color.Gray,modifier=Modifier.padding(vertical=12.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,uiLocalized("More options"),tint=Color.White)}
            IconButton(onClick={
                if(player.currentSongId.value==song.id)player.setPlaying(!player.currentSongPlayingState.value)
                else {
                    player.updateQueue(listOf(song),sourceName=song.title)
                    player.updateSongState(song.coverUri,song.title,song.singer,true,song.id,0,song.title)
                    SongPlayer.playSong(song.url,context,"song/${song.id}")
                }
            }){Icon(if(player.currentSongId.value==song.id && player.currentSongPlayingState.value)Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                uiLocalized("Play"),tint=Color(0xFF1ED760),modifier=Modifier.size(46.dp))}
        }
        Row(Modifier.fillMaxWidth().padding(vertical=16.dp)) {
            TrackNumberOrPlaying(song,"1",Modifier.width(28.dp))
            Column {Text(song.title,color=Color.White);Row {SongExplicitBadge(song);Text(song.singer,color=Color.Gray)}}
        }
    }
}
