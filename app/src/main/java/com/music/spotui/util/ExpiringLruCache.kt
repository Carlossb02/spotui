package com.music.spotui.util

/** Small memory-only cache with a monotonic lifetime and deterministic eviction. */
internal class ExpiringLruCache<K, V>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<V>(val value: V, val createdAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    init { require(capacity > 0 && ttlMs > 0) }

    @Synchronized fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (now() - entry.createdAt !in 0 until ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized fun remove(key: K) { entries.remove(key) }

    @Synchronized fun clear() { entries.clear() }

    @Synchronized fun put(key: K, value: V) {
        entries[key] = Entry(value, now())
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}
