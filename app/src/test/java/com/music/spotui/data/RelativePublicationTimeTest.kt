package com.music.spotui.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativePublicationTimeTest {
    private val now = 1_800_000_000_000L
    @Test fun fullUnitsDistinguishMinutesFromMonths() {
        assertEquals("Hace 1 minuto", relativePublicationTime(now-60_000,now))
        assertEquals("Hace 59 minutos", relativePublicationTime(now-59*60_000,now))
        assertEquals("Hace 1 hora", relativePublicationTime(now-60*60_000,now))
        assertEquals("Hace 23 horas", relativePublicationTime(now-23*60*60_000,now))
        assertEquals("Hace 1 día", relativePublicationTime(now-24*60*60_000,now))
        assertEquals("Hace 1 mes", relativePublicationTime(now-30L*24*60*60_000,now))
        assertEquals("Hace 2 meses", relativePublicationTime(now-60L*24*60*60_000,now))
        assertEquals("Hace 2 años", relativePublicationTime(now-730L*24*60*60_000,now))
    }
    @Test fun futureAndVeryRecentDatesNeverHaveNegativeAges() {
        assertEquals("Ahora mismo", relativePublicationTime(now+60_000,now))
        assertEquals("Ahora mismo", relativePublicationTime(now-1,now))
    }
}
