package com.music.spotui.sync

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.CustomMetadataPref
import com.music.spotui.data.preferences.applyMetadataEdit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MetadataRevisionSyncTest {
    private fun revision(cover: String, at: Long) = SyncTree.changed(emptyMap(),
        SyncTree.flatten(JSONObject().put("type", "string").put("value", JSONObject().put("spotify:track",
            JSONObject().put("coverUri", cover).put("title", "song").put("updatedAt", at)))), SyncStamp(at, 0, "device$at"))
    @Test fun clearingACoverSurvivesTheOriginalPlaylistRow() {
        val song = SongsModel(1, "song", "old album", "artist", "https://old/cover", "query")
        val changed = applyMetadataEdit(song, CustomMetadataPref.TrackMetadataOverride("song", "artist", "", "", 200))
        assertEquals("", changed.coverUri); assertEquals("", changed.album)
        assertEquals("artist", changed.singer)
    }
    @Test fun aNewCoverIsTheValueShownWhenReopeningTheEditor() {
        val song = SongsModel(1, "song", "album", "artist", "https://old/cover", "query")
        val changed = applyMetadataEdit(song, CustomMetadataPref.TrackMetadataOverride("song", "artist", "album", "https://new/cover", 200))
        assertEquals("https://new/cover", changed.coverUri)
    }
    @Test fun aBlankRevisionWinsAgainstLegacyFillFieldsOnBothDevices() {
        val old = revision("https://old/cover", 100); val cleared = revision("", 200)
        val key = "CustomMetadata:overrides_json"
        val a = PodcastProgressSync.merge(key, old, cleared); val b = PodcastProgressSync.merge(key, cleared, old)
        assertEquals(a, b)
        assertEquals("", (a.value() as JSONObject).getJSONObject("value").getJSONObject("spotify:track").getString("coverUri"))
        assertEquals(a, PodcastProgressSync.merge(key, a, old))
    }
    @Test fun retiredLyricOptionsAreNotTreatedAsMissingAccountRequirements() {
        assertFalse(CloudPreferences.allowed("settings_prefs", "player_lyric_color"))
        assertTrue(CloudPreferences.allowed("settings_prefs", "player_current_lyric_line"))
    }
}
