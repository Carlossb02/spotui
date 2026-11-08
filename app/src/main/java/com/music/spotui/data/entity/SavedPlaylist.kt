package com.music.spotui.data.entity

/** Membership comes only from local lists or positively verified editable Spotify lists. */
data class SavedPlaylist(
    val id: String,
    val name: String,
    val coverUri: String = "",
    val local: Boolean = false,
    val trackIds: Set<String> = emptySet(),
    val extraSongs: List<SongsModel> = emptyList(),
    val checked: Boolean = false,
    val failed: Boolean = false,
) {
    fun contains(song: SongsModel): Boolean {
        val trackId = cleanTrack(song.spotifyTrackId)
        if (trackId.isNotBlank() && trackId in trackIds) return true
        return extraSongs.any { other ->
            val otherId = cleanTrack(other.spotifyTrackId)
            when {
                trackId.isNotBlank() && otherId.isNotBlank() -> trackId == otherId
                song.url.isNotBlank() && other.url.isNotBlank() -> song.url == other.url
                else -> song.id != 0 && song.id != -1 && song.id == other.id
            }
        }
    }
    companion object {
        fun cleanTrack(id: String): String = id.removePrefix("spotify:track:")
            .removePrefix("track:").substringBefore('?').substringBefore('|').trim()
    }
}

data class SavedLibrarySnapshot(
    val playlists: List<SavedPlaylist> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    private val remoteTrackIds = playlists.flatMapTo(HashSet()) { it.trackIds }
    private val listsWithLocalTracks = playlists.filter { it.extraSongs.isNotEmpty() }
    fun contains(song: SongsModel): Boolean =
        SavedPlaylist.cleanTrack(song.spotifyTrackId).takeIf { it.isNotBlank() }?.let { it in remoteTrackIds } == true ||
            listsWithLocalTracks.any { it.contains(song) }
}
