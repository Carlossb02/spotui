package com.music.spotui.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PublicationDateTest {
    @Test fun youtubeAbbreviatedAndNoYearDatesAreRecognised() {
        val now=Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
        assertEquals(now-24*60*60_000L,publicationTime("hace 1 d",now,utc))
        assertEquals(now-2*60*60_000L,publicationTime("hace 2 h",now,utc))
        assertEquals(Instant.parse("2026-11-13T00:00:00Z").toEpochMilli(),publicationTime("13 nov",now,utc))
        assertEquals(Instant.parse("2025-12-30T00:00:00Z").toEpochMilli(),publicationTime("30 dic",now,utc))
        assertNull(publicationTime("Description about 13 nov",now,utc))
    }
    private val now=Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val utc=ZoneId.of("UTC")
    @Test fun spanishAndEnglishDatesHaveTheSamePublicationDay() {
        val expected=Instant.parse("2026-11-12T00:00:00Z").toEpochMilli()
        for(label in listOf("12 noviembre 2026","12 de noviembre de 2026","Nov 12, 2026","2026-11-12"))
            assertEquals(label,expected,publicationTime(label,now,utc))
    }
    @Test fun monthMeansCalendarMonthAndExcludesFutureDates() {
        val boundary=Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        assertTrue(withinLastMonth(boundary,now,utc))
        assertFalse(withinLastMonth(boundary-1,now,utc))
        assertFalse(withinLastMonth(now+1,now,utc))
    }
    @Test fun relativeDatesAreParsedWithoutExtractingWordsFromDescriptions() {
        val expected=now-2*24*60*60_000L
        assertEquals(expected,publicationTime("Hace 2 días",now,utc))
        assertEquals(expected,publicationTime("2 days ago",now,utc))
        assertNull(publicationTime("A story about what happened 2 days ago",now,utc))
        assertNull(publicationTime("2026",now,utc))
    }
    @Test fun cachedRelativePublicationDoesNotMoveForwardOnEveryRefresh() {
        val previous=now-3*60*60_000L
        assertEquals(previous,stablePublicationTime(previous,"Hace 2 horas",now+60*60_000L))
    }
    @Test fun noveltyIsStrictlyAfterTheReadWatermarkAndIndependentByCategory() {
        assertFalse(hasUnreadReleases(listOf(99,100),100))
        assertTrue(hasUnreadReleases(listOf(101),100))
        assertFalse(hasUnreadReleases(listOf(101),200))
        assertTrue(hasUnreadReleases(listOf(201),200))
    }
}
