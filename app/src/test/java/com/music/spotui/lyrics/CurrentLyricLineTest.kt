package com.music.spotui.lyrics

import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.entity.LyricLine
import org.junit.Assert.*
import org.junit.Test

class CurrentLyricLineTest {
    private val lyrics = Lyrics(listOf(LyricLine(1000, "First"), LyricLine(3000, ""), LyricLine(5000, "Last")), true)
    @Test fun futureLyricIsNotShownBeforeSingingStarts() { assertNull(currentLyricLine(lyrics, 999)) }
    @Test fun timestampsAndSeekUseCurrentPosition() {
        assertEquals("Last", currentLyricLine(lyrics, 5000))
        assertEquals("First", currentLyricLine(lyrics, 1500))
    }
    @Test fun instrumentalGapsClearThePreviousLine() { assertNull(currentLyricLine(lyrics, 4000)) }
    @Test fun PlainLyricsNeverPretendToBeSynced() { assertNull(currentLyricLine(lyrics.copy(synced = false), 2000)) }
}
