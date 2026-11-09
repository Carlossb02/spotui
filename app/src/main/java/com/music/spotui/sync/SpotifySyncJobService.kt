package com.music.spotui.sync

import android.app.job.JobParameters
import android.app.job.JobService
import com.music.spotui.data.api.SpotifyPendingWrites
import kotlinx.coroutines.*

/** Android resumes the durable outbox when a network is available, even after process death. */
class SpotifySyncJobService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val complete = try { SpotifyPendingWrites.drain(applicationContext) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            jobFinished(params, !complete)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work = null; return true }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}
