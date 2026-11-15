package com.music.spotui.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.api.ResolvedSongArtwork
import com.music.spotui.data.entity.SongsModel

@Composable
internal fun rememberSongArtwork(song:SongsModel):String {
    val context=LocalContext.current
    val revision by ResolvedSongArtwork.revision.collectAsState()
    val cover=remember(song,revision){
        if(com.music.spotui.ui.notification.PodcastCover.isEpisode(song))com.music.spotui.ui.notification.PodcastCover.preferredUri(context,song)
        else ResolvedSongArtwork.preferred(context,song)
    }
    LaunchedEffect(song.url,song.coverUri,revision) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){ResolvedSongArtwork.ensure(context.applicationContext,song)}
    }
    return cover
}
