package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.Spotify
import com.music.spotui.data.entity.SavedLibrarySnapshot
import com.music.spotui.data.entity.SavedPlaylist
import com.music.spotui.data.preferences.LocalPlaylistPref
import com.music.spotui.data.preferences.PlaylistAdditionsPref
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One shared, progressive scan; never fetch all playlists once per player or per song. */
object SavedTrackLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(SavedLibrarySnapshot())
    val snapshot = _snapshot.asStateFlow()
    private var account = ""
    private var generation = 0L
    private var job: Job? = null
    private var localJob: Job? = null
    private var fetchedAt = 0L
    private var refreshRequested = false
    private var hydrated = false
    private var revision = 0L
    private var appContext: Context? = null
    private var persistJob: Job? = null
    private val edits = mutableMapOf<String, MutableMap<String, CachedMembershipEdit>>()
    internal data class Mutation(val generation: Long, val version: Long, val playlist: String, val track: String, val saved: Boolean)
    private data class Preview(val base: Boolean, val saved: Boolean, val version: Long)
    private val previews = mutableMapOf<Pair<String, String>, Preview>()

    private fun withPreviews(snapshot: SavedLibrarySnapshot): SavedLibrarySnapshot {
        val app = appContext
        val durable = if (app != null) SpotifyPendingWrites.entries(app).filter { it.kind == "playlist" } else emptyList()
        if (previews.isEmpty() && durable.isEmpty()) return snapshot
        return snapshot.copy(playlists = snapshot.playlists.map { original ->
        val writes = durable.filter { it.target == original.id }
        val list = if (writes.isEmpty()) original else original.copy(trackIds = original.trackIds.toMutableSet().apply {
            writes.forEach { if (it.saved) add(it.track) else remove(it.track) }
        })
        val pending = previews.filterKeys { it.first == list.id }
        if (pending.isEmpty()) list else list.copy(trackIds = list.trackIds.toMutableSet().apply {
            pending.forEach { (key, value) -> if (value.saved) add(key.second) else remove(key.second) }
        })
    })
    }

    /** Current user intent only: a partial membership scan must never hide unscanned rows. */
    @Synchronized internal fun playlistEdits(playlistId:String,pending:Map<String,Boolean>):Map<String,Boolean> {
        val id=SpotifySync.cleanPlaylistId(playlistId)
        val now=System.currentTimeMillis()
        val result=edits[id].orEmpty().filterValues {now-it.createdAt in 0..120_000L}.mapValues {it.value.saved}.toMutableMap()
        result.putAll(pending)
        previews.forEach {(key,edit)->if(key.first==id)result[key.second]=edit.saved}
        return result
    }

    @Synchronized internal fun optimistic(context: Context, playlistId: String, trackId: String, saved: Boolean): Mutation? {
        ensureLoaded(context)
        val id = SpotifySync.cleanPlaylistId(playlistId)
        val track = SpotifySync.cleanTrackId(trackId)
        val list = snapshot.value.playlists.firstOrNull { it.id == id && !it.local && it.checked } ?: return null
        val key = id to track
        val version = ++revision
        previews[key] = Preview(previews[key]?.base ?: (track in list.trackIds), saved, version)
        _snapshot.update(::withPreviews)
        return Mutation(generation, version, id, track, saved)
    }

    @Synchronized internal fun settle(mutation: Mutation?, playlistId: String, trackId: String, saved: Boolean, success: Boolean) {
        if (mutation == null) { if (success) confirmed(playlistId, trackId, saved); return }
        if (mutation.generation != generation) return
        val key = mutation.playlist to mutation.track
        val preview = previews[key]
        if (preview?.version == mutation.version) {
            previews.remove(key)
            if (!success) _snapshot.update { old -> old.copy(playlists = old.playlists.map { list ->
                if (list.id != key.first) list else list.copy(trackIds = if (preview.base) list.trackIds + key.second else list.trackIds - key.second)
            }) }
        } else if (success && preview != null) previews[key] = preview.copy(base = saved)
        if (success) confirmed(playlistId, trackId, saved) else persist()
    }

    private data class Permission(val editable: Boolean, val checkedAt: Long)
    private val permissions = mutableMapOf<String, Permission>()

    @Synchronized fun ensureLoaded(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val session = SpotifySession.spDc(app)
        appContext = app
        if (session != account) {
            account = session
            generation++
            job?.cancel()
            edits.clear()
            previews.clear()
            permissions.clear()
            refreshRequested = false
            hydrated = false
            persistJob?.cancel()
            fetchedAt = 0L
            _snapshot.value = SavedLibrarySnapshot()
        }
        if (job?.isActive == true) {
            if (force) refreshRequested = true
            return
        }
        val age = android.os.SystemClock.elapsedRealtime() - fetchedAt
        val lifetime = if (snapshot.value.failed) 10_000L else 60_000L
        if (!force && fetchedAt != 0L && age in 0 until lifetime) return
        val token = generation
        fetchedAt = android.os.SystemClock.elapsedRealtime()
        val request = scope.launch(start = CoroutineStart.LAZY) { load(app, token) }
        job = request
        request.invokeOnCompletion {
            synchronized(this@SavedTrackLibrary) {
                if (job === request && token == generation) {
                    job = null
                    if (refreshRequested) {
                        refreshRequested = false
                        ensureLoaded(app, force = true)
                    }
                }
            }
        }
        request.start()
    }

    /** Local writes update every player without another Spotify scan. */
    @Synchronized fun localChanged(context: Context) {
        val app = context.applicationContext
        localJob?.cancel()
        localJob = scope.launch {
            delay(80L)
            val locals = localLists(app)
            synchronized(this@SavedTrackLibrary) {
                _snapshot.update { old -> old.copy(playlists = locals + old.playlists.filterNot { it.local }.map { remote ->
                    remote.copy(extraSongs = PlaylistAdditionsPref.tracks(app, remote.id),hiddenKeys=PlaylistAdditionsPref.removedKeys(app,remote.id))
                }) }
                persist()
            }
        }
    }

    @Synchronized fun confirmed(playlistId: String, trackId: String, saved: Boolean) {
        val id = SpotifySync.cleanPlaylistId(playlistId)
        val track = SpotifySync.cleanTrackId(trackId)
        if (id.isBlank() || track.isBlank()) return
        previews[id to track]?.let { previews[id to track] = it.copy(base = saved) }
        edits.getOrPut(id) { mutableMapOf() }[track] = CachedMembershipEdit(saved, ++revision, System.currentTimeMillis())
        _snapshot.update { old -> withPreviews(old.copy(playlists = old.playlists.map { pl ->
            if (pl.id != id) pl else pl.copy(trackIds = if (saved) pl.trackIds + track else pl.trackIds - track)
        })) }
        persist()
    }

    /** A newly created list has known ownership and complete initial membership. */
    @Synchronized fun created(context: Context, playlistId: String, name: String, trackIds: Set<String>) {
        ensureLoaded(context)
        val id = SpotifySync.cleanPlaylistId(playlistId)
        if (id.isBlank()) return
        val entry = SavedPlaylist(id, name, trackIds = trackIds, checked = true)
        _snapshot.update { it.copy(playlists = it.playlists.filterNot { p -> p.id == id } + entry) }
        trackIds.forEach { confirmed(id, it, true) }
        persist()
        invalidatePlaylist(context, id)
    }

    /** Called only after a successful playlist write, including bulk/copy operations. */
    @Synchronized fun invalidatePlaylist(context: Context, playlistId: String) {
        fetchedAt = 0L
        permissions.remove(SpotifySync.cleanPlaylistId(playlistId))
        ensureLoaded(context, force = true)
    }

    @Synchronized private fun persist() {
        val app = appContext ?: return
        val token = generation
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(100L)
            val data = synchronized(this@SavedTrackLibrary) {
                if (token != generation) return@launch
                val durable = snapshot.value.playlists.map { list ->
                    val pending = previews.filterKeys { it.first == list.id }
                    if (pending.isEmpty()) list else list.copy(trackIds = list.trackIds.toMutableSet().apply {
                        pending.forEach { (key, value) -> if (value.base) add(key.second) else remove(key.second) }
                    })
                }
                account to CachedPlaylistSnapshot(durable, edits.mapValues { it.value.toMap() })
            }
            runCatching { SavedPlaylistCache.write(app, data.first, data.second) }
        }
    }

    private fun localLists(context: Context): List<SavedPlaylist> = LocalPlaylistPref.getLocalPlaylists(context).map {
        SavedPlaylist(it.id, it.name, it.coverUri, local = true, extraSongs = it.songs, checked = true)
    }

    @Synchronized private fun publish(token: Long, change: (SavedLibrarySnapshot) -> SavedLibrarySnapshot) {
        if (token == generation) { _snapshot.update { withPreviews(change(it)) }; persist() }
    }

    private suspend fun load(app: Context, token: Long) {
        val locals = localLists(app)
        val disk = synchronized(this) { if (!hydrated) account else null }?.let { SavedPlaylistCache.read(app, it) }?.let { cached ->
            cached.copy(playlists=cached.playlists.map {list->list.copy(extraSongs=PlaylistAdditionsPref.tracks(app,list.id),hiddenKeys=PlaylistAdditionsPref.removedKeys(app,list.id))})
        }
        synchronized(this) {
            if (token != generation) return
            if (disk != null && !hydrated) {
                disk.edits.forEach { (playlist, tracks) ->
                    val target = edits.getOrPut(playlist) { mutableMapOf() }
                    tracks.forEach { (track, edit) ->
                        if (track !in target && System.currentTimeMillis() - edit.createdAt in 0..120_000L) target[track] = edit
                        revision = maxOf(revision, edit.version)
                    }
                }
                val current = snapshot.value.playlists.associateBy { it.id }
                _snapshot.update { withPreviews(it.copy(playlists = locals + (current.values.filterNot { it.local } + disk.playlists).distinctBy { it.id })) }
                hydrated = true
            }
        }
        publish(token) { it.copy(playlists = locals + it.playlists.filterNot { p -> p.local }, loading = true, failed = false) }
        try {
            // User-library work has lower priority than locating the track the user just tapped.
            while (com.music.spotui.di.SongPlayer.isPreparingTrack()) delay(200L)
            val listings = SpotifySync.allLibraryPlaylists(app)
            val user = Spotify.me().getOrThrow().id
            check(user.isNotBlank())
            val permits = Semaphore(2)
            val previous = snapshot.value.playlists.associateBy { it.id }
            val order = listings.mapIndexed { index, list -> SpotifySync.cleanPlaylistId(list.id) to index }.toMap()
            fun verified(list: com.metrolist.spotify.models.SpotifyPlaylist): Boolean =
                list.owner?.id == user || list.contributors.any { it.id == user }
            fun entry(list: com.metrolist.spotify.models.SpotifyPlaylist): SavedPlaylist {
                val id = SpotifySync.cleanPlaylistId(list.id)
                return SavedPlaylist(id, list.name, list.images.firstOrNull()?.url.orEmpty(),
                    trackIds = previous[id]?.trackIds.orEmpty(),
                    extraSongs = PlaylistAdditionsPref.tracks(app, id),
                    hiddenKeys = PlaylistAdditionsPref.removedKeys(app,id),
                    checked = previous[id]?.checked == true)
            }
            val known = listings.filter(::verified).map(::entry).distinctBy { it.id }
            val listingIds = listings.map { SpotifySync.cleanPlaylistId(it.id) }.toSet()
            publish(token) { old -> old.copy(playlists = localLists(app) +
                (known.map { entry -> old.playlists.firstOrNull { it.id == entry.id && !it.local }?.copy(
                    name = entry.name, coverUri = entry.coverUri, extraSongs = entry.extraSongs, hiddenKeys=entry.hiddenKeys) ?: entry } +
                    old.playlists.filter { !it.local && (it.id in listingIds || edits[it.id]?.values?.any { edit -> System.currentTimeMillis() - edit.createdAt in 0..120_000L } == true) }).distinctBy { it.id }
                    .sortedBy { order[it.id] ?: Int.MAX_VALUE }) }
            // Owned lists appear immediately; a slow third-party permission check must not
            // delay all membership indicators. Network work remains bounded to two requests.
            supervisorScope {
                listings.sortedBy { !verified(it) }.map { list -> launch {
                    permits.withPermit {
                        val id = SpotifySync.cleanPlaylistId(list.id)
                        var canEdit = verified(list)
                        try {
                            while (com.music.spotui.di.SongPlayer.isPreparingTrack()) delay(200L)
                            if (!canEdit) {
                                val cached = synchronized(this@SavedTrackLibrary) { permissions[id] }
                                canEdit = cached?.takeIf { android.os.SystemClock.elapsedRealtime() - it.checkedAt in 0..600_000L }?.editable
                                    ?: verified(Spotify.playlist(id).getOrThrow()).also { allowed ->
                                        synchronized(this@SavedTrackLibrary) {
                                            if (token == generation) permissions[id] = Permission(allowed, android.os.SystemClock.elapsedRealtime())
                                        }
                                    }
                            }
                            if (!canEdit) {
                                publish(token) { old -> old.copy(playlists = old.playlists.filterNot { it.id == id && !it.local }) }
                                return@withPermit
                            }
                            val newEntry = entry(list)
                            publish(token) { old ->
                                if (old.playlists.any { it.id == id }) old else old.copy(
                                    playlists = old.playlists.filter { it.local } +
                                        (old.playlists.filterNot { it.local } + newEntry).sortedBy { order[it.id] ?: Int.MAX_VALUE })
                            }
                            while (com.music.spotui.di.SongPlayer.isPreparingTrack()) delay(200L)
                            val readRevision = synchronized(this@SavedTrackLibrary) { revision }
                            val ids = SpotifySync.playlistTrackIds(app, id, forceRefresh = true)
                            synchronized(this@SavedTrackLibrary) {
                                if (token != generation) return@withPermit
                                val merged = ids.toMutableSet()
                                val pending = edits[id]
                                pending?.toMap()?.forEach { (track, edit) ->
                                    val acknowledged = edit.version <= readRevision && (track in ids) == edit.saved
                                    val expired = System.currentTimeMillis() - edit.createdAt > 120_000L
                                    if (acknowledged || expired) pending.remove(track)
                                    else if (edit.saved) merged.add(track) else merged.remove(track)
                                }
                                publish(token) { old -> old.copy(playlists = old.playlists.map {
                                    if (it.id == id) it.copy(trackIds = merged, checked = true, failed = false) else it
                                }) }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            publish(token) { old -> old.copy(failed = true, playlists = old.playlists.map {
                                if (canEdit && it.id == id) it.copy(failed = true) else it
                            }) }
                        }
                    }
                } }.joinAll()
            }
            synchronized(this) { if (token == generation) fetchedAt = android.os.SystemClock.elapsedRealtime() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { publish(token) { it.copy(failed = true) } } finally { publish(token) { it.copy(loading = false) } }
    }
}
