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

    fun loadShow(context: Context, showId: String, showName: String = "") {
        _isSubscribed.value = SubscribedPodcastPref.isPodcastSubscribed(context, showId)
        if (showKey == showId) return
        showKey = showId
        _visibleCount.value = 30
        if (showName.isNotBlank() && _show.value == null) {
            _show.value = PodcastModel(id = showId, name = showName, publisher = "", coverUri = "")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = repository.provideShow(showId)
            if (loaded != null) {
                _show.value = loaded
                _isSubscribed.value = SubscribedPodcastPref.isPodcastSubscribed(context, loaded.id)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.provideShowEpisodes(showId, showName).collect { res ->
                _episodes.value = res
                if (res is Response.Success) {
                    val count = res.data.size
                    if (count > _visibleCount.value && _visibleCount.value <= 30) {
                        _visibleCount.value = count
                    }
                }
            }
        }
    }

    fun toggleSubscription(context: Context) {
        val currentShow = _show.value ?: return
        val newState = SubscribedPodcastPref.toggleSubscribedPodcast(context, currentShow)
        _isSubscribed.value = newState
        Api.HomeCache.library = null
    }

    fun loadMoreEpisodes() {
        _visibleCount.value += 15
    }
}
