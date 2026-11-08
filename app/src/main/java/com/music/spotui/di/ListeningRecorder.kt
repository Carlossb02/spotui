package com.music.spotui.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.historyClearGeneration
import com.music.spotui.data.preferences.storeQualifiedListening
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One service-owned recorder, independent of screen lifecycle and decorative video players. */
internal object ListeningRecorder {
    private val lock = Any()
    private var session: QualifiedListeningSession? = null
    private var epoch = 0L
    private var incoming: QualifiedListeningSession? = null
    private var incomingUrl: String? = null
    private var incomingEpoch = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Write(val context: Context, val entry: com.music.spotui.data.preferences.HistoryEntry, val epoch: Long)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    init { scope.launch { for (write in writes) runCatching { storeQualifiedListening(write.context, write.entry, write.epoch) }
        .onFailure { Log.w("ListeningHistory", "Could not store qualified listening session", it) } } }
    fun begin(song: SongsModel) = synchronized(lock) {
        epoch = historyClearGeneration.get()
        session = QualifiedListeningSession(song, System.currentTimeMillis(), java.util.UUID.randomUUID().toString().replace("-", "").take(16))
    }
    fun beginIncoming(song: SongsModel) = synchronized(lock) {
        incomingEpoch = historyClearGeneration.get()
        incomingUrl = song.url
        incoming = QualifiedListeningSession(song, System.currentTimeMillis(), java.util.UUID.randomUUID().toString().replace("-", "").take(16))
    }
    fun sampleIncoming(context: Context, playing: Boolean, durationMs: Long) = synchronized(lock) {
        incoming?.sample(SystemClock.elapsedRealtime(), playing, durationMs)?.let {
            writes.trySend(Write(context.applicationContext, it, incomingEpoch))
        }
    }
    fun promoteIncoming(song: SongsModel) = synchronized(lock) {
        if (incomingUrl == song.url && incoming != null) { session = incoming; epoch = incomingEpoch }
        else begin(song)
        discardIncoming()
    }
    fun discardIncoming() = synchronized(lock) { incoming = null; incomingUrl = null }
    fun sample(context: Context, playing: Boolean, durationMs: Long) = synchronized(lock) {
        session?.sample(SystemClock.elapsedRealtime(), playing, durationMs)?.let {
            writes.trySend(Write(context.applicationContext, it, epoch))
        }
    }
}
