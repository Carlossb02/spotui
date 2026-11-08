package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifyArtist
import com.metrolist.spotify.models.SpotifyTrack
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.metrolist.spotify.models.SpotifyHomeFeedItem
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.PlaylistSortOption
import com.music.spotui.data.preferences.getPlaylistSortOption
import com.music.spotui.data.preferences.isPlaylistSortDescending
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Spotify-backed replacement for the original Firebase Firestore data source.
 * Preserves the original `Flow<Response<List<...>>>` contract so the existing
 * ViewModels / UI keep working unchanged.
 *
 * Metadata (albums, artists, songs) comes from Spotify's web API; actual audio
 * is resolved from YouTube at playback time (see SongPlayer), so [SongsModel.url]
 * carries a stable playback key with the Spotify id plus the title/artist search
 * text, rather than a direct stream URL.
 */
class Api @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private fun stableId(key: String): Int = key.hashCode() and 0x7fffffff

    /**
     * Process-level cache for the Home feeds. The Home ViewModel is recreated on
     * every navigation back to Home and would otherwise re-hit the network each
     * time (slow). Once loaded we emit the cached result instantly, then refresh
     * in the background so the list stays fresh without blocking the UI.
     */
    companion object HomeCache {
        /** Sentinel id for the special pinned "Liked Songs" library entry. */
        const val LIKED_SONGS_ID = "liked-songs"
        /** Sentinel id for the special pinned "Downloaded" library entry. */
        const val DOWNLOADS_ID = "downloaded-offline"

        @Volatile var albums: List<AlbumsModel>? = null
        @Volatile var artists: List<ArtistsModel>? = null
        @Volatile var songs: List<SongsModel>? = null
        @Volatile var home: HomeFeedModel? = null
        @Volatile var library: List<com.music.spotui.data.entity.LibraryEntry>? = null
        @Volatile var libraryAccount: String? = null

        private val albumDetailCache = java.util.concurrent.ConcurrentHashMap<String, AlbumsModel>()
        private fun albumKey(name: String, artist: String) = "${name.trim().lowercase(java.util.Locale.ROOT)}|${artist.trim().lowercase(java.util.Locale.ROOT)}"
        fun getAlbumDetail(name: String, artist: String): AlbumsModel? = albumDetailCache[albumKey(name, artist)]
        fun setAlbumDetail(name: String, artist: String, model: AlbumsModel) { albumDetailCache[albumKey(name, artist)] = model }

        private val playlistSongsCache = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
        private val playlistDetailCache = java.util.concurrent.ConcurrentHashMap<String, AlbumsModel>()

        fun getPlaylistSongs(id: String): List<SongsModel>? = playlistSongsCache[cleanId(id)]
        fun setPlaylistSongs(id: String, songs: List<SongsModel>) {
            playlistSongsCache[cleanId(id)] = songs
        }
        fun getPlaylistDetail(id: String): AlbumsModel? = playlistDetailCache[cleanId(id)]
        fun setPlaylistDetail(id: String, album: AlbumsModel) {
            playlistDetailCache[cleanId(id)] = album
        }
        fun invalidatePlaylist(id: String) {
            val key = cleanId(id)
            playlistSongsCache.remove(key)
            playlistDetailCache.remove(key)
        }

        fun cleanId(id: String): String =
            id.removePrefix("spotify:playlist:")
              .removePrefix("spotify:album:")
              .removePrefix("playlist:")
              .removePrefix("album:")
              .trim()

        /** Drop all cached feeds (e.g. on logout / account switch). */
        fun clear() {
            albums = null; artists = null; songs = null; home = null; library = null
            playlistSongsCache.clear()
            playlistDetailCache.clear()
            albumDetailCache.clear()
        }
    }

    private fun SpotifyTrack.toSongModel(): SongsModel = try {
        val trackId = id.takeIf { it.isNotBlank() } ?: (uri?.takeIf { it.isNotBlank() } ?: "local_${name.hashCode()}")
        val trackName = name.takeIf { it.isNotBlank() } ?: "Unknown Track"
        val singer = artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val cover = album?.images?.firstOrNull()?.url ?: ""
        
        if (trackId.isNotBlank() && !trackId.startsWith("local_")) {
            isrc?.takeIf { it.isNotBlank() }?.let {
                com.music.spotui.di.SongPlayer.registerIsrc(trackId, it)
            }
        }
        
        val baseSong = SongsModel(
            id = stableId("track:$trackId"),
            title = trackName.take(128),
            album = album?.name ?: "",
            singer = singer,
            coverUri = cover,
            // Playback resolves the search text in this key against YouTube, but
            // keeps the Spotify id in the cache key so same-named tracks do not
            // reuse each other's stream.
            url = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(trackId, trackName, singer),
            spotifyTrackId = trackId,
            explicit = explicit,
            durationMs = durationMs,
            artistIds = artists.joinToString(",") { it.id.orEmpty() },
            isLocalSpotifyTrack = isLocal ||
                uri?.startsWith("spotify:local:", ignoreCase = true) == true ||
                trackId.startsWith("local_", ignoreCase = true) ||
                trackId.startsWith("spotify:local:", ignoreCase = true),
        )
        val overridden = com.music.spotui.data.preferences.CustomMetadataPref.applyOverride(context, baseSong)
        // Full SpotifyTrack is already authoritative: reuse its metadata during
        // playback instead of making the SAME Spotify request while YouTube
        // candidate queries are ready. Never mark edited metadata as verified.
        if (overridden.title == baseSong.title &&
            overridden.singer == baseSong.singer && overridden.album == baseSong.album &&
            !baseSong.isLocalSpotifyTrack && trackId.length == 22 && trackName.length <= 128) {
            com.music.spotui.di.SongPlayer.registerVerifiedSpotifySnapshot(
                baseSong.url, trackId, baseSong.title, baseSong.singer,
                baseSong.album, baseSong.durationMs, baseSong.explicit)
        }
        val playable = if (overridden.title != baseSong.title || overridden.singer != baseSong.singer) {
            overridden.copy(
                url = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(trackId, overridden.title, overridden.singer)
            )
        } else {
            overridden
        }
        LibraryContentMemory.register(context, playable)
        playable
    } catch (e: Exception) {
        Log.e("Api", "toSongModel failed for track: $name", e)
        SongsModel(
            id = stableId("track:local_${name.hashCode()}"),
            title = name.takeIf { it.isNotBlank() } ?: "Local Track",
            album = album?.name ?: "",
            singer = "Local File",
            coverUri = "",
            url = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery("local_${name.hashCode()}", name.takeIf { it.isNotBlank() } ?: "Local Track", "Local File"),
            spotifyTrackId = "local_${name.hashCode()}",
            explicit = false,
            durationMs = durationMs,
            artistIds = "",
        )
    }

    private fun SpotifyAlbum.toAlbumModel(): AlbumsModel = AlbumsModel(
        id = stableId("album:$id"),
        artists = artists.joinToString(", ") { it.name },
        coverUri = images.firstOrNull()?.url ?: "",
        name = name,
        time = releaseDate ?: "",
        type = albumType.orEmpty(),
        spotifyAlbumId = id,
    )

    private fun SpotifyArtist.toArtistModel(): ArtistsModel = ArtistsModel(
        name = name,
        coverUri = images.firstOrNull()?.url ?: "",
        id = id,
    )

    suspend fun getAlbums(): Flow<Response<List<AlbumsModel>>> = flow {
        HomeCache.albums?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.albums == null) emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            return@flow
        }
        Spotify.newReleases(limit = 20).fold(
            onSuccess = { resp ->
                val list = resp.albums?.items.orEmpty().map { it.toAlbumModel() }
                HomeCache.albums = list
                emit(Response.Success(list))
            },
            onFailure = {
                Log.e("Api", "getAlbums failed", it)
                if (HomeCache.albums == null) emit(Response.Error(it.message ?: "error"))
            },
        )
    }

    /** Artists the user follows on Spotify (library "Artists" filter). */
    fun storedFollowedArtists() = LibraryContentMemory.artists(context)
    suspend fun getFollowedArtists(): List<ArtistsModel> {
        val cached = storedFollowedArtists().orEmpty()
        if (!SpotifyTokenProvider.ensureToken(context)) return cached
        val session = SpotifySession.spDc(context)
        return try {
            val fresh = fetchAllPages(requireComplete = true) { offset -> Spotify.myArtists(limit = 50, offset = offset) }.map { it.toArtistModel() }
            LibraryContentMemory.putArtists(context, fresh, session)
            fresh
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) { Log.w("Api", "Followed artists refresh failed", failure); cached }
    }

    suspend fun getArtists(): Flow<Response<List<ArtistsModel>>> = flow {
        HomeCache.artists?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.artists == null) emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            return@flow
        }
        Spotify.topArtists(limit = 20).fold(
            onSuccess = { paging ->
                val list = paging.items.map { it.toArtistModel() }
                HomeCache.artists = list
                emit(Response.Success(list))
            },
            onFailure = {
                Log.e("Api", "getArtists failed", it)
                if (HomeCache.artists == null) emit(Response.Error(it.message ?: "error"))
            },
        )
    }

    suspend fun getSongs(): Flow<Response<List<SongsModel>>> = flow {
        HomeCache.songs?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.songs == null) emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            return@flow
        }
        Spotify.topTracks(limit = 50).fold(
            onSuccess = { paging ->
                val list = paging.items.map { it.toSongModel() }
                HomeCache.songs = list
                emit(Response.Success(list))
            },
            onFailure = {
                Log.e("Api", "getSongs failed", it)
                if (HomeCache.songs == null) emit(Response.Error(it.message ?: "error"))
            },
        )
    }

    /**
     * Personalized Spotify home feed (the `home` GQL operation) — the real
     * landing page: "Your top mixes", "Jump back in", "Your favorite artists",
     * etc. Process-cached like the other home feeds for instant re-entry.
     */
    suspend fun getHomeFeed(): Flow<Response<HomeFeedModel>> = flow {
        HomeCache.home?.let { emit(Response.Success(it)) } ?: emit(Response.Loading())
        if (!SpotifyTokenProvider.ensureToken(context)) {
            if (HomeCache.home == null) emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            return@flow
        }
        Spotify.home(sectionItemsLimit = 20).fold(
            onSuccess = { feed ->
                val sections = feed.sections.mapNotNull { section ->
                    // The GQL feed can repeat an item inside one section (and
                    // repeat whole sections, e.g. two "New releases" rows) —
                    // that showed up as duplicated album cards on Home.
                    val items = section.items
                        .distinctBy { it.uri }
                        .mapNotNull { it.toHomeItem() }
                        .distinctBy { item ->
                            when (item) {
                                is HomeItem.Playlist -> "playlist:" + (cleanId(item.id).ifBlank { item.name.trim().lowercase() })
                                is HomeItem.Album -> "album:" + item.name.trim().lowercase() + "|" + item.artists.trim().lowercase()
                                is HomeItem.Artist -> "artist:" + (cleanId(item.id).ifBlank { item.name.trim().lowercase() })
                            }
                        }
                    if (items.isEmpty()) null
                    else HomeSection(title = section.title ?: "", items = items)
                }.distinctBy { it.title.lowercase().ifBlank { it.hashCode().toString() } }
                val model = HomeFeedModel(greeting = feed.greeting ?: "", sections = sections)
                HomeCache.home = model
                emit(Response.Success(model))
            },
            onFailure = {
                Log.e("Api", "getHomeFeed failed", it)
                if (HomeCache.home == null) emit(Response.Error(it.message ?: "error"))
            },
        )
    }

    private fun SpotifyHomeFeedItem.toHomeItem(): HomeItem? = when (this) {
        is SpotifyHomeFeedItem.Album -> HomeItem.Album(
            name = name,
            imageUrl = imageUrl ?: "",
            subtitle = artists.joinToString(", ") { it.name }.ifBlank { "Album" },
            artists = artists.joinToString(", ") { it.name },
        )
        is SpotifyHomeFeedItem.Artist -> HomeItem.Artist(
            name = name,
            imageUrl = imageUrl ?: "",
            id = id,
        )
        is SpotifyHomeFeedItem.Playlist -> HomeItem.Playlist(
            name = name,
            imageUrl = imageUrl ?: "",
            subtitle = (madeForUsername ?: ownerName)?.let { "Playlist • $it" } ?: "Playlist",
            id = id,
        )
    }

    /**
     * Live track search via Spotify's GraphQL search (not the rate-limited
     * personalized endpoints). Blank query yields an empty list.
     */
    suspend fun searchTracks(query: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (query.isBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Error("Spotify not authenticated — set sp_dc cookie")); return@flow
        }
        Spotify.search(query, types = listOf("track"), limit = 30).fold(
            onSuccess = { res -> emit(Response.Success(res.tracks?.items.orEmpty().map { it.toSongModel() })) },
            onFailure = { Log.e("Api", "searchTracks failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * Combined search: tracks + albums + artists in a single GraphQL call
     * (searchDesktop, not rate-limited). Powers the Search screen so users can
     * find albums and artists, not just songs.
     */
    suspend fun searchEverything(query: String, includePodcasts: Boolean = true): Flow<Response<SearchResults>> = kotlinx.coroutines.flow.channelFlow {
        var currentResults = SearchResults()
        val resultLock = kotlinx.coroutines.sync.Mutex()
        send(Response.Loading(currentResults))

        if (query.isBlank()) {
            send(Response.Success(SearchResults()))
            return@channelFlow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            send(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            return@channelFlow
        }

        coroutineScope {
            val spotifyJob = launch {
                val spotifyRes = runCatching {
                    Spotify.search(query, types = listOf("track", "album", "artist", "playlist"), limit = 20).getOrNull()
                }.getOrNull()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (spotifyRes != null) {
                    resultLock.withLock {
                        currentResults = currentResults.copy(
                            songs = spotifyRes.tracks?.items.orEmpty().map { it.toSongModel() },
                            albums = spotifyRes.albums?.items.orEmpty().map { it.toAlbumModel() },
                            artists = spotifyRes.artists?.items.orEmpty().map { it.toArtistModel() },
                            playlists = spotifyRes.playlists?.items.orEmpty().filter { it.id.isNotBlank() }.map { p ->
                                com.music.spotui.data.entity.LibraryEntry(
                                    spotifyId = p.id,
                                    name = p.name,
                                    subtitle = "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""),
                                    coverUri = p.images.firstOrNull()?.url.orEmpty(),
                                    isPlaylist = true
                                )
                            }.distinctBy { it.spotifyId },
                        )
                        send(Response.Loading(currentResults))
                    }
                }
            }

            val podcastJob = if (includePodcasts) launch {
                try {
                    kotlinx.coroutines.delay(250L)
                    val (ytShows, ytEpisodes) = com.music.spotui.providers.PodcastProvider.searchPodcasts(query)
                    resultLock.withLock {
                        currentResults = currentResults.copy(
                            shows = (currentResults.shows + ytShows).distinctBy { it.id },
                            episodes = (currentResults.episodes + ytEpisodes).distinctBy { it.id },
                        )
                        send(Response.Loading(currentResults))
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.e("Api", "searchPodcasts failed for query: $query", e)
                }
            } else null

            spotifyJob.join()
            podcastJob?.join()
        }

        send(Response.Success(currentResults))
    }

    /** A podcast show's episodes, as playable [SongsModel]. Emits progressively as continuation pages load. */
    suspend fun getShowEpisodes(showId: String, showName: String = ""): Flow<Response<List<SongsModel>>> = flow {
        val session = SpotifySession.spDc(context)
        val key = "podcast:$showId"
        val cached = LibraryContentMemory.read(context, key)
        if (cached != null) emit(Response.Success(cached)) else emit(Response.Loading())
        var latest: List<SongsModel>? = null
        com.music.spotui.providers.PodcastProvider.getShowEpisodesFlow(showId, showName).collect { episodes ->
            if (episodes.isNotEmpty()) {
                latest = episodes
                if (cached == null) emit(Response.Success(episodes))
            }
        }
        latest?.let { episodes ->
            LibraryContentMemory.put(context, key, episodes, session)
            emit(Response.Success(episodes))
        } ?: run { if (cached == null) emit(Response.Error("No hay episodios disponibles para este programa. Comprueba la conexión y la sesión de Spotify.")) }
    }

    /** Show header (name/publisher/cover) for the detail screen. */
    suspend fun getShow(showId: String, showName: String = ""): PodcastModel? {
        return com.music.spotui.providers.PodcastProvider.getShow(showId, showName)
    }

    /**
     * Spotify's recommendation engine: given a few seed track ids (the tracks the
     * user is currently/recently playing), returns a list of recommended tracks to
     * extend the queue with — i.e. the "autoplay radio" that keeps music going once
     * a playlist/album ends. Up to 5 seeds are allowed by Spotify; we cap there.
     * Returns an empty list on failure so callers can simply fall back to looping.
     */
    suspend fun getRecommendations(seedTrackIds: List<String>): List<SongsModel> {
        val seeds = seedTrackIds.filter { it.isNotBlank() }.distinct()
        if (seeds.isEmpty()) return emptyList()
        if (!SpotifyTokenProvider.ensureToken(context)) return emptyList()
        val seedId = seeds.last()
        // Primary: the exact radio the Spotify web player queues after this track —
        // the inspiredby-mix station playlist. This IS Spotify's real queue, so the
        // continuation matches what open.spotify.com would play next.
        Spotify.trackRadio(seedId).getOrNull()
            ?.filter { it.id != seedId }
            ?.takeIf { it.isNotEmpty() }
            ?.let { radio -> return radio.map { it.toSongModel() } }
        Log.w("Api", "track radio empty — trying native recommender")
        // Fallback 1: Spotify's own recommender (the SEO "recommended tracks" GQL op).
        // Still Spotify's real backend, so it beats any local heuristic when available.
        Spotify.recommendedTracks(seedId).getOrNull()?.takeIf { it.isNotEmpty() }?.let { native ->
            return native.map { it.toSongModel() }
        }
        Log.w("Api", "native recommender empty — trying artist radio")
        val seedTrack = Spotify.track(seedId).getOrNull()
            ?: return emptyList<SongsModel>().also { Log.w("Api", "getRecommendations: seed track unresolved") }

        // Fallback 2: artist radio built purely from GQL endpoints (seed artist top
        // tracks + related artists' top tracks). Unlike the heuristic engine this does
        // NOT depend on me/top/tracks|artists, which are frequently HTTP 429 rate-limited
        // with the web token — so it keeps working when the profile-based engine can't.
        artistRadio(seedTrack).takeIf { it.isNotEmpty() }?.let { radio ->
            return radio.map { it.toSongModel() }
        }

        Log.w("Api", "artist radio empty — falling back to heuristic engine")
        // Fallback 3: profile-based heuristic engine (best-personalized but needs top data).
        return com.music.spotui.data.recommendation.SpotifyRecommendationEngine
            .getRecommendations(seedTrack, limit = 25)
            .map { it.toSongModel() }
            .ifEmpty { emptyList<SongsModel>().also { Log.w("Api", "getRecommendations returned no tracks") } }
    }

    /**
     * A reliable "song radio" from a seed track using only GQL endpoints (not the
     * rate-limited REST top-data ones): the seed artist's top tracks plus the top
     * tracks of related artists, deduped and shuffled. Mirrors how tapping a single
     * song on Spotify continues into similar music.
     */
    private suspend fun artistRadio(seedTrack: SpotifyTrack): List<SpotifyTrack> {
        val seedArtistId = seedTrack.artists.firstOrNull()?.id ?: return emptyList()
        val out = mutableListOf<SpotifyTrack>()
        val seen = mutableSetOf(seedTrack.id)
        fun add(tracks: List<SpotifyTrack>?, cap: Int) {
            tracks.orEmpty().asSequence()
                .filter { it.id.isNotEmpty() && seen.add(it.id) }
                .take(cap)
                .forEach { out.add(it) }
        }
        add(Spotify.artistTopTracks(seedArtistId).getOrNull()?.tracks, cap = 10)
        Spotify.artistRelatedArtists(seedArtistId).getOrNull().orEmpty().take(6).forEach { related ->
            related.id.takeIf { it.isNotEmpty() }?.let { rid ->
                add(Spotify.artistTopTracks(rid).getOrNull()?.tracks, cap = 5)
            }
        }
        return out.shuffled().take(30)
    }

    /**
     * Loads the curated playlists for a Browse category/genre. Real Spotify opens
     * a genre catalogue (a page of playlists) rather than running a keyword song
     * search — we approximate that by searching playlists for the genre name and
     * presenting them as a grid the user can open. Returns LibraryEntry rows so
     * the Category screen can reuse the existing playlist row/tile rendering.
     */
    suspend fun getCategoryPlaylists(genre: String): Flow<Response<List<com.music.spotui.data.entity.LibraryEntry>>> = flow {
        emit(Response.Loading())
        if (genre.isBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Error("Spotify not authenticated — set sp_dc cookie")); return@flow
        }
        Spotify.search(genre, types = listOf("playlist"), limit = 24).fold(
            onSuccess = { res ->
                val mapped = res.playlists?.items.orEmpty().map { p ->
                    com.music.spotui.data.entity.LibraryEntry(
                        spotifyId = p.id,
                        name = p.name,
                        subtitle = "Playlist" + (p.owner?.displayName?.let { " • $it" } ?: ""),
                        coverUri = p.images.firstOrNull()?.url ?: "",
                        isPlaylist = true,
                    )
                }.distinctBy { cleanId(it.spotifyId).ifBlank { it.name.trim().lowercase() } }
                emit(Response.Success(mapped))
            },
            onFailure = { Log.e("Api", "getCategoryPlaylists failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * Loads an artist's top tracks. The UI navigates by artist *name*, so we
     * resolve the artist via GraphQL search, then fetch its top tracks via the
     * GraphQL queryArtistOverview endpoint (neither is rate-limited).
     */
    suspend fun getArtistSongs(artistName: String): Flow<Response<List<SongsModel>>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Error("Spotify not authenticated — set sp_dc cookie")); return@flow
        }
        val artist = Spotify.search(artistName, types = listOf("artist"), limit = 1).getOrNull()
            ?.artists?.items?.firstOrNull()
        val artistId = artist?.id
        if (artistId.isNullOrBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }
        val artistCover = artist.images.firstOrNull()?.url ?: ""
        Spotify.artistTopTracks(artistId).fold(
            onSuccess = { resp ->
                emit(Response.Success(resp.tracks.map { track ->
                    val song = track.toSongModel()
                    // GQL top-tracks often omit album art — fall back to the artist image.
                    if (song.coverUri.isBlank()) song.copy(coverUri = artistCover) else song
                }))
            },
            onFailure = { Log.e("Api", "getArtistSongs failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * Loads the full Spotify-style artist page (header, monthly listeners, bio,
     * popular tracks with play counts, discography, related artists) in one GQL
     * round-trip. When the caller knows the exact Spotify artist id it is used
     * directly; otherwise the name is resolved via search (fuzzy — a query like
     * "RAM" may resolve to "Rammstein", so ids are strongly preferred).
     */
    suspend fun getArtistOverview(artistName: String, knownArtistId: String = ""): Flow<Response<ArtistOverviewModel>> = flow {
        emit(Response.Loading())
        if (artistName.isBlank() && knownArtistId.isBlank()) {
            emit(Response.Success(ArtistOverviewModel(name = artistName))); return@flow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Error("Spotify not authenticated — set sp_dc cookie")); return@flow
        }
        val knownId = if (knownArtistId.isNotBlank()) knownArtistId
                      else if (artistName.matches(Regex("^[a-zA-Z0-9]{22}$"))) artistName
                      else ""
        val artist = if (knownId.isBlank()) {
            // Prefer an EXACT name match among the top hits — the first fuzzy
            // hit for a short name like "RAM" can be a different artist entirely.
            val hits = Spotify.search(artistName, types = listOf("artist"), limit = 5).getOrNull()
                ?.artists?.items.orEmpty()
            hits.firstOrNull { it.name.equals(artistName, ignoreCase = true) } ?: hits.firstOrNull()
        } else null
        val artistId = knownId.ifBlank { artist?.id.orEmpty() }
        if (artistId.isBlank()) {
            emit(Response.Error("Artist not found")); return@flow
        }
        val searchCover = artist?.images?.firstOrNull()?.url ?: ""

        val playlistName = artistName.ifBlank { artist?.name.orEmpty() }
        val featuringPlaylist = try {
            var searchRes = Spotify.search("Featuring $playlistName", types = listOf("playlist"), limit = 10).getOrNull()
            var list = searchRes?.playlists?.items.orEmpty()
            var match = list.firstOrNull {
                it.name.equals("Featuring $playlistName", ignoreCase = true) ||
                it.name.equals("This Is $playlistName", ignoreCase = true)
            } ?: list.firstOrNull {
                it.name.contains("Featuring $playlistName", ignoreCase = true) ||
                it.name.contains("This Is $playlistName", ignoreCase = true)
            }
            if (match == null) {
                searchRes = Spotify.search("This Is $playlistName", types = listOf("playlist"), limit = 10).getOrNull()
                list = searchRes?.playlists?.items.orEmpty()
                match = list.firstOrNull {
                    it.name.equals("Featuring $playlistName", ignoreCase = true) ||
                    it.name.equals("This Is $playlistName", ignoreCase = true)
                } ?: list.firstOrNull {
                    it.name.contains("Featuring $playlistName", ignoreCase = true) ||
                    it.name.contains("This Is $playlistName", ignoreCase = true)
                } ?: list.firstOrNull()
            }
            match?.let {
                com.music.spotui.data.entity.LibraryEntry(
                    spotifyId = it.id,
                    name = it.name,
                    subtitle = "Playlist" + (it.owner?.displayName?.let { owner -> " • $owner" } ?: ""),
                    coverUri = it.images.firstOrNull()?.url ?: "",
                    isPlaylist = true,
                )
            }
        } catch (e: Exception) {
            null
        }

        Spotify.artistOverview(artistId).fold(
            onSuccess = { o ->
                val avatar = o.avatarImages.firstOrNull()?.url?.ifBlank { null } ?: searchCover
                val header = o.headerImages.firstOrNull()?.url?.ifBlank { null } ?: avatar
                emit(Response.Success(ArtistOverviewModel(
                    id = o.id,
                    name = o.name.ifBlank { artistName },
                    verified = o.verified,
                    monthlyListeners = o.monthlyListeners,
                    biography = o.biography,
                    headerImage = header,
                    avatarImage = avatar,
                    topTracks = o.topTracks.map { t ->
                        val song = t.track.toSongModel()
                        ArtistTrackUi(
                            song = if (song.coverUri.isBlank()) song.copy(coverUri = avatar) else song,
                            playcount = t.playcount,
                        )
                    },
                    popularReleases = o.popularReleases.map { it.toAlbumModel() },
                    appearsOn = o.appearsOn.map { it.toAlbumModel() },
                    relatedArtists = o.relatedArtists.map { it.toArtistModel() },
                    featuringPlaylist = featuringPlaylist,
                )))
            },
            onFailure = { Log.e("Api", "getArtistOverview failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * Loads the actual track list for an album. The UI navigates by album *name*
     * (the real Spotify id is lost during mapping), so we resolve the album via
     * search, then fetch its tracks. Uses GraphQL endpoints (not rate-limited).
     */
    suspend fun getAlbumSongs(albumName: String, artist: String = ""): Flow<Response<List<SongsModel>>> = flow {
        val session = SpotifySession.spDc(context)
        val key = "album:$albumName|$artist"
        val cached = LibraryContentMemory.read(context, key)?.let { libraryOverrides(it) }
        if (cached != null) emit(Response.Success(cached)) else emit(Response.Loading())
        if (albumName.isBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            val col = com.music.spotui.data.preferences.OfflineCollectionsPref.getCollection(context, "album:$albumName|$artist")
            if (col != null) {
                val downloadedSongs = col.songs.filter {
                    com.music.spotui.data.preferences.isDownloaded(context, it.id.toString())
                }
                emit(Response.Success(downloadedSongs))
            } else if (cached == null) {
                emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            }
            return@flow
        }
        // If albumName is a Spotify album ID (22-char base62), try fetching directly first.
        val directAlbum = if (albumName.matches(Regex("^[a-zA-Z0-9]{22}$"))) {
            Spotify.album(albumName).getOrNull()
        } else null

        if (directAlbum != null) {
            HomeCache.setAlbumDetail(albumName, artist, directAlbum.toAlbumModel())
            val songs = directAlbum.tracks?.items.orEmpty().map { it.toSongModel() }
            LibraryContentMemory.put(context, key, songs, session)
            emit(Response.Success(songs))
            return@flow
        }

        // Two albums can share a name (different artists). Search the name and,
        // when we know the artist, pick the candidate whose artists match instead
        // of blindly taking the first (most-popular) result.
        val candidates = Spotify.search(
            if (artist.isBlank()) albumName else "$albumName $artist",
            types = listOf("album"),
            limit = 10,
        ).getOrNull()?.albums?.items.orEmpty()
        val albumId = pickAlbum(candidates, albumName, artist)?.id
        if (albumId.isNullOrBlank()) {
            if (cached == null) emit(Response.Success(emptyList())); return@flow
        }
        Spotify.album(albumId).fold(
            onSuccess = { full ->
                HomeCache.setAlbumDetail(albumName, artist, full.toAlbumModel())
                val songs = full.tracks?.items.orEmpty().map { it.toSongModel() }
                LibraryContentMemory.put(context, key, songs, session)
                emit(Response.Success(songs))
            },
            onFailure = { Log.e("Api", "getAlbumSongs failed", it); if (cached == null) emit(Response.Error(it.message ?: "error")) },
        )
    }

    /**
     * Loads the real track list for a playlist by its Spotify id (daily mixes,
     * Discover Weekly, personalized playlists, etc). Uses the fetchPlaylist GQL
     * endpoint directly — keyed by id, so it returns the *actual* playlist
     * content rather than a best-effort name search.
     *
     * Supports Reverse Offset Fetching: when sorted by "Date added (newest to oldest)"
     * (the default), fetches the newest tracks first (offset = total - 100) so the user
     * can start listening immediately without UI scroll jumps, loading older chunks in the
     * background.
     */
    suspend fun getPlaylistSongs(playlistId: String): Flow<Response<List<SongsModel>>> = flow {
        val clean = cleanId(playlistId)
        if (playlistId.startsWith("local_pl_")) {
            val local = com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylist(context, playlistId)
            if (local != null) {
                HomeCache.setPlaylistSongs(clean, local.songs)
                emit(Response.Success(local.songs))
                return@flow
            }
        }
        val session = SpotifySession.spDc(context)
        val key = "playlist:$clean"
        val cached = (LibraryContentMemory.read(context, key)
            ?: HomeCache.getPlaylistSongs(clean).takeIf { HomeCache.libraryAccount == session })?.let { libraryOverrides(it) }
        if (cached != null) {
            emit(Response.Success(cached))
        } else {
            emit(Response.Loading())
        }

        if (playlistId.isBlank()) {
            emit(Response.Success(emptyList())); return@flow
        }

        if (!SpotifyTokenProvider.ensureToken(context)) {
            val col = com.music.spotui.data.preferences.OfflineCollectionsPref.getCollection(context, clean)
            if (col != null) {
                val downloadedSongs = col.songs.filter {
                    com.music.spotui.data.preferences.isDownloaded(context, it.id.toString())
                }
                emit(Response.Success(downloadedSongs))
            } else if (cached == null) {
                emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            }
            return@flow
        }

        val sortOption = getPlaylistSortOption(context, clean)
        val isDesc = isPlaylistSortDescending(context, clean)
        val isReverse = (sortOption == PlaylistSortOption.DATE && isDesc)

        val probeResult = Spotify.playlistTracks(clean, limit = if (isReverse) 1 else 100, offset = 0)
        if (probeResult.isFailure) {
            val error = probeResult.exceptionOrNull()
            Log.e("Api", "getPlaylistSongs probe failed", error)
            if (cached == null) {
                emit(Response.Error(error?.message ?: "error"))
            }
            return@flow
        }

        val probe = probeResult.getOrThrow()
        val total = probe.total

        if (total == 0) {
            LibraryContentMemory.put(context, key, emptyList(), session)
            HomeCache.setPlaylistSongs(clean, emptyList())
            emit(Response.Success(emptyList()))
            return@flow
        }


        if (!isReverse) {
            val songs = probe.items.mapNotNull { it.track?.toSongModel() }.toMutableList()
            if (cached == null) emit(Response.Success(songs.toList()))
            var offset = probe.offset + probe.limit.coerceAtLeast(1)
            while (offset < total) {
                val page = Spotify.playlistTracks(clean, limit = 100, offset = offset).getOrNull() ?: return@flow
                songs += page.items.mapNotNull { it.track?.toSongModel() }
                val next = page.offset + page.limit.coerceAtLeast(1)
                if (next <= offset) return@flow
                offset = next
                if (cached == null) emit(Response.Success(songs.toList()))
            }
            LibraryContentMemory.put(context, key, songs, session)
            HomeCache.setPlaylistSongs(clean, songs.toList())
            emit(Response.Success(songs.toList()))
        } else {
            if (total <= 100) {
                val pageResult = Spotify.playlistTracks(clean, limit = 100, offset = 0)
                if (pageResult.isSuccess) {
                    val songs = pageResult.getOrThrow().items.mapNotNull { it.track?.toSongModel() }
                    LibraryContentMemory.put(context, key, songs, session)
                    HomeCache.setPlaylistSongs(clean, songs)
                    emit(Response.Success(songs))
                } else if (cached == null) {
                    emit(Response.Error(pageResult.exceptionOrNull()?.message ?: "error"))
                }
            } else {
                val songs = mutableListOf<SongsModel>()
                var offset = ((total - 1) / 100) * 100

                while (offset >= 0) {
                    val pageResult = Spotify.playlistTracks(clean, limit = 100, offset = offset)
                    val page = pageResult.getOrNull() ?: return@flow

                    val pageSongs = page.items.mapNotNull { it.track?.toSongModel() }
                    songs.addAll(0, pageSongs)
                    if (cached == null) emit(Response.Success(songs.toList()))
                    offset -= 100
                }
                LibraryContentMemory.put(context, key, songs, session)
                HomeCache.setPlaylistSongs(clean, songs.toList())
                emit(Response.Success(songs.toList()))
            }
        }
    }

    /**
     * Follows a paged endpoint until every item is fetched, so libraries with
     * more than one page (50+ playlists/albums) load completely.
     */
    private suspend fun <T> fetchAllPages(
        requireComplete: Boolean = false,
        fetch: suspend (offset: Int) -> kotlin.Result<com.metrolist.spotify.models.SpotifyPaging<T>>,
    ): List<T> {
        if (requireComplete) {
            val result = mutableListOf<T>()
            var offset = 0
            do {
                val page = fetch(offset).getOrThrow()
                result += page.items
                val next = page.offset + page.limit.coerceAtLeast(1)
                check(next > offset) { "Library pagination did not advance" }
                offset = next
            } while (offset < page.total)
            return result
        }
        val first = fetch(0).getOrNull() ?: return emptyList()
        val items = first.items.toMutableList()
        var offset = first.items.size
        while (offset < first.total && first.items.isNotEmpty()) {
            val page = fetch(offset).getOrNull() ?: break
            if (page.items.isEmpty()) break
            items += page.items
            offset += page.items.size
        }
        return items
    }


    private fun deduplicateLibraryEntries(entries: List<com.music.spotui.data.entity.LibraryEntry>): List<com.music.spotui.data.entity.LibraryEntry> {
        return entries.distinctBy { entry ->
            val cid = cleanId(entry.spotifyId)
            val typeKey = when {
                entry.isPodcast -> "podcast"
                entry.isPlaylist -> "playlist"
                else -> "album"
            }
            if (cid == LIKED_SONGS_ID || cid == DOWNLOADS_ID) {
                cid
            } else if (cid.isNotBlank()) {
                "$typeKey:$cid"
            } else {
                "$typeKey:${entry.name.trim().lowercase()}"
            }
        }
    }

    /**
     * "Your Library" — the user's actual saved Spotify albums plus their
     * playlists (followed + created), merged into one list. Uses the libraryV3
     * GQL endpoint (not rate-limited). Process-cached for instant re-entry.
     */
    fun cachedLibraryEntries(): List<com.music.spotui.data.entity.LibraryEntry>? {
        val session = SpotifySession.spDc(context)
        return if (HomeCache.libraryAccount == session) HomeCache.library ?: LibraryCatalogCache.peek(context)
            else LibraryCatalogCache.peek(context)
    }

    private fun liveLibrary(remote: List<com.music.spotui.data.entity.LibraryEntry>): List<com.music.spotui.data.entity.LibraryEntry> {
        val localEntries = com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylists(context).map { pl ->
            com.music.spotui.data.entity.LibraryEntry(
                spotifyId = pl.id,
                name = pl.name,
                subtitle = "Lista local • ${pl.songs.size} " + (if (pl.songs.size == 1) "canción" else "canciones"),
                coverUri = pl.coverUri,
                isPlaylist = true,
                isLocal = true,
            )
        }
        val favoriteEntries = com.music.spotui.data.preferences.FavoritePlaylistsPref.entries(context)
        val podcastEntries = com.music.spotui.data.preferences.SubscribedPodcastPref.getSubscribedPodcasts(context).map { pod ->
            com.music.spotui.data.entity.LibraryEntry(
                spotifyId = pod.id,
                name = pod.name,
                subtitle = "Podcast" + (if (pod.publisher.isNotBlank()) " • ${pod.publisher}" else ""),
                coverUri = com.music.spotui.ui.notification.PodcastCover.preferredShowUri(
                    context, pod.id, pod.coverUri
                ),
                isPlaylist = false,
                artists = pod.publisher,
                isPodcast = true,
            )
        }
        val shortcuts = listOf(
            com.music.spotui.data.entity.LibraryEntry(LIKED_SONGS_ID, "Liked Songs", "Playlist • Liked songs", "https://misc.scdn.co/liked-songs/liked-songs-640.png", true),
            com.music.spotui.data.entity.LibraryEntry(DOWNLOADS_ID, "Downloaded", "Available offline", "", true),
        )
        val preferred = shortcuts + localEntries + favoriteEntries + podcastEntries + remote
        val offline = com.music.spotui.data.preferences.OfflineCollectionsPref.getOfflineCollections(context).filter { col ->
            val id = cleanId(col.id)
            preferred.none { entry ->
                (id.isNotBlank() && id == cleanId(entry.spotifyId)) ||
                    (entry.isPlaylist == col.isPlaylist && entry.name.equals(col.name, ignoreCase = true) &&
                        (entry.isPlaylist || entry.artists.equals(col.artists, ignoreCase = true)))
            }
        }.map { col ->
            com.music.spotui.data.entity.LibraryEntry(col.id, col.name,
                if (col.isPlaylist) "Playlist • Available offline" else "Album • ${col.artists} • Available offline",
                col.coverUri, col.isPlaylist, col.artists)
        }
        return deduplicateLibraryEntries(preferred + offline)
    }

    suspend fun getLibrary(): Flow<Response<List<com.music.spotui.data.entity.LibraryEntry>>> = flow {
        val session = SpotifySession.spDc(context)
        if (HomeCache.libraryAccount != session) { HomeCache.library = null; HomeCache.libraryAccount = session }
        val seed = HomeCache.library?.filterNot { it.isLocal || it.isPodcast || it.spotifyId == LIKED_SONGS_ID || it.spotifyId == DOWNLOADS_ID }
            ?: LibraryCatalogCache.read(context)
        val initial = liveLibrary(seed)
        HomeCache.library = initial
        emit(Response.Success(initial))
        if (!SpotifyTokenProvider.ensureToken(context)) return@flow
        try {
            val remote = coroutineScope {
                val albums = async(kotlinx.coroutines.Dispatchers.IO) {
                    fetchAllPages(requireComplete = true) { offset -> Spotify.myAlbums(limit = 50, offset = offset) }.map { a ->
                        com.music.spotui.data.entity.LibraryEntry(a.id, a.name, "Album • " + a.artists.joinToString(", ") { it.name },
                            a.images.firstOrNull()?.url.orEmpty(), false, a.artists.joinToString(", ") { it.name })
                    }
                }
                val playlists = async(kotlinx.coroutines.Dispatchers.IO) {
                    LibraryCatalogCache.playlistEntries(SpotifySync.allLibraryPlaylists(context))
                }
                playlists.await() + albums.await()
            }
            if (SpotifySession.spDc(context) != session) return@flow
            LibraryCatalogCache.replace(context, session, remote)
            val fresh = liveLibrary(remote)
            HomeCache.library = fresh
            emit(Response.Success(fresh))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("SpotuiLibrary", "Background library refresh failed; retaining cached library", failure)
        }
    }

    /**
     * The looping Spotify Canvas video URL for a track (or null). Process-cached
     * per track id — including negative results — so the player only fetches once.
     */
    suspend fun getCanvasUrl(trackId: String): String? {
        if (trackId.isBlank()) return null
        CanvasCache.map[trackId]?.let { return it.value }
        if (!SpotifyTokenProvider.ensureToken(context)) return null
        val url = runCatching { Spotify.canvasUrl(trackId) }.getOrNull()
        CanvasCache.map[trackId] = CanvasCache.Entry(url)
        return url
    }

    private object CanvasCache {
        class Entry(val value: String?)
        val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    }

    fun cachedCollectionSongs(key: String): List<SongsModel>? = LibraryContentMemory.peek(context, key)?.let { libraryOverrides(it) }
    val contentRevision get() = LibraryContentMemory.revision
    private fun libraryOverrides(songs: List<SongsModel>): List<SongsModel> =
        com.music.spotui.data.preferences.CustomMetadataPref.applyOverrides(context, songs)

    /** The user's Spotify "Liked Songs" (saved tracks) as playable songs. */
    suspend fun getLikedSongs(): Flow<Response<List<SongsModel>>> = flow {
        val session = SpotifySession.spDc(context)
        val cached = LibraryContentMemory.read(context, "liked")?.let { libraryOverrides(it) }
        if (cached != null) emit(Response.Success(cached)) else emit(Response.Loading())
        if (!SpotifyTokenProvider.ensureToken(context)) {
            if (cached == null) emit(Response.Success(com.music.spotui.data.preferences.getDownloadedSongs(context).filter {
                com.music.spotui.data.preferences.isSongLiked(context, it.id.toString())
            }))
            return@flow
        }
        try {
            val models = mutableListOf<SongsModel>()
            var offset = 0
            do {
                val page = Spotify.likedSongs(limit = 50, offset = offset).getOrThrow()
                val additions = page.items.map { it.track.toSongModel() }
                models += additions
                if (cached == null) {
                    // First load is progressive. Later visits retain the complete snapshot during refresh.
                    val editor = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE).edit()
                    additions.forEach { editor.putString(it.id.toString(), it.id.toString()) }
                    editor.apply()
                    emit(Response.Success(models.toList()))
                }
                val next = page.offset + page.limit.coerceAtLeast(1)
                check(next > offset) { "Liked songs pagination did not advance" }
                offset = next
            } while (offset < page.total)
            if (SpotifySession.spDc(context) != session) return@flow
            val fresh = LibraryContentMemory.mergeLiked(context, models, session)
            emit(Response.Success(libraryOverrides(fresh)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("Api", "Liked songs refresh failed; retaining library", failure)
            if (cached == null) emit(Response.Error(failure.message ?: "error"))
        }
    }

    /** The logged-in user's account (name, email, avatar, plan) for settings. */
    suspend fun getAccount(): Flow<Response<com.music.spotui.data.entity.AccountModel>> = flow {
        emit(Response.Loading())
        if (!SpotifyTokenProvider.ensureToken(context)) {
            emit(Response.Error("Spotify not authenticated")); return@flow
        }
        Spotify.me().fold(
            onSuccess = { u ->
                emit(Response.Success(com.music.spotui.data.entity.AccountModel(
                    name = u.displayName ?: u.id,
                    email = u.email ?: "",
                    imageUrl = u.images.firstOrNull()?.url ?: "",
                    plan = u.product?.replaceFirstChar { it.uppercase() } ?: "",
                )))
            },
            onFailure = { Log.e("Api", "getAccount failed", it); emit(Response.Error(it.message ?: "error")) },
        )
    }

    /** Loads playlist metadata (name, cover, owner, track count) by id. */
    suspend fun getPlaylist(playlistId: String): Flow<Response<AlbumsModel>> = flow {
        val clean = cleanId(playlistId)
        val cached = HomeCache.getPlaylistDetail(clean)
        if (cached != null) {
            emit(Response.Success(cached))
        } else {
            emit(Response.Loading())
        }
        if (playlistId.isBlank()) {
            emit(Response.Error("missing playlist id")); return@flow
        }
        if (playlistId.startsWith("local_pl_")) {
            val local = com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylist(context, playlistId)
            if (local != null) {
                val model = AlbumsModel(
                    id = stableId("playlist:${local.id}"),
                    artists = "Local Playlist",
                    coverUri = local.coverUri,
                    name = local.name,
                    time = "${local.songs.size} song" + (if (local.songs.size == 1) "" else "s"),
                )
                HomeCache.setPlaylistDetail(clean, model)
                emit(Response.Success(model))
                return@flow
            }
        }
        if (!SpotifyTokenProvider.ensureToken(context)) {
            val col = com.music.spotui.data.preferences.OfflineCollectionsPref.getCollection(context, clean)
            if (col != null) {
                val model = AlbumsModel(
                    id = stableId("playlist:${col.id}"),
                    artists = col.artists,
                    coverUri = col.coverUri,
                    name = col.name,
                    time = "Available offline",
                )
                emit(Response.Success(model))
            } else if (cached == null) {
                emit(Response.Error("Spotify not authenticated — set sp_dc cookie"))
            }
            return@flow
        }
        Spotify.playlist(clean).fold(
            onSuccess = { p ->
                val model = AlbumsModel(
                    id = stableId("playlist:${p.id}"),
                    artists = p.owner?.displayName ?: "",
                    coverUri = p.images.firstOrNull()?.url ?: "",
                    name = p.name,
                    time = stripHtml(p.description),
                )
                HomeCache.setPlaylistDetail(clean, model)
                emit(Response.Success(model))
            },
            onFailure = {
                Log.e("Api", "getPlaylist failed", it)
                if (cached == null) emit(Response.Error(it.message ?: "error"))
            },
        )
    }

    /**
     * Spotify playlist descriptions come as HTML (e.g. `<a href=spotify:...>Rickey
     * F</a>, …`). Render to plain text — keep the link labels, drop the tags —
     * and decode entities so the UI doesn't show raw markup.
     */
    private fun stripHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return androidx.core.text.HtmlCompat
            .fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
    }

    /**
     * From a list of same/similar-named album candidates, prefer one whose name
     * matches exactly AND whose artist matches the requested one; fall back to a
     * name match, then an artist match, then the first result.
     */
    private fun pickAlbum(
        candidates: List<com.metrolist.spotify.models.SpotifyAlbum>,
        albumName: String,
        artist: String,
    ): com.metrolist.spotify.models.SpotifyAlbum? {
        if (candidates.isEmpty()) return null
        if (artist.isBlank()) {
            return candidates.firstOrNull { it.name.equals(albumName, ignoreCase = true) }
                ?: candidates.first()
        }
        val wantArtists = artist.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        fun artistMatches(a: com.metrolist.spotify.models.SpotifyAlbum): Boolean {
            val names = a.artists.joinToString(" ") { it.name }.lowercase()
            return wantArtists.any { it.isNotBlank() && names.contains(it) }
        }
        val nameMatches = candidates.filter { it.name.equals(albumName, ignoreCase = true) }
        return nameMatches.firstOrNull { artistMatches(it) }
            ?: candidates.firstOrNull { artistMatches(it) }
            ?: nameMatches.firstOrNull()
            ?: candidates.first()
    }
}
