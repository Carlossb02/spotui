package com.music.spotui.providers

import com.metrolist.spotify.models.SpotifyShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastDiscoveryPolicyTest {
    private val policy = PodcastDiscoveryPolicy
    private val show = SpotifyShow(id = "1234567890123456789012", name = "Es la Mañana de Federico", publisher = "esRadio")
    private val episode = "Las noticias del lunes por la mañana"
    private val video = PodcastDiscoveryPolicy.Video("abcdefghijk", episode, "esRadio", 900)

    @Test fun spanishTitleVariantFindsTheSameShow() {
        assertTrue(policy.showTitleMatches("Las mañanas de Federico", show.name))
        assertEquals("manana federico", policy.broaderQuery("Las mañanas de Federico"))
        assertTrue(policy.relevantShow("mañanas de federico", show.name))
        assertFalse(policy.showTitleMatches("Es la Mañana de Federico: recopilación de vídeos", show.name))
    }

    @Test fun aSpotifyPlaylistNeverQualifiesAsAPodcast() {
        assertTrue(policy.validShow(show.copy(uri = "spotify:show:${show.id}")))
        assertFalse(policy.validShow(show.copy(uri = "spotify:playlist:${show.id}")))
        assertFalse(policy.validShow(show.copy(uri = "spotify:episode:${show.id}")))
    }

    @Test fun anEpisodeNeedsTitlePublisherAndComparableDuration() {
        assertEquals(video, policy.episodeVideo(episode, 900_000, show, listOf(video)))
        listOf(video.copy(seconds = 160), video.copy(publisher = "Canal musical"),
            video.copy(title = "$episode | mejores clips"), video.copy(seconds = null)).forEach {
            assertNull(policy.episodeVideo(episode, 900_000, show, listOf(it)))
        }
    }

    @Test fun aTieBetweenUploadsDoesNotChooseAnArbitraryVideo() {
        assertNull(policy.episodeVideo(episode, 900_000, show, listOf(video, video.copy(id = "abcdefghijl"))))
        assertEquals(video, policy.episodeVideo(episode, 900_000, show, listOf(video, video)))
    }
}
