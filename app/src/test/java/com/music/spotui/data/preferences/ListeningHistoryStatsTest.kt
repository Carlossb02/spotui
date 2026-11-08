package com.music.spotui.data.preferences

import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryStatsTest {
    private fun entry(id: Int, url: String = "song:$id", artist: String = "Artist", album: String = "Album") =
        HistoryEntry(id.toLong(), id, "Title", artist, album, url = url, qualified = true)

    @Test fun podcastsNeverCountAsArtistsOrSongs() {
        val stats = ListeningHistoryStats(listOf(entry(1), entry(2, "yt_ep:episode|label", "Host", "Show"), entry(3, "episode:spotify")))
        assertEquals(1, stats.music.size)
        assertEquals(2, stats.podcasts.size)
        assertEquals(listOf("Artist"), stats.artists.map { it.name })
        assertEquals(1, stats.tracks.size)
    }
    @Test fun podcastRankingGroupsEpisodesByShow() {
        val a = entry(1, "yt_ep:first", album = "Show")
        val b = entry(2, "yt_ep:second", album = "Show")
        val stats = ListeningHistoryStats(listOf(a, b, entry(3, "yt_ep:other", album = "Other")))
        assertEquals("Show", stats.shows.first().name)
        assertEquals(2, stats.shows.first().plays)
        assertEquals(b, stats.shows.first().latest)
    }
    @Test fun sameTitleDifferentRecordingStaysSeparate() {
        val stats = ListeningHistoryStats(listOf(entry(1), entry(2)))
        assertEquals(2, stats.tracks.size)
    }
    @Test fun episodeDisplaySuffixDoesNotSplitTheSameRecording() {
        assertEquals(entry(1, "yt_ep:original|old").recordingKey(), entry(1, "yt_ep:original|new").recordingKey())
    }
    @Test fun filtersPreserveChronologicalInput() {
        val music = entry(2)
        val podcast = entry(1, "yt_ep:first")
        val stats = ListeningHistoryStats(listOf(music, podcast))
        assertEquals(listOf(music, podcast), stats.filtered(HistoryKind.ALL))
        assertEquals(listOf(music), stats.filtered(HistoryKind.MUSIC))
        assertEquals(listOf(podcast), stats.filtered(HistoryKind.PODCASTS))
    }
    @Test fun blankShowFallsBackToHost() {
        val stats = ListeningHistoryStats(listOf(entry(1, "yt_ep:first", "Host", "")))
        assertEquals("Host", stats.shows.single().name)
    }
    @Test fun emptyHistoryHasNoInventedRankings() {
        val stats = ListeningHistoryStats(emptyList())
        assertTrue(stats.artists.isEmpty())
        assertTrue(stats.tracks.isEmpty())
        assertTrue(stats.shows.isEmpty())
    }
    @Test fun minutesIncludeEarlierMusicRecordsToo() {
        val a=entry(1).copy(durationMs=180_000,playCount=3)
        val podcast=entry(2,"yt_ep:one").copy(durationMs=3_600_000,playCount=4)
        val legacy=entry(3).copy(qualified=false,durationMs=180_000,playCount=5)
        val stats=ListeningHistoryStats(listOf(a,podcast,legacy))
        assertEquals(24L,stats.musicMinutes)
        assertEquals(8,stats.musicPlays)
        assertEquals(5,stats.legacyPlays)
    }
    @Test fun annualRankingCombinesMonthlyCountersForSameRecording() {
        val a=entry(1).copy(playCount=2)
        val b=a.copy(ts=100,playCount=3)
        val stats=ListeningHistoryStats(listOf(a,b))
        assertEquals(5,stats.tracks.single().plays)
    }
    @Test fun missingDurationIsNotReplacedWithAnInventedAverage() {
        val stats=ListeningHistoryStats(listOf(entry(1).copy(durationMs=0,playCount=3)))
        assertEquals(0L,stats.musicMinutes)
        assertEquals(3,stats.missingDurations)
    }
    @Test fun earlierRecordsParticipateInRankingsAndTotals() {
        val old = entry(1).copy(qualified=false,playCount=7)
        val fresh = entry(2).copy(playCount=2)
        val stats = ListeningHistoryStats(listOf(old,fresh))
        assertEquals(9,stats.musicPlays)
        assertEquals(old.recordingKey(),stats.tracks.first().latest.recordingKey())
    }
    @Test fun legacySpotifyQueriesShareIdentityWithNewRecords() {
        val old=entry(1,"spotify:track:abc|Song Artist").copy(qualified=false)
        val fresh=old.copy(spotifyTrackId="abc",qualified=true,durationMs=180_000)
        val stats=ListeningHistoryStats(listOf(old,fresh))
        assertEquals(1,stats.tracks.size)
        assertEquals(2,stats.tracks.single().plays)
        assertEquals(6L,stats.musicMinutes)
    }
}
