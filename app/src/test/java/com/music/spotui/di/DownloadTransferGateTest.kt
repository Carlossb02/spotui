package com.music.spotui.di

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadTransferGateTest {
    @Test fun pausePreservesOffsetAndAllowsAnotherCollectionToDownload() = runBlocking {
        val gate = DownloadTransferGate(1)
        gate.await("first", "recording1")
        var offset = 1234
        gate.pause(setOf("recording1"), true)
        val continuation = async { gate.await("first", "recording1"); offset += 10; gate.release("first") }
        yield()
        withTimeout(1000) { gate.await("other", "recording2") }
        assertEquals(1234, offset)
        assertFalse(continuation.isCompleted)
        gate.release("other")
        gate.pause(setOf("recording1"), false)
        withTimeout(1000) { continuation.await() }
        assertEquals(1244, offset)
    }
    @Test fun cancelWhilePausedDoesNotReleaseAnotherTransfersSlot() = runBlocking {
        val gate = DownloadTransferGate(1)
        gate.await("first", "one"); gate.pause(setOf("one"), true)
        val paused = launch { try { gate.await("first", "one") } finally { gate.release("first") } }
        yield()
        gate.await("other", "two")
        paused.cancelAndJoin()
        val waiting = async { gate.await("third", "three"); gate.release("third") }
        yield(); assertFalse(waiting.isCompleted)
        gate.release("other")
        withTimeout(1000) { waiting.await() }
    }
    @Test fun pauseAlsoAppliesToTracksThatHaveNotStartedYet() = runBlocking {
        val gate = DownloadTransferGate(2)
        gate.pause(setOf("one", "two"), true)
        val future = async { gate.await("futureQuery", "two"); gate.release("futureQuery") }
        yield(); assertFalse(future.isCompleted)
        gate.pause(setOf("one", "two"), false)
        withTimeout(1000) { future.await() }
    }
}
