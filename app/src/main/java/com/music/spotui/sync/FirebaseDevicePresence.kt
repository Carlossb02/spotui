package com.music.spotui.sync

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Presence remains compatible with rules deployed before the optional device-kind field. */
internal class FirebaseDevicePresence(
    private val reference: DatabaseReference,
    private val name: String,
    private val kind: String,
    private val active: () -> Boolean,
) {
    private var legacy = false
    private fun record(online: Boolean): Map<String, Any> = linkedMapOf<String, Any>(
        "name" to name, "online" to online, "lastSeen" to ServerValue.TIMESTAMP,
    ).apply { if (!legacy) put("kind", kind) }

    private fun permissionDenied(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("permission denied", ignoreCase = true) }

    private suspend fun armDisconnect() {
        reference.onDisconnect().setValue(record(false)).await()
    }

    suspend fun connect() {
        if (!active()) return
        try {
            armDisconnect()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (legacy || !permissionDenied(error) || !active()) throw error
            legacy = true
            armDisconnect()
        }
        if (!active()) return
        try {
            // Replace the complete small record: a stale unsupported field must also be removed.
            reference.setValue(record(true)).await()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (legacy || !permissionDenied(error) || !active()) throw error
            legacy = true
            armDisconnect()
            if (active()) reference.setValue(record(true)).await()
        }
    }

    fun disconnect() {
        // Use the same accepted schema when explicitly closing the session.
        reference.setValue(record(false))
    }
}
