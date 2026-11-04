package com.music.spotui.util

/** Thread-safe LRU for deterministic CPU-only computations. Never caches remote results.
 * Compute outside the monitor so unrelated matches never block one another.
 */
internal class BoundedMemoizer<K : Any, V : Any>(private val maxEntries: Int) {
    init { require(maxEntries > 0) }
    private val values = LinkedHashMap<K, V>(16, .75f, true)

    fun getOrCompute(key: K, compute: () -> V): V {
        synchronized(values) { values[key]?.let { return it } }
        val computed = compute() // Failures are deliberately not memoized.
        return synchronized(values) {
            values[key] ?: computed.also {
                values[key] = it
                if (values.size > maxEntries) {
                    val oldest = values.entries.iterator()
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }
    fun clear() { synchronized(values) { values.clear() } }
}
