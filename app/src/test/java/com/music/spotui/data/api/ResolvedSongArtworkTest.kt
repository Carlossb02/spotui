package com.music.spotui.data.api

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.libraryIdentity
import com.music.spotui.sync.CloudPreferences
import com.music.spotui.sync.SyncOptions
import org.junit.Assert.*
import org.junit.Test

class ResolvedSongArtworkTest {
    @Test fun artworkIsDerivedFromAnExactVideoRatherThanAnEditedTitle() {
        assertEquals("https://i.ytimg.com/vi/BufrmkPmi2g/hqdefault.jpg",videoArtworkUrl("BufrmkPmi2g"))
        assertNull(videoArtworkUrl("Don't Leave"))
        assertNull(videoArtworkUrl("https://youtube.com/watch?v=BufrmkPmi2g"))
        val song=SongsModel(1,"Old title","","Artist","","query",spotifyTrackId="spotify:local:artist:album:track")
        assertEquals(song.libraryIdentity(),song.copy(title="Edited title",singer="New artist").libraryIdentity())
    }
    @Test fun onlyTheTextReferenceUsesTheExistingMetadataSyncCategory() {
        assertTrue("resolved_song_artwork_v1" in CloudPreferences.names)
        assertEquals(SyncOptions.Category.METADATA,SyncOptions.category("resolved_song_artwork_v1:spotify:local:track"))
    }
}
