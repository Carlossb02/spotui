package com.music.spotui.di

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackResolutionPreparationTest {
    @Test fun cacheAndCandidateStagesDoNotRepeatFailedMetadataRequests() = runBlocking {
        var requests = 0
        withResolutionPreparation({ requests++; null as String? }) { metadata ->
            repeat(3) {
                assertNull(metadata()) // cache validation
                assertNull(metadata()) // song candidates
                assertNull(metadata()) // video fallback
            }
        }
        assertEquals(1, requests)
    }

    @Test fun exactMetadataIsSharedByConcurrentConsumersWithoutChangingItsValue() = runBlocking {
        var requests = 0
        val result = withResolutionPreparation({ requests++; delay(10); "exact recording metadata" }) { metadata ->
            coroutineScope { (1..3).map { async { metadata() } }.awaitAll() }
        }
        assertEquals(List(3) { "exact recording metadata" }, result)
        assertEquals(1, requests)
    }

    @Test fun manualSourcesAndCompleteCacheHitsNeverFetchUnusedMetadata() = runBlocking {
        assertEquals("manual video", withResolutionPreparation({ error("Unnecessary metadata lookup") }) { "manual video" })
    }

    @Test fun aLaterPlaybackRequestCanRecoverFromAMetadataMiss() = runBlocking {
        var requests = 0
        suspend fun request() = withResolutionPreparation({ if (++requests == 1) null else "recovered" }) { it() }
        assertNull(request()); assertEquals("recovered", request()); assertEquals(2, requests)
    }

    @Test fun skippingCancelsPreparationInsteadOfLeavingOldNetworkWorkAlive() = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val playback = launch {
            withResolutionPreparation({
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }) { it() }
        }
        started.await(); playback.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }

    @Test fun identicalRawSearchCanFinishWhileAuthoritativeMetadataIsStillLoading() = runBlocking {
        val searchReady = CompletableDeferred<Unit>()
        val result = withResolutionPreparation(
            prepare = { withTimeout(1000) { searchReady.await() }; "authoritative edition" },
            warmup = { searchReady.complete(Unit) },
        ) { metadata -> metadata() }
        assertEquals("authoritative edition", result)
    }

    @Test fun unusedSearchIsCancelledWithoutHoldingUpTheFinalSelection() = runBlocking {
        val searchStarted = CompletableDeferred<Unit>(); val searchStopped = CompletableDeferred<Unit>()
        val selected = withResolutionPreparation(
            prepare = { searchStarted.await(); "final winner" },
            warmup = { searchStarted.complete(Unit); try { awaitCancellation() } finally { searchStopped.complete(Unit) } },
        ) { metadata -> metadata() }
        assertEquals("final winner", selected); assertTrue(searchStopped.isCompleted)
    }

    @Test fun preparationFailureDoesNotCancelThePlaybackFallback() = runBlocking {
        val selected = withResolutionPreparation<String, String>({ error("metadata service unavailable") }) { metadata ->
            try { metadata() } catch (_: IllegalStateException) { "existing fallback" }
        }
        assertEquals("existing fallback", selected)
    }
}
