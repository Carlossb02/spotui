package com.music.spotui.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.bumptech.glide.integration.compose.*
import androidx.compose.ui.layout.ContentScale
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.viewmodel.PlayerViewModel

@Composable
private fun isPlaying(song:SongsModel):Boolean {
    val player:PlayerViewModel=hiltViewModel()
    return player.currentSongId.value==song.id && player.currentSongPlayingState.value
}

@Composable
private fun Bars(modifier:Modifier) {
    val transition=rememberInfiniteTransition(label="playingBars")
    val a by transition.animateFloat(.25f,1f,infiniteRepeatable(tween(440),RepeatMode.Reverse),label="bar1")
    val b by transition.animateFloat(1f,.3f,infiniteRepeatable(tween(610),RepeatMode.Reverse),label="bar2")
    val c by transition.animateFloat(.5f,1f,infiniteRepeatable(tween(340),RepeatMode.Reverse),label="bar3")
    Canvas(modifier) {
        listOf(a,b,c).forEachIndexed {i,height->
            val x=size.width*(i+1)/4
            drawLine(Color(0xFF1ED760),Offset(x,size.height),Offset(x,size.height*(1-height)),
                strokeWidth=size.width/7,cap=StrokeCap.Round)
        }
    }
}

@Composable
internal fun TrackNumberOrPlaying(song:SongsModel,number:String,modifier:Modifier=Modifier,fontSize:TextUnit=13.sp) {
    Box(modifier,contentAlignment=Alignment.CenterStart) {
        if(isPlaying(song))Bars(Modifier.size(18.dp))
        else Text(number,color=Color.Gray,fontSize=fontSize)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun PlayingTrackCover(song:SongsModel,modifier:Modifier=Modifier.size(48.dp),showPlaying:Boolean=true) {
    Box(modifier.clip(RoundedCornerShape(4.dp))) {
        if(com.music.spotui.data.preferences.PodcastResumeStore.isEpisode(song.url))
            EpisodeArtwork(song,modifier=Modifier.fillMaxSize(),radius=4.dp)
        else GlideImage(model=rememberSongArtwork(song),contentDescription=song.title,contentScale=ContentScale.Crop,
            modifier=Modifier.fillMaxSize(),loading=placeholder(R.drawable.placeholder),failure=placeholder(R.drawable.placeholder))
        if(showPlaying && isPlaying(song)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.5f)))
            Bars(Modifier.size(20.dp).align(Alignment.Center))
        }
    }
}
