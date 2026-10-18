package com.music.spotui.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(private val repository: AppRepository)  : ViewModel() {


    private val _albums : MutableStateFlow<Response<List<AlbumsModel>>> = MutableStateFlow(Response.Loading())
    val albums : StateFlow<Response<List<AlbumsModel>>> = _albums

    private val _artists : MutableStateFlow<Response<List<ArtistsModel>>> = MutableStateFlow(Response.Loading())
    val artists : StateFlow<Response<List<ArtistsModel>>> = _artists

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home

    private val _songs : MutableStateFlow<Response<List<com.music.spotui.data.entity.SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<com.music.spotui.data.entity.SongsModel>>> = _songs

    private val _podcastFeed = MutableStateFlow<Response<com.music.spotui.data.entity.PodcastFeedModel>>(Response.Loading())
    val podcastFeed: StateFlow<Response<com.music.spotui.data.entity.PodcastFeedModel>> = _podcastFeed

    private val _selectedPodcastCategory = MutableStateFlow<com.music.spotui.data.entity.PodcastCategoryModel?>(null)
    val selectedPodcastCategory: StateFlow<com.music.spotui.data.entity.PodcastCategoryModel?> = _selectedPodcastCategory

    private val _isPodcastCategoryLoading = MutableStateFlow(false)
    val isPodcastCategoryLoading: StateFlow<Boolean> = _isPodcastCategoryLoading

    private val _isPodcastsLoading = MutableStateFlow(false)
    val isPodcastsLoading: StateFlow<Boolean> = _isPodcastsLoading

    private val _audiobooks : MutableStateFlow<Response<com.music.spotui.data.entity.SearchResults>> = MutableStateFlow(Response.Loading())
    val audiobooks : StateFlow<Response<com.music.spotui.data.entity.SearchResults>> = _audiobooks

    private val _followedArtists : MutableStateFlow<List<ArtistsModel>> = MutableStateFlow(emptyList())
    val followedArtists : StateFlow<List<ArtistsModel>> = _followedArtists

    private val _selectedFilter = MutableStateFlow("All")
    val selectedFilter: StateFlow<String> = _selectedFilter

    private val _isFollowingOnly = MutableStateFlow(false)
    val isFollowingOnly: StateFlow<Boolean> = _isFollowingOnly

    init {
        fetchHome()
        fetchArtists()
        fetchAlbums()
        fetchSongs()
        fetchFollowedArtists()
    }

    fun setSelectedFilter(filter: String) {
        if (_selectedFilter.value != filter) {
            _selectedFilter.value = filter
            _isFollowingOnly.value = false
            if (filter == "Podcasts") {
                ensurePodcastsLoaded()
            } else if (filter == "Audiobooks") {
                ensureAudiobooksLoaded()
            }
        }
    }

    fun ensurePodcastsLoaded() {
        if (_podcastFeed.value !is Response.Success && !_isPodcastsLoading.value) {
            fetchPodcasts()
        }
    }

    fun ensureAudiobooksLoaded() {
        if (_audiobooks.value !is Response.Success) {
            fetchAudiobooks()
        }
    }

    fun toggleFollowing() {
        _isFollowingOnly.value = !_isFollowingOnly.value
    }

    fun resetFilters() {
        _selectedFilter.value = "All"
        _isFollowingOnly.value = false
    }

    private fun fetchHome() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideHomeFeed().collect { feed ->
            _home.value = feed
        }
    }

    private fun fetchAlbums() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAlbums().collect{ album ->
            _albums.value = album as Response<List<AlbumsModel>>
        }
    }

    private fun fetchArtists() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideArtists().collect { artist ->
            _artists.value = artist as Response<List<ArtistsModel>>
        }
    }

    private fun fetchSongs() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideSongs().collect { songRes ->
            _songs.value = songRes
        }
    }

    private fun fetchPodcasts() = viewModelScope.launch(Dispatchers.IO) {
        _isPodcastsLoading.value = true
        try {
            repository.providePodcastFeedFlow().collect { feed ->
                _podcastFeed.value = Response.Success(feed)
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to fetch podcast feed", e)
            if (_podcastFeed.value !is Response.Success) {
                _podcastFeed.value = Response.Error(e.message ?: "Failed to load podcast feed")
            }
        } finally {
            _isPodcastsLoading.value = false
        }
    }

    private fun fetchAudiobooks() = viewModelScope.launch(Dispatchers.IO) {
        repository.searchEverything("audiobook").collect { abRes ->
            if (abRes is Response.Success && (abRes.data.shows.isNotEmpty() || abRes.data.episodes.isNotEmpty() || abRes.data.albums.isNotEmpty())) {
                _audiobooks.value = abRes
            } else if (abRes is Response.Error || (abRes is Response.Success && abRes.data.albums.isEmpty())) {
                repository.searchEverything("story").collect { fallbackRes ->
                    _audiobooks.value = fallbackRes
                }
            } else {
                _audiobooks.value = abRes
            }
        }
    }

    private fun fetchFollowedArtists() = viewModelScope.launch(Dispatchers.IO) {
        _followedArtists.value = repository.provideFollowedArtists()
    }

    suspend fun getAlbumSongs(albumName: String, artist: String = "") = repository.provideAlbumSongs(albumName, artist)
}