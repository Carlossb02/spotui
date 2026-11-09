package com.music.spotui.data.api

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
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
    private const val JOB_ID = 0x535057
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainGate = Mutex()
    private val journals = mutableMapOf<String, SpotifyWriteJournal>()
    private var installed = false
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
            schedule(app)
            kick(app)
            true
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Log.w("SpotifyOutbox", "Could not retain library change", failure); false }
    }
    fun install(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (installed) return
            installed = true
            sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "sp_dc") kick(app) }
            app.getSharedPreferences("spotify_session", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(sessionListener)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { kick(app) }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kick(app)
                }
            }
            networkListener = callback
            runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(callback) }
        }
        kick(app)
    }
    fun kick(context: Context) {
        scope.launch {
            try { if (!drain(context.applicationContext)) schedule(context.applicationContext) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Log.w("SpotifyOutbox", "Will retry pending changes", failure); schedule(context.applicationContext) }
        }
    }
    private fun schedule(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // Do not replace an existing backoff window after each connectivity callback.
            if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID,
                ComponentName(context, com.music.spotui.sync.SpotifySyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }.onFailure { Log.w("SpotifyOutbox", "Background retry unavailable; will retry on next launch", it) }
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
            } else failed = true
        }
        !failed && store.entries().isEmpty()
    }
    private suspend fun send(context: Context, owner: String, edit: PendingSpotifyWrite): Boolean = try {
        check(account(context) == owner) { "Spotify account changed" }
        if (edit.kind != "playlist") {
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
