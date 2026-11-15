package com.music.spotui.data.preferences

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalPlaylistRatingTest {
    private val id="0Zx8khUcEfCFK2AEoIhC92"
    @Test fun oldLocalCopyGetsItsExactSpotifyRecordingRatingWithoutChangingThePlaylist() {
        val root=JSONObject("""{"id":"local_pl_test","name":"Mine","songs":[{"id":7,"title":"My custom title","spotifyTrackId":"$id","explicit":false},{"id":8,"title":"Other","spotifyTrackId":"1234567890123456789012","explicit":false}]}""")
        val repaired=JSONObject(repairLocalPlaylistRating(root.toString(),id,true)!!)
        assertEquals("Mine",repaired.getString("name"))
        val songs=repaired.getJSONArray("songs")
        assertEquals(2,songs.length())
        assertEquals(7,songs.getJSONObject(0).getInt("id"))
        assertEquals("My custom title",songs.getJSONObject(0).getString("title"))
        assertTrue(songs.getJSONObject(0).getBoolean("explicit"))
        assertFalse(songs.getJSONObject(1).getBoolean("explicit"))
    }
    @Test fun oldPlaybackQueryCanSupplyTheIdButTheTitleCannot() {
        val raw="""{"songs":[{"title":"Don't Leave","url":"spotify:track:$id|Don't Leave Snakehips"},{"title":"Don't Leave","url":"other"}]}"""
        val songs=JSONObject(repairLocalPlaylistRating(raw,id,true)!!).getJSONArray("songs")
        assertTrue(songs.getJSONObject(0).getBoolean("explicit"))
        assertFalse(songs.getJSONObject(1).has("explicit"))
        assertNull(repairLocalPlaylistRating("""{"songs":[{"spotifyTrackId":"$id","explicit":true}]}""",id,true))
    }
}
