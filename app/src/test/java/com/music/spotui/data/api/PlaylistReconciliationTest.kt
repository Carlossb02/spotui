package com.music.spotui.data.api

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class PlaylistReconciliationTest {
    private fun song(id: Char) = SongsModel(id.code, id.toString(), "", "", "", "spotify:track:" + id.toString().repeat(22), id.toString().repeat(22))
    @Test fun exportsOnlyKnownCatalogIdentities() {
        val track = song('a')
        assertEquals("spotify:track:" + "a".repeat(22), exportPlaylistUri(track))
        assertNull(exportPlaylistUri(track.copy(isLocalSpotifyTrack = true)))
        assertNull(exportPlaylistUri(track.copy(url = "file:/local.mp3")))
        assertNull(exportPlaylistUri(track.copy(url = "content://audio/5")))
        assertNull(exportPlaylistUri(track.copy(url = "spotify:local:artist:album:song")))
        assertNull(exportPlaylistUri(track.copy(spotifyTrackId = "", url = "query by title")))
        assertNull(exportPlaylistUri(track.copy(spotifyTrackId = "bad", url = "")))
    }
    @Test fun untouchedLocalPullsRemoteEditsIncludingOrderAndDeletion() {
        assertEquals(listOf("c", "a"), reconcilePlaylist(listOf("a", "b"), listOf("a", "b"), listOf("c", "a")))
    }
    @Test fun untouchedRemoteReceivesExactLocalOrderIncludingDuplicates() {
        assertEquals(listOf("b", "a", "a"), reconcilePlaylist(listOf("a", "b"), listOf("b", "a", "a"), listOf("a", "b")))
    }
    @Test fun concurrentDifferentAdditionsSurvive() {
        assertEquals(listOf("a", "b", "c"), reconcilePlaylist(listOf("a"), listOf("a", "b"), listOf("a", "c")))
    }
    @Test fun echoedAdditionsAreNotUploadedTwice() {
        assertEquals(listOf("a", "a"), reconcilePlaylist(listOf("a"), listOf("a", "a"), listOf("a", "a")))
    }
    @Test fun concurrentDeletionAndAdditionOfSameSongRetainOneOccurrence() {
        assertEquals(listOf("a"), reconcilePlaylist(listOf("a"), emptyList(), listOf("a", "a")))
    }
    @Test fun concurrentDeletionsAndReorderingKeepRemoteAddition() {
        assertEquals(listOf("c", "d"), reconcilePlaylist(listOf("a", "b", "c"), listOf("c", "a"), listOf("b", "c", "d")))
    }
    @Test fun migrationImportsBothListsWithoutRemovingRemoteEntries() {
        assertEquals(listOf("a", "a", "b", "c"), reconcilePlaylist(emptyList(), listOf("a", "a", "b"), listOf("a", "c")))
    }
    @Test fun localFilesKeepTheirRelativePositionsAfterSync() {
        val local = song('x').copy(isLocalSpotifyTrack = true)
        val local2 = song('y').copy(url = "file:/audio.mp3")
        val a = song('a'); val b = song('b'); val c = song('c')
        assertEquals(listOf(local, b, local2, c, a),
            retainLocalPlaylistTracks(listOf(local, a, local2, b), listOf(b, c, a)))
        assertEquals(listOf(local, local2), retainLocalPlaylistTracks(listOf(local, a, local2, b), emptyList()))
    }
    @Test fun interruptedAcknowledgedUploadIsNotDuplicated() {
        assertEquals(PendingPlaylistResult.APPLIED, pendingPlaylistResult(listOf("a"), listOf("a", "b"), listOf("a", "b")))
    }
    @Test fun failedUploadCanBeRetriedAndConcurrentEditRequiresReview() {
        assertEquals(PendingPlaylistResult.NOT_APPLIED, pendingPlaylistResult(listOf("a"), listOf("a", "b"), listOf("a")))
        assertEquals(PendingPlaylistResult.CONCURRENT, pendingPlaylistResult(listOf("a"), listOf("a", "b"), listOf("a", "c")))
    }
    @Test fun reorderingOneSideAndAddingOnOtherDoesNotDropAnything() {
        val merged = reconcilePlaylist(listOf("a", "b", "c"), listOf("c", "b", "a"), listOf("a", "b", "c", "d"))
        assertEquals(listOf("c", "b", "a", "d"), merged)
        assertEquals(merged, reconcilePlaylist(merged, merged, merged))
    }
}
