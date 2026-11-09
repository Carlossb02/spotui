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
            jobFinished(params,!complete)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {job?.cancel();return true}
    override fun onDestroy(){job?.cancel();super.onDestroy()}
    companion object {
        private const val ID=231702
        fun cancel(context:Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(ID);scheduler.cancel(ID+1)
        }
        fun schedule(context:Context) {
            if(!context.getSharedPreferences("google_device_sync_config",0).getBoolean("enabled",false))return
            runCatching {
                val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if(scheduler.getPendingJob(ID+1)==null) scheduler.schedule(JobInfo.Builder(ID+1,ComponentName(context,GoogleSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60_000L).build())
                if(scheduler.getPendingJob(ID)!=null)return
                scheduler.schedule(JobInfo.Builder(ID,ComponentName(context,GoogleSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            }
        }
    }
}
