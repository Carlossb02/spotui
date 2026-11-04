package com.music.spotui.audio

import org.junit.Assert.*
import org.junit.Test

class YouTubeVolumeTest {
    @Test fun perceptualLevelIsNotTreatedAsARelativeOffset() {
        assertEquals(-1.56, YouTubeVolume.correctionDb(-5.44, -12.44)!!, 0.0001)
        assertEquals(4.0, YouTubeVolume.correctionDb(null, -18.0)!!, 0.0001)
    }
    @Test fun relativeFallbackAndInvalidDataAreConservative() {
        assertEquals(-4.0, YouTubeVolume.correctionDb(4.0, null)!!, 0.0001)
        assertNull(YouTubeVolume.correctionDb(Double.NaN, Double.POSITIVE_INFINITY))
    }
    @Test fun naturalCrossfadeHasContinuousEndpointsAndNoCorrelatedPeakBoost() {
        assertEquals(1f to 0f, YouTubeVolume.crossfadeGains(0f, true))
        assertEquals(0f to 1f, YouTubeVolume.crossfadeGains(1f, true))
        var previous = 0f
        for (i in 0..1000) {
            val (outgoing, incoming) = YouTubeVolume.crossfadeGains(i / 1000f, true)
            assertEquals(1f, outgoing + incoming, 0.00001f)
            assertTrue(incoming >= previous)
            previous = incoming
        }
    }
    @Test fun codecPreferenceDoesNotOverrideHighQualityOrLowDataChoice() {
        assertTrue(AudioFormatPreference.score(256000, "audio/mp4; codecs=mp4a", false) >
            AudioFormatPreference.score(48000, "audio/webm; codecs=opus", false))
        assertTrue(AudioFormatPreference.score(48000, "audio/webm; codecs=opus", true) >
            AudioFormatPreference.score(160000, "audio/webm; codecs=opus", true))
    }
}
