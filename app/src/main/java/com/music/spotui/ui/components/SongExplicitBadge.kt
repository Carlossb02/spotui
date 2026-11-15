package com.music.spotui.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.api.ExplicitContentStore
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer

@Composable
internal fun SongExplicitBadge(song:SongsModel,resolveMissing:Boolean=false) {
    val context=LocalContext.current
    val id=com.music.spotui.data.api.ratingSpotifyId(song)
    val ratings by ExplicitContentStore.ratings.collectAsState()
    val initial=remember(id){ExplicitContentStore.known(context,id)}
    val known=ratings[id] ?: initial
    LaunchedEffect(id,song.explicit,resolveMissing) {
        SongPlayer.awaitAudioStartup()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if(song.explicit)ExplicitContentStore.remember(context,id,true)
            else if(resolveMissing)ExplicitContentStore.ensure(context,id)
        }
    }
    if(known ?: song.explicit)ExplicitBadge()
}
