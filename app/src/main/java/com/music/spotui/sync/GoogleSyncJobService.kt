package com.music.spotui.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

class GoogleSyncJobService:JobService() {
    private var job:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        GoogleDeviceSync.install(applicationContext)
        job=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val complete=try { GoogleDeviceSync.awaitIdle() }catch(c:CancellationException){throw c}catch(_:Exception){false}
            jobFinished(params,!complete && GoogleDeviceSync.hasPendingWork())
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {job?.cancel();return GoogleDeviceSync.hasPendingWork()}
    override fun onDestroy(){job?.cancel();super.onDestroy()}
    companion object {
        private const val ID=231702
        fun cancelLegacy(context:Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(ID+1)
        }
        fun cancel(context:Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(ID);scheduler.cancel(ID+1)
        }
        fun schedule(context:Context) {
            if(!GoogleDeviceSync.hasPendingWork())return
            if(!context.getSharedPreferences("google_device_sync_config",0).getBoolean("enabled",false))return
            runCatching {
                val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                // Retire the old periodic poll; retry jobs are scheduled by pending changes.
                scheduler.cancel(ID+1)
                if(scheduler.getPendingJob(ID)!=null)return
                scheduler.schedule(JobInfo.Builder(ID,ComponentName(context,GoogleSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            }
        }
    }
}
