package com.music.spotui.ui.components

import com.music.spotui.data.entity.SongsModel
import kotlin.random.Random

internal const val STORY_DURATION_MS = 30_000L

internal fun supportsCollectionStory(song: SongsModel): Boolean =
    song.url.isNotBlank() && !song.url.startsWith("episode:") && !song.url.startsWith("sync_pending:")

internal fun collectionStoryItems(songs: List<SongsModel>, random: Random = Random.Default): List<SongsModel> =
    songs.asSequence().filter(::supportsCollectionStory).distinctBy { it.url }.toList()
        .shuffled(random)

internal fun storyStartPosition(durationMs: Long): Long =
    ((durationMs - STORY_DURATION_MS) / 2).coerceAtLeast(0L)
