package com.music.spotui.ui.components

import com.music.spotui.data.entity.SongsModel
import kotlin.random.Random

internal const val STORY_DURATION_MS = 14_000L
internal const val MAX_COLLECTION_STORIES = 12

internal fun collectionStoryItems(songs: List<SongsModel>, random: Random = Random.Default): List<SongsModel> =
    songs.asSequence().filter { it.url.isNotBlank() }.distinctBy { it.url }.toList()
        .shuffled(random).take(MAX_COLLECTION_STORIES)

internal fun storyStartPosition(durationMs: Int, episode: Boolean): Long {
    val duration = durationMs.toLong()
    val preferred = if (episode) (duration / 12).coerceAtMost(480_000L)
    else (duration / 3).coerceAtMost(65_000L)
    return preferred.coerceIn(0L, (duration - STORY_DURATION_MS).coerceAtLeast(0L))
}
