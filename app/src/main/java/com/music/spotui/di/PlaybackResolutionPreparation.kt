package com.music.spotui.di

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException

/** All stages of one resolution use the same authoritative metadata, including a miss.
 * A subsequent playback request can try again; failures never become a session cache. */
internal suspend fun <T, R> withResolutionPreparation(
    prepare: suspend () -> T,
    warmup: (suspend () -> Unit)? = null,
    resolve: suspend (suspend () -> T) -> R,
): R = supervisorScope {
    val warming = warmup?.let { work -> async(start = CoroutineStart.LAZY) {
        try { work() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A speculative search cannot invalidate authoritative metadata. */ }
    } }
    val prepared = async(start = CoroutineStart.LAZY) { warming?.start(); prepare() }
    try { resolve { prepared.await() } } finally { warming?.cancel(); prepared.cancel() }
}
