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
class AlbumViewModel @Inject constructor(private val repository: AppRepository, private val currentSongState: CurrentSongState) :  ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState

    val currentSongId: State<Int> get() = currentSongState.songId

    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    private val _albums : MutableStateFlow<Response<List<AlbumsModel>>> = MutableStateFlow(Response.Loading())
    val albums : StateFlow<Response<List<AlbumsModel>>> = _albums
    private val _albumDetail = MutableStateFlow<AlbumsModel?>(null)
    val albumDetail: StateFlow<AlbumsModel?> = _albumDetail

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>, startSongId: Int? = null, sourceName: String? = null) =
        currentSongState.updateQueue(songs, startSongId = startSongId, sourceName = sourceName)

    fun startShuffled(songs: List<SongsModel>, sourceName: String? = null) = currentSongState.startShuffled(songs, sourceName)

    /** Pause/resume global playback (the header play button stays visible while playing). */
    fun setPlaying(playing: Boolean) {
        if (playing) com.music.spotui.di.SongPlayer.play() else com.music.spotui.di.SongPlayer.pause()
        currentSongState.setPlaying(playing)
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    val likeState = currentSongState.likeState

    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }
    init {
        fetchAlbums()
    }

    private var albumKey: String? = null
    private var albumLoadJob: kotlinx.coroutines.Job? = null

    /** Loads the tracks for a specific album (resolved via Spotify search). */
    fun loadAlbumSongs(name: String, artist: String = "") {
        val key = "$name|$artist"
        if (albumKey == key) return
        albumKey = key
        albumLoadJob?.cancel()
        _albumDetail.value = null
        _songs.value = repository.cachedCollectionSongs("album:$name|$artist")?.let { Response.Success(it) } ?: Response.Loading()
        albumLoadJob = viewModelScope.launch(Dispatchers.IO) {
            repository.provideAlbumSongs(name, artist).collect { songs ->
                if (albumKey != key) return@collect
                _albumDetail.value = com.music.spotui.data.api.Api.HomeCache.getAlbumDetail(name, artist)
                _songs.value = songs as Response<List<SongsModel>>
            }
        }
    }

    private fun fetchAlbums() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAlbums().collect{ album ->
            _albums.value = album as Response<List<AlbumsModel>>
        }
    }


}