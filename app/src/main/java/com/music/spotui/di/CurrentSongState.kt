package com.music.spotui.di

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.music.spotui.data.entity.SongsModel
import javax.inject.Inject
import javax.inject.Singleton

enum class RepeatMode {
    OFF,
    ONE,
    ALL
}

@Singleton
class CurrentSongState @Inject constructor() {
    private val _title: MutableState<String> = mutableStateOf("")
    val title: State<String> get() = _title

    private val _album: MutableState<String> = mutableStateOf("")
    val album : State<String> get() = _album

    private val _singer: MutableState<String> = mutableStateOf("")
    val singer: State<String> get() = _singer

    private val _artistIds: MutableState<String> = mutableStateOf("")
    val artistIds: State<String> get() = _artistIds

    private val _coverUri: MutableState<String> = mutableStateOf("")
    val coverUri: State<String> get() = _coverUri

    private val _playingState: MutableState<Boolean> = mutableStateOf(false)
    val playingState: State<Boolean> get() = _playingState

    // Monotonically increasing counter bumped on every user/UI toggle.
    // _lastPlayGen stores the gen at the *most recent play command* from the
    // UI (updateSongState(…, playingState=true, …)).  When ExoPlayer's
    // onIsPlayingChanged(true) fires later (after buffering), we compare:
    // if _playGen != _lastPlayGen the user has already toggled again (e.g.
    // tapped pause) and the callback is stale — drop it.
    private var _playGen = 0L
    private var _lastPlayGen = 0L

    private val _songIndex: MutableState<Int> = mutableStateOf(0)
    val songIndex : State<Int> get() = _songIndex

    private val _songId: MutableState<Int> = mutableStateOf(0)
    val songId : State<Int> get() = _songId

    private val _songUrl: MutableState<String> = mutableStateOf("")
    val songUrl: State<String> get() = _songUrl

    fun setSongUrl(url: String) { _songUrl.value = url }

    // The actual list the user is playing (album tracks, search results, liked
    // songs…). Next/previous operate on THIS, not on a re-derived global feed.
    private val _queue: MutableState<List<SongsModel>> = mutableStateOf(emptyList())
    val queue: State<List<SongsModel>> get() = _queue

    private val _sourceName = mutableStateOf(
        com.music.spotui.data.preferences.loadPlaybackSource(com.music.spotui.MyApplication.instance)
    )
    val sourceName: State<String> get() = _sourceName

    private val queueOrder = PlaybackQueueOrder()

    fun currentQueueIndex(): Int {
        val q = _queue.value
        val stored = _songIndex.value
        return stored.takeIf { q.getOrNull(it)?.id == _songId.value }
            ?: q.indexOfFirst { it.id == _songId.value }.takeIf { it >= 0 }
            ?: q.indexOfFirst { it.url == _songUrl.value }.takeIf { it >= 0 } ?: -1
    }

    private val playbackWrites = com.music.spotui.util.OrderedIoWrites()
    private fun persistQueue() {
        // Capture immutable order/cursor together. Encoding two large JSON arrays must not block a tap.
        com.music.spotui.sync.CloudPlayback.queueEdited()
        val queue = _queue.value
        val original = queueOrder.originalSongs.ifEmpty { queue }
        val manual = queueOrder.manualIndices
        val cursor = _songIndex.value
        val indices = queueOrder.originalIndices
        playbackWrites.submit {
            com.music.spotui.data.preferences.saveLastQueue(com.music.spotui.MyApplication.instance,
                queue, original, manual, cursor, indices)
        }
    }
    private fun persistCursor() {
        val cursor = _songIndex.value
        playbackWrites.submit { com.music.spotui.data.preferences.saveLastQueueCursor(com.music.spotui.MyApplication.instance, cursor) }
    }

    fun enqueue(songs: List<SongsModel>, next: Boolean = false) {
        queueOrder.enqueue(songs, currentQueueIndex(), next)
        _queue.value = queueOrder.songs
        updateQueue(queueOrder.songs, isManualEdit = true)
    }

    fun moveQueueItem(from: Int, to: Int) {
        val cur = currentQueueIndex()
        queueOrder.move(from, to)
        _songIndex.value = when {
            cur == from -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
        updateQueue(queueOrder.songs, isManualEdit = true)
    }

    fun removeQueueItem(at: Int) {
        val cur = currentQueueIndex()
        if (at !in _queue.value.indices || at == cur) return
        queueOrder.remove(at)
        if (at < cur) _songIndex.value = cur - 1
        updateQueue(queueOrder.songs, isManualEdit = true)
    }

    fun updateQueue(
        songs: List<SongsModel>, isManualEdit: Boolean = false,
        startSongId: Int? = null, sourceName: String? = null,
    ) {
        if (!isManualEdit) {
            _sourceName.value = sourceName ?: songs.firstOrNull()?.album.orEmpty()
        } else if (sourceName != null) {
            _sourceName.value = sourceName
        }
        com.music.spotui.data.preferences.savePlaybackSource(
            com.music.spotui.MyApplication.instance, _sourceName.value
        )
        if (!isManualEdit) {
            queueOrder.replace(songs, shuffle.value, startSongId)
        } else if (_queue.value.isEmpty()) {
            val app = com.music.spotui.MyApplication.instance
            val persisted = com.music.spotui.data.preferences.loadLastQueue(app)
            val restoring = songs.map { it.id to it.url } == persisted.map { it.id to it.url }
            queueOrder.replace(songs, false,
                restoredManual = if (restoring) com.music.spotui.data.preferences.loadManualQueueIndices(app) else emptySet(),
                restoredOriginal = if (restoring) com.music.spotui.data.preferences.loadOriginalQueue(app) else songs,
                restoredOriginalIndices = if (restoring) com.music.spotui.data.preferences.loadOriginalQueueIndices(app) else emptyList())
            _songIndex.value = com.music.spotui.data.preferences.loadLastQueueIndex(
                com.music.spotui.MyApplication.instance, songs,
                com.music.spotui.data.preferences.loadLastPlayback(com.music.spotui.MyApplication.instance)?.first?.id ?: _songId.value)
        } else queueOrder.reconcile(songs)
        _queue.value = queueOrder.songs
        // Seed the lossless resolver: map each track's play query → its Spotify id so
        // SongPlayer can resolve a FLAC stream from a play site that only has the query.
        SongPlayer.registerLossless(songs.map { it.url to it.spotifyTrackId })
        SongPlayer.registerAlternativeKeys(songs.map {
            it.url to com.music.spotui.data.preferences.alternativeStreamKey(it)
        })
        // Seed explicit flags so the YouTube fallback picks the matching edit.
        SongPlayer.registerExplicit(songs.map { it.url to it.explicit })
        // Seed durations so the YouTube match can reject same-title wrong-artist
        // songs (they almost always have a different length).
        SongPlayer.registerDuration(songs.mapNotNull { s -> if (s.durationMs > 0) s.url to s.durationMs else null })
        // Seed exact title/artist/album metadata so the YouTube fallback can score
        // candidates against the actual Spotify track instead of only the search
        // query string.
        SongPlayer.registerMetadata(songs.map {
            it.url to SongPlayer.TrackMatchMetadata(
                title = it.title,
                artist = it.singer,
                album = it.album,
            )
        })
        // Seed the lyrics resolver with track ids so it can use Spotify's own
        // color-lyrics endpoint (exact synced lyrics) instead of LRCLIB matching.
        com.music.spotui.data.api.LyricsApi.registerTracks(songs)
        // Persist the current queue so a fresh launch can restore it.
        persistQueue()
    }

    fun updateTrackInQueue(targetSong: SongsModel, newTitle: String, newArtist: String, newAlbum: String, newCoverUri: String) {
        val q = _queue.value
        val isMatch = { item: SongsModel ->
            (targetSong.id != 0 && targetSong.id != -1 && item.id == targetSong.id) ||
            (targetSong.url.isNotBlank() && item.url == targetSong.url) ||
            (targetSong.spotifyTrackId.isNotBlank() && item.spotifyTrackId == targetSong.spotifyTrackId)
        }
        val updatedQ = q.map { song ->
            if (isMatch(song)) {
                song.copy(
                    title = newTitle,
                    singer = newArtist,
                    album = newAlbum,
                    coverUri = newCoverUri
                )
            } else song
        }
        _queue.value = updatedQ
        if(targetSong.coverUri!=newCoverUri)com.music.spotui.ui.notification.PlaybackArtwork.refresh(
                com.music.spotui.MyApplication.instance,"song/${targetSong.id}",newCoverUri) {
                if(_songId.value==targetSong.id)SongPlayer.setNowPlayingMeta(_title.value,_singer.value,_coverUri.value)
            }
        val activeSong = updatedQ.getOrNull(currentQueueIndex())?.takeIf {it.id==_songId.value}
        if (activeSong != null && isMatch(activeSong)) {
            _title.value = newTitle
            _singer.value = newArtist
            _album.value = newAlbum
            _coverUri.value = newCoverUri
            SongPlayer.setNowPlayingMeta(newTitle, newArtist, newCoverUri)
        }
        updateQueue(updatedQ, isManualEdit = true)
        if (targetSong.url.isNotBlank()) {
            SongPlayer.registerMetadata(listOf(targetSong.url to SongPlayer.TrackMatchMetadata(newTitle, newArtist, newAlbum)))
            if(targetSong.title!=newTitle || targetSong.singer!=newArtist || targetSong.album!=newAlbum)
                SongPlayer.invalidateSongCacheByUrl(targetSong.url, com.music.spotui.MyApplication.instance,
                    reloadIfPlaying=false,clearAltStream=false,songTitle=newTitle,songId=targetSong.id)
        }
    }

    val shuffle = mutableStateOf(
        com.music.spotui.data.preferences.loadShuffleEnabled(com.music.spotui.MyApplication.instance)
    )
    val repeat = mutableStateOf(
        com.music.spotui.data.preferences.loadRepeatMode(com.music.spotui.MyApplication.instance)
    )
    val likeState = mutableStateOf(false)

    /** Shuffle changes the context order; manually queued requests keep their priority. */
    fun updateShuffleState(newShuffleState: Boolean) {
        if (newShuffleState == shuffle.value) return
        _songIndex.value = queueOrder.shuffle(newShuffleState, currentQueueIndex())
        _queue.value = queueOrder.songs
        shuffle.value = newShuffleState
        com.music.spotui.data.preferences.saveShuffleEnabled(com.music.spotui.MyApplication.instance, newShuffleState)
        persistQueue()
    }

    fun startShuffled(songs: List<SongsModel>, sourceName: String? = null): SongsModel? {
        if (songs.isEmpty()) return null
        shuffle.value = true
        com.music.spotui.data.preferences.saveShuffleEnabled(com.music.spotui.MyApplication.instance, true)
        updateQueue(songs, startSongId = songs.random().id, sourceName = sourceName)
        return _queue.value.firstOrNull()
    }
    fun updateRepeatState(newRepeatState : RepeatMode){
        repeat.value = newRepeatState
        com.music.spotui.sync.CloudPlayback.queueEdited()
        com.music.spotui.data.preferences.saveRepeatMode(com.music.spotui.MyApplication.instance, newRepeatState)
    }

    private val _isResolving: MutableState<Boolean> = mutableStateOf(false)
    val isResolving: State<Boolean> get() = _isResolving

    private val _resolveStatus: MutableState<String> = mutableStateOf("")
    val resolveStatus: State<String> get() = _resolveStatus

    private val _isBuffering: MutableState<Boolean> = mutableStateOf(false)
    val isBuffering: State<Boolean> get() = _isBuffering

    private val _resolveError: MutableState<String?> = mutableStateOf(null)
    val resolveError: State<String?> get() = _resolveError

    private val _resolveDetailNote: MutableState<String?> = mutableStateOf(null)
    val resolveDetailNote: State<String?> get() = _resolveDetailNote

    fun updateResolveError(error: String?) {
        _resolveError.value = error
    }

    fun updateResolveDetailNote(note: String?) {
        _resolveDetailNote.value = note
    }

    fun updateResolveState(isResolving: Boolean, status: String = "") {
        _isResolving.value = isResolving
        _resolveStatus.value = status
        if (isResolving) {
            _resolveError.value = null
        }
    }

    fun updateBufferingState(isBuffering: Boolean) {
        _isBuffering.value = isBuffering
    }

    /** Reconciles the in-app UI playingState directly with SongPlayer.isPlaying().
     *  Call this on UI screen entry (e.g. PlayerScreen launch) or state checks to
     *  guarantee that if audio is physically playing in the player engine, the UI
     *  play/pause button immediately shows Pause (is playing = true) and never
     *  gets stuck displaying Play (is playing = false). */
    fun syncWithPlayer() {
        com.music.spotui.sync.CloudPlayback.remotePlaying()?.let { _playingState.value=it;return }
        val realIsPlaying = SongPlayer.isResolvingOrPlaying()
        if (_playingState.value != realIsPlaying) {
            _playGen++
            if (realIsPlaying) _lastPlayGen = _playGen
            _playingState.value = realIsPlaying
        }
    }

    /** Sync the play/pause state without touching the rest of the now-playing
     *  metadata — used to reflect the engine's real state (e.g. ExoPlayer or
     *  web player callbacks, system notification actions, Bluetooth media keys).
     *
     *  CRITICAL FIX: If audio is actually playing in SongPlayer, or if playing is true,
     *  we MUST update _playingState.value = true and align _lastPlayGen = _playGen.
     *  Previously, dropping onIsPlayingChanged(true) when _playGen != _lastPlayGen left
     *  _lastPlayGen un-updated, permanently locking out all future background play
     *  events until a manual UI play tap. Syncing generation counters when audio plays
     *  prevents UI desynchronization permanently. */
    fun updatePlayingState(playing: Boolean) {
        com.music.spotui.sync.CloudPlayback.remotePlaying()?.let { _playingState.value=it;return }
        val realIsPlaying = SongPlayer.isResolvingOrPlaying()
        if (playing || realIsPlaying) {
            _playGen++
            _lastPlayGen = _playGen
            _playingState.value = true
        } else {
            if (!realIsPlaying) {
                _playingState.value = false
            }
        }
    }

    /** User-initiated play/pause toggle from album/liked/playlist screens.
     *  Bumps the generation counter and updates the UI playingState. */
    fun setPlaying(playing: Boolean) {
        _playGen++
        if (playing) _lastPlayGen = _playGen
        _playingState.value = playing
    }

    fun updateLikeState(newLikeState : Boolean){
        likeState.value = newLikeState
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int, album : String, artistIds: String = "") {
        val requestedIndex = songIndex.takeIf { _queue.value.getOrNull(it)?.id == songId }
            ?: _queue.value.indexOfFirst { it.id == songId }
        val actualIndex = queueOrder.select(requestedIndex, currentQueueIndex())
        val queueChanged = _queue.value != queueOrder.songs
        if (queueChanged) {
            _queue.value = queueOrder.songs
        }
        _coverUri.value = coverUri
        _title.value = title
        _album.value = _queue.value.firstOrNull { it.id == songId }?.album ?: album
        _singer.value = singer
        if (artistIds.isNotBlank()) {
            _artistIds.value = artistIds
        } else if (songId == _songId.value) {
            // Keep existing _artistIds.value
        } else {
            val queueSong = _queue.value.firstOrNull { it.id == songId }
            _artistIds.value = queueSong?.artistIds.orEmpty()
        }
        _playingState.value = playingState
        _playGen++
        if (playingState) _lastPlayGen = _playGen
        _songIndex.value = if (actualIndex >= 0) actualIndex else songIndex
        _songId.value = songId
        _queue.value.getOrNull(_songIndex.value)?.let { com.music.spotui.data.api.LibraryContentMemory.register(com.music.spotui.MyApplication.instance, it) }
        if (queueChanged) persistQueue() else persistCursor()
        // Feed the system media notification (MediaSession) with the current track.
        SongPlayer.setNowPlayingMeta(title, singer, coverUri)
        // Persist the current track so a fresh launch can restore the session.
        if (playingState && title.isNotBlank() && com.music.spotui.sync.CloudPlayback.remotePlaying()==null) {
            _queue.value.firstOrNull { it.id == songId }?.let { track ->
                com.music.spotui.data.preferences.saveLastPlayback(
                    com.music.spotui.MyApplication.instance, track)
            }
            // Prefetch the next track in the queue to make transitions seamless.
            val q = _queue.value
            if (q.isNotEmpty()) {
                val curIndex = _songIndex.value
                val nextIdx = if (curIndex + 1 < q.size) curIndex + 1 else if (repeat.value == RepeatMode.ALL) 0 else -1
                if (nextIdx in q.indices) {
                    SongPlayer.prefetch(q[nextIdx].url, com.music.spotui.MyApplication.instance)
                }
            }
        }
        // Warm the lyrics cache in the background so they're already loaded by the
        // time the user opens the player / scrolls to the lyrics card.
        if (playingState && title.isNotBlank() && com.music.spotui.sync.CloudPlayback.remotePlaying()==null) {
            _queue.value.firstOrNull { it.id == songId }?.let { track ->
                com.music.spotui.data.api.LyricsApi.prefetch(
                    track.title, track.singer, track.album, track.durationMs / 1000, track.spotifyTrackId
                )
            }

        }
    }

    internal fun devicePlaybackQueue() = com.music.spotui.sync.DevicePlaybackQueue(
        _queue.value, queueOrder.manualIndices, queueOrder.originalIndices)

    /** Remote clocks redraw metadata without resolving audio or recording a local listen. */
    internal fun applyDevicePlayback(queue:com.music.spotui.sync.DevicePlaybackQueue, frame:com.music.spotui.sync.DevicePlaybackState) {
        val song=queue.songs.getOrNull(frame.index)?:return
        if(_queue.value!=queue.songs || queueOrder.manualIndices!=queue.manual || queueOrder.originalIndices!=queue.original) {
            queueOrder.replace(queue.songs,false,restoredManual=queue.manual,restoredOriginalIndices=queue.original)
            _queue.value=queueOrder.songs
        }
        _songIndex.value=frame.index;_songId.value=song.id;_songUrl.value=song.url
        _title.value=song.title;_album.value=song.album;_singer.value=song.singer
        _artistIds.value=song.artistIds;_coverUri.value=song.coverUri;_playingState.value=frame.playing
        _sourceName.value=frame.source;shuffle.value=frame.shuffle
        repeat.value=runCatching {RepeatMode.valueOf(frame.repeat)}.getOrDefault(RepeatMode.OFF)
        _isResolving.value=false;_isBuffering.value=false;_resolveError.value=null
    }

    internal fun retainDevicePlayback(frame:com.music.spotui.sync.DevicePlaybackState) {
        val q=devicePlaybackQueue()
        val song=q.songs.getOrNull(frame.index)?:return
        playbackWrites.submit {
            val context=com.music.spotui.MyApplication.instance
            com.music.spotui.data.preferences.saveLastQueue(context,q.songs,q.original.mapNotNull(q.songs::getOrNull),q.manual,frame.index,q.original)
            com.music.spotui.data.preferences.saveLastPlayback(context,song)
            com.music.spotui.data.preferences.saveLastPosition(context,frame.position)
            com.music.spotui.data.preferences.saveShuffleEnabled(context,frame.shuffle)
            com.music.spotui.data.preferences.saveRepeatMode(context,runCatching {RepeatMode.valueOf(frame.repeat)}.getOrDefault(RepeatMode.OFF))
            com.music.spotui.data.preferences.savePlaybackSource(context,frame.source)
        }
    }

    init { com.music.spotui.sync.CloudPlayback.bind(this) }

}
