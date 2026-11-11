package com.music.spotui.sync

import org.junit.Assert.*
import org.junit.Test

class SyncOptionsTest {
    @Test fun downloadsAndSpotifyCredentialsAlwaysStayOnTheDevice() {
        listOf("Downloads:12", "spotify_session:sp_dc", "google_device_sync_config:enabled", "google_sync_categories:PLAYER")
            .forEach { assertFalse(it, SyncOptions.allows(it)) }
    }
    @Test fun eachTextStoreHasAnExplicitCategory() {
        assertEquals(SyncOptions.Category.LIBRARY,SyncOptions.category("LocalPlaylists:lists"))
        assertEquals(SyncOptions.Category.METADATA,SyncOptions.category("AlternativeStreams:spotify:track"))
        assertEquals(SyncOptions.Category.PODCASTS,SyncOptions.category("spotui_podcast_resume_v1:episodes"))
        assertEquals(SyncOptions.Category.HISTORY,SyncOptions.category("history:recording"))
        assertEquals(SyncOptions.Category.PLAYER,SyncOptions.category("player:session"))
        assertNull(SyncOptions.category("unknown_store:secret"))
    }
}
