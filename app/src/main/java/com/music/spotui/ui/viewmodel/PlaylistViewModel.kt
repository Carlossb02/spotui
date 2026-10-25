package com.music.spotui.ui.viewmodel

import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId

    private val _songs: MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs: StateFlow<Response<List<SongsModel>>> = _songs

    private val _playlist: MutableStateFlow<Response<AlbumsModel>> = MutableStateFlow(Response.Loading())
    val playlist: StateFlow<Response<AlbumsModel>> = _playlist

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs, isManualEdit = true)

    fun addAllToQueue(songs: List<SongsModel>) {
        if (songs.isEmpty()) return
        val current = currentSongState.queue.value
        val currentId = currentSongState.songId.value
        val currentUrl = currentSongState.songUrl.value
        val idx = current.indexOfFirst { it.id == currentId || it.url == currentUrl }
        val insertAt = if (idx >= 0) idx + 1 else 0
        val incoming = songs.distinctBy { it.id }.filterNot {
            idx >= 0 && current[idx].id == it.id
        }
        val incomingIds = incoming.map { it.id }.toSet()
        // Move matching upcoming tracks to the front rather than duplicating IDs;
        // duplicates confuse the current-track index during transitions.
        val future = current.drop(insertAt).filterNot { it.id in incomingIds }
        currentSongState.updateQueue(
            current.take(insertAt) + incoming + future, isManualEdit = true
        )
    }

    fun startShuffled(songs: List<SongsModel>) = currentSongState.startShuffled(songs)

    /** Pause/resume global playback (the header play button stays visible while playing). */
    fun setPlaying(playing: Boolean) {
        if (playing) com.music.spotui.di.SongPlayer.play() else com.music.spotui.di.SongPlayer.pause()
        currentSongState.setPlaying(playing)
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex: Int = 0, album: String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    val likeState = currentSongState.likeState

    fun updateLikeState(likeState: Boolean) {
        currentSongState.updateLikeState(likeState)
    }

    private var playlistKey: String? = null

    fun loadPlaylist(playlistId: String) {
        if (playlistKey == playlistId) return
        reloadPlaylist(playlistId)
    }

    fun reloadPlaylist(playlistId: String) {
        val differentPlaylist = playlistKey != playlistId
        playlistKey = playlistId
        // Initial navigation may show a loader; refreshing the same collection must not
        // clear its content or discard its LazyListState/visible scroll anchor.
        if (differentPlaylist) {
            _playlist.value = Response.Loading()
            _songs.value = Response.Loading()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.providePlaylist(playlistId).collect { result ->
                if (result !is Response.Loading || _playlist.value !is Response.Success) {
                    _playlist.value = result
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.providePlaylistSongs(playlistId).collect { result ->
                if (result !is Response.Loading || _songs.value !is Response.Success) {
                    _songs.value = result
                }
            }
        }
    }
}
