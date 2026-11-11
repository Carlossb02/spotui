package com.music.spotui.ui.repository

import com.music.spotui.data.api.Api
import com.music.spotui.data.entity.SongsModel
import javax.inject.Inject

class AppRepository @Inject constructor(private val api : Api) {

    suspend fun provideAlbums() = api.getAlbums()

    suspend fun provideHomeFeed() = api.getHomeFeed()

    suspend fun provideArtists() = api.getArtists()

    suspend fun provideSongs() = api.getSongs()

    suspend fun searchSongs(query: String) = api.searchTracks(query)

    suspend fun searchEverything(query: String, includePodcasts: Boolean = true) = api.searchEverything(query, includePodcasts)

    suspend fun provideAlbumSongs(albumName: String, artist: String = "") = api.getAlbumSongs(albumName, artist)

    suspend fun provideArtistSongs(artistName: String) = api.getArtistSongs(artistName)

    suspend fun provideArtistOverview(artistName: String, artistId: String = "") = api.getArtistOverview(artistName, artistId)

    suspend fun providePlaylistSongs(playlistId: String) = api.getPlaylistSongs(playlistId)

    suspend fun providePlaylist(playlistId: String) = api.getPlaylist(playlistId)

    suspend fun provideShowEpisodes(showId: String, showName: String = "") = api.getShowEpisodes(showId, showName)

    suspend fun provideShow(showId: String, showName: String = "") = api.getShow(showId, showName)

    suspend fun providePodcastFeed(categoryParams: String? = null, categoryBrowseId: String? = null) =
        com.music.spotui.providers.PodcastProvider.getYouTubePodcastsFeed()

    fun providePodcastFeedFlow(forceRefresh: Boolean = false) =
        com.music.spotui.providers.PodcastProvider.getYouTubePodcastsFeedFlow(forceRefresh)

    fun cachedLibraryEntries() = api.cachedLibraryEntries()

    suspend fun provideLibrary() = api.getLibrary()

    suspend fun provideFollowedArtists() = api.getFollowedArtists()

    suspend fun provideCategoryPlaylists(genre: String) = api.getCategoryPlaylists(genre)

    suspend fun provideRecommendations(seedTrackIds: List<String>) = api.getRecommendations(seedTrackIds)

    fun cachedPlaylistDetail(id:String) = api.cachedPlaylistDetail(id)
    fun playlistSongsForDisplay(id:String,fallback:List<SongsModel>) = api.playlistSongsForDisplay(id,fallback)
    fun cachedLikedSongs() = api.cachedCollectionSongs("liked")
    fun cachedCollectionSongs(key: String) = api.cachedCollectionSongs(key)
    fun storedFollowedArtists() = api.storedFollowedArtists()
    val libraryContentRevision get() = api.contentRevision

    suspend fun provideLikedSongs() = api.getLikedSongs()

    suspend fun provideAccount() = api.getAccount()

    suspend fun provideCanvasUrl(trackId: String) = api.getCanvasUrl(trackId)
}