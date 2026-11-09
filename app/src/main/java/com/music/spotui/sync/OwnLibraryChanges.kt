package com.music.spotui.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object OwnLibraryChanges {
    private val mutable=MutableStateFlow(0L)
    val revision=mutable.asStateFlow()
    private val listeners=mutableListOf<Pair<SharedPreferences,SharedPreferences.OnSharedPreferenceChangeListener>>()
    @Synchronized fun install(context:Context) {
        if(listeners.isNotEmpty())return
        CloudPreferences.names.forEach { name->
            val prefs=context.getSharedPreferences(name,0)
            val listener=SharedPreferences.OnSharedPreferenceChangeListener { _, _->changed();GoogleDeviceSync.requestSync() }
            prefs.registerOnSharedPreferenceChangeListener(listener);listeners+=prefs to listener
        }
    }
    fun changed(){mutable.update { it+1L }}
}
