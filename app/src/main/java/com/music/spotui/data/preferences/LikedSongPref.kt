package com.music.spotui.data.preferences

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.content.Context
import com.music.spotui.data.entity.SongsModel

/** All player sizes observe the same local like changes, including an open sheet. */
object LikedSongChanges {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    fun changed() { _revision.update { it + 1L } }
}

fun addLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString(songId, songId)
    editor.apply()
    LikedSongChanges.changed()
    songId.toIntOrNull()?.let { com.music.spotui.data.api.LibraryContentMemory.noteLike(context, it, true) }
}

fun removeLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.remove(songId)
    editor.apply()
    LikedSongChanges.changed()
    songId.toIntOrNull()?.let { com.music.spotui.data.api.LibraryContentMemory.noteLike(context, it, false) }
}

fun isSongLiked(context: Context, songId: String): Boolean {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    return sharedPreferences.contains(songId)
}

fun getLikedSongIds(context: Context): Set<Int> {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    return sharedPreferences.all.keys.mapNotNull { it.toIntOrNull() }.toSet()
}

fun getSongsByIds(songIds: Set<Int>, songs: List<SongsModel>): List<SongsModel> {
    return songs.filter { song -> song.id in songIds }
}