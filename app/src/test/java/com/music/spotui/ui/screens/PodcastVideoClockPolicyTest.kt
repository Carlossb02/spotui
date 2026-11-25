package com.music.spotui.ui.screens

import org.junit.Assert.*
import org.junit.Test

class PodcastVideoClockPolicyTest {
    @Test fun visibleLagIsCorrectedWithoutWaitingForFiveSecondsOfDrift() {
        assertTrue(PodcastVideoClockPolicy.recover(1500, 6000, 2000))
        assertTrue(PodcastVideoClockPolicy.recover(-1500, 6000, 2000))
    }
    @Test fun bufferingAndRecentSeeksDoNotCauseRepeatedSeekLoops() {
        assertFalse(PodcastVideoClockPolicy.recover(3000, 500, 2000))
        assertFalse(PodcastVideoClockPolicy.recover(3000, 6000, 500))
        assertFalse(PodcastVideoClockPolicy.recover(100, 6000, 2000))
    }
    @Test fun smallCorrectionsAdjustOnlyTheVisualClockInTheRightDirection() {
        assertEquals(1f, PodcastVideoClockPolicy.speed(100), 0f)
        assertTrue(PodcastVideoClockPolicy.speed(500) > 1f)
        assertTrue(PodcastVideoClockPolicy.speed(-500) < 1f)
    }
    @Test fun initialAlignmentCatchesUpBeforeShowingTheImage() {
        assertFalse(PodcastVideoClockPolicy.readyToReveal(400))
        assertFalse(PodcastVideoClockPolicy.readyToReveal(-400))
        assertTrue(PodcastVideoClockPolicy.readyToReveal(250))
        assertTrue(PodcastVideoClockPolicy.speed(400, catchingUp = true) > PodcastVideoClockPolicy.speed(400))
        assertTrue(PodcastVideoClockPolicy.speed(-400, catchingUp = true) < PodcastVideoClockPolicy.speed(-400))
    }
}
