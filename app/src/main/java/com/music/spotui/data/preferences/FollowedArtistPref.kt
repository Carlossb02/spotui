package com.music.spotui.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.asStateFlow

private val artistFollowChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
val followedArtistRevision = artistFollowChanges.asStateFlow()

// Local record of artists followed from the app (keyed by Spotify artist id),
// so the Follow button keeps its state across visits. The follow itself is also
// mirrored to the real Spotify account via SpotifySync.

fun addFollowedArtist(context: Context, artistId: String, name: String) {
    if (artistId.isBlank()) return
    context.getSharedPreferences("FollowedArtists", Context.MODE_PRIVATE)
        .edit().putString(artistId, name).apply()
    artistFollowChanges.value++
}

fun removeFollowedArtist(context: Context, artistId: String) {
    if (artistId.isBlank()) return
    context.getSharedPreferences("FollowedArtists", Context.MODE_PRIVATE)
        .edit().remove(artistId).apply()
    artistFollowChanges.value++
}

fun isArtistFollowed(context: Context, artistId: String): Boolean =
    artistId.isNotBlank() &&
        context.getSharedPreferences("FollowedArtists", Context.MODE_PRIVATE).contains(artistId)
