package com.music.spotui.di

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay

/** Audio writes cannot hold up resolution of the following recording. */
internal class PlaybackPreloadPipeline {
    private val resolutions = Semaphore(1)
    private val writes = Semaphore(1)

    suspend fun <T : Any> prepare(resolve: suspend () -> T?, completeCache: (suspend (T) -> Unit)? = null, beforeCompleteCache: suspend () -> Boolean = { true }, cache: suspend (T) -> Unit) {
        val value = resolutions.withPermit { resolve() } ?: return
        writes.withPermit { cache(value) }
        if (completeCache != null) {
            delay(1_500)
            if (beforeCompleteCache()) writes.withPermit { completeCache(value) }
        }
    }
}
