package com.music.spotui.providers

import com.metrolist.innertube.models.EpisodeItem
import com.music.spotui.data.publicationTime
import com.music.spotui.data.withinLastMonth
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PodcastPublicationRegressionTest {
    private val now=Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    @Test fun wildProjectOneOnOneMatchesAcrossSpacingVariants() {
        assertTrue(PodcastProvider.podcastNameScore("The Wild Project 1vs1", "The Wild Project 1 vs. 1 (cada jueves)") >= 85)
        assertEquals(0, PodcastProvider.podcastNameScore("The Wild Project 1vs1", "The Wild Project"))
    }
    // Reduced, public YouTube Music browse response for MPSPPLzuFY9Ixj9Z4G5-eRHblrmwMOY7tLUCHi.
    @Test fun realWildProjectBrowseResponseSurvivesTheRecentEpisodeFilter() {
        val raw=javaClass.getResource("/podcasts/wild-project-1vs1.json")!!.readText()
        val songs=PodcastProvider.parseEpisodesFromBrowse(JSONObject(raw),"THE WILD PROJECT 1VS1 (CADA JUEVES)")
        assertEquals(20,songs.size)
        assertTrue(songs.first().title.startsWith("The Wild Project #393"))
        assertEquals("hace 1 d",songs.first().releaseDate)
        assertTrue(songs.count {song->publicationTime(song.releaseDate,now)?.let {withinLastMonth(it,now)}==true}>0)
    }
    @Test fun publicationInHoursIsNotConsumedAsEpisodeDuration() {
        val root=JSONObject("""{"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Episode"}]},"onTap":{"watchEndpoint":{"videoId":"abcdefghijk"}},"subtitle":{"runs":[{"text":"hace 2 horas"}]}}}""")
        val song=PodcastProvider.parseEpisodesFromBrowse(root,"Show").single()
        assertEquals("hace 2 horas",song.releaseDate)
        assertEquals(0,song.durationMs)
    }
    @Test fun datesInFreeFormDescriptionsNeverBecomePublicationDates() {
        val root=JSONObject("""{"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Episode"}]},"onTap":{"watchEndpoint":{"videoId":"abcdefghijk"}},"description":{"runs":[{"text":"hoy"}]}}}""")
        assertEquals("",PodcastProvider.parseEpisodesFromBrowse(root,"Show").single().releaseDate)
    }
    @Test fun alternateEpisodeMappingPreservesThePublicationAndExplicitMetadata() {
        val song=PodcastProvider.episodeToSong(EpisodeItem(id="abcdefghijk",title="Episode",author=null,
            publishDateText="hace 1 d",thumbnail="",explicit=true),"Show")
        assertEquals("hace 1 d",song.releaseDate)
        assertTrue(song.explicit)
    }
}
