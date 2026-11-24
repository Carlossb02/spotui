package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.Spotify
import com.music.spotui.data.preferences.LocalPlaylistPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Edits owned playlists with the Spotify session already in use by the library. */
internal object SpotifyPlaylistEditorApi {
    data class Details(val name: String, val description: String, val ownerId: String)

    suspend fun load(context: Context, id: String, localId: String?): Details =
        withContext(Dispatchers.IO) {
            val session = SpotifySession.spDc(context)
            val playlist = ownedPlaylist(context, id, session)
            val ownerId = checkNotNull(playlist.owner).id
            val name = linkedPlaylist(context, id, ownerId, localId)?.name ?: playlist.name
            val markers = localId?.let(::playlistLinkMarkers).orEmpty()
            val description = playlist.description.orEmpty()
            val editableDescription = if (markers.isEmpty()) description else description.lineSequence()
                .filterNot { it.trim() in markers }.joinToString("\n").trim()
            Details(name, editableDescription, ownerId)
        }

    suspend fun save(
        context: Context,
        id: String,
        ownerId: String,
        localId: String?,
        name: String?,
        description: String?,
    ) = withContext(Dispatchers.IO) {
        val session = SpotifySession.spDc(context)
        val remote = ownedPlaylist(context, id, session)
        check(remote.owner?.id == ownerId) { "La cuenta de Spotify ha cambiado." }
        val local = linkedPlaylist(context, id, ownerId, localId)
        val remoteName = name?.takeIf { local == null && it != remote.name }
        val marker = localId?.let(::playlistLinkMarkers)?.firstOrNull { current ->
            remote.description.orEmpty().lineSequence().any { it.trim() == current }
        }
        val remoteDescription = description?.let {
            if (marker == null) it else listOf(it, marker).filter(String::isNotBlank).joinToString("\n")
        }?.takeUnless { it == remote.description.orEmpty() }
        checkSession(context, session)
        when {
            remoteDescription != null -> Spotify.editPlaylistAttributes(id, remoteName, remoteDescription).getOrThrow()
            remoteName != null -> Spotify.renamePlaylist(id, remoteName).getOrThrow()
        }
        checkSession(context, session)
        if (localId != null && name != null) {
            // Use the existing mirror journal so a retry can confirm the name
            // without overwriting either an intervening edit or the song list.
            val latest = checkNotNull(linkedPlaylist(context, id, ownerId, localId))
            check(latest.name == local?.name || latest.name == name) { "El nombre ha cambiado. Vuelve a abrir el editor." }
            LocalPlaylistPref.saveSyncedPlaylist(context, latest, latest.copy(name = name))
            LocalSpotifyMirror.onRenamed(context, localId, name)
        }
    }

    private fun linkedPlaylist(context: Context, id: String, ownerId: String, localId: String?) =
        localId?.let {
            LocalPlaylistPref.getLocalPlaylist(context, it)?.takeIf { playlist ->
                playlist.syncedSpotifyId == id && (playlist.spotifyOwnerId.isBlank() || playlist.spotifyOwnerId == ownerId)
            } ?: error("El vínculo de la lista ha cambiado. Vuelve a abrir el editor.")
        }

    private suspend fun ownedPlaylist(context: Context, id: String, session: String):
        com.metrolist.spotify.models.SpotifyPlaylist {
        require(id.matches(Regex("[A-Za-z0-9]{22}")))
        check(session.isNotBlank() && SpotifyTokenProvider.ensureToken(context)) {
            "Vuelve a iniciar sesión en Spotify desde los ajustes de Spotidroid."
        }
        checkSession(context, session)
        val ownerId = Spotify.me().getOrThrow().id
        check(ownerId.isNotBlank()) { "Spotify no pudo confirmar tu cuenta." }
        val playlist = Spotify.playlist(id).getOrThrow()
        checkSession(context, session)
        check(!playlist.owner?.id.isNullOrBlank()) { "Spotify no pudo confirmar quién es el propietario de la lista." }
        check(playlist.owner?.id == ownerId) { "Solo el propietario puede editar esta lista en Spotify." }
        return playlist
    }

    private suspend fun checkSession(context: Context, session: String) {
        currentCoroutineContext().ensureActive()
        check(SpotifySession.spDc(context) == session) { "La cuenta de Spotify ha cambiado." }
    }

}
