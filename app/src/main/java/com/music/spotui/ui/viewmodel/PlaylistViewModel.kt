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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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

    fun updateQueue(songs: List<SongsModel>, startSongId: Int? = null, sourceName: String? = null) =
        currentSongState.updateQueue(songs, startSongId = startSongId, sourceName = sourceName)

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

    fun startShuffled(songs: List<SongsModel>, sourceName: String? = null) = currentSongState.startShuffled(songs, sourceName)

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

    @Volatile private var playlistKey: String? = null
    private var metadataJob:Job?=null
    private var songsJob:Job?=null
    @Volatile private var rawSongs:List<SongsModel> = emptyList()
    @Volatile private var loadGeneration=0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(repository.libraryContentRevision,com.music.spotui.sync.OwnLibraryChanges.revision,
                com.music.spotui.data.api.SavedTrackLibrary.snapshot) {_,_,_->Unit}.collectLatest {
                val id=playlistKey?:return@collectLatest
                val generation=loadGeneration
                val updated=repository.playlistSongsForDisplay(id,rawSongs)
                val detail=repository.cachedPlaylistDetail(id)
                if(playlistKey==id && loadGeneration==generation) {
                    if(_songs.value is Response.Success || updated.isNotEmpty())_songs.value=Response.Success(updated)
                    if(detail!=null)_playlist.value=Response.Success(detail)
                }
            }
        }
    }

    fun loadPlaylist(playlistId: String) {
        if (playlistKey == playlistId) return
        com.music.spotui.sync.GoogleDeviceSync.libraryOpened()
        reloadPlaylist(playlistId)
    }

    fun reloadPlaylist(playlistId: String) {
        val different=playlistKey!=playlistId
        playlistKey=playlistId
        val generation=++loadGeneration
        metadataJob?.cancel();songsJob?.cancel()
        if(different) {
            rawSongs=emptyList()
            _playlist.value=repository.cachedPlaylistDetail(playlistId)?.let {Response.Success(it)}?:Response.Loading()
            val initial=repository.cachedCollectionSongs("playlist:${com.music.spotui.data.api.Api.HomeCache.cleanId(playlistId)}")
                ?:repository.playlistSongsForDisplay(playlistId,emptyList()).takeIf {it.isNotEmpty()}
            _songs.value=initial?.let {rawSongs=it;Response.Success(it)}?:Response.Loading()
        } else if(_songs.value is Response.Success) {
            _songs.value=Response.Success(repository.playlistSongsForDisplay(playlistId,rawSongs))
        }
        metadataJob=viewModelScope.launch(Dispatchers.IO) {
            repository.providePlaylist(playlistId).collect {result->
                if(generation==loadGeneration && playlistKey==playlistId && (result !is Response.Loading || _playlist.value !is Response.Success))_playlist.value=result
            }
        }
        songsJob=viewModelScope.launch(Dispatchers.IO) {
            repository.providePlaylistSongs(playlistId).collect {result->
                if(generation!=loadGeneration || playlistKey!=playlistId)return@collect
                if(result is Response.Success) {
                    rawSongs=result.data.orEmpty()
                    _songs.value=Response.Success(repository.playlistSongsForDisplay(playlistId,rawSongs))
                } else if(_songs.value !is Response.Success)_songs.value=result
            }
        }
    }
}
