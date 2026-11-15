package com.music.spotui.di

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Resolve concurrently, but consume in rank order: fastest never replaces best match. */
internal suspend fun <T, R : Any> orderedFirstNotNull(
    candidates: List<T>,
    parallelism: Int,
    resolve: suspend (T, Int) -> R?,
): R? = supervisorScope {
    val permits = Semaphore(parallelism)
    val jobs = candidates.mapIndexed { index, candidate ->
        async { permits.withPermit { resolve(candidate, index) } }
    }
    try {
        for (job in jobs) job.await()?.let { return@supervisorScope it }
        null
    } finally {
        jobs.forEach { it.cancel() }
    }
}
