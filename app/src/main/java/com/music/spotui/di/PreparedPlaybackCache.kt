package com.music.spotui.di

import java.util.concurrent.ConcurrentHashMap

/** Reuse an already selected recording only while its inputs and signed URL remain valid. */
internal class PreparedPlaybackCache<K, I, V : Any> {
    private data class Entry<I, V>(val input: I, val value: V, val expiresAt: Long)
    private val entries = ConcurrentHashMap<K, Entry<I, V>>()

    fun put(key: K, input: I, value: V, expiresAt: Long) {
        entries[key] = Entry(input, value, expiresAt)
    }

    fun get(key: K, input: I, now: Long): V? = entries[key]?.takeIf {
        it.input == input && it.expiresAt > now + 30_000L
    }?.value

    fun remove(key: K) { entries.remove(key) }
    fun clear() { entries.clear() }
}
