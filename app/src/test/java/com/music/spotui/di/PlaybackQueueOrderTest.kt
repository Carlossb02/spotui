package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueOrderTest {
    @Test fun shuffleToggleNeverReplacesTheCanonicalPlaylistOrderWithTheVisibleOrder() {
        val q=queue();q.move(2,1);q.shuffle(true,0);q.shuffle(false,0)
        assertEquals(listOf(1,2,3),q.ids())
        q.shuffle(true,0);q.shuffle(false,0)
        assertEquals(listOf(1,2,3),q.ids())
    }
    @Test fun editedPlaybackUrlsKeepTheirOccurrenceAndOriginalRank() {
        val q=queue();q.shuffle(true,0)
        q.reconcile(q.songs.map {if(it.id==2)it.copy(url="edited query",title="Edited") else it})
        q.shuffle(false,0)
        assertEquals(listOf(1,2,3),q.ids())
    }
    @Test fun manualRequestsRemainInTheirPrioritySectionAfterMoving() {
        val q=queue();q.enqueue(listOf(song(4),song(5)),0,false)
        q.move(1,3);q.prioritizeManual(0)
        assertEquals(setOf(1,2),q.manualIndices)
        assertEquals(listOf(2,3),q.songs.drop(3).map {it.id})
    }
    private fun song(id: Int) = SongsModel().copy(id = id, title = "Track $id", url = "query:$id")
    private fun queue() = PlaybackQueueOrder().apply { replace(listOf(song(1), song(2), song(3)), false) }
    private fun PlaybackQueueOrder.ids() = songs.map { it.id }

    @Test fun manualRequestsAreFifoBeforeTheContext() {
        val q = queue()
        q.enqueue(listOf(song(4)), 0, false)
        q.enqueue(listOf(song(5), song(6)), 0, false)
        assertEquals(listOf(1, 4, 5, 6, 2, 3), q.ids())
    }
    @Test fun playNextGoesBeforeTheManualQueue() {
        val q = queue()
        q.enqueue(listOf(song(4), song(5)), 0, false)
        q.enqueue(listOf(song(6)), 0, true)
        assertEquals(listOf(1, 6, 4, 5, 2, 3), q.ids())
    }
    @Test fun consumedRequestsDoNotRepeatWithTheContext() {
        val q = queue()
        q.enqueue(listOf(song(4), song(5)), 0, false)
        q.select(1, 0)
        assertEquals(2, q.select(2, 1))
        assertEquals(3, q.select(3, 2))
        assertEquals(listOf(2, 3), q.ids().drop(3))
        q.select(4, 3)
        assertEquals(0, q.select(0, 4))
        assertEquals(listOf(1, 2, 3), q.ids())
        assertTrue(q.manualIndices.isEmpty())
    }

    @Test fun duplicateRecordingOccurrencesStayIndependent() {
        val q = queue()
        q.enqueue(listOf(song(2), song(2)), 0, false)
        q.remove(1)
        assertEquals(listOf(1, 2, 2, 3), q.ids())
        q.select(1, 0)
        q.select(2, 1)
        assertEquals(listOf(1, 2, 2, 3), q.ids())
        assertEquals(2, q.select(2, 1))
    }
    @Test fun shufflePreservesPriorityAndRestoresContextAfterActiveManualTrack() {
        val q = queue()
        q.enqueue(listOf(song(4), song(5)), 0, false)
        q.shuffle(true, 0)
        assertEquals(listOf(1, 4, 5), q.ids().take(3))
        q.select(1, 0)
        val current = q.shuffle(false, 1)
        assertEquals(listOf(1, 4, 5, 2, 3), q.ids())
        assertEquals(1, current)
    }
    @Test fun metadataChangesAndRadioAppendRetainManualPriority() {
        val q = queue()
        q.enqueue(listOf(song(4)), 0, false)
        q.reconcile(q.songs.map { it.copy(title = "Edited") } + song(8))
        q.enqueue(listOf(song(5)), 0, false)
        assertEquals(listOf(1, 4, 5, 2, 3, 8), q.ids())
    }
    @Test fun restoreKeepsDuplicateIdentityAndOriginalOrder() {
        val q = queue()
        q.enqueue(listOf(song(2)), 0, false)
        q.shuffle(true, 0)
        val restored = PlaybackQueueOrder()
        restored.replace(q.songs, false, restoredManual = q.manualIndices,
            restoredOriginal = q.originalSongs, restoredOriginalIndices = q.originalIndices)
        restored.shuffle(false, 0)
        assertEquals(listOf(1, 2, 2, 3), restored.ids())
        assertEquals(setOf(1), restored.manualIndices)
    }
    @Test fun replacingTheContextRemovesOldManualRequests() {
        val q = queue()
        q.enqueue(listOf(song(4)), 0, false)
        q.replace(listOf(song(7)), false)
        assertEquals(listOf(7), q.ids())
        assertTrue(q.manualIndices.isEmpty())
    }
    @Test fun legacyRestorationDoesNotMatchTheManualCopyAsThePlaylistOccurrence() {
        val q = queue()
        q.enqueue(listOf(song(2)), 0, false)
        q.move(3, 2)
        val restored = PlaybackQueueOrder()
        restored.replace(q.songs, false, restoredManual = q.manualIndices,
            restoredOriginal = q.originalSongs)
        restored.shuffle(false, 0)
        assertEquals(listOf(1, 2, 2, 3), restored.ids())
        assertEquals(setOf(1), restored.manualIndices)
    }
}
