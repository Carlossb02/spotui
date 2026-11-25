package com.music.spotui.providers

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.YouTubeVideoSearch
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyEpisode
import com.metrolist.spotify.models.SpotifyShow
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.publicationTime
import com.music.spotui.util.ExpiringLruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Spotify confirms the show and episode metadata. Only verified video IDs enter the YouTube player. */
internal object SpotifyPodcastEpisodes {
    private const val PAGE_SIZE = 24
    private const val PAGE_LIMIT = 4
    private const val VIDEO_LOOKUP_LIMIT = 6
    private val shows = ExpiringLruCache<String, SpotifyShow>(128, 30 * 60_000L)
    private val videos = ExpiringLruCache<String, PodcastDiscoveryPolicy.Video>(256, 30 * 60_000L)
    private val videoPermits = Semaphore(3)

    fun remember(show: SpotifyShow) = shows.put(show.id, show)

    fun asModel(show: SpotifyShow) = PodcastModel(
        id = "spotify:show:${show.id}", name = show.name, publisher = show.publisher,
        coverUri = show.images.firstOrNull()?.url.orEmpty(),
    )

    suspend fun show(id: String): SpotifyShow? = shows.get(id) ?: withTimeoutOrNull(4_000L) {
        Spotify.show(id).getOrNull()?.takeIf { it.id == id && PodcastDiscoveryPolicy.validShow(it) }
            ?.also(::remember)
    }

    suspend fun youtubeEpisodes(show: SpotifyShow, publishedSince: Long?): List<SongsModel> = coroutineScope {
        val queries = listOf(show.name, "${show.name} ${show.publisher}".trim()).distinct()
        val now = System.currentTimeMillis()
        val typed = queries.map { query -> async {
            withTimeoutOrNull(6_000L) {
                YouTube.search(query, YouTube.SearchFilter.FILTER_EPISODE).getOrNull()?.items.orEmpty()
                    .filterIsInstance<EpisodeItem>().filter { episode ->
                        val author = episode.author?.name.orEmpty()
                        val matchesShow = episode.podcast?.name?.let {
                            PodcastDiscoveryPolicy.showTitleMatches(show.name, it)
                        } == true
                        val matchesPublisher = PodcastDiscoveryPolicy.publisherMatches(show.publisher, author)
                        val matchesTimestamp = publishedSince == null ||
                            publicationTime(episode.publishDateText.orEmpty(), now)?.let { it >= publishedSince } == true
                        matchesTimestamp && matchesShow && (matchesPublisher || author.isBlank() || show.publisher.isBlank())
                    }
            }.orEmpty()
        } }.awaitAll().flatten().distinctBy { it.id }
        if (typed.isNotEmpty() || publishedSince != null || show.publisher.isBlank()) {
            return@coroutineScope typed.map { PodcastProvider.episodeToSong(it, show.name) }
        }
        val ordinary = try {
            withTimeoutOrNull(6_000L) { YouTubeVideoSearch.find(queries.last()) }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        ordinary.filter {
            it.seconds in 480..43_200 && it.id.matches(Regex("[A-Za-z0-9_-]{11}")) &&
                PodcastDiscoveryPolicy.publisherMatches(show.publisher, it.publisher) &&
                PodcastDiscoveryPolicy.relevantShow(show.name, it.title) &&
                !PodcastDiscoveryPolicy.showTitleMatches(show.name, it.title)
        }.distinctBy { it.id }.map { video ->
            PodcastProvider.episodeToSong(EpisodeItem(id = video.id, title = video.title,
                author = com.metrolist.innertube.models.Artist(video.publisher, null),
                duration = video.seconds.toInt(), thumbnail = "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"), show.name)
        }
    }

    fun pages(show: SpotifyShow, publishedSince: Long?) = flow {
        val entries = linkedMapOf<String, SongsModel>()
        val now = System.currentTimeMillis()
        var offset = 0
        repeat(PAGE_LIMIT) {
            val page = withTimeoutOrNull(5_000L) { Spotify.showEpisodes(show.id, PAGE_SIZE, offset).getOrNull() }
                ?: return@flow
            val episodes = page.items.filter {
                it.name.isNotBlank() && PodcastDiscoveryPolicy.validEpisodeId(it.id, it.uri) &&
                    (it.show?.id.isNullOrBlank() || it.show?.id == show.id)
            }
            val recent = if (publishedSince == null) episodes else episodes.filter {
                publicationTime(it.releaseDate, now)?.let { date -> date >= publishedSince } == true
            }
            if (recent.isNotEmpty()) {
                val additions = recent.filterNot { it.id in entries }
                val verified = coroutineScope {
                    additions.take(VIDEO_LOOKUP_LIMIT).map { episode -> async {
                        episode.id to matchingVideo(show, episode)
                    } }.awaitAll().toMap()
                }
                additions.forEach { episode ->
                    entries[episode.id] = episode.toSong(show, verified[episode.id] ?: videos.get(episode.id))
                }
                emit(entries.values.toList())
            }
            val next = page.offset + page.limit
            if (page.offset != offset || page.items.isEmpty() || next <= offset || next >= page.total ||
                (publishedSince != null && episodes.isNotEmpty() && episodes.all {
                    publicationTime(it.releaseDate, now)?.let { date -> date < publishedSince } == true
                })) return@flow
            offset = next
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun matchingVideo(show: SpotifyShow, episode: SpotifyEpisode): PodcastDiscoveryPolicy.Video? {
        videos.get(episode.id)?.let { return it }
        if (episode.durationMs <= 0) return null
        return withTimeoutOrNull(4_500L) {
            videoPermits.withPermit {
                val query = listOf(episode.name, show.publisher.ifBlank { show.name }).joinToString(" ")
                val podcastResults = withTimeoutOrNull(1_500L) {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_EPISODE).getOrNull()?.items.orEmpty()
                        .filterIsInstance<EpisodeItem>().map {
                            PodcastDiscoveryPolicy.Video(it.id, it.title, it.author?.name.orEmpty(),
                                it.duration?.toLong(), it.podcast?.name, it.thumbnail)
                        }
                }.orEmpty()
                val match = PodcastDiscoveryPolicy.episodeVideo(episode.name, episode.durationMs, show, podcastResults)
                    ?: ordinaryVideo(query, show, episode)
                match?.also { videos.put(episode.id, it) }
            }
        }
    }

    private suspend fun ordinaryVideo(query: String, show: SpotifyShow, episode: SpotifyEpisode): PodcastDiscoveryPolicy.Video? {
        if (show.publisher.isBlank()) return null
        val candidates = try {
            YouTubeVideoSearch.find(query).map { PodcastDiscoveryPolicy.Video(it.id, it.title, it.publisher, it.seconds) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return PodcastDiscoveryPolicy.episodeVideo(episode.name, episode.durationMs, show, candidates)
    }

    private fun SpotifyEpisode.toSong(show: SpotifyShow, video: PodcastDiscoveryPolicy.Video?): SongsModel {
        val url = video?.let { "yt_ep:${it.id}|${name} ${show.publisher}" } ?: "episode:$id"
        val key = video?.let { "yt_ep:${it.id}" } ?: url
        return SongsModel(id = key.hashCode() and Int.MAX_VALUE, title = name, album = show.name,
            singer = show.publisher, coverUri = images.firstOrNull()?.url
                ?: video?.cover?.takeIf { it.isNotBlank() } ?: show.images.firstOrNull()?.url.orEmpty(),
            url = url, durationMs = durationMs, description = description, releaseDate = releaseDate)
    }
}
