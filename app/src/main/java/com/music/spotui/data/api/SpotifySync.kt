package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fire-and-forget mirroring of local library actions to the user's REAL Spotify
 * account (web-player token): liking a song, saving an album or following an
 * artist here shows up in Spotify itself. Failures are logged, never surfaced —
 * the local action already succeeded.
 */
object SpotifySync {

    private const val TAG = "SpotifySync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cleanId(id: String, prefix: String): String =
        id.removePrefix(prefix)
            .removePrefix(prefix.removeSuffix(":"))
            .substringBefore("?")
            .substringBefore("|")
            .trim()

    fun cleanTrackId(id: String): String =
        cleanId(id, "spotify:track:")

    fun cleanPlaylistId(id: String): String =
        cleanId(id, "spotify:playlist:")

    fun setTrackSaved(context: Context, trackId: String, saved: Boolean) {
        val clean = cleanTrackId(trackId)
        setSaved(context, clean, "spotify:track:$clean", saved)
    }

    fun setAlbumSaved(context: Context, albumId: String, saved: Boolean) {
        val clean = cleanId(albumId, "spotify:album:")
        setSaved(context, clean, "spotify:album:$clean", saved)
    }

    fun setArtistFollowed(context: Context, artistId: String, followed: Boolean) {
        val clean = cleanId(artistId, "spotify:artist:")
        setSaved(context, clean, "spotify:artist:$clean", followed)
    }

    private fun setSaved(context: Context, id: String, uri: String, saved: Boolean) {
        if (id.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) {
                Log.w(TAG, "no token — skipped syncing $uri saved=$saved")
                return@launch
            }
            val result =
                if (saved) Spotify.addToLibrary(listOf(uri))
                else Spotify.removeFromLibrary(listOf(uri))
            result.fold(
                onSuccess = { Log.d(TAG, "synced $uri saved=$saved") },
                onFailure = { Log.w(TAG, "failed syncing $uri saved=$saved", it) },
            )
        }
    }

    /** Adds a track to one of the user's playlists on Spotify itself. */
    fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        val cleanPl = cleanPlaylistId(playlistId)
        val cleanTr = cleanTrackId(trackId)
        if (cleanPl.isBlank() || cleanTr.isBlank()) { onDone(false); return }
        val app = context.applicationContext
        scope.launch {
            val ok = SpotifyTokenProvider.ensureToken(app) &&
                Spotify.addTracksToPlaylist(cleanPl, listOf("spotify:track:$cleanTr")).isSuccess
            if (ok) {
                membershipCache[cleanPl]?.add(cleanTr)
                Api.HomeCache.invalidatePlaylist(cleanPl)
            }
            if (!ok) Log.w(TAG, "failed adding track $cleanTr to playlist $cleanPl")
            onDone(ok)
        }
    }

    /** Removes every occurrence of a track from one of the user's playlists on
     *  Spotify itself (resolves the playlist-scoped item uids first). */
    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        val cleanPl = cleanPlaylistId(playlistId)
        val cleanTr = cleanTrackId(trackId)
        if (cleanPl.isBlank() || cleanTr.isBlank()) { onDone(false); return }
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { onDone(false); return@launch }
            val uri = "spotify:track:$cleanTr"
            val refs = Spotify.playlistTracks(cleanPl, limit = 100).getOrNull()
                ?.items.orEmpty()
                .filter { pt ->
                    val tid = pt.track?.id?.let { cleanTrackId(it) }.orEmpty()
                    tid == cleanTr || pt.track?.uri == uri
                }
                .mapNotNull { pt -> pt.uid?.let { Spotify.PlaylistItemRef(uri = uri, uid = it) } }
            val ok = refs.isNotEmpty() &&
                Spotify.removeTracksFromPlaylist(cleanPl, refs).isSuccess
            if (ok) {
                membershipCache[cleanPl]?.remove(cleanTr)
                Api.HomeCache.invalidatePlaylist(cleanPl)
            }
            if (!ok) Log.w(TAG, "failed removing track $cleanTr from playlist $cleanPl")
            onDone(ok)
        }
    }

    /** Creates a new playlist on Spotify and adds the track to it. */
    fun createPlaylistWithTrack(context: Context, name: String, trackId: String, public: Boolean = false, onDone: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { onDone(false); return }
        val cleanTr = cleanTrackId(trackId)
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { onDone(false); return@launch }
            val playlist = Spotify.createPlaylist(name, public).getOrNull()
            if (playlist == null || playlist.id.isBlank()) {
                Log.w(TAG, "createPlaylist '$name' failed"); onDone(false); return@launch
            }
            val ok = cleanTr.isBlank() ||
                Spotify.addTracksToPlaylist(playlist.id, listOf("spotify:track:$cleanTr")).isSuccess
            if (ok && cleanTr.isNotBlank()) {
                membershipCache[playlist.id] = mutableSetOf(cleanTr)
            }
            onDone(ok)
        }
    }

    // ── Playlist membership (which playlists contain a track) ──
    // Cached per playlist for the app session so the "Saved in" sheet can show
    // check marks without re-fetching on every open.
    private val membershipCache = ConcurrentHashMap<String, MutableSet<String>>()

    /** Track ids contained in [playlistId] (first call fetches, then cached). */
    suspend fun playlistTrackIds(context: Context, playlistId: String): Set<String> {
        val cleanPl = cleanPlaylistId(playlistId)
        if (cleanPl.isBlank()) return emptySet()
        membershipCache[cleanPl]?.let { return it }
        if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return emptySet()
        val ids = Spotify.playlistTracks(cleanPl, limit = 100).getOrNull()
            ?.items.orEmpty()
            .mapNotNull { pt -> pt.track?.id?.let { cleanTrackId(it) }?.takeIf { it.isNotBlank() } }
            .toMutableSet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(ids) }
        membershipCache[cleanPl] = set
        return set
    }
}
