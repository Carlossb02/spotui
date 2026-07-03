package com.music.spotui.di

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.music.spotui.data.entity.SongsModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentSongState @Inject constructor() {
    private val _title: MutableState<String> = mutableStateOf("")
    val title: State<String> get() = _title

    private val _album: MutableState<String> = mutableStateOf("")
    val album : State<String> get() = _album

    private val _singer: MutableState<String> = mutableStateOf("")
    val singer: State<String> get() = _singer

    private val _coverUri: MutableState<String> = mutableStateOf("")
    val coverUri: State<String> get() = _coverUri

    private val _playingState: MutableState<Boolean> = mutableStateOf(false)
    val playingState: State<Boolean> get() = _playingState

    private val _songIndex: MutableState<Int> = mutableStateOf(0)
    val songIndex : State<Int> get() = _songIndex

    private val _songId: MutableState<Int> = mutableStateOf(0)
    val songId : State<Int> get() = _songId

    // The actual list the user is playing (album tracks, search results, liked
    // songs…). Next/previous operate on THIS, not on a re-derived global feed.
    private val _queue: MutableState<List<SongsModel>> = mutableStateOf(emptyList())
    val queue: State<List<SongsModel>> get() = _queue

    fun updateQueue(songs: List<SongsModel>) {
        _queue.value = songs
        // Seed the lossless resolver: map each track's play query → its Spotify id so
        // SongPlayer can resolve a FLAC stream from a play site that only has the query.
        SongPlayer.registerLossless(songs.map { it.url to it.spotifyTrackId })
        // Seed the lyrics resolver with track ids so it can use Spotify's own
        // color-lyrics endpoint (exact synced lyrics) instead of LRCLIB matching.
        com.music.spotui.data.api.LyricsApi.registerTracks(songs)
    }

    val shuffle = mutableStateOf(false)
    val repeat = mutableStateOf(false)
    val likeState = mutableStateOf(false)
    fun updateShuffleState(newShuffleState: Boolean) {
        shuffle.value = newShuffleState
    }
    fun updateRepeatState(newRepeatState : Boolean){
        repeat.value = newRepeatState
    }

    /** Sync the play/pause state without touching the rest of the now-playing
     *  metadata — used to reflect the web player's real state (e.g. after the
     *  system notification's pause button) back into the in-app UI. */
    fun updatePlayingState(playing: Boolean) {
        _playingState.value = playing
    }

    fun updateLikeState(newLikeState : Boolean){
        likeState.value = newLikeState
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int, album : String) {
        _coverUri.value = coverUri
        _title.value = title
        _album.value = album
        _singer.value = singer
        _playingState.value = playingState
        _songIndex.value = songIndex
        _songId.value = songId
        // Feed the system media notification (MediaSession) with the current track.
        SongPlayer.setNowPlayingMeta(title, singer, coverUri)
        // Persist the current track so a fresh launch can restore the session.
        if (playingState && title.isNotBlank()) {
            _queue.value.firstOrNull { it.id == songId }?.let { track ->
                com.music.spotui.data.preferences.saveLastPlayback(
                    com.music.spotui.MyApplication.instance, track)
            }
        }
        // Warm the lyrics cache in the background so they're already loaded by the
        // time the user opens the player / scrolls to the lyrics card.
        if (playingState && title.isNotBlank()) {
            com.music.spotui.data.api.LyricsApi.prefetch(title, singer, album)
            // Log the play into the local listening history (History & stats screen).
            com.music.spotui.data.preferences.addListeningHistory(
                com.music.spotui.MyApplication.instance,
                com.music.spotui.data.preferences.HistoryEntry(
                    ts = System.currentTimeMillis(),
                    songId = songId,
                    title = title,
                    singer = singer,
                    album = album,
                    image = coverUri,
                ),
            )
        }
    }
}