package com.music.spotui.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ExtraLyricsSourcesTest {
    private val track = LyricsMatcher.Track("Hello", "Adele", "25", 295)

    @Test fun plainLyricsCanSurviveADifferentCutButNotAWrongArtist() {
        assertNotNull(LyricsMatcher.plainScore(track, LyricsMatcher.Candidate("Hello - Live", "Adele", "Live", 350, false)))
        assertNull(LyricsMatcher.plainScore(track, LyricsMatcher.Candidate("Hello", "Lionel Richie", "", 295, false)))
        assertNull(LyricsMatcher.plainScore(track, LyricsMatcher.Candidate("Hello (Remix)", "Adele", "", 295, false)))
    }

    @Test fun unsynchronisedFallbackHasNoResidualTimestampsOrMetadata() {
        val lyrics = LyricsText.plain("[ar:Adele]\n[ti:Hello]\n[00:01.20]First line\n[00:03.00]Second line")!!
        assertFalse(lyrics.synced)
        assertTrue(lyrics.lines.all { it.timeMs == 0L })
        assertEquals(listOf("First line", "Second line"), lyrics.lines.map { it.text })
    }

    @Test fun ovhUsesTheNamedArtistAndReturnsPlainText() {
        val requested = mutableListOf<String>()
        val result = ExtraLyricsSources.lyricsOvh(track) { url ->
            requested.add(url); """{"lyrics":"First line\nSecond line"}"""
        }!!
        assertEquals("https://api.lyrics.ovh/v1/Adele/Hello", requested.single())
        assertFalse(result.synced)
    }

    @Test fun kuGouRejectsWrongArtistsEvenWhenTheyHaveHigherBackendScores() = runBlocking {
        val text = Base64.getEncoder().encodeToString("[00:01.00]Correct line".toByteArray())
        val requests = mutableListOf<String>()
        val result = ExtraLyricsSources.kuGou(track) { url ->
            requests.add(url)
            if (url.contains("/search?")) """{"candidates":[
                {"id":"wrong","accesskey":"key","song":"Hello","singer":"Someone Else","duration":295000,"score":100},
                {"id":"correct","accesskey":"key","song":"Hello","singer":"Adele","duration":295000,"score":1}
            ]}""" else """{"content":"$text"}"""
        }!!
        assertEquals("Correct line", result.lines.single().text)
        assertTrue(requests.last().contains("id=correct"))
    }

    @Test fun kuGouTimingMismatchIsDowngradedToPlainLyrics() = runBlocking {
        val text = Base64.getEncoder().encodeToString("[00:01.00]Correct words".toByteArray())
        val result = ExtraLyricsSources.kuGou(track) { url ->
            if (url.contains("/search?")) """{"candidates":[{"id":"cut","accesskey":"key","song":"Hello","singer":"Adele","duration":350000}]}"""
            else """{"content":"$text"}"""
        }!!
        assertFalse(result.synced)
        assertEquals(0L, result.lines.single().timeMs)
    }

    @Test fun meaningfulSubtitleIsNotMistakenForAFeaturingTag() {
        val track = LyricsMatcher.Track("Song (Afterlife)", "Artist", "Album", 200)
        assertNull(LyricsMatcher.plainScore(track, LyricsMatcher.Candidate("Song", "Artist", "Album", 200, false)))
    }

    @Test fun malformedOrMissingProviderResultsAreNotLyrics() = runBlocking {
        assertNull(ExtraLyricsSources.kuGou(track) { "unavailable" })
        assertNull(ExtraLyricsSources.lyricsOvh(track) { """{"error":"Not found"}""" })
        assertNull(LyricsText.plain("null"))
    }
}
