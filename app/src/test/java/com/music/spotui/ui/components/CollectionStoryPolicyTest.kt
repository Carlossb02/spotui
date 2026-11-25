package com.music.spotui.ui.components

import com.music.spotui.data.entity.SongsModel
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionStoryPolicyTest {
    @Test
    fun shuffledStoriesContainOnlyUniquePlayableCollectionItems() {
        val songs = (1..25).map { id -> SongsModel().copy(id = id, url = "track-$id") }
        val results = collectionStoryItems(songs + songs + SongsModel() + SongsModel().copy(url = "episode:web-only"), Random(52))
        assertEquals(songs.size, results.size)
        assertEquals(results.size, results.map { it.url }.toSet().size)
        assertTrue(results.all { it in songs })
        assertTrue(results != songs)
    }

    @Test
    fun clipStartsWithinAvailableMedia() {
        assertEquals(0L, storyStartPosition(9_000))
        assertEquals(0L, storyStartPosition(20_000))
        assertEquals(85_000L, storyStartPosition(200_000))
        assertEquals(3_585_000L, storyStartPosition(7_200_000))
    }
}
