package com.music.spotui.sync
import com.music.spotui.di.CurrentSongState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudPlayerEntryPoint {
    fun currentSongState():CurrentSongState
}
