package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.Spotify
import com.music.spotui.data.preferences.addLikedAlbumId
import com.music.spotui.data.preferences.removeLikedAlbumId
import com.music.spotui.data.preferences.isAlbumLiked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Account state uses Spotify album IDs; the old numeric ID remains a local alias. */
object AlbumLibrary {
    private val gate = kotlinx.coroutines.sync.Mutex()

    suspend fun read(context: Context, spotifyId: String, localId: Int): Boolean? =
        gate.withLock { readUnlocked(context, spotifyId, localId) }

    suspend fun setSaved(context: Context, spotifyId: String, localId: Int, saved: Boolean): Boolean =
        gate.withLock { setSavedUnlocked(context, spotifyId, localId, saved) }

    fun cached(context: Context, spotifyId: String, localId: Int): Boolean {
        val prefs = context.getSharedPreferences("AlbumLibraryState", Context.MODE_PRIVATE)
        return if (spotifyId.isNotBlank() && prefs.contains(spotifyId)) prefs.getBoolean(spotifyId, false)
            else isAlbumLiked(context, localId.toString())
    }

    private fun remember(context: Context, spotifyId: String, localId: Int, saved: Boolean) {
        context.getSharedPreferences("AlbumLibraryState", Context.MODE_PRIVATE).edit()
            .putBoolean(spotifyId, saved).apply()
        if (saved) addLikedAlbumId(context, localId.toString()) else removeLikedAlbumId(context, localId.toString())
    }

    /** null means unavailable, never 'not saved' following a failed/partial scan. */
    private suspend fun readUnlocked(context: Context, spotifyId: String, localId: Int): Boolean? = withContext(Dispatchers.IO) {
        if (!spotifyId.matches(Regex("[A-Za-z0-9]{22}"))) return@withContext null
        try {
            if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return@withContext null
            var offset = 0
            while (true) {
                val page = Spotify.myAlbums(limit = 50, offset = offset).getOrThrow()
                if (page.items.any { it.id == spotifyId }) {
                    remember(context, spotifyId, localId, true)
                    return@withContext true
                }
                val next = page.offset + page.limit.coerceAtLeast(1)
                check(next > offset) { "Album pagination did not advance" }
                offset = next
                if (offset >= page.total) break
            }
            remember(context, spotifyId, localId, false)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun setSavedUnlocked(context: Context, spotifyId: String, localId: Int, saved: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!spotifyId.matches(Regex("[A-Za-z0-9]{22}"))) return@withContext false
        try {
            if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return@withContext false
            val uri = listOf("spotify:album:$spotifyId")
            val result = if (saved) Spotify.addToLibrary(uri) else Spotify.removeFromLibrary(uri)
            if (result.isFailure) return@withContext false
            remember(context, spotifyId, localId, saved)
            Api.HomeCache.library = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
