package com.music.spotui.di

import com.music.spotui.data.entity.SongsModel

/** Tracks occurrences, rather than song IDs: a manual request may repeat a context track. */
internal class PlaybackQueueOrder {
    private data class Entry(val token: Long, val song: SongsModel, val manual: Boolean = false, val anchor: Long? = null)
    private var serial = 0L
    private var entries = emptyList<Entry>()
    private var original = emptyList<Long>()
    val songs get() = entries.map { it.song }
    val manualIndices get() = entries.indices.filter { entries[it].manual }.toSet()
    val originalIndices: List<Int> get() {
        val positions = entries.withIndex().associate { it.value.token to it.index }
        return original.mapNotNull { positions[it] }
    }
    val originalSongs: List<SongsModel> get() {
        val byToken = entries.associateBy { it.token }
        return original.mapNotNull { byToken[it]?.song }
    }

    fun replace(songs: List<SongsModel>, shuffled: Boolean, startSongId: Int? = null,
                restoredManual: Set<Int> = emptySet(), restoredOriginal: List<SongsModel> = emptyList(), restoredOriginalIndices: List<Int> = emptyList()) {
        var lastContext: Long? = null
        entries = songs.mapIndexed { i, song ->
            Entry(++serial, song, i in restoredManual, if (i in restoredManual) lastContext else null).also {
                if (!it.manual) lastContext = it.token
            }
        }
        original = when {
            restoredOriginalIndices.size == entries.size && restoredOriginalIndices.toSet() == entries.indices.toSet() ->
                restoredOriginalIndices.map { entries[it].token }
            restoredOriginal.isEmpty() -> entries.map { it.token }
            else -> {
                val pool = entries.groupBy { it.song.id to it.song.url }.mapValues { ArrayDeque(it.value) }
                val matched = restoredOriginal.mapNotNull { pool[it.id to it.url]?.removeFirstOrNull()?.token }
                val known = matched.toHashSet()
                matched + entries.filter { it.token !in known }.map { it.token }
            }
        }
        if (shuffled) {
            val first = entries.firstOrNull { it.song.id == startSongId } ?: entries.firstOrNull()
            entries = listOfNotNull(first) + entries.filter { it.token != first?.token }.shuffled()
        }
    }
    fun reconcile(songs: List<SongsModel>) {
        val pool = entries.groupBy { it.song.id to it.song.url }.mapValues { ArrayDeque(it.value) }
        entries = songs.map { song ->
            pool[song.id to song.url]?.removeFirstOrNull()?.copy(song = song) ?: Entry(++serial, song)
        }
        val tokens = entries.map { it.token }.toSet()
        val known = original.toHashSet()
        original = original.filter { it in tokens } + entries.filter { it.token !in known }.map { it.token }
    }
    fun enqueue(songs: List<SongsModel>, current: Int, next: Boolean) {
        if (songs.isEmpty()) return
        var at = (current + 1).coerceIn(0, entries.size)
        if (!next) {
            while (at < entries.size && entries[at].manual) at++
        }
        val active = entries.getOrNull(current)
        val anchor = if (active?.manual == true) active.anchor else active?.token
        entries = entries.toMutableList().apply { addAll(at, songs.map { Entry(++serial, it, true, anchor) }) }
    }
    fun move(from: Int, to: Int): Int {
        if (from !in entries.indices || to !in entries.indices) return from
        entries = entries.toMutableList().apply { add(to, removeAt(from)) }
        return to
    }
    fun remove(at: Int) {
        if (at in entries.indices) entries = entries.filterIndexed { i, _ -> i != at }
    }
    /** Keep played occurrences for Previous; omit consumed requests when the context repeats. */
    fun select(at: Int, previous: Int): Int {
        if (at !in entries.indices) return at
        if (at == 0 && previous == entries.lastIndex && previous > 0 && entries.any { !it.manual }) {
            val selected = entries[at]
            entries = entries.filter { !it.manual || it.token == selected.token }
            return entries.indexOfFirst { it.token == selected.token }
        }
        return at
    }
    fun shuffle(enabled: Boolean, current: Int): Int {
        val active = entries.getOrNull(current) ?: return 0
        val manual = entries.drop(current + 1).filter { it.manual }
        val base = entries.filterNot { it.manual }
        if (enabled) {
            original = entries.map { it.token }
            entries = entries.take(current + 1) + manual + entries.drop(current + 1).filterNot { it.manual }.shuffled()
        } else {
            val ranks = original.withIndex().associate { it.value to it.index }
            val ordered = base.sortedBy { ranks[it.token] ?: Int.MAX_VALUE }
            val activeBase = ordered.indexOfFirst { it.token == active.token }
            val resume = if (activeBase >= 0) activeBase + 1 else {
                ordered.indexOfFirst { it.token == active.anchor }.let { if (it >= 0) it + 1 else 0 }
            }
            entries = ordered.take(resume) + (if (active.manual) listOf(active) else emptyList()) + manual + ordered.drop(resume)
        }
        return entries.indexOfFirst { it.token == active.token }.coerceAtLeast(0)
    }
}
