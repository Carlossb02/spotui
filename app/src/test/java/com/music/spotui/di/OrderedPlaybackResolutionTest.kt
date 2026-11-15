package com.music.spotui.di

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OrderedPlaybackResolutionTest {
    @Test fun fastestCandidateDoesNotReplaceFirstMatchingRecording() = runBlocking {
        val result = orderedFirstNotNull(listOf("preferred", "faster"), 2) { value, index ->
            delay(if (index == 0) 50 else 1)
            value
        }
        assertEquals("preferred", result)
    }
    @Test fun failedHigherRankedCandidateFallsBackInOriginalOrder() = runBlocking {
        val result = orderedFirstNotNull(listOf(0, 1, 2), 2) { value, _ ->
            delay(if (value == 1) 30 else 1)
            value.takeIf { it > 0 }
        }
        assertEquals(1, result)
    }
    @Test fun resolutionStaysBoundedAndCancelsUnusedRequests() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = mutableListOf<Int>()
        assertEquals(0, orderedFirstNotNull((0..9).toList(), 2) { value, _ ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                delay(if (value == 0) 40 else 10_000)
                completed += value
                value
            } finally { active.decrementAndGet() }
        })
        assertEquals(listOf(0), completed)
        assertTrue(peak.get() <= 2)
        assertEquals(0, active.get())
    }
    @Test fun allMissesRemainAMiss() = runBlocking {
        assertNull(orderedFirstNotNull(listOf(1, 2), 2) { _, _ -> null as String? })
    }
}
