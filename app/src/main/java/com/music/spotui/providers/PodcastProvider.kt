package com.music.spotui.providers

import android.util.Log
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.YTItem
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PodcastProvider {

    private const val TAG = "PodcastProvider"
    private const val YOUTUBE_PODCAST_PREFIX = "yt_podcast:"
    private const val YOUTUBE_EPISODE_PREFIX = "yt_ep:"
    private const val ITUNES_PREFIX = "itunes:"

    // YouTube Music pide la página de un programa con "MPSP" + id de playlist.
    private const val PODCAST_BROWSE_PREFIX = "MPSP"

    // Límites para cargar episodios cuando la búsqueda no los devuelve.
    private const val MAX_SHOWS_FOR_EPISODES = 5
    private const val MAX_EPISODES_PER_SHOW = 10

    private const val BROWSE_URL =
        "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20250310.01.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) " +
                "Gecko/20100101 Firefox/128.0"

    private fun stableId(key: String): Int =
        key.hashCode() and 0x7fffffff

    private fun podcastBrowseId(showId: String): String {
        val raw = showId.removePrefix(YOUTUBE_PODCAST_PREFIX).trim()
        return when {
            raw.isBlank() -> ""
            raw.startsWith(PODCAST_BROWSE_PREFIX) -> raw
            raw.startsWith("PL") -> "$PODCAST_BROWSE_PREFIX$raw"
            else -> raw
        }
    }

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
                ?: showName.ifBlank {
                    episode.author?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: "Podcast"
                },
            singer = episode.author?.name.orEmpty(),
            coverUri = episode.thumbnail,
            url = "https://www.youtube.com/watch?v=$videoId",
            description = episode.title,
            durationMs = (episode.duration ?: 0) * 1000
        )
    }

    private fun itemToShow(item: YTItem): PodcastModel? = when (item) {
        is PodcastItem -> PodcastModel(
            id = "$YOUTUBE_PODCAST_PREFIX${item.id}",
            name = item.title,
            publisher = item.author?.name.orEmpty(),
            coverUri = item.thumbnail.orEmpty()
        )

        is PlaylistItem -> if (item.id.startsWith(PODCAST_BROWSE_PREFIX)) {
            PodcastModel(
                id = "$YOUTUBE_PODCAST_PREFIX${item.id}",
                name = item.title,
                publisher = item.author?.name.orEmpty(),
                coverUri = item.thumbnail.orEmpty()
            )
        } else null

        else -> null
    }

    // ------------------------------------------------------------------
    // Petición directa a YouTube Music (no depende del parser de innertube)
    // ------------------------------------------------------------------

    private fun browseRaw(browseId: String): JSONObject? {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "es")
                        .put("gl", "ES")
                )
            )
            .put("browseId", browseId)

        val conn = (URL(BROWSE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
        }

        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "browse HTTP $code for $browseId")
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Recorre todo el JSON llamando a [onObj] por cada objeto (con la clave que lo contiene). */
    private fun walk(node: Any?, key: String = "", onObj: (String, JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                onObj(key, node)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    walk(node.opt(k), k, onObj)
                }
            }

            is JSONArray -> for (i in 0 until node.length()) {
                walk(node.opt(i), key, onObj)
            }
        }
    }

    private fun findFirst(node: Any?, wanted: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(wanted)) return node.opt(wanted)
                val keys = node.keys()
                while (keys.hasNext()) {
                    findFirst(node.opt(keys.next()), wanted)?.let { return it }
                }
            }

            is JSONArray -> for (i in 0 until node.length()) {
                findFirst(node.opt(i), wanted)?.let { return it }
            }
        }
        return null
    }

    private fun collectTexts(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                node.optString("text").takeIf { it.isNotBlank() }?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) collectTexts(node.opt(keys.next()), out)
            }

            is JSONArray -> for (i in 0 until node.length()) {
                collectTexts(node.opt(i), out)
            }
        }
    }

    private val durationRegex = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

    private fun parseDurationMs(text: String): Int {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
        return seconds * 1000
    }

    private fun parseEpisodesFromBrowse(
        root: JSONObject,
        showName: String
    ): List<SongsModel> {
        val rendererNames = mutableSetOf<String>()
        val episodes = mutableListOf<SongsModel>()

        walk(root) { key, obj ->
            if (key.endsWith("Renderer")) rendererNames.add(key)

            if (
                key != "musicMultiRowListItemRenderer" &&
                key != "musicResponsiveListItemRenderer"
            ) return@walk

            val videoId = findFirst(obj, "videoId") as? String
                ?: return@walk

            // Título
            val title = obj.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    val texts = mutableListOf<String>()
                    collectTexts(obj.opt("flexColumns"), texts)
                    texts.firstOrNull()
                }
                ?: return@walk

            // Miniatura (la última suele ser la mayor)
            val thumbs = findFirst(obj, "thumbnails") as? JSONArray
            val cover = thumbs
                ?.optJSONObject(thumbs.length() - 1)
                ?.optString("url")
                .orEmpty()

            // Duración: primer texto con forma H:MM:SS o M:SS
            val texts = mutableListOf<String>()
            collectTexts(obj, texts)
            val durationMs = texts
                .firstOrNull { durationRegex.matches(it.trim()) }
                ?.let { parseDurationMs(it.trim()) }
                ?: 0

            episodes.add(
                SongsModel(
                    id = stableId("$YOUTUBE_EPISODE_PREFIX$videoId"),
                    title = title,
                    album = showName.ifBlank { "Podcast" },
                    singer = "",
                    coverUri = cover,
                    url = "https://www.youtube.com/watch?v=$videoId",
                    description = title,
                    durationMs = durationMs
                )
            )
        }

        if (episodes.isEmpty()) {
            Log.w(TAG, "Browse sin episodios. Renderers: $rendererNames")
        }

        return episodes.distinctBy { it.id }
    }

    // ------------------------------------------------------------------
    // API pública
    // ------------------------------------------------------------------

    suspend fun searchPodcasts(
        query: String
    ): Pair<List<PodcastModel>, List<SongsModel>> =
        withContext(Dispatchers.IO) {

            if (query.isBlank()) {
                return@withContext emptyList<PodcastModel>() to
                        emptyList<SongsModel>()
            }

            val shows = mutableListOf<PodcastModel>()
            val episodes = mutableListOf<SongsModel>()

            val filters = listOf(
                YouTube.SearchFilter.FILTER_PODCAST,
                YouTube.SearchFilter.FILTER_EPISODE
            )

            for (filter in filters) {
                try {
                    val result = YouTube.search(query, filter)
                        .onFailure { Log.e(TAG, "Search FAILED: $query", it) }
                        .getOrNull() ?: continue

                    for (item in result.items) {
                        if (item is EpisodeItem) {
                            episodes.add(episodeToSong(item))
                        } else {
                            itemToShow(item)?.let { shows.add(it) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Search crashed: query=$query, filter=$filter", e)
                }
            }

            val distinctShows = shows.distinctBy { it.id }
            var distinctEpisodes = episodes.distinctBy { it.id }

            if (distinctEpisodes.isEmpty() && distinctShows.isNotEmpty()) {
                distinctEpisodes = coroutineScope {
                    distinctShows
                        .take(MAX_SHOWS_FOR_EPISODES)
                        .map { show ->
                            async {
                                getShowEpisodes(show.id, show.name)
                                    .take(MAX_EPISODES_PER_SHOW)
                            }
                        }
                        .awaitAll()
                        .flatten()
                        .distinctBy { it.id }
                }
            }

            distinctShows to distinctEpisodes
        }

    suspend fun getShow(
        showId: String,
        showName: String = ""
    ): PodcastModel? = withContext(Dispatchers.IO) {

        if (showId.isBlank() || showId.startsWith(ITUNES_PREFIX)) {
            return@withContext null
        }

        val browseId = podcastBrowseId(showId)
        if (browseId.isBlank()) return@withContext null

        try {
            val page = YouTube.podcast(browseId)
                .onFailure {
                    Log.e(TAG, "podcast() FAILED: browseId=$browseId", it)
                }
                .getOrNull()
                ?: return@withContext null

            val show = page.podcast

            PodcastModel(
                id = "$YOUTUBE_PODCAST_PREFIX${show.id}",
                name = show.title.ifBlank { showName },
                publisher = show.author?.name.orEmpty(),
                coverUri = show.thumbnail.orEmpty()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load podcast: $showId", e)
            null
        }
    }

    /**
     * Episodios de un programa.
     * 1) YouTube.podcast() de innertube.
     * 2) Si viene vacío, petición directa a YouTube Music.
     */
    suspend fun getShowEpisodes(
        showId: String,
        showName: String = ""
    ): List<SongsModel> = withContext(Dispatchers.IO) {

        if (showId.isBlank() || showId.startsWith(ITUNES_PREFIX)) {
            return@withContext emptyList()
        }

        val browseId = podcastBrowseId(showId)
        if (browseId.isBlank()) return@withContext emptyList()

        var resolvedName = showName

        // 1) innertube
        try {
            val page = YouTube.podcast(browseId)
                .onFailure {
                    Log.e(TAG, "podcast() FAILED: browseId=$browseId", it)
                }
                .getOrNull()

            if (page != null) {
                resolvedName = showName.ifBlank { page.podcast.title }
                Log.d(TAG, "innertube $browseId: ${page.episodes.size} episodes")

                if (page.episodes.isNotEmpty()) {
                    return@withContext page.episodes
                        .distinctBy { it.id }
                        .map { episodeToSong(it, resolvedName) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "innertube podcast page failed: $showId", e)
        }

        // 2) Petición directa
        try {
            val root = browseRaw(browseId) ?: return@withContext emptyList()
            val list = parseEpisodesFromBrowse(root, resolvedName)
            Log.d(TAG, "direct browse $browseId: ${list.size} episodes")
            list
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "direct browse failed: $showId", e)
            emptyList()
        }
    }
}