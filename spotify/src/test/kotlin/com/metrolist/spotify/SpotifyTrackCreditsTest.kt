package com.metrolist.spotify
import com.metrolist.spotify.models.parseTrackCredits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.Assert.*

class SpotifyTrackCreditsTest {
    @Test fun readsOnlyActualNamedContributorsAndArtistLinks() {
        val data = parseTrackCredits(Json.parseToJsonElement("""{"roleCredits":[{"roleTitle":"Writers","artists":[{"name":"Person","uri":"spotify:artist:1234567890123456789012"},{"name":"No profile"},{"name":""}]}],"sourceNames":["Label"]}""").jsonObject)
        assertEquals("Writers", data.roles.single().title)
        assertEquals(2, data.roles.single().people.size)
        assertEquals("1234567890123456789012", data.roles.single().people.first().artistId)
        assertEquals("", data.roles.single().people.last().artistId)
        assertEquals(listOf("Label"), data.sources)
    }
    @Test fun missingCreditsNeverInventsPerformers() {
        assertTrue(parseTrackCredits(Json.parseToJsonElement("""{"trackTitle":"A song","sourceNames":["Label"]}""").jsonObject).roles.isEmpty())
    }
    @Test fun ignoresEmptyRolesAndDeduplicatesPeople() {
        val data = parseTrackCredits(Json.parseToJsonElement("""{"roleCredits":[{"roleTitle":"Producers","artists":[{"name":"P"},{"name":"P"}]},{"roleTitle":"Writers","artists":[]},{"artists":[{"name":"X"}]}]}""").jsonObject)
        assertEquals(1, data.roles.size)
        assertEquals(1, data.roles.single().people.size)
    }
}
