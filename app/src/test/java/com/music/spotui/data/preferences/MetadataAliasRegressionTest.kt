package com.music.spotui.data.preferences
import org.junit.Assert.*
import org.junit.Test
class MetadataAliasRegressionTest {
    @Test fun aStaleDeviceLocalIdCannotHideTheNewCoverForTheSameSpotifyTrack() {
        val old=CustomMetadataPref.TrackMetadataOverride("title","artist","album","old.jpg",10)
        val fresh=old.copy(coverUri="https://example.com/chosen.jpg",updatedAt=20)
        assertEquals(fresh,preferredMetadataOverride(listOf("spotify:track","id:old"),mapOf("spotify:track" to fresh,"id:old" to old)))
        assertEquals(fresh.coverUri,preferredMetadataOverride(listOf("id:old","spotify:track"),mapOf("spotify:track" to fresh,"id:old" to old))?.coverUri)
    }
    @Test fun canonicalSpotifyAliasWinsForLegacyEntriesWithoutTimestamps() {
        val canonical=CustomMetadataPref.TrackMetadataOverride("title","artist","album","chosen.jpg")
        assertEquals(canonical,preferredMetadataOverride(listOf("spotify:x","id:1"),mapOf("spotify:x" to canonical,"id:1" to canonical.copy(coverUri="old.jpg"))))
    }
}
