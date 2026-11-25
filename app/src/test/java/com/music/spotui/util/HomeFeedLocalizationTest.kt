package com.music.spotui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFeedLocalizationTest {
    @Test
    fun translatesSpotifyHeadingsAndKeepsPersonalNames() {
        assertEquals("Hecho para carlos", localizedHomeLabel("Made for carlos", "es"))
        assertEquals("Más como Aitana", localizedHomeLabel("More like Aitana", "es"))
        assertEquals("Vuelve a escuchar", localizedHomeLabel("Jump back in", "es"))
        assertEquals("Made for carlos", localizedHomeLabel("Hecho para carlos", "en"))
        assertEquals("Mi música", localizedHomeLabel("Mi música", "es"))
    }
}
