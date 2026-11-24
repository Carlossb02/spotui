package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylistContributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A playlist is writable only if ownership or collaboration is positively verified.
 * Being followed/saved in a user's library is NOT permission to edit.
 */
@Serializable
data class PlaylistAccessInfo(
    val canEdit: Boolean = false,
    val ownerName: String = "",
    val ownerImage: String? = null,
    val contributors: List<SpotifyPlaylistContributor> = emptyList(),
    val available: Boolean = false,
    val isOwner: Boolean = false,
    val coverUri: String = "",
    val description: String = "",
    val playlistName: String = "",
    val ownerId: String = "",
    val ownershipVerified: Boolean = false,
)

object PlaylistAccess {
    private fun key(session: String, playlistId: String): String {
        val account = java.security.MessageDigest.getInstance("SHA-256")
            .digest(session.toByteArray()).joinToString("") { "%02x".format(it) }
        return account + ":" + SpotifySync.cleanPlaylistId(playlistId)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("spotify_playlist_headers", Context.MODE_PRIVATE)

    internal fun cached(context: Context, playlistId: String): PlaylistAccessInfo? {
        val raw = prefs(context).getString(key(SpotifySession.spDc(context), playlistId), null) ?: return null
        return runCatching { Json.decodeFromString<PlaylistAccessInfo>(raw) }.getOrNull()
            ?.copy(canEdit = false, isOwner = false, ownershipVerified = false)
    }

    suspend fun load(context: Context, playlistId: String): PlaylistAccessInfo = withContext(Dispatchers.IO) {
        if (playlistId.startsWith("local_pl_")) return@withContext PlaylistAccessInfo(canEdit = true, available = true)
        val session = SpotifySession.spDc(context)
        val previous = cached(context, playlistId)
        val fallback = previous ?: PlaylistAccessInfo()
        if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return@withContext fallback
        val currentUser = Spotify.me().getOrNull()
        val currentId = currentUser?.id.orEmpty()
        val details = Spotify.playlist(SpotifySync.cleanPlaylistId(playlistId)).getOrNull()
            ?: return@withContext fallback
        if (SpotifySession.spDc(context) != session) return@withContext PlaylistAccessInfo()
        if (details.id.isBlank() || details.name.isBlank()) return@withContext fallback
        val owner = details.owner
        val knownOwner = previous?.takeIf { owner?.id?.isNotBlank() == true && it.ownerId == owner.id }
        val members = details.contributors
        val ownerProfile = owner?.id?.takeIf { id ->
            id.isNotBlank() && (owner.imageUrl.isNullOrBlank() || owner.displayName.isNullOrBlank() || owner.displayName == id)
        }
            ?.let { id -> if (id == currentId) currentUser else Spotify.user(id).getOrNull() }
        val cachedMembers = previous?.contributors.orEmpty().associateBy { it.id }
        val picturedMembers = members.filter { it.id != owner?.id }.take(6).map { contributor ->
            val profile = if (contributor.imageUrl.isNullOrBlank() || contributor.displayName == contributor.id)
                (if (contributor.id == currentId) currentUser else Spotify.user(contributor.id).getOrNull())
            else null
            contributor.copy(
                displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: contributor.displayName,
                imageUrl = contributor.imageUrl?.takeIf { it.isNotBlank() } ?: profile?.images?.firstOrNull()?.url
                    ?: cachedMembers[contributor.id]?.imageUrl,
            )
        }
        val isOwner = currentId.isNotBlank() && owner?.id == currentId
        val fresh = PlaylistAccessInfo(
            canEdit = isOwner || (currentId.isNotBlank() && members.any { it.id == currentId }),
            ownerName = owner?.displayName?.takeIf { it.isNotBlank() && it != owner?.id }
                ?: ownerProfile?.displayName ?: knownOwner?.ownerName.orEmpty(),
            ownerImage = owner?.imageUrl?.takeIf { it.isNotBlank() } ?: ownerProfile?.images?.firstOrNull()?.url ?: knownOwner?.ownerImage,
            contributors = picturedMembers,
            available = true,
            isOwner = isOwner,
            coverUri = details.images.firstOrNull()?.url.orEmpty(),
            description = details.description.orEmpty(),
            playlistName = details.name,
            ownerId = owner?.id.orEmpty(),
            ownershipVerified = currentId.isNotBlank() && !owner?.id.isNullOrBlank(),
        )
        if (SpotifySession.spDc(context) != session) return@withContext PlaylistAccessInfo()
        prefs(context).edit().putString(key(session, playlistId),
            Json.encodeToString(fresh.copy(canEdit = false, isOwner = false, ownershipVerified = false))).apply()
        fresh
    }
}
