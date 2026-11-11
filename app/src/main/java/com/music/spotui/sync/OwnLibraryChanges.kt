package com.music.spotui.sync

import kotlinx.coroutines.*
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object OwnLibraryChanges {
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(0L)
    val revision=mutable.asStateFlow()
    private val listeners=mutableListOf<Pair<SharedPreferences,SharedPreferences.OnSharedPreferenceChangeListener>>()
    @Synchronized fun install(context:Context) {
        if(listeners.isNotEmpty())return
        CloudPreferences.names.forEach { name->
            val prefs=context.getSharedPreferences(name,0)
            val listener=SharedPreferences.OnSharedPreferenceChangeListener { _, key->
                if(key==null) {
                    changed()
                    if(SyncOptions.allows("$name:"))GoogleDeviceSync.requestSync()
                    return@OnSharedPreferenceChangeListener
                }
                if(!CloudPreferences.allowed(name,key))return@OnSharedPreferenceChangeListener
                // Position sampling stays local; explicit playback events flush it.
                if(name=="spotui_podcast_resume_v1" && key!="manually_seen")return@OnSharedPreferenceChangeListener
                if(name!="spotui_podcast_resume_v1" && name!="RecentItems")changed()
                if(SyncOptions.allows("$name:$key"))GoogleDeviceSync.requestSync()
            }
            prefs.registerOnSharedPreferenceChangeListener(listener);listeners+=prefs to listener
        }
        work.launch {com.music.spotui.data.preferences.PlaylistSyncedPendingPref.recoverOwnedTracks(context)}
    }
    fun changed(){mutable.update { it+1L }}
}
