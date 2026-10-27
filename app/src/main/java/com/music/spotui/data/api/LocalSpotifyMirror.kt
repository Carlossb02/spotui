package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LocalPlaylistPref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps locally created playlists connected to the Spotify playlist created for them.
 * An unavailable Spotify account leaves the local playlist usable, never pretends it synced.
 * External edits in Spotify are NOT replaced wholesale (avoids deleting other users' changes).
 */
object LocalSpotifyMirror {
    private const val TAG = "LocalSpotifyMirror"
    private const val PREF = "local_spotify_mirrors_v1"
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    // Observable synchronization result. The previous implementation swallowed
    // errors; the UI misleadingly announced that a playlist was syncing forever.
    enum class Phase { SYNCING, SYNCED, FAILED }
    data class SyncStatus(val phase: Phase, val details: String = "")
    val statuses = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    private fun status(id: String, phase: Phase, details: String = "") {
        statuses.update { it + (id to SyncStatus(phase, details)) }
    }
    private fun notifyFailure(context: Context, details: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, details, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun spotifyId(context: Context, localId: String): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(localId, null)

    fun onCreated(context: Context, localId: String) {
        val app = context.applicationContext
        if (spotifyId(app, localId) != null) {
            status(localId, Phase.SYNCED)
            return
        }
        status(localId, Phase.SYNCING)
        work.launch {
            writeLock.withLock {
                if (spotifyId(app, localId) != null) {
                    status(localId, Phase.SYNCED)
                    return@withLock
                }
                if (!SpotifyTokenProvider.ensureToken(app)) {
                    status(localId, Phase.FAILED, "Vuelve a iniciar sesión en Spotify y reintenta")
                    notifyFailure(app, "Spotify no está conectado. Reintenta desde la playlist.")
                    return@withLock
                }
                val local = LocalPlaylistPref.getLocalPlaylist(app, localId) ?: return@withLock
                val result = Spotify.createPlaylist(local.name, public = false)
                val remote = result.getOrNull()
                if (remote == null || remote.id.isBlank()) {
                    val reason = result.exceptionOrNull()?.message ?: "Respuesta sin identificador"
                    Log.e(TAG, "Spotify creation failed for '${local.name}': $reason", result.exceptionOrNull())
                    status(localId, Phase.FAILED, reason)
                    notifyFailure(app, "No se creó en Spotify: $reason")
                    return@withLock
                }
                // Persist successful remote creation before uploading its tracks,
                // so retries never accidentally create duplicate remote playlists.
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(localId, remote.id).commit()
                val ids = LocalPlaylistPref.getLocalPlaylist(app, localId)?.songs.orEmpty()
                    .mapNotNull(::spotifyUri)
                var uploadError: String? = null
                ids.chunked(100).forEach { group ->
                    Spotify.addTracksToPlaylist(remote.id, group).onFailure {
                        Log.e(TAG, "Tracks were not uploaded to ${remote.id}", it)
                        uploadError = it.message ?: "Error al subir canciones"
                    }
                }
                Api.HomeCache.library = null
                if (uploadError != null) {
                    status(localId, Phase.FAILED, "Lista creada; canciones pendientes: $uploadError")
                    notifyFailure(app, "Lista creada en Spotify, pero algunas canciones no se copiaron")
                } else {
                    status(localId, Phase.SYNCED)
                }
            }
        }
    }

    fun retry(context: Context, localId: String) {
        val remote = spotifyId(context, localId)
        if (remote == null) {
            onCreated(context, localId)
        } else {
            val app = context.applicationContext
            status(localId, Phase.SYNCING)
            work.launch {
                writeLock.withLock {
                    if (!SpotifyTokenProvider.ensureToken(app)) {
                        status(localId, Phase.FAILED, "La sesión de Spotify ha caducado")
                        return@withLock
                    }
                    var offset = 0
                    val present = mutableSetOf<String>()
                    while (true) {
                        val page = Spotify.playlistTracks(remote, limit = 100, offset = offset).getOrNull() ?: break
                        page.items.mapNotNullTo(present) { it.track?.uri }
                        offset += page.items.size
                        if (page.items.isEmpty() || offset >= page.total) break
                    }
                    val missing = LocalPlaylistPref.getLocalPlaylist(app, localId)?.songs.orEmpty()
                        .mapNotNull(::spotifyUri).distinct().filterNot { it in present }
                    var error: Throwable? = null
                    for (group in missing.chunked(100)) {
                        Spotify.addTracksToPlaylist(remote, group).onFailure { error = it }
                        if (error != null) break
                    }
                    if (error == null) status(localId, Phase.SYNCED)
                    else status(localId, Phase.FAILED, error?.message ?: "Error de Spotify")
                }
            }
        }
    }

    fun onSongChanged(context: Context, localId: String, song: SongsModel, added: Boolean) {
        val app = context.applicationContext
        work.launch {
            writeLock.withLock {
                val remoteId = spotifyId(app, localId) ?: return@withLock
                val uri = spotifyUri(song) ?: return@withLock
                if (!SpotifyTokenProvider.ensureToken(app)) return@withLock
                // Read the server state to avoid duplicates when a track arrived during creation.
                var present = false
                var offset = 0
                if (added) {
                    while (true) {
                        val page = Spotify.playlistTracks(remoteId, limit = 100, offset = offset).getOrNull() ?: break
                        if (page.items.any { it.track?.uri == uri || it.track?.id == uri.substringAfterLast(':') }) {
                            present = true
                            break
                        }
                        offset += page.items.size
                        if (page.items.isEmpty() || offset >= page.total) break
                    }
                }
                if (added && !present) {
                    Spotify.addTracksToPlaylist(remoteId, listOf(uri)).onFailure { Log.w(TAG, "Add failed", it) }
                } else if (!added) {
                    SpotifySync.removeTrackFromPlaylist(app, remoteId, uri.substringAfterLast(':'))
                }
                Api.HomeCache.invalidatePlaylist(remoteId)
            }
        }
    }

    fun onRenamed(context: Context, localId: String, name: String) {
        val app = context.applicationContext
        work.launch {
            writeLock.withLock {
                val remote = spotifyId(app, localId) ?: return@withLock
                if (!SpotifyTokenProvider.ensureToken(app)) return@withLock
                Spotify.renamePlaylist(remote, name).onFailure { Log.w(TAG, "Rename failed", it) }
                Api.HomeCache.library = null
            }
        }
    }

    /** Spotify cannot sync YouTube-only/local audio: no invented track match is used. */
    private fun spotifyUri(song: SongsModel): String? {
        val id = SpotifySync.cleanTrackId(song.spotifyTrackId)
        return id.takeIf { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9]{22}")) }
            ?.let { "spotify:track:$it" }
    }
}
