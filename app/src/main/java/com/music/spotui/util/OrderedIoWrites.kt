package com.music.spotui.util

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Submit order is retained even when callers return immediately to start audio. */
internal class OrderedIoWrites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    fun submit(write: () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        gate.withLock { yield(); write() }
    }
}
