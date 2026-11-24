package com.music.spotui.sync

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.database.*
import com.music.spotui.data.preferences.RELEASE_NEWS_PREFS

/** Four monotonic integers, sent only when a read/reset changes. No polling or media. */
internal class ReleaseChecksSync(context: Context, private val reference: DatabaseReference) {
    private val prefs = context.getSharedPreferences(RELEASE_NEWS_PREFS, 0)
    private val known = mutableMapOf<String, Long>()
    private val pending = mutableSetOf<String>()
    private var ready = false
    @Volatile private var closed = false
    private val keys = listOf("music", "podcasts", "music_reset", "podcasts_reset")
    private fun enabled(key: String) = SyncOptions.allows("$RELEASE_NEWS_PREFS:$key")
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in keys && ready && !closed) publish(key!!)
    }
    private val listener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            if (closed) return
            keys.forEach { key ->
                val remote = snapshot.child(key).getValue(Long::class.java) ?: 0L
                known[key] = maxOf(known[key] ?: 0, remote)
                if (enabled(key) && remote > prefs.getLong(key, 0)) prefs.edit().putLong(key, remote).apply()
            }
            ready = true
            keys.forEach(::publish)
        }
        override fun onCancelled(error: DatabaseError) {
            ready = false
            android.util.Log.w("ReleaseChecksSync", "Realtime updates unavailable: " + error.message)
        }
    }
    init { prefs.registerOnSharedPreferenceChangeListener(changes); reference.addValueEventListener(listener) }
    private fun publish(key: String) {
        val local = prefs.getLong(key, 0)
        if (closed || !ready || !enabled(key) || local <= (known[key] ?: 0) || !pending.add(key)) return
        reference.child(key).runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (closed || !enabled(key)) return Transaction.abort()
                val previous = (data.value as? Number)?.toLong() ?: 0L
                if (previous >= local) return Transaction.abort()
                data.value = local
                return Transaction.success(data)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                pending.remove(key)
                if (closed) return
                if (error != null) {
                    android.util.Log.w("ReleaseChecksSync", "Could not share read status: " + error.message)
                    return
                }
                val remote = (snapshot?.value as? Number)?.toLong() ?: if (committed) local else 0L
                known[key] = maxOf(known[key] ?: 0, remote)
                if (remote >= local) publish(key)
            }
        }, false)
    }
    fun close() { closed = true; prefs.unregisterOnSharedPreferenceChangeListener(changes); reference.removeEventListener(listener) }
}
