package com.music.spotui.data.api

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.AtomicFile
import android.util.Log
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Durable, account-scoped outbox. Local actions survive loss of network, cache cleaning and process death. */
internal object SpotifyPendingWrites {
    internal const val JOB_ID = 0x535057
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainGate = Mutex()
    private val journals = mutableMapOf<String, SpotifyWriteJournal>()
    private var work: Job? = null
    private val signals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var sessionListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var networkListener: ConnectivityManager.NetworkCallback? = null
    private fun account(context: Context) = SpotifySession.spDc(context).takeIf { it.isNotBlank() }?.let { cookie ->
        java.security.MessageDigest.getInstance("SHA-256").digest(cookie.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    @Synchronized private fun journal(context: Context, owner: String): SpotifyWriteJournal = journals.getOrPut(owner) {
        val atomic = AtomicFile(File(context.filesDir, "spotify_pending_$owner.json"))
        val raw = try { atomic.openRead().bufferedReader().use { it.readText() } } catch (_: java.io.FileNotFoundException) { null }
        SpotifyWriteJournal(raw) { value ->
            val stream = atomic.startWrite()
            try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) } catch (e: Throwable) { atomic.failWrite(stream); throw e }
        }
    }
    fun entries(context: Context): List<PendingSpotifyWrite> {
        val owner = account(context) ?: return emptyList()
        return journal(context, owner).entries()
    }
    fun desired(context: Context, kind: String, target: String): Boolean? =
        entries(context).lastOrNull { it.kind == kind && it.target == target }?.saved

    /** Completion means accepted into durable storage; remote confirmation happens separately. */
    suspend fun enqueue(context: Context, kind: String, target: String, track: String = "", saved: Boolean,
        expectedSession: String = SpotifySession.spDc(context),
    ): Boolean = withContext(Dispatchers.IO) {
        // The caller may use UNDISPATCHED to reserve FIFO order. Dispatch before disk access.
        yield()
        val app = context.applicationContext
        if (SpotifySession.spDc(app) != expectedSession) return@withContext false
        val owner = account(app) ?: return@withContext false
        try {
            journal(app, owner).enqueue(kind, target, track, saved)
            kick(app)
            true
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Log.w("SpotifyOutbox", "Could not retain library change", failure); false }
    }
    @Synchronized fun setVisible(context: Context, visible: Boolean) {
        val app = context.applicationContext
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val preferences = app.getSharedPreferences("spotify_session", Context.MODE_PRIVATE)
        LocalSpotifyMirror.setVisible(app, visible)
        if (!visible) {
            work?.cancel()
            work = null
            networkListener?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            networkListener = null
            sessionListener?.let(preferences::unregisterOnSharedPreferenceChangeListener)
            sessionListener = null
            return
        }
        if (work != null) return
        sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "sp_dc") kick(app)
        }.also(preferences::registerOnSharedPreferenceChangeListener)
        networkListener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kick(app) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kick(app)
            }
        }.also { runCatching { connectivity.registerDefaultNetworkCallback(it) } }
        work = scope.launch {
            for (signal in signals) {
                val complete = try {
                    drain(app)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("SpotifyOutbox", "Will retry pending changes", failure)
                    false
                }
                if (!complete) {
                    delay(30_000)
                    signals.trySend(Unit)
                }
            }
        }
        kick(app)
    }

    fun kick(context: Context) {
        LocalSpotifyMirror.kick(context)
        signals.trySend(Unit)
    }

    suspend fun drain(context: Context): Boolean = drainGate.withLock {
        val owner = account(context) ?: return@withLock true
        val store = journal(context, owner)
        if (store.entries().isEmpty()) return@withLock true
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@withLock false
        var failed = false
        // A failure for one playlist must not block independent likes or other playlists.
        for (edit in store.entries()) {
            currentCoroutineContext().ensureActive()
            if (account(context) != owner) return@withLock true
            if (store.entries().none { it == edit }) continue
            while (com.music.spotui.di.SongPlayer.isPreparingTrack()) delay(200L)
            val ok = withTimeoutOrNull(30_000L) {
                if (!SpotifyTokenProvider.ensureToken(context)) false else send(context, owner, edit)
            } ?: false
            if (account(context) != owner) return@withLock true
            if (ok) {
                // A late completion can only acknowledge its own version, never a newer toggle.
                val acknowledged = store.acknowledge(edit)
                if (acknowledged && edit.kind == "track") LibraryContentMemory.confirmLike(context, edit.target, edit.saved)
                if (acknowledged && edit.kind == "playlist") {
                    SavedTrackLibrary.confirmed(edit.target, edit.track, edit.saved)
                    Api.HomeCache.invalidatePlaylist(edit.target)
                }
                if (acknowledged && edit.kind == "playlist_library") {
                    Api.HomeCache.library = null
                    com.music.spotui.sync.OwnLibraryChanges.changed()
                    if (!edit.saved) {
                        com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylists(context)
                            .filter { it.syncedSpotifyId == edit.target }
                            .forEach { LocalSpotifyMirror.refresh(context, it.id) }
                    }
                }
            } else failed = true
        }
        !failed && store.entries().isEmpty()
    }

    /** Shares the outbox gate so an earlier pending save cannot restore a removed playlist. */
    suspend fun removePlaylistConfirmed(context: Context, session: String, playlistId: String) = drainGate.withLock {
        val owner = checkNotNull(account(context)) { "Vuelve a conectar Spotify." }
        check(SpotifySession.spDc(context) == session) { "La cuenta de Spotify ha cambiado." }
        check(SpotifyTokenProvider.ensureToken(context)) { "Vuelve a conectar Spotify." }
        val user = Spotify.me().getOrThrow().id
        check(user.isNotBlank() && account(context) == owner) { "La cuenta de Spotify ha cambiado." }
        val store = journal(context, owner)
        store.entries().filter { it.kind == "playlist_library" && it.target == playlistId }
            .forEach { store.acknowledge(it) }
        SpotifySync.writePlaylistMembership(context, session, user, playlistId, saved = false)
    }

    private suspend fun send(context: Context, owner: String, edit: PendingSpotifyWrite): Boolean = try {
        check(account(context) == owner) { "Spotify account changed" }
        if (edit.kind == "playlist_library") {
            val session = SpotifySession.spDc(context)
            val user = Spotify.me().getOrThrow().id
            check(user.isNotBlank() && account(context) == owner) { "Spotify account changed" }
            SpotifySync.writePlaylistMembership(context, session, user, edit.target, edit.saved)
            true
        } else if (edit.kind != "playlist") {
            val uri = "spotify:${edit.kind}:${edit.target}"
            (if (edit.saved) Spotify.addToLibrary(listOf(uri)) else Spotify.removeFromLibrary(listOf(uri))).isSuccess
        } else {
            val uri = "spotify:track:${edit.track}"
            val refs = mutableListOf<Spotify.PlaylistItemRef>()
            var found = false
            var missingUid = false
            var offset = 0
            do {
                check(account(context) == owner) { "Spotify account changed" }
                val page = Spotify.playlistTracks(edit.target, limit = 100, offset = offset).getOrThrow()
                page.items.forEach { item ->
                    if (item.track?.id?.let(SpotifySync::cleanTrackId) == edit.track || item.track?.uri == uri) {
                        found = true
                        val uid = item.uid
                        if (uid == null) missingUid = true else refs += Spotify.PlaylistItemRef(uri, uid)
                    }
                }
                val next = page.offset + page.limit.coerceAtLeast(1)
                check(next > offset) { "Playlist pagination did not advance" }
                offset = next
            } while (offset < page.total && (!edit.saved || !found))
            check(account(context) == owner) { "Spotify account changed" }
            if (edit.saved) found || Spotify.addTracksToPlaylist(edit.target, listOf(uri)).isSuccess
            else !found || !missingUid && refs.isNotEmpty() && Spotify.removeTracksFromPlaylist(edit.target, refs).isSuccess
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Log.w("SpotifyOutbox", "Library write postponed", failure); false }
}
