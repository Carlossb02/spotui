package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class QualifiedListeningSessionTest {
    private val song = SongsModel().copy(id = 1, title = "Song", singer = "Artist", url = "song:1", durationMs = 180_000, spotifyTrackId = "spotify-id")
    @Test fun thresholdIsThirtySecondsOfPlaying() {
        val s = QualifiedListeningSession(song, 1000L, "one")
        assertNull(s.sample(0L, true, 180_000))
        assertNull(s.sample(29_999L, true, 180_000))
        val entry = s.sample(30_000L, true, 180_000)!!
        assertTrue(entry.qualified); assertEquals(180_000L, entry.durationMs); assertEquals("spotify-id", entry.spotifyTrackId)
    }
    @Test fun pauseAndBufferingDoNotCount() {
        val s = QualifiedListeningSession(song, 1000L, "one")
        s.sample(0, true, 180_000); assertNull(s.sample(15_000, false, 180_000))
        assertNull(s.sample(150_000, false, 180_000)); assertNull(s.sample(160_000, true, 180_000))
        assertNotNull(s.sample(175_000, false, 180_000))
    }
    @Test fun movingTheSeekBarDoesNotEarnTime() {
        val s = QualifiedListeningSession(song, 1000L, "one")
        s.sample(0, true, 180_000)
        // Media position is intentionally not an input; a jump to 2:59 earns no extra milliseconds.
        assertNull(s.sample(20, true, 180_000))
    }
    @Test fun qualifyingOnceDoesNotCountEveryFollowingTick() {
        val s = QualifiedListeningSession(song, 1000L, "one")
        s.sample(0, true, 180_000); assertNotNull(s.sample(30_000, true, 180_000))
        assertNull(s.sample(60_000, true, 180_000)); assertNull(s.sample(80_000, false, 180_000))
    }
    @Test fun consecutiveReplaysHaveDifferentSessionIds() {
        val one = QualifiedListeningSession(song, 1000L, "one")
        val two = QualifiedListeningSession(song, 2000L, "two")
        one.sample(0, true, 180_000); two.sample(0, true, 180_000)
        assertNotEquals(one.sample(30_000, true, 180_000)!!.eventId, two.sample(30_000, true, 180_000)!!.eventId)
    }
    @Test fun lateDurationUpdatesSameEventRatherThanAddingAPlay() {
        val s = QualifiedListeningSession(song.copy(durationMs = 0), 1000L, "one")
        s.sample(0, true, 0); assertEquals(0L, s.sample(30_000, true, 0)!!.durationMs)
        val update = s.sample(31_000, true, 200_000)!!
        assertEquals("one", update.eventId); assertEquals(200_000L, update.durationMs)
    }
    @Test fun missingOrNegativeDurationDoesNotInventMinutes() {
        val s = QualifiedListeningSession(song.copy(durationMs = 0), 1000L, "one")
        s.sample(0, true, Long.MIN_VALUE)
        assertEquals(0L, s.sample(30_000, true, Long.MIN_VALUE)!!.durationMs)
    }
    @Test fun metadataDurationWinsOverAnUnrelatedEngineDuration() {
        val s = QualifiedListeningSession(song, 1000L, "one")
        s.sample(0, true, 999_999L)
        assertEquals(180_000L, s.sample(30_000, true, 999_999L)!!.durationMs)
    }
}
