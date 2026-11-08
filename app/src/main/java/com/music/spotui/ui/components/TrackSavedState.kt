package com.music.spotui.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.spotui.data.api.SavedTrackLibrary
import com.music.spotui.data.entity.SongsModel

/** Used by all player sizes; liking a track remains a separate action. */
@Composable
fun rememberTrackSaved(song: SongsModel?, liked: Boolean): Boolean {
    val context = LocalContext.current.applicationContext
    val snapshot by SavedTrackLibrary.snapshot.collectAsState()
    val likeRevision by com.music.spotui.data.preferences.LikedSongChanges.revision.collectAsState()
    val actuallyLiked = remember(song?.id, likeRevision, liked) {
        song?.let { com.music.spotui.data.preferences.isSongLiked(context, it.id.toString()) } ?: liked
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) SavedTrackLibrary.ensureLoaded(context)
        }
        owner.lifecycle.addObserver(observer)
        SavedTrackLibrary.ensureLoaded(context)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return actuallyLiked || (song != null && snapshot.contains(song))
}
