package com.music.spotui.ui.components

import org.junit.Assert.*
import org.junit.Test

class CollectionDownloadStateTest {
    @Test fun completedFilesAndRealTransferProgressContributeOnlyOnce() {
        val state = collectionDownloadState(setOf("first", "second"), setOf("first"), listOf("first" to 100, "second" to 50))
        assertEquals(.75f, state.progress, .001f); assertFalse(state.complete); assertTrue(state.active)
    }
    @Test fun greenCompletionWaitsForPendingLyricsAndFinalQueueRemoval() {
        val finishing = collectionDownloadState(setOf("one"), setOf("one"), listOf("one" to 100))
        assertFalse(finishing.complete); assertTrue(finishing.progress < 1f)
        val done = collectionDownloadState(setOf("one"), setOf("one"), emptyList())
        assertTrue(done.complete); assertEquals(1f, done.progress, 0f)
    }
    @Test fun anotherPlaylistsDownloadsDoNotActivateThisRing() {
        val state = collectionDownloadState(setOf("one"), emptySet(), listOf("other" to 50))
        assertFalse(state.active); assertFalse(state.complete); assertEquals(0f, state.progress, 0f)
    }
    @Test fun emptyCollectionsAreNeverMarkedAsDownloaded() {
        assertFalse(collectionDownloadState(emptySet(), emptySet(), emptyList()).complete)
    }
}
