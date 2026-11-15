package com.music.spotui.di

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore

/** Pausing preserves the caller's open file and byte offset, releasing only its transfer slot. */
internal class DownloadTransferGate(slots: Int) {
    private val semaphore = Semaphore(slots)
    private val held = ConcurrentHashMap.newKeySet<String>()
    private val pausedKeys = MutableStateFlow<Set<String>>(emptySet())
    val paused = pausedKeys.asStateFlow()
    fun pause(keys: Set<String>, pause: Boolean) { pausedKeys.update { if (pause) it + keys else it - keys } }
    fun release(query: String) { if (held.remove(query)) semaphore.release() }
    suspend fun await(query: String, recording: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (recording in pausedKeys.value) {
                release(query)
                pausedKeys.first { recording !in it }
            }
            if (query !in held) {
                semaphore.acquire()
                held.add(query)
            }
            if (recording !in pausedKeys.value) return
        }
    }
}
