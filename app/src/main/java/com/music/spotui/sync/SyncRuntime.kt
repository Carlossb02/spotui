package com.music.spotui.sync

import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.notification.ManualPlaybackRun
import kotlinx.coroutines.*

/** Local process checks only. Never emits a network heartbeat. */
internal object SyncRuntime {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var visible=false
    private var available=false
    fun visible(value:Boolean) {
        scope.launch {
            visible=value
            evaluate()
        }
    }
    fun playbackChanged(){scope.launch {evaluate()}}
    private fun evaluate() {
        val next=syncRuntimeAvailable(visible,SongPlayer.isResolvingOrPlaying(),ManualPlaybackRun.opened)
        if(next!=available){available=next;GoogleDeviceSync.runtimeChanged(next)}
    }
}

internal fun syncRuntimeAvailable(visible:Boolean,playing:Boolean,openedManually:Boolean) =
    openedManually && (visible || playing)
internal fun hasSyncPeer(devices:List<GoogleDeviceSync.Device>,actor:String)=devices.any {it.id!=actor && it.online}
internal fun hasKnownSyncPeer(devices: List<GoogleDeviceSync.Device>, actor: String) =
    devices.any { it.id.isNotBlank() && it.id != actor }
