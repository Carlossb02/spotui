package com.music.spotui.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OrderedIoWritesTest {
    @Test fun queueEncodingDoesNotBlockTheCallerAndCursorCannotBeOverwrittenByAnOlderSnapshot() = runBlocking {
        val writes = OrderedIoWrites()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val persisted = mutableListOf<String>()
        val first = writes.submit { started.countDown(); check(release.await(3, TimeUnit.SECONDS)); persisted += "queue A, cursor 0" }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val second = writes.submit { persisted += "queue B, cursor 0" }
        val cursor = writes.submit { persisted += "cursor 7" }
        assertFalse(first.isCompleted)
        release.countDown()
        joinAll(first, second, cursor)
        assertEquals(listOf("queue A, cursor 0", "queue B, cursor 0", "cursor 7"), persisted)
    }
}
