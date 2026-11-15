package com.music.spotui.di

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaybackPreloadPipelineTest {
    @Test fun aSkipCanCancelTheFullDownloadAfterItsPlayablePrefixWasCommitted() = runBlocking {
        val pipeline = PlaybackPreloadPipeline()
        val prefix = CompletableDeferred<Unit>(); var prefixSaved = false; var fullStarted = false
        val request = launch {
            pipeline.prepare(resolve = { "chosen stream" }, completeCache = { fullStarted = true; awaitCancellation() },
                cache = { prefixSaved = true; prefix.complete(Unit) })
        }
        prefix.await(); request.cancelAndJoin()
        assertTrue(prefixSaved); assertFalse(fullStarted)
    }
    @Test fun theNextPrefixGetsAChanceBeforeThePreviousFullFileDownload() = runBlocking {
        val pipeline = PlaybackPreloadPipeline()
        val firstReady = CompletableDeferred<Unit>(); var nextReady = false
        val first = launch { pipeline.prepare(resolve = { "first" }, completeCache = { awaitCancellation() }, cache = { firstReady.complete(Unit) }) }
        firstReady.await()
        try {
            withTimeout(1000) { pipeline.prepare(resolve = { "second" }, cache = { nextReady = true }) }
            assertTrue(nextReady)
        } finally { first.cancelAndJoin() }
    }
    @Test fun nextRecordingResolvesBeforePreviousAudioDownloadFinishes() = runBlocking {
        val pipeline = PlaybackPreloadPipeline()
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val nextResolved = CompletableDeferred<Unit>()
        val cached = mutableListOf<String>()
        val first = async {
            pipeline.prepare({ "selected-first" }) {
                writing.complete(Unit); finishWrite.await(); cached += it
            }
        }
        writing.await()
        val second = async {
            pipeline.prepare({ nextResolved.complete(Unit); "selected-second" }) { cached += it }
        }
        try {
            withTimeout(1000) { nextResolved.await() }
            assertTrue(cached.isEmpty())
            finishWrite.complete(Unit)
            first.await(); second.await()
            assertEquals(listOf("selected-first", "selected-second"), cached)
        } finally { first.cancel(); second.cancel(); finishWrite.complete(Unit) }
    }

    @Test fun resolutionAndDownloadConcurrencyRemainBounded() = runBlocking {
        val pipeline = PlaybackPreloadPipeline()
        val resolving = AtomicInteger(); val writing = AtomicInteger()
        val peakResolutions = AtomicInteger(); val peakWrites = AtomicInteger()
        val cached = mutableListOf<Int>()
        (1..5).map { value -> async {
            pipeline.prepare({
                peakResolutions.updateAndGet { maxOf(it, resolving.incrementAndGet()) }
                try { delay(5); value } finally { resolving.decrementAndGet() }
            }) {
                peakWrites.updateAndGet { maxOf(it, writing.incrementAndGet()) }
                try { delay(10); cached += it } finally { writing.decrementAndGet() }
            }
        } }.awaitAll()
        assertEquals(1, peakResolutions.get()); assertEquals(1, peakWrites.get())
        assertEquals((1..5).toList(), cached)
    }

    @Test fun skippingCancelsTheOldWriterWithoutDiscardingNextResolvedSource() = runBlocking {
        val pipeline = PlaybackPreloadPipeline()
        val writing = CompletableDeferred<Unit>(); val resolvedNext = CompletableDeferred<Unit>()
        val old = launch { pipeline.prepare({ "old" }) { writing.complete(Unit); awaitCancellation() } }
        writing.await()
        val next = async { var cached = ""; pipeline.prepare({ resolvedNext.complete(Unit); "next" }) { cached = it }; cached }
        resolvedNext.await(); old.cancelAndJoin()
        assertEquals("next", withTimeout(1000) { next.await() })
    }

    @Test fun aMissNeverStartsAnAudioDownload() = runBlocking {
        PlaybackPreloadPipeline().prepare<String>({ null }) { error("A miss cannot be cached") }
    }
}
