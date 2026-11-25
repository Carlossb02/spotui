package com.music.spotui.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpotifyWriteJournalTest {
    @Test fun playlistMembershipAndTrackEditsStayIndependentAcrossRestart() {
        var snapshot: String? = null
        val journal = SpotifyWriteJournal(null) { snapshot = it }
        val trackEdit = journal.enqueue("playlist", "playlist-id", "track-id", true)
        val earlierSave = journal.enqueue("playlist_library", "playlist-id", "", true)
        val removal = journal.enqueue("playlist_library", "playlist-id", "", false)

        assertFalse(journal.acknowledge(earlierSave))
        val restored = SpotifyWriteJournal(snapshot) { snapshot = it }
        assertEquals(listOf(trackEdit, removal), restored.entries())
    }
}
