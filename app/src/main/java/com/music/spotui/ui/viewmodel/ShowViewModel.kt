package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.SubscribedPodcastPref
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShowViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId

    private val _episodes: MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val episodes: StateFlow<Response<List<SongsModel>>> = _episodes

    private val _show: MutableStateFlow<PodcastModel?> = MutableStateFlow(null)
    val show: StateFlow<PodcastModel?> = _show

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed

    private val _visibleCount = MutableStateFlow(15)
    val visibleCount: StateFlow<Int> = _visibleCount

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex: Int = 0, album: String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    private var showKey: String? = null
    private var showJob: Job? = null
    private var episodeJob: Job? = null

    fun retryShow(context: Context, showId: String, showName: String = "") {
        showKey = null
        loadShow(context, showId, showName)
    }

    fun loadShow(context: Context, showId: String, showName: String = "") {
        _isSubscribed.value = SubscribedPodcastPref.isPodcastSubscribed(context, showId)
        if (showKey == showId) return
        showKey = showId
        showJob?.cancel()
        episodeJob?.cancel()
        _episodes.value = Response.Loading()
        _visibleCount.value = 15
        if (showName.isNotBlank() && _show.value == null) {
            _show.value = PodcastModel(id = showId, name = showName, publisher = "", coverUri = "")
        }
        showJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = repository.provideShow(showId, showName)
            if (loaded != null) {
                _show.value = loaded
                _isSubscribed.value = SubscribedPodcastPref.isPodcastSubscribed(context, loaded.id)
                // A user could have followed the provisional card before artwork
                // arrived. Repair that stored record as soon as details load.
                if (_isSubscribed.value && loaded.coverUri.isNotBlank()) {
                    val saved = SubscribedPodcastPref.getSubscribedPodcasts(context)
                        .firstOrNull { it.id == loaded.id }
                    if (saved != null && saved.coverUri.isBlank()) {
                        SubscribedPodcastPref.addSubscribedPodcast(context, loaded)
                    }
                }
            }
        }
        episodeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.provideShowEpisodes(showId, showName).collect { res ->
                    _episodes.value = res
                    // Keep pagination incremental: rendering every episode at once
                    // makes initial layout and image decoding needlessly expensive.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _episodes.value = Response.Error(e.message ?: "No se pudieron cargar los episodios")
            }
        }
    }

    fun toggleSubscription(context: Context) {
        val currentShow = _show.value ?: return
        val episodeCover = (_episodes.value as? Response.Success)?.data
            ?.firstOrNull { it.coverUri.isNotBlank() }?.coverUri.orEmpty()
        val completeShow = if (currentShow.coverUri.isBlank() && episodeCover.isNotBlank()) {
            currentShow.copy(coverUri = episodeCover)
        } else currentShow
        val newState = SubscribedPodcastPref.toggleSubscribedPodcast(context, completeShow)
        _isSubscribed.value = newState
        Api.HomeCache.library = null
    }

    fun loadMoreEpisodes() {
        _visibleCount.value += 15
    }
}
