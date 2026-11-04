package com.music.spotui.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsMatcherTest {
    private val track = LyricsMatcher.Track("Hello", "Adele", "25", 295)
    private fun candidate(title: String = "Hello", artist: String = "Adele", duration: Int = 295, synced: Boolean = true) =
        LyricsMatcher.Candidate(title, artist, "25", duration, synced)

    @Test fun exactMatchSurvives() { assertNotNull(LyricsMatcher.score(track, candidate())) }
    @Test fun wrongArtistCannotWinWithSyncedLyrics() { assertNull(LyricsMatcher.score(track, candidate(artist = "Lionel Richie"))) }
    @Test fun tributeIsNotTheArtist() { assertNull(LyricsMatcher.score(track, candidate(artist = "Tribute to Adele"))) }
    @Test fun wrongRecordingCannotWinWithSyncedLyrics() { assertNull(LyricsMatcher.score(track, candidate(duration = 340))) }
    @Test fun liveAndRemixStaySeparate() {
        assertNull(LyricsMatcher.score(track, candidate(title = "Hello - Live")))
        assertNull(LyricsMatcher.score(track.copy(title = "Hello (Remix)"), candidate()))
    }
    @Test fun meaningfulSubtitleIsPreserved() {
        assertNull(LyricsMatcher.score(track.copy(title = "Hello - Part 1"), candidate(title = "Hello - Part 2")))
        assertNull(LyricsMatcher.score(track.copy(title = "Hello (Part 1)"), candidate(title = "Hello (Part 2)")))
    }
    @Test fun accentsAndRemasterNoiseStillMatch() {
        val spanish = LyricsMatcher.Track("Corazón - 2011 Remaster", "Beyoncé", "Album", 200)
        assertNotNull(LyricsMatcher.score(spanish, LyricsMatcher.Candidate("Corazon", "Beyonce", "Album", 201, true)))
    }
    @Test fun sharedGuestCannotReplaceTheLeadArtist() {
        val duet = track.copy(artist = "Adele, Guest")
        assertNull(LyricsMatcher.score(duet, candidate(artist = "Someone Else, Guest")))
        assertNotNull(LyricsMatcher.score(duet, candidate(artist = "Adele feat. Guest")))
    }
    @Test fun syncedBonusDoesNotOutweighARecordingMatch() {
        val exactPlain = LyricsMatcher.score(track, candidate(synced = false))!!
        val lessCertainSynced = LyricsMatcher.score(track, candidate(duration = 298))!!
        assertTrue(exactPlain > lessCertainSynced)
    }
}
