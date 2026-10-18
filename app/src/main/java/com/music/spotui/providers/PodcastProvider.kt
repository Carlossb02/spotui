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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
        val authorName = episode.author?.name?.takeIf { it.isNotBlank() } ?: showName
        val queryText = listOf(episode.title, authorName).filter { it.isNotBlank() }.joinToString(" ")

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
            url = "$YOUTUBE_EPISODE_PREFIX$videoId|$queryText",
            description = episode.title,
            durationMs = (episode.duration ?: 0) * 1000
        )
    }

    private fun itemToShow(item: YTItem): PodcastModel? {
        val show = when (item) {
            is PodcastItem -> PodcastModel(
                id = if (item.id.startsWith(YOUTUBE_PODCAST_PREFIX)) item.id else "$YOUTUBE_PODCAST_PREFIX${item.id}",
                name = item.title,
                publisher = item.author?.name.orEmpty(),
                coverUri = item.thumbnail.orEmpty()
            )

            is PlaylistItem -> if (item.isPodcast || item.id.startsWith(PODCAST_BROWSE_PREFIX) || item.id.startsWith("MPSP")) {
                PodcastModel(
                    id = if (item.id.startsWith(YOUTUBE_PODCAST_PREFIX)) item.id else "$YOUTUBE_PODCAST_PREFIX${item.id}",
                    name = item.title,
                    publisher = item.author?.name.orEmpty(),
                    coverUri = item.thumbnail.orEmpty()
                )
            } else null

            else -> null
        }

        if (show != null) {
            val lowerName = show.name.lowercase()
            val lowerPub = show.publisher.lowercase()
            val musicKeywords = listOf("music", "cancion", "canción", "álbum", "album", "mezcla", "reggaeton", "pop español", "éxitos", "musica")
            if (musicKeywords.any { lowerName.contains(it) || lowerPub.contains(it) }) {
                return null
            }
        }
        return show
    }

    // ------------------------------------------------------------------
    // Petición directa a YouTube Music (no depende del parser de innertube)
    // ------------------------------------------------------------------

    private fun browseRaw(browseId: String, params: String? = null): JSONObject? {
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
        if (!params.isNullOrBlank()) {
            body.put("params", params)
        }

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

    private fun browseContinuation(token: String): JSONObject? {
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
            .put("continuation", token)

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
                Log.e(TAG, "browseContinuation HTTP $code")
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "browseContinuation failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun findContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("continuationCommand")) {
                    val token = node.optJSONObject("continuationCommand")?.optString("token")
                    if (!token.isNullOrBlank()) return token
                }
                if (node.has("nextContinuationData")) {
                    val token = node.optJSONObject("nextContinuationData")?.optString("continuation")
                    if (!token.isNullOrBlank()) return token
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "continuation" && node.opt(k) is String) {
                        val token = node.optString("continuation")
                        if (token.isNotBlank() && token.length > 20) return token
                    } else {
                        val found = findContinuationToken(node.opt(k))
                        if (found != null) return found
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val found = findContinuationToken(node.opt(i))
                if (found != null) return found
            }
        }
        return null
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

    private val timePattern = Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}$""")

    private fun parseDurationFromText(text: String): Int {
        val trimmed = text.trim().lowercase()
        if (timePattern.matches(trimmed)) {
            return parseDurationMs(trimmed)
        }
        var totalSeconds = 0
        val hourMatch = Regex("""(\d+)\s*h""").find(trimmed)
        if (hourMatch != null) {
            totalSeconds += (hourMatch.groupValues[1].toIntOrNull() ?: 0) * 3600
        }
        val minMatch = Regex("""(\d+)\s*min""").find(trimmed)
        if (minMatch != null) {
            totalSeconds += (minMatch.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        val secMatch = Regex("""(\d+)\s*seg""").find(trimmed)
        if (secMatch != null) {
            totalSeconds += (secMatch.groupValues[1].toIntOrNull() ?: 0)
        }
        return totalSeconds * 1000
    }

    private fun isDateText(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower.contains("hace") || lower.contains("ayer") || lower.contains("hoy") ||
                lower.contains("ago") || lower.contains("yesterday") ||
                Regex("""\b(ene|feb|mar|abr|may|jun|jul|ago|sep|oct|nov|dic|jan|apr|aug)\b""").containsMatchIn(lower) ||
                Regex("""\b20\d{2}\b""").containsMatchIn(lower)
    }

    private fun parseShowFromBrowse(root: JSONObject, showId: String, fallbackName: String = ""): PodcastModel? {
        var title: String? = null
        var publisher: String? = null
        var coverUri: String? = null

        walk(root) { key, obj ->
            if (
                key == "musicDetailHeaderRenderer" ||
                key == "musicResponsiveHeaderRenderer" ||
                key == "musicHeaderRenderer" ||
                key == "musicEditablePlaylistDetailHeaderRenderer"
            ) {
                if (title == null) {
                    val titleObj = obj.optJSONObject("title")
                    title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?.takeIf { it.isNotBlank() && it != "Podcast" && it != "Programa" }
                }
                if (publisher == null) {
                    val subtitleObj = obj.optJSONObject("subtitle")
                        ?: obj.optJSONObject("straplineTextOne")
                        ?: obj.optJSONObject("author")
                        ?: obj.optJSONObject("owner")
                    val runs = subtitleObj?.optJSONArray("runs")
                    if (runs != null) {
                        val texts = mutableListOf<String>()
                        for (i in 0 until runs.length()) {
                            val txt = runs.optJSONObject(i)?.optString("text").orEmpty().trim()
                            if (txt.isNotBlank() && txt != "•" && txt != "Podcast" && txt != "Programa" && !isDateText(txt)) {
                                texts.add(txt)
                            }
                        }
                        if (texts.isNotEmpty()) publisher = texts.first()
                    }
                }
                if (coverUri == null) {
                    val thumbs = findFirst(obj, "thumbnails") as? JSONArray
                    coverUri = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
                }
            }
        }

        val finalTitle = title?.takeIf { it.isNotBlank() && it != "Podcast" }
            ?: fallbackName.takeIf { it.isNotBlank() && it != "Podcast" }
            ?: return null

        return PodcastModel(
            id = if (showId.startsWith(YOUTUBE_PODCAST_PREFIX)) showId else "$YOUTUBE_PODCAST_PREFIX$showId",
            name = finalTitle,
            publisher = publisher.orEmpty(),
            coverUri = coverUri.orEmpty()
        )
    }

    private fun parseEpisodesFromBrowse(
        root: JSONObject,
        showName: String,
        defaultCover: String = ""
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

            // Miniatura
            val thumbs = findFirst(obj, "thumbnails") as? JSONArray
            val cover = thumbs
                ?.optJSONObject(thumbs.length() - 1)
                ?.optString("url")
                .orEmpty()
                .ifBlank { defaultCover }

            // Descripción y textos secundarios
            val allTexts = mutableListOf<String>()
            collectTexts(obj, allTexts)

            var durationMs = 0
            var releaseDate = ""
            var description = ""

            val descObj = obj.optJSONObject("description") ?: obj.optJSONObject("subtitle")
            val descRuns = descObj?.optJSONArray("runs")
            if (descRuns != null) {
                val sb = StringBuilder()
                for (rIdx in 0 until descRuns.length()) {
                    sb.append(descRuns.optJSONObject(rIdx)?.optString("text").orEmpty())
                }
                description = sb.toString().trim()
            }

            for (txt in allTexts) {
                val clean = txt.trim()
                if (clean.isBlank()) continue
                if (durationMs == 0) {
                    val dur = parseDurationFromText(clean)
                    if (dur > 0) {
                        durationMs = dur
                        continue
                    }
                }
                if (releaseDate.isBlank() && isDateText(clean)) {
                    releaseDate = clean
                }
            }

            val queryText = listOf(title, showName).filter { it.isNotBlank() }.joinToString(" ")
            episodes.add(
                SongsModel(
                    id = stableId("$YOUTUBE_EPISODE_PREFIX$videoId"),
                    title = title,
                    album = showName.ifBlank { "Podcast" },
                    singer = showName,
                    coverUri = cover,
                    url = "$YOUTUBE_EPISODE_PREFIX$videoId|$queryText",
                    description = description.ifBlank { title },
                    durationMs = durationMs,
                    releaseDate = releaseDate
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
            val distinctEpisodes = episodes.distinctBy { it.id }

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
            val root = browseRaw(browseId)
            if (root != null) {
                val parsedShow = parseShowFromBrowse(root, showId, showName)
                if (parsedShow != null) return@withContext parsedShow
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getShow direct browse failed for $showId", e)
        }

        try {
            val page = YouTube.podcast(browseId).getOrNull()
            if (page != null && page.podcast.title.isNotBlank() && page.podcast.title != "Podcast") {
                return@withContext PodcastModel(
                    id = "$YOUTUBE_PODCAST_PREFIX${page.podcast.id}",
                    name = page.podcast.title,
                    publisher = page.podcast.author?.name.orEmpty(),
                    coverUri = page.podcast.thumbnail.orEmpty()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load podcast via innertube: $showId", e)
        }

        if (showName.isNotBlank() && showName != "Podcast") {
            PodcastModel(id = showId, name = showName, publisher = "", coverUri = "")
        } else null
    }

    /**
     * Episodios de un programa.
     * 1) Petición directa a YouTube Music.
     * 2) Fallback innertube.
     */
    fun getShowEpisodesFlow(
        showId: String,
        showName: String = ""
    ): kotlinx.coroutines.flow.Flow<List<SongsModel>> = kotlinx.coroutines.flow.flow {
        if (showId.isBlank() || showId.startsWith(ITUNES_PREFIX)) {
            emit(emptyList())
            return@flow
        }

        val browseId = podcastBrowseId(showId)
        if (browseId.isBlank()) {
            emit(emptyList())
            return@flow
        }

        var resolvedName = showName

        try {
            val root = browseRaw(browseId)
            if (root != null) {
                val headerShow = parseShowFromBrowse(root, showId, showName)
                if (headerShow != null && headerShow.name.isNotBlank()) {
                    resolvedName = headerShow.name
                }
                val initialEpisodes = parseEpisodesFromBrowse(root, resolvedName, headerShow?.coverUri.orEmpty())
                if (initialEpisodes.isNotEmpty()) {
                    val allEpisodes = initialEpisodes.toMutableList()
                    emit(allEpisodes.toList())

                    var currentRoot = root
                    var pageCount = 0
                    val maxPages = 15

                    while (pageCount < maxPages) {
                        val token = findContinuationToken(currentRoot) ?: break
                        val contRoot = browseContinuation(token) ?: break
                        val nextBatch = parseEpisodesFromBrowse(contRoot, resolvedName, headerShow?.coverUri.orEmpty())
                        if (nextBatch.isEmpty()) break
                        val newEpisodes = nextBatch.filter { ep -> allEpisodes.none { it.id == ep.id } }
                        if (newEpisodes.isEmpty()) break
                        allEpisodes.addAll(newEpisodes)
                        emit(allEpisodes.toList())
                        currentRoot = contRoot
                        pageCount++
                    }

                    Log.d(TAG, "Loaded total ${allEpisodes.size} episodes across ${pageCount + 1} pages for $showId")
                    return@flow
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "direct browse episodes failed: $showId", e)
        }

        try {
            val page = YouTube.podcast(browseId).getOrNull()
            if (page != null && page.episodes.isNotEmpty()) {
                resolvedName = page.podcast.title.ifBlank { showName }
                val eps = page.episodes.map { episodeToSong(it, resolvedName) }
                emit(eps)
                return@flow
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "innertube podcast page failed: $showId", e)
        }

        emit(emptyList())
    }

    suspend fun getShowEpisodes(
        showId: String,
        showName: String = ""
    ): List<SongsModel> = withContext(Dispatchers.IO) {
        var last: List<SongsModel> = emptyList()
        getShowEpisodesFlow(showId, showName).collect { last = it }
        last
    }

    private fun extractPodcastShowsFromNode(node: Any?): List<PodcastModel> {
        val shows = mutableListOf<PodcastModel>()
        val seen = mutableSetOf<String>()

        walk(node) { key, obj ->
            if (key == "musicTwoRowItemRenderer" || key == "musicResponsiveListItemRenderer") {
                val titleObj = obj.optJSONObject("title")
                val title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?.takeIf { it.isNotBlank() && it != "Podcast" && it != "Programa" }
                    ?: run {
                        val flex = obj.optJSONArray("flexColumns")
                        flex?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    }
                    ?: return@walk

                val subtitleObj = obj.optJSONObject("subtitle")
                var publisher = ""
                val subRuns = subtitleObj?.optJSONArray("runs")
                if (subRuns != null) {
                    val texts = mutableListOf<String>()
                    for (i in 0 until subRuns.length()) {
                        val txt = subRuns.optJSONObject(i)?.optString("text").orEmpty().trim()
                        if (txt.isNotBlank() && txt != "•" && txt != "Podcast" && txt != "Programa" && !isDateText(txt)) {
                            texts.add(txt)
                        }
                    }
                    publisher = texts.firstOrNull().orEmpty()
                }

                var coverUri = ""
                val thumbs = findFirst(obj, "thumbnails") as? JSONArray
                if (thumbs != null && thumbs.length() > 0) {
                    coverUri = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                }

                var browseId = ""
                val nav = obj.optJSONObject("navigationEndpoint")
                val browse = nav?.optJSONObject("browseEndpoint")
                val watch = nav?.optJSONObject("watchEndpoint")

                val pageType = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")

                if (pageType == "MUSIC_PAGE_TYPE_PLAYLIST" ||
                    pageType == "MUSIC_PAGE_TYPE_ALBUM" ||
                    pageType == "MUSIC_PAGE_TYPE_AUDIOBOOK" ||
                    pageType == "MUSIC_PAGE_TYPE_ARTIST"
                ) {
                    return@walk
                }

                if (browse != null && browse.has("browseId")) {
                    browseId = browse.optString("browseId")
                } else if (watch != null && watch.has("playlistId")) {
                    browseId = watch.optString("playlistId")
                } else {
                    val runs = titleObj?.optJSONArray("runs")
                    if (runs != null) {
                        for (r in 0 until runs.length()) {
                            val runNav = runs.optJSONObject(r)?.optJSONObject("navigationEndpoint")
                            val runBrowse = runNav?.optJSONObject("browseEndpoint")
                            if (runBrowse != null && runBrowse.has("browseId")) {
                                browseId = runBrowse.optString("browseId")
                                break
                            }
                        }
                    }
                }

                if (browseId.isBlank() || browseId.startsWith("MV") || browseId.startsWith("MPED")) {
                    return@walk
                }

                val finalId = podcastBrowseId(browseId)
                val fullId = if (finalId.startsWith(YOUTUBE_PODCAST_PREFIX)) finalId else "$YOUTUBE_PODCAST_PREFIX$finalId"

                if (seen.add(fullId)) {
                    shows.add(
                        PodcastModel(
                            id = fullId,
                            name = title,
                            publisher = publisher,
                            coverUri = coverUri
                        )
                    )
                }
            }
        }
        return shows
    }

    private val showSearchCache = java.util.concurrent.ConcurrentHashMap<String, PodcastModel?>()

    fun getYouTubePodcastsFeedFlow(): kotlinx.coroutines.flow.Flow<com.music.spotui.data.entity.PodcastFeedModel> = kotlinx.coroutines.flow.channelFlow {
        val spanishPodcastCategories = listOf(
            "Podcasts populares en España" to listOf(
                "The Wild Project", "La Ruina", "Nadie Sabe Nada", "Terrores Nocturnos", "Cuarto Milenio",
                "Entiende Tu Mente", "La Escóbula de la Brújula", "BLACK MANGO", "La Noche de Cuesta",
                "Poco Se Habla", "Estirando el Chicle", "Tengo un Plan", "Nude Project", "Caso 63"
            ),
            "Comedia y Humor" to listOf(
                "La Ruina", "Nadie Sabe Nada", "Estirando el Chicle", "Poco Se Habla",
                "Ilustres Ignorantes", "Misterios Y Cubatas", "No Hay Negros en el Tíbet"
            ),
            "Crímenes Reales y Misterio" to listOf(
                "Terrores Nocturnos", "Días Extraños con Santiago Camacho", "Espacio en Blanco",
                "Criminopatía", "Negra y Criminal", "Caso 63"
            ),
            "Noticias y Política" to listOf(
                "Un Tema Al Día", "La noche de Cuesta", "Herrera en COPE", "La Ventana"
            ),
            "Ciencia, Historia y Tecnología" to listOf(
                "La Escóbula de la Brújula", "A Hombros de Gigantes", "Materia Oscura", "Xataka", "Kernel"
            ),
            "Deportes" to listOf(
                "Linea de cal", "El Partidazo de COPE", "El Larguero", "El Desmarque", "La Libreta de Van Gaal"
            ),
            "Negocios y Emprendimiento" to listOf(
                "Tengo un Plan", "Nude Project", "Itnig", "Libros para Emprendedores"
            )
        )

        val sectionShowsMap = java.util.concurrent.ConcurrentHashMap<String, MutableList<PodcastModel>>()
        spanishPodcastCategories.forEach { (catTitle, _) ->
            sectionShowsMap[catTitle] = java.util.Collections.synchronizedList(mutableListOf())
        }

        fun currentSnapshot(): com.music.spotui.data.entity.PodcastFeedModel {
            val sections = spanishPodcastCategories.map { (catTitle, _) ->
                com.music.spotui.data.entity.PodcastSectionModel(
                    title = catTitle,
                    shows = sectionShowsMap[catTitle]?.toList().orEmpty().distinctBy { it.id }
                )
            }
            return com.music.spotui.data.entity.PodcastFeedModel(
                categories = emptyList(),
                selectedCategory = null,
                sections = sections
            )
        }

        send(currentSnapshot())

        for ((catTitle, podcastQueries) in spanishPodcastCategories) {
            coroutineScope {
                podcastQueries.map { query ->
                    async(Dispatchers.IO) {
                        val cached = showSearchCache[query]
                        val resolved = if (cached != null) cached else {
                            runCatching {
                                var resultShow: PodcastModel? = null
                                val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_PODCAST).getOrNull()
                                for (item in searchResult?.items.orEmpty()) {
                                    val show = itemToShow(item)
                                    if (show != null && show.name.isNotBlank()) {
                                        resultShow = show
                                        break
                                    }
                                }
                                if (resultShow == null) {
                                    val (fallbackShows, _) = searchPodcasts(query)
                                    resultShow = fallbackShows.firstOrNull()
                                }
                                if (resultShow == null) {
                                    resultShow = PodcastModel(
                                        id = "$YOUTUBE_PODCAST_PREFIX$query",
                                        name = query,
                                        publisher = "Podcast",
                                        coverUri = ""
                                    )
                                }
                                showSearchCache[query] = resultShow
                                resultShow
                            }.getOrNull()
                        }

                        if (resolved != null) {
                            val list = sectionShowsMap[catTitle]
                            if (list != null && list.none { it.id == resolved.id }) {
                                list.add(resolved)
                            }
                        }
                    }
                }.awaitAll()
            }
            send(currentSnapshot())
        }
    }

    suspend fun getYouTubePodcastsFeed(): com.music.spotui.data.entity.PodcastFeedModel {
        var last = com.music.spotui.data.entity.PodcastFeedModel()
        getYouTubePodcastsFeedFlow().collect { last = it }
        return last
    }
}