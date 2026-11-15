package com.music.spotui.audio

import org.junit.Assert.*
import org.junit.Test

class AudioFormatPreferenceTest {
    @Test fun zeroAverageDoesNotHideHighQualityStream() {
        val high=AudioFormatPreference.bitrate(0,256000,null,null)
        assertTrue(AudioFormatPreference.score(high,"audio/mp4",false) >
            AudioFormatPreference.score(128000,"audio/mp4",false))
    }
    @Test fun missingBitrateCanBeRecoveredFromStreamSize() {
        assertEquals(256000,AudioFormatPreference.bitrate(null,0,3200000,100000))
    }
    @Test fun averageDescribesVariableBitrateBetterThanPeak() {
        assertEquals(128000,AudioFormatPreference.bitrate(128000,256000,3200000,100000))
    }
    @Test fun dataSaverDoesNotChooseUnknownSizeOverKnownSmallStream() {
        assertTrue(AudioFormatPreference.score(48000,"audio/webm; codecs=opus",true) >
            AudioFormatPreference.score(0,"audio/mp4",true))
    }
    @Test fun codecPreferenceCannotPromoteVeryLowQualityAudio() {
        assertTrue(AudioFormatPreference.score(256000,"audio/mp4",false) >
            AudioFormatPreference.score(64000,"audio/webm; codecs=opus",false))
    }
}
