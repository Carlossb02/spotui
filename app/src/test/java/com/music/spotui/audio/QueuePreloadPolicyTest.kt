package com.music.spotui.audio
import org.junit.Assert.*
import org.junit.Test
class QueuePreloadPolicyTest {
    @Test fun windowUsesTheActualUpcomingOrderAndAvoidsDuplicateWork() {
        assertEquals(listOf("manual","next","last"),QueuePreloadPolicy.next(listOf("current","manual","next","next","last"),0,3))
        assertTrue(QueuePreloadPolicy.next(listOf("only"),0,3).isEmpty())
        assertTrue(QueuePreloadPolicy.next(listOf("a"),-1,3).isEmpty())
    }
    @Test fun fullKnownRecordingIsCachedWithABoundedPerTrackBudget() {
        assertEquals(6_000_000L,QueuePreloadPolicy.targetBytes(6_000_000,240_000,160_000))
        assertEquals(QueuePreloadPolicy.MAX_TRACK_BYTES,QueuePreloadPolicy.targetBytes(200_000_000,0,0))
        assertTrue(QueuePreloadPolicy.targetBytes(null,240_000,160_000)>4_000_000)
    }
}
