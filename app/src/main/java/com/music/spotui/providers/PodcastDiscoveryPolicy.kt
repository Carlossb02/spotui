package com.music.spotui.providers

import com.metrolist.spotify.models.SpotifyShow
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object PodcastDiscoveryPolicy {
    private val spotifyId = Regex("[A-Za-z0-9]{22}")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val punctuation = Regex("[^a-z0-9]+")
    private val accents = Regex("""\p{M}+""")
    private val articles = setOf("el", "la", "los", "las", "de", "del", "un", "una")
    private val formatWords = setOf("podcast", "oficial", "official")
    private val publisherSuffixes = setOf("oficial", "official", "podcast", "podcasts")

    private fun words(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(accents, "").lowercase(Locale.ROOT).split(punctuation).filter(String::isNotEmpty)

    private fun showWords(value: String): List<String> {
        val parts = words(value)
        val title = if (parts.firstOrNull() == "es" && parts.getOrNull(1) in articles) parts.drop(1) else parts
        return title.filterNot { it in articles || it in formatWords }.map {
            if (it.length > 4 && (it.endsWith("as") || it.endsWith("os") || it.endsWith("es"))) it.dropLast(1) else it
        }
    }

    fun broaderQuery(query: String): String? = showWords(query).takeIf { it.size >= 2 }
        ?.joinToString(" ")?.takeUnless { it == words(query).joinToString(" ") }

    fun validShow(show: SpotifyShow): Boolean = spotifyId.matches(show.id) && show.name.isNotBlank() &&
        (show.uri == null || show.uri == "spotify:show:${show.id}" || show.uri == "show:${show.id}")

    fun validEpisodeId(id: String, uri: String?): Boolean = spotifyId.matches(id) &&
        (uri == null || uri == "spotify:episode:$id" || uri == "episode:$id")

    fun showTitleMatches(expected: String, candidate: String): Boolean =
        showWords(expected).takeIf { it.isNotEmpty() } == showWords(candidate)

    fun relevantShow(query: String, name: String): Boolean {
        val terms = showWords(query)
        return terms.isNotEmpty() && showWords(name).windowed(terms.size).any { it == terms }
    }

    fun publisherMatches(expected: String, candidate: String): Boolean {
        fun key(raw: String) = words(raw).filterNot { it in publisherSuffixes }.joinToString("")
        return key(expected).takeIf { it.isNotEmpty() } == key(candidate)
    }

    data class Video(
        val id: String,
        val title: String,
        val publisher: String,
        val seconds: Long?,
        val podcast: String? = null,
        val cover: String = "",
    )

    fun episodeVideo(episode: String, durationMs: Int, show: SpotifyShow, candidates: List<Video>): Video? {
        val title = words(episode)
        if (title.size < 3 || durationMs <= 0) return null
        val seconds = durationMs / 1_000.0
        val tolerance = (seconds * .05).coerceIn(8.0, 60.0)
        return candidates.asSequence().filter { video ->
            val length = video.seconds ?: return@filter false
            val candidate = words(video.title)
            val suffix = candidate.drop(title.size)
            val titleMatches = candidate == title ||
                (candidate.take(title.size) == title && (suffix == listOf("podcast") ||
                    suffix == words(show.name) || suffix == words(show.publisher)))
            videoId.matches(video.id) && titleMatches && abs(length - seconds) <= tolerance &&
                (publisherMatches(show.publisher, video.publisher) ||
                    (video.podcast != null && showTitleMatches(show.name, video.podcast) &&
                        (show.publisher.isBlank() || video.publisher.isBlank())))
        }.distinctBy { it.id }.take(2).toList().singleOrNull()
    }
}
