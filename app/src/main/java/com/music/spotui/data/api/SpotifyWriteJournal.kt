package com.music.spotui.data.api

import org.json.JSONArray
import org.json.JSONObject

/** Only the latest intent for each resource is needed. No track metadata or credentials. */
internal data class PendingSpotifyWrite(
    val kind: String, val target: String, val track: String, val saved: Boolean, val version: Long,
) {
    val key get() = "$kind:$target:$track"
}

internal class SpotifyWriteJournal(raw: String?, private val persist: (String) -> Unit) {
    private var pending = linkedMapOf<String, PendingSpotifyWrite>()
    private var version = 0L
    init {
        val rows = JSONArray(raw ?: "[]")
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val edit = PendingSpotifyWrite(o.getString("kind"), o.getString("target"), o.optString("track"), o.getBoolean("saved"), o.getLong("version"))
            require(edit.kind in setOf("track", "album", "artist", "playlist"))
            pending[edit.key] = edit
            version = maxOf(version, edit.version)
        }
    }
    @Synchronized fun entries(): List<PendingSpotifyWrite> = pending.values.toList()
    @Synchronized fun enqueue(kind: String, target: String, track: String, saved: Boolean): PendingSpotifyWrite {
        require(kind in setOf("track", "album", "artist", "playlist") && target.isNotBlank())
        val edit = PendingSpotifyWrite(kind, target, track, saved, ++version)
        val next = LinkedHashMap(pending)
        next.remove(edit.key)
        next[edit.key] = edit
        commit(next)
        return edit
    }
    @Synchronized fun acknowledge(edit: PendingSpotifyWrite): Boolean {
        if (pending[edit.key] != edit) return false
        commit(LinkedHashMap(pending).apply { remove(edit.key) })
        return true
    }
    private fun commit(next: LinkedHashMap<String, PendingSpotifyWrite>) {
        val a = JSONArray()
        next.values.forEach { a.put(JSONObject().put("kind", it.kind).put("target", it.target)
            .put("track", it.track).put("saved", it.saved).put("version", it.version)) }
        persist(a.toString()) // If disk fails, retain the previous durable state.
        pending = next
    }
}
