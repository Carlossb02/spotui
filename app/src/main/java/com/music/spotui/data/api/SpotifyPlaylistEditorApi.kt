package com.music.spotui.data.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.metrolist.spotify.Spotify
import com.music.spotui.data.preferences.LocalPlaylistPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Edits owned playlists with the Spotify session already in use by the library. */
internal object SpotifyPlaylistEditorApi {
    data class Details(val name: String, val description: String)

    suspend fun load(context: Context, id: String, ownerId: String, localId: String?): Details =
        withContext(Dispatchers.IO) {
            val session = SpotifySession.spDc(context)
            val playlist = ownedPlaylist(context, id, ownerId, session)
            val name = linkedPlaylist(context, id, ownerId, localId)?.name ?: playlist.name
            val marker = localId?.let { "Spotui-ID: $it" }
            val description = playlist.description.orEmpty()
            Details(name, if (marker == null) description else description.lineSequence()
                .filterNot { it.trim() == marker }.joinToString("\n").trim())
        }

    suspend fun save(
        context: Context,
        id: String,
        ownerId: String,
        localId: String?,
        name: String?,
        description: String?,
        cover: Uri?,
    ) = withContext(Dispatchers.IO) {
        val coverData = cover?.let { encodeCover(context, it) }
        val session = SpotifySession.spDc(context)
        val remote = ownedPlaylist(context, id, ownerId, session)
        val local = linkedPlaylist(context, id, ownerId, localId)
        val remoteName = name?.takeIf { local == null && it != remote.name }
        val marker = localId?.let { "Spotui-ID: $it" }
            ?.takeIf { current -> remote.description.orEmpty().lineSequence().any { it.trim() == current } }
        val remoteDescription = description?.let {
            if (marker == null) it else listOf(it, marker).filter(String::isNotBlank).joinToString("\n")
        }?.takeUnless { it == remote.description.orEmpty() }
        checkSession(context, session)
        if (coverData != null) {
            Spotify.uploadPlaylistCover(id, coverData).getOrThrow()
            checkSession(context, session)
        }
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

    private suspend fun ownedPlaylist(context: Context, id: String, ownerId: String, session: String):
        com.metrolist.spotify.models.SpotifyPlaylist {
        require(id.matches(Regex("[A-Za-z0-9]{22}")) && ownerId.isNotBlank())
        check(session.isNotBlank() && SpotifyTokenProvider.ensureToken(context)) {
            "Vuelve a iniciar sesión en Spotify desde los ajustes de Spotui."
        }
        checkSession(context, session)
        check(Spotify.me().getOrThrow().id == ownerId) { "Solo el propietario puede editar esta lista." }
        val playlist = Spotify.playlist(id).getOrThrow()
        checkSession(context, session)
        check(playlist.owner?.id == ownerId) { "Solo el propietario puede editar esta lista." }
        return playlist
    }

    private suspend fun checkSession(context: Context, session: String) {
        currentCoroutineContext().ensureActive()
        check(SpotifySession.spDc(context) == session) { "La cuenta de Spotify ha cambiado." }
    }

    private fun encodeCover(context: Context, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "No se pudo leer la imagen." }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1_024) {
            options.inSampleSize *= 2
        }
        val cover = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("No se pudo abrir la portada.")
        try {
            for (quality in 90 downTo 30 step 10) {
                val bytes = ByteArrayOutputStream()
                if (cover.compress(Bitmap.CompressFormat.JPEG, quality, bytes) && bytes.size() <= 192_000) {
                    return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            }
            error("La imagen supera el tamaño que admite Spotify.")
        } finally {
            cover.recycle()
        }
    }
}
