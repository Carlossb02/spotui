package com.music.spotui.di

import org.junit.Assert.*
import org.junit.Test

class PreparedPlaybackCacheTest {
    private data class Input(val track: String = "recording", val title: String = "title",
        val artist: String = "artist", val duration: Int = 200_000, val quality: String = "high", val explicit: Boolean = true)

    @Test fun preparedRecordingRemainsInstantAfterMetadataTtlUntilItsUrlNearsExpiry() {
        val cache = PreparedPlaybackCache<String, Input, String>()
        cache.put("query", Input(), "exact selected stream", 3_600_000)
        assertEquals("exact selected stream", cache.get("query", Input(), 20 * 60_000))
        assertNull(cache.get("query", Input(), 3_570_000))
        assertNull(cache.get("query", Input(), 3_600_001))
    }

    @Test fun metadataEditionAndQualityChangesNeverReuseTheOldSelection() {
        val cache = PreparedPlaybackCache<String, Input, String>(); val input = Input()
        cache.put("query", input, "old stream", 3_600_000)
        listOf(input.copy(track = "other"), input.copy(title = "edited"), input.copy(artist = "other"),
            input.copy(duration = 210_000), input.copy(quality = "low"), input.copy(explicit = false))
            .forEach { assertNull(cache.get("query", it, 1000)) }
    }

    @Test fun resettingOrRejectingAStreamAlwaysForcesResolutionAgain() {
        val cache = PreparedPlaybackCache<String, Input, String>()
        cache.put("first", Input(), "first stream", 3_600_000)
        cache.put("second", Input(), "second stream", 3_600_000)
        cache.remove("first"); assertNull(cache.get("first", Input(), 1000))
        assertEquals("second stream", cache.get("second", Input(), 1000))
        cache.clear(); assertNull(cache.get("second", Input(), 1000))
    }
}
