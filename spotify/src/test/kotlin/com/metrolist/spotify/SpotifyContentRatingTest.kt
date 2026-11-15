package com.metrolist.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyContentRatingTest {
    private fun track(rating:String)=Spotify.parseGqlTrack(Json.parseToJsonElement(
        """{"uri":"spotify:track:1234567890123456789012","name":"Don't Leave",$rating}""").jsonObject)
    @Test fun graphQlExplicitLabelReachesTheTrackModel() {
        assertTrue(track(""""contentRating":{"label":"EXPLICIT"}""").explicit)
    }
    @Test fun noneAndMissingLabelsAreNotExplicit() {
        assertFalse(track(""""contentRating":{"label":"NONE"}""").explicit)
        assertFalse(track(""""contentRating":null""").explicit)
    }
    @Test fun booleanPayloadIsAlsoSupported() {
        assertTrue(track(""""explicit":true""").explicit)
        assertFalse(track(""""explicit":false""").explicit)
    }
}
