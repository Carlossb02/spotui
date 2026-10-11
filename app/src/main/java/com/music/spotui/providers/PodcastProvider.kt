
package com.music.spotui.providers

import android.util.Log
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PodcastProvider {

    private const val TAG = "PodcastProvider"
    private const val YOUTUBE_PODCAST_PREFIX = "yt_podcast:"
    private const val YOUTUBE_EPISODE_PREFIX = "yt_ep:"

    private fun stableId(key: String): Int =
        key.hashCode() and 0x7fffffff

    /**
     * El ID interno se elimina antes de consultar la API de YouTube.
     */
    private fun rawShowId(showId: String): String =
        showId.removePrefix(YOUTUBE_PODCAST_PREFIX).trim()

    private fun episodeToSong(
        episode: EpisodeItem,
        showName: String = ""
    ): SongsModel {
        val videoId = episode.id

        return SongsModel(
            id = stableId("$YOUTUBE_EPISODE_PREFIX$videoId"),
            title = episode.title,
            album = episode.podcast?.name
                ?.takeIf { it.isNotBlank() }
                ?: showName.takeIf { it.isNotBlank() }
                ?: "Podcast",
            singer = episode.author?.name.orEmpty(),
            coverUri = episode.thumbnail,
            url = "https://www.youtube.com/watch?v=$videoId",
            description = episode.title,
            durationMs = (episode.duration ?: 0) * 1000
        )
    }

    private fun podcastToModel(
        item: PodcastItem
    ): PodcastModel =
        PodcastModel(
            id = "$YOUTUBE_PODCAST_PREFIX${item.id}",
            name = item.title,
            publisher = item.author?.name.orEmpty(),
            coverUri = item.thumbnail.orEmpty()
        )

    /**
     * Busca programas y episodios mediante los filtros específicos
     * de YouTube Music. Utiliza vídeos y canciones como alternativas.
     */
    suspend fun searchPodcasts(
        query: String
    ): Pair<List<PodcastModel>, List<SongsModel>> =
        withContext(Dispatchers.IO) {

            if (query.isBlank()) {
                return@withContext emptyList<PodcastModel>() to
                        emptyList<SongsModel>()
            }

            val shows = linkedMapOf<String, PodcastModel>()
            val episodes = linkedMapOf<String, SongsModel>()

            val filters = listOf(
                "PODCAST" to YouTube.SearchFilter.FILTER_PODCAST,
                "EPISODE" to YouTube.SearchFilter.FILTER_EPISODE,
                "VIDEO" to YouTube.SearchFilter.FILTER_VIDEO,
                "SONG" to YouTube.SearchFilter.FILTER_SONG
            )

            for ((filterName, filter) in filters) {
                try {
                    val result = YouTube.search(query, filter)
                        .getOrElse { error ->
                            Log.w(
                                TAG,
                                "Search failed: query=$query, filter=$filterName",
                                error
                            )
                            continue
                        }

                    Log.d(
                        TAG,
                        "Search: query=$query, filter=$filterName, " +
                                "results=${result.items.size}"
                    )

                    for (item in result.items) {
                        when (item) {
                            is PodcastItem -> {
                                shows.putIfAbsent(
                                    item.id,
                                    podcastToModel(item)
                                )
                            }

                            is EpisodeItem -> {
                                episodes.putIfAbsent(
                                    item.id,
                                    episodeToSong(item)
                                )
                            }

                            else -> {
                                // No convertir canciones o vídeos genéricos
                                // en episodios sin confirmar su tipo.
                                Log.v(
                                    TAG,
                                    "Ignored result: type=${item::class.simpleName}"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Podcast search failed: query=$query, filter=$filterName",
                        e
                    )
                }
            }

            shows.values.toList() to episodes.values.toList()
        }

    /**
     * Obtiene los metadatos reales del programa mediante su browse ID.
     */
    suspend fun getShow(
        showId: String,
        showName: String = ""
    ): PodcastModel? = withContext(Dispatchers.IO) {

        if (showId.isBlank()) {
            return@withContext null
        }

        if (showName.isNotBlank() && showName != "Podcast") {
            return@withContext PodcastModel(
                id = showId,
                name = showName,
                publisher = "",
                coverUri = ""
            )
        }

        val id = rawShowId(showId)

        try {
            val page = YouTube.podcast(id).getOrThrow()
            val podcast = page.podcast

            if (podcast.title.isNotBlank() && podcast.title != "Podcast") {
                return@withContext PodcastModel(
                    id = "$YOUTUBE_PODCAST_PREFIX${podcast.id}",
                    name = podcast.title,
                    publisher = podcast.author?.name.orEmpty(),
                    coverUri = podcast.thumbnail.orEmpty()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load podcast: $id", e)
        }

        PodcastModel(
            id = showId,
            name = showName.ifBlank { "Podcast" },
            publisher = "",
            coverUri = ""
        )
    }

    /**
     * Carga los episodios del programa usando el nombre del show.
     */
    suspend fun getShowEpisodes(
        showId: String,
        showName: String = ""
    ): List<SongsModel> = withContext(Dispatchers.IO) {

        if (showId.isBlank()) {
            return@withContext emptyList()
        }

        val episodes = linkedMapOf<String, SongsModel>()
        val query = showName.ifBlank { rawShowId(showId) }

        if (query.isBlank() || query == "Podcast") {
            return@withContext emptyList()
        }

        val filters = listOf(
            "EPISODE" to YouTube.SearchFilter.FILTER_EPISODE,
            "VIDEO" to YouTube.SearchFilter.FILTER_VIDEO,
            "PODCAST" to YouTube.SearchFilter.FILTER_PODCAST
        )

        for ((filterName, filter) in filters) {
            try {
                val result = YouTube.search(query, filter)
                    .getOrElse { error ->
                        Log.w(
                            TAG,
                            "Episode search failed: $filterName",
                            error
                        )
                        continue
                    }

                for (item in result.items) {
                    if (item is EpisodeItem) {
                        val resolvedShowName = showName.ifBlank { item.podcast?.name ?: "Podcast" }
                        episodes.putIfAbsent(
                            item.id,
                            episodeToSong(item, resolvedShowName)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to load episodes: show=$showId, query=$query, filter=$filterName",
                    e
                )
            }
        }

        episodes.values.toList()
    }
}
