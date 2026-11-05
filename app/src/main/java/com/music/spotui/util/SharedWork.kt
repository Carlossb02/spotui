package com.music.spotui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Identical requests share one job; cancellation only stops it after the last waiter leaves. */
internal class SharedWork<K, V>(private val scope: CoroutineScope) {
    data class Result<V>(val value: V, val reused: Boolean)
    private class Entry<V>(val job: Deferred<V>, var waiters: Int = 0)
    private val lock = Any()
    private val entries = HashMap<K, Entry<V>>()

    suspend fun result(key: K, work: suspend () -> V): Result<V> {
        val (entry, reused) = synchronized(lock) {
            val old = entries[key]
            val selected = old ?: Entry(scope.async(start = CoroutineStart.LAZY) { work() }).also { created ->
                entries[key] = created
                created.job.invokeOnCompletion {
                    synchronized(lock) { if (entries[key] === created) entries.remove(key) }
                }
            }
            selected.waiters++
            selected to (old != null)
        }
        entry.job.start()
        try {
            return Result(entry.job.await(), reused)
        } finally {
            val abandoned = synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.job.isCompleted) {
                    if (entries[key] === entry) entries.remove(key)
                    true
                } else false
            }
            if (abandoned) entry.job.cancel()
        }
    }

    suspend fun await(key: K, work: suspend () -> V): V = result(key, work).value

    fun cancel(key: K) {
        val job = synchronized(lock) { entries.remove(key)?.job }
        job?.cancel()
    }

    fun cancelAll() {
        val jobs = synchronized(lock) { entries.values.map { it.job }.also { entries.clear() } }
        jobs.forEach { it.cancel() }
    }
}
