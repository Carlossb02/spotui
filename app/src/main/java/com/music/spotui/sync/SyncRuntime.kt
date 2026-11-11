package com.music.spotui.sync

import android.app.ActivityManager
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.*

/** Local process checks only. Never emits a network heartbeat. */
internal object SyncRuntime {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var installed=false
    private var visible=false
    private val started=mutableSetOf<Activity>()
    private var opened=false
    private var available=false
    private var evaluateJob:Job?=null
    fun install(context:Context) {
        if(installed)return
        installed=true
        scope.launch {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity:Activity){started+=activity;opened=true;evaluate()}
                override fun onActivityStopped(activity:Activity){started-=activity;visible(false)}
                override fun onActivityDestroyed(activity:Activity){started-=activity;visible(false)}
                override fun onActivityCreated(activity:Activity,state:Bundle?){}
                override fun onActivityResumed(activity:Activity){}
                override fun onActivityPaused(activity:Activity){}
                override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
            })
            while(isActive){evaluate();delay(5_000)}
        }
    }
    fun visible(value:Boolean) {
        scope.launch {
            visible=value;if(value)opened=true
            evaluateJob?.cancel()
            if(value)evaluate()else evaluateJob=scope.launch {delay(2_000);evaluate()}
        }
    }
    fun playbackChanged(){scope.launch {evaluate()}}
    private fun evaluate() {
        val ui=visible || started.isNotEmpty()
        val playing=SongPlayer.isResolvingOrPlaying()
        val info=ActivityManager.RunningAppProcessInfo()
        val service=if(ui || playing)false else runCatching {ActivityManager.getMyMemoryState(info)
            info.importance in setOf(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE)
        }.getOrDefault(false)
        val next=syncRuntimeAvailable(ui,playing,opened && service)
        if(next!=available){available=next;GoogleDeviceSync.runtimeChanged(next)}
    }
}

internal fun syncRuntimeAvailable(visible:Boolean,playing:Boolean,perceptibleService:Boolean)=visible || playing || perceptibleService
internal fun hasSyncPeer(devices:List<GoogleDeviceSync.Device>,actor:String)=devices.any {it.id!=actor && it.online}
