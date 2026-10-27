package com.music.spotui.data.api

import android.content.Context
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylistContributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A playlist is writable only if ownership or collaboration is positively verified.
 * Being followed/saved in a user's library is NOT permission to edit.
 */
data class PlaylistAccessInfo(
    val canEdit: Boolean = false,
    val ownerName: String = "",
    val ownerImage: String? = null,
    val contributors: List<SpotifyPlaylistContributor> = emptyList(),
    val available: Boolean = false,
)

object PlaylistAccess {
    suspend fun load(context: Context, playlistId: String): PlaylistAccessInfo = withContext(Dispatchers.IO) {
        if (playlistId.startsWith("local_pl_")) return@withContext PlaylistAccessInfo(canEdit = true, available = true)
        if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return@withContext PlaylistAccessInfo()
        val currentUser = Spotify.me().getOrNull()
        val currentId = currentUser?.id.orEmpty()
        val details = Spotify.playlist(SpotifySync.cleanPlaylistId(playlistId)).getOrNull()
            ?: return@withContext PlaylistAccessInfo()
        val owner = details.owner
        val members = details.contributors
        val ownerProfile = owner?.id?.takeIf { it.isNotBlank() && owner?.imageUrl.isNullOrBlank() }
            ?.let { id -> if (id == currentId) currentUser else Spotify.user(id).getOrNull() }
        val picturedMembers = members.filter { it.id != owner?.id }.take(6).map { contributor ->
            val profile = if (contributor.imageUrl.isNullOrBlank() || contributor.displayName == contributor.id)
                (if (contributor.id == currentId) currentUser else Spotify.user(contributor.id).getOrNull())
            else null
            contributor.copy(
                displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: contributor.displayName,
                imageUrl = contributor.imageUrl ?: profile?.images?.firstOrNull()?.url,
            )
        }
        PlaylistAccessInfo(
            canEdit = currentId.isNotBlank() && (owner?.id == currentId || members.any { it.id == currentId }),
            ownerName = owner?.displayName?.takeIf { it.isNotBlank() }
                ?: ownerProfile?.displayName.orEmpty(),
            ownerImage = owner?.imageUrl ?: ownerProfile?.images?.firstOrNull()?.url,
            contributors = picturedMembers,
            available = true,
        )
    }
}
