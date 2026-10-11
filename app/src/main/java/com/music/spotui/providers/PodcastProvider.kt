package com.music.spotui.providers

import android.util.Log
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyEpisode
import com.metrolist.spotify.models.SpotifyShow
import com.music.spotui.MyApplication
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PodcastProvider {

    private const val TAG = "PodcastProvider"

    private fun stableId(key: String): Int =
        key.hashCode() and 0x7fffffff

    private fun episodeToSong(
        episode: SpotifyEpisode,
        showName: String = ""
    ): SongsModel {
        val episodeId = episode.id

        return SongsModel(
            id = stableId("spotify_ep:$episodeId"),
            title = episode.name,
            album = episode.show?.name
                ?: showName.ifBlank { "Podcast" },
            singer = "",
            coverUri = episode.images.firstOrNull()?.url.orEmpty(),
            url = "episode:$episodeId",
            spotifyTrackId = episodeId,
            description = episode.description,
            durationMs = episode.durationMs
        )
    }

    private fun showToModel(
        show: SpotifyShow
    ): PodcastModel =
        PodcastModel(
            id = show.id,
            name = show.name,
            publisher = show.publisher,
            coverUri = show.images.firstOrNull()?.url.orEmpty()
        )

    /**
     * Busca programas y episodios mediante la API REST de podcasts de Spotify.
     */
    suspend fun searchPodcasts(
        query: String
    ): Pair<List<PodcastModel>, List<SongsModel>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext emptyList<PodcastModel>() to emptyList<SongsModel>()
            }

            try {
                if (!SpotifyTokenProvider.ensureToken(MyApplication.instance)) {
                    Log.w(TAG, "searchPodcasts: Spotify token not ensured")
                    return@withContext emptyList<PodcastModel>() to emptyList<SongsModel>()
                }

                val result = Spotify.searchPodcasts(query).getOrNull()
                val shows = result?.shows?.items.orEmpty().map { showToModel(it) }
                val episodes = result?.episodes?.items.orEmpty().map { episodeToSong(it) }
                Log.d(TAG, "searchPodcasts query=$query shows=${shows.size} episodes=${episodes.size}")
                shows to episodes
            } catch (e: Exception) {
                Log.e(TAG, "searchPodcasts failed: $query", e)
                emptyList<PodcastModel>() to emptyList<SongsModel>()
            }
        }

    /**
     * Obtiene los metadatos reales del programa mediante su ID de Spotify.
     */
    suspend fun getShow(
        showId: String,
        showName: String = ""
    ): PodcastModel? = withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext null

        try {
            if (!SpotifyTokenProvider.ensureToken(MyApplication.instance)) {
                Log.w(TAG, "getShow: Spotify token not ensured")
            } else {
                val show = Spotify.show(showId).getOrNull()
                if (show != null && show.name.isNotBlank()) {
                    return@withContext showToModel(show)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getShow failed: $showId", e)
        }

        PodcastModel(
            id = showId,
            name = showName.ifBlank { "Podcast" },
            publisher = "",
            coverUri = ""
        )
    }

    /**
     * Carga los episodios del programa usando la API de Spotify.
     */
    suspend fun getShowEpisodes(
        showId: String,
        showName: String = ""
    ): List<SongsModel> = withContext(Dispatchers.IO) {
        if (showId.isBlank()) return@withContext emptyList()

        try {
            if (!SpotifyTokenProvider.ensureToken(MyApplication.instance)) {
                Log.w(TAG, "getShowEpisodes: Spotify token not ensured")
                return@withContext emptyList()
            }

            val paging = Spotify.showEpisodes(showId).getOrNull()
            val episodes = paging?.items.orEmpty()
            val show = getShow(showId, showName)
            val resolvedName = show?.name ?: showName

            Log.d(TAG, "getShowEpisodes showId=$showId episodes=${episodes.size}")
            episodes.map { episodeToSong(it, resolvedName) }
        } catch (e: Exception) {
            Log.e(TAG, "getShowEpisodes failed: $showId", e)
            emptyList()
        }
    }
}
