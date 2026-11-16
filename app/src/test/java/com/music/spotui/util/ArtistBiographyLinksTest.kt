package com.music.spotui.util
import org.junit.Assert.*
import org.junit.Test
class ArtistBiographyLinksTest {
    @Test fun spotifyAnchorsStayInsideTheApp() {
        val relative=artistBiographyLink("/track/4uLU6hMCjMI75M1A2tKUQC")
        assertEquals("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",relative)
        assertTrue(isSpotifyBiographyLink(relative))
        assertTrue(isSpotifyBiographyLink("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(isSpotifyBiographyLink("https://spotify.link/example"))
        assertEquals("https://open.spotify.com/artist/example",artistBiographyLink("//open.spotify.com/artist/example"))
    }
    @Test fun lookalikeAndExternalHostsAreNotTreatedAsSpotify() {
        assertFalse(isSpotifyBiographyLink("https://open.spotify.com.example.com/track/example"))
        assertFalse(isSpotifyBiographyLink("https://example.com"))
        assertFalse(isSpotifyBiographyLink("javascript:alert(1)"))
    }
}
