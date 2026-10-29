package com.music.spotui.providers

import android.util.Log
import com.metrolist.innertube.YouTube
import com.music.spotui.MyApplication
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

object PodcastProvider {

    private const val TAG = "PodcastProvider"
    private const val YOUTUBE_PODCAST_PREFIX = "yt_podcast:"
    private const val YOUTUBE_EPISODE_PREFIX = "yt_ep:"
    private const val ITUNES_PREFIX = "itunes:"
    private const val LEGACY_SPOTIFY_SHOW_PREFIX = "spotify:show:"

    // YouTube Music pide la página de un programa con "MPSP" + id de playlist.
    private const val PODCAST_BROWSE_PREFIX = "MPSP"

    // Límites para cargar episodios cuando la búsqueda no los devuelve.
    private const val MAX_SHOWS_FOR_EPISODES = 5
    private const val MAX_EPISODES_PER_SHOW = 10

    // Episode -> true show ID, populated when show results are browsed. It is a
    // hint for radio only, never influences audio matching or the player URL.
    private val knownEpisodeShows = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private val episodeMemoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SongsModel>>>()
    private const val EPISODE_CACHE_TTL_MS = 15 * 60_000L

    private const val SHOW_MAP_PREFS = "spotui_episode_show_map_v1"
    private const val SHOW_MAP_TTL_MS = 30L * 24 * 60 * 60 * 1000

    fun knownShowForEpisode(url: String): Pair<String, String>? {
        if (!url.startsWith("yt_ep:")) return null
        val episodeId = url.removePrefix("yt_ep:").substringBefore('|')
        knownEpisodeShows[episodeId]?.let { return it }
        // Recent episode cards survive process death; this association must too.
        val saved = runCatching {
            MyApplication.instance.getSharedPreferences(SHOW_MAP_PREFS, android.content.Context.MODE_PRIVATE)
                .getString(episodeId, null)
        }.getOrNull() ?: return null
        val parts = saved.split('\n', limit = 3)
        if (parts.size != 3 ||
            System.currentTimeMillis() - (parts[0].toLongOrNull() ?: 0L) !in 0L..SHOW_MAP_TTL_MS ||
            parts[1].isBlank()) return null
        val result = parts[1] to parts[2]
        knownEpisodeShows[episodeId] = result
        return result
    }

    private fun rememberEpisodes(showId: String, showName: String, episodes: List<SongsModel>) {
        if (showId.isBlank() || episodes.isEmpty()) return
        if (knownEpisodeShows.size > 1500) knownEpisodeShows.clear()
        val prefs = MyApplication.instance.getSharedPreferences(
            SHOW_MAP_PREFS, android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val now = System.currentTimeMillis()
        // The most recent show entries are enough for continue-listening radio;
        // avoid writing hundreds of old episode keys from playlist continuations.
        episodes.take(40).forEach { episode ->
            if (episode.url.startsWith("yt_ep:")) {
                val episodeId = episode.url.removePrefix("yt_ep:").substringBefore('|')
                knownEpisodeShows[episodeId] = showId to showName
                editor.putString(episodeId, "$now\n$showId\n$showName")
            }
        }
        editor.apply()
    }

    /** Restore a podcast association for episodes opened from recent history.
     * Spotify-like recent cards often carry the episode title and show name but
     * no MPSP browse ID. Never guess the ID from a creator name; require the
     * exact episode video ID in a verified YouTube Music episode result. */
    suspend fun findShowByExactEpisode(episodeUrl: String, episodeTitle: String): PodcastModel? =
        withContext(Dispatchers.IO) {
            val videoId = episodeUrl.removePrefix(YOUTUBE_EPISODE_PREFIX).substringBefore('|')
            if (!episodeUrl.startsWith(YOUTUBE_EPISODE_PREFIX) ||
                !videoId.matches(Regex("[A-Za-z0-9_-]{11}")) || episodeTitle.isBlank())
                return@withContext null
            knownShowForEpisode(episodeUrl)?.let { (id, name) ->
                return@withContext PodcastModel(id = id, name = name, publisher = "", coverUri = "")
            }
            val exact = withTimeoutOrNull(7_000L) {
                YouTube.search(episodeTitle, YouTube.SearchFilter.FILTER_EPISODE)
                    .getOrNull()?.items.orEmpty()
                    .filterIsInstance<EpisodeItem>()
                    .firstOrNull { it.id == videoId && !it.podcast?.id.isNullOrBlank() }
            } ?: return@withContext null
            // episodeToSong persists the verified episode -> show mapping.
            episodeToSong(exact)
            val podcast = exact.podcast ?: return@withContext null
            val showId = podcast.id.let { if (it.startsWith(YOUTUBE_PODCAST_PREFIX)) it
                else "$YOUTUBE_PODCAST_PREFIX$it" }
            PodcastModel(id = showId, name = podcast.name,
                publisher = exact.author?.name.orEmpty(), coverUri = exact.thumbnail)
        }

    /** One fast podcast-only search, ranked by show name; avoids the second
     * independent episode search used by searchPodcasts(). */
    suspend fun findShowForAutofill(name: String): PodcastModel? = withContext(Dispatchers.IO) {
        if (name.isBlank() || name == "Podcast") return@withContext null
        val candidates = YouTube.search(name, YouTube.SearchFilter.FILTER_PODCAST)
            .getOrNull()?.items.orEmpty().mapNotNull { itemToShow(it) }
        candidates.maxByOrNull { podcastNameScore(name, it.name) }
            ?.takeIf { podcastNameScore(name, it.name) >= 70 }
    }

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

    /**
     * A real YouTube Music podcast item with a plausible name is sufficient.
     * The previous very strict ID/name filter rejected genuine MPSP results.
     * We never fabricate IDs from the user's search text.
     */
    internal fun podcastNameScore(query: String, title: String): Int {
        fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return 0
        if (q == t) return 100
        if (t.startsWith("$q ") || t.endsWith(" $q")) return 90
        if (t.contains(" $q ")) return 85
        // Some shows have a suffix/prefix (e.g. "La Ruina - Podcast oficial").
        val generic = setOf("podcast", "oficial", "programa", "con", "de", "el", "la", "los", "las", "un", "una")
        val qWords = q.split(' ').filter { it !in generic }
        val tWords = t.split(' ').filter { it !in generic }
        if (qWords.isNotEmpty() && tWords.size <= qWords.size + 5 &&
            (" " + tWords.joinToString(" ") + " ").contains(" " + qWords.joinToString(" ") + " ")) return 70
        return 0
    }

    /** Discover a playable show using YouTube Music only. */
    private suspend fun resolveCuratedPodcast(query: String): PodcastModel? {
        suspend fun search(q: String, filter: YouTube.SearchFilter): List<PodcastModel> {
            val items = YouTube.search(q, filter)
                .onFailure { Log.w(TAG, "YouTube podcast search failed for '$q'", it) }
                .getOrNull()?.items.orEmpty()
            return items.mapNotNull { item ->
                val show = itemToShow(item) ?: return@mapNotNull null
                val realId = show.id.removePrefix(YOUTUBE_PODCAST_PREFIX)
                if (realId.isBlank() || realId.equals(query, ignoreCase = true)) return@mapNotNull null
                val score = podcastNameScore(query, show.name)
                if (score < 70) return@mapNotNull null
                score to show
            }.sortedByDescending { it.first }.map { it.second }
        }

        val attempts = listOf(
            query to YouTube.SearchFilter.FILTER_PODCAST,
            query to YouTube.SearchFilter(""),
            "$query podcast" to YouTube.SearchFilter.FILTER_PODCAST
        )
        for ((text, filter) in attempts) {
            val candidates = withTimeoutOrNull(6_000L) { search(text, filter) }.orEmpty()
            for (candidate in candidates.take(3)) {
                if (candidate.coverUri.startsWith("http")) return candidate
                // Keep valid IDs even if YouTube omitted artwork in its search response.
                val detail = withTimeoutOrNull(2_500L) { getShow(candidate.id, candidate.name) }
                if (detail != null && detail.coverUri.startsWith("http")) return detail
            }
        }
        return null
    }

    private fun episodeToSong(
        episode: EpisodeItem,
        showName: String = ""
    ): SongsModel {
        val videoId = episode.id
        val pod = episode.podcast
        pod?.id?.takeIf { it.isNotBlank() }?.let { showId ->
            val realShowId = if (showId.startsWith(YOUTUBE_PODCAST_PREFIX)) showId else "$YOUTUBE_PODCAST_PREFIX$showId"
            val realShowName = pod.name.ifBlank { showName }
            knownEpisodeShows[videoId] = realShowId to realShowName
            runCatching {
                MyApplication.instance.getSharedPreferences(SHOW_MAP_PREFS, android.content.Context.MODE_PRIVATE)
                    .edit().putString(videoId, "${System.currentTimeMillis()}\n$realShowId\n$realShowName").apply()
            }
        }
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
        // Duration is a short metadata label, not free-form description text.
        if (text.length > 32 || text.contains('\n') || text.contains("http", ignoreCase = true)) return 0
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

    // Match the COMPLETE publication-date label, never substrings inside episode descriptions.
    // In particular, `hoy`, `ago`, month names or four-digit years found in an entire
    // video description must never turn that description into releaseDate.
    private val relativePublicationDate = Regex(
        """(?i)^(?:(?:publicad[oa]\s+)?hace\s+\d+\s+(?:segundos?|minutos?|horas?|d[ií]as?|semanas?|meses?|a[nñ]os?)|(?:\d+\s+(?:seconds?|minutes?|hours?|days?|weeks?|months?|years?)\s+ago)|hoy|ayer|today|yesterday)$"""
    )
    private val calendarPublicationDate = Regex(
        """(?i)^(?:(?:\d{1,2}\s+(?:de\s+)?(?:ene(?:ro)?|feb(?:rero)?|mar(?:zo)?|abr(?:il)?|may(?:o)?|jun(?:io)?|jul(?:io)?|ago(?:sto)?|sep(?:tiembre)?|set(?:iembre)?|oct(?:ubre)?|nov(?:iembre)?|dic(?:iembre)?|jan(?:uary)?|apr(?:il)?|aug(?:ust)?|dec(?:ember)?)\s+(?:de\s+)?\d{4})|(?:\d{4}[-/]\d{1,2}[-/]\d{1,2})|(?:(?:ene|feb|mar|abr|may|jun|jul|ago|sep|oct|nov|dic|jan|apr|aug|dec)[a-z]*\s+\d{1,2},?\s+\d{4}))$"""
    )

    fun validatedReleaseDate(value: String): String? {
        val date = value.trim().trimStart('•', '·', '-').trim()
        if (date.length !in 2..48 || date.any { it == '\n' || it == '\r' || it == '\t' } ||
            date.contains("http", ignoreCase = true) || date.contains("www.", ignoreCase = true)) return null
        return date.takeIf { relativePublicationDate.matches(it) || calendarPublicationDate.matches(it) }
    }

    private fun isDateText(text: String): Boolean = validatedReleaseDate(text) != null

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

            // Descripción y textos secundarios (extraídos solo de flexColumns, subtitle y description)
            val allTexts = mutableListOf<String>()
            val flex = obj.optJSONArray("flexColumns")
            if (flex != null) {
                for (fIdx in 0 until flex.length()) {
                    collectTexts(flex.optJSONObject(fIdx), allTexts)
                }
            }
            obj.optJSONObject("subtitle")?.let { collectTexts(it, allTexts) }
            obj.optJSONObject("description")?.let { collectTexts(it, allTexts) }

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
                    releaseDate = validatedReleaseDate(clean).orEmpty()
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

        // Old versions saved some Spotify show IDs. Resolve by name on YouTube instead.
        if (showId.startsWith(LEGACY_SPOTIFY_SHOW_PREFIX)) {
            return@withContext showName.takeIf { it.isNotBlank() }
                ?.let { withTimeoutOrNull(15_000L) { resolveCuratedPodcast(it) } }
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

        // Emisión instantánea desde caché en memoria si está fresco
        val cached = episodeMemoryCache[showId]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < EPISODE_CACHE_TTL_MS && cached.second.isNotEmpty()) {
            emit(cached.second)
            if (now - cached.first < 3 * 60_000L) {
                return@flow
            }
        }

        if (showId.startsWith(LEGACY_SPOTIFY_SHOW_PREFIX)) {
            val youtubeShow = showName.takeIf { it.isNotBlank() }
                ?.let { withTimeoutOrNull(15_000L) { resolveCuratedPodcast(it) } }
            if (youtubeShow != null) {
                getShowEpisodesFlow(youtubeShow.id, youtubeShow.name).collect { emit(it) }
            } else emit(emptyList())
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
                    rememberEpisodes(showId, resolvedName, allEpisodes)
                    episodeMemoryCache[showId] = System.currentTimeMillis() to allEpisodes.toList()
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
                        rememberEpisodes(showId, resolvedName, newEpisodes)
                        episodeMemoryCache[showId] = System.currentTimeMillis() to allEpisodes.toList()
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
                rememberEpisodes(showId, resolvedName, eps)
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

    // Verified show metadata only. Never cache a fabricated card without a source id.
    private val showSearchCache = java.util.concurrent.ConcurrentHashMap<String, PodcastModel>()
    private const val VERIFIED_SHOW_CACHE_PREFS = "podcasts_youtube_only_v3"
    private const val VERIFIED_SHOW_TTL_MS = 6 * 60 * 60_000L

    private fun verifiedCachedShow(query: String): PodcastModel? {
        showSearchCache[query]?.let { return it }
        val prefs = MyApplication.instance.getSharedPreferences(VERIFIED_SHOW_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
        val stored = prefs.getString(query, null) ?: return null
        return runCatching {
            val obj = JSONObject(stored)
            val ageMs = System.currentTimeMillis() - obj.optLong("savedAt", 0L)
            if (ageMs < 0L || ageMs > VERIFIED_SHOW_TTL_MS) return@runCatching null
            val show = PodcastModel(
                id = obj.getString("id"),
                name = obj.getString("name"),
                publisher = obj.optString("publisher"),
                coverUri = obj.getString("cover")
            )
            if (show.coverUri.startsWith("http") &&
                (show.id.startsWith(YOUTUBE_PODCAST_PREFIX)) &&
                podcastNameScore(query, show.name) >= 80
            ) show.also { showSearchCache[query] = it } else null
        }.getOrNull()
    }

    private fun storeVerifiedShow(query: String, show: PodcastModel) {
        showSearchCache[query] = show
        MyApplication.instance.getSharedPreferences(VERIFIED_SHOW_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(query, JSONObject()
                .put("id", show.id)
                .put("name", show.name)
                .put("publisher", show.publisher)
                .put("cover", show.coverUri)
                .put("savedAt", System.currentTimeMillis())
                .toString())
            .apply()
    }

    fun getYouTubePodcastsFeedFlow(forceRefresh: Boolean = false): kotlinx.coroutines.flow.Flow<com.music.spotui.data.entity.PodcastFeedModel> = channelFlow {
        if (forceRefresh) showSearchCache.clear()
        val spanishPodcastCategories = listOf(
            "Podcasts populares en España" to listOf(
                "The Wild Project", "La Ruina", "Nadie Sabe Nada", "Terrores Nocturnos",
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
                "Línea de Cal", "El Partidazo de COPE", "El Larguero", "El Desmarque", "La Libreta de Van Gaal"
            ),
            "Negocios y Emprendimiento" to listOf(
                "Tengo un Plan", "Nude Project", "Itnig", "Libros para Emprendedores"
            )
        )

        val queries = spanishPodcastCategories.flatMap { it.second }.distinct()
        // Thread-safe snapshots. Query order, not network completion order, drives UI ordering.
        val resolved = java.util.concurrent.ConcurrentHashMap<String, PodcastModel>()
        if (!forceRefresh) {
            for (query in queries) verifiedCachedShow(query)?.let { resolved[query] = it }
        }

        fun snapshot(): com.music.spotui.data.entity.PodcastFeedModel {
            val sections = spanishPodcastCategories.map { (title, names) ->
                com.music.spotui.data.entity.PodcastSectionModel(
                    title = title,
                    shows = names.mapNotNull { resolved[it] }.distinctBy { it.id }
                )
            }
            return com.music.spotui.data.entity.PodcastFeedModel(sections = sections)
        }

        send(snapshot()) // Fast cached result, before network lookups.

        // All categories search concurrently; bounded concurrency avoids overloading APIs.
        val semaphore = Semaphore(6)
        coroutineScope {
            queries.filterNot { resolved.containsKey(it) }.map { query ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val show = withTimeoutOrNull(19_000L) { resolveCuratedPodcast(query) }
                            if (show != null && show.coverUri.startsWith("http") && show.id.startsWith(YOUTUBE_PODCAST_PREFIX)) {
                                resolved[query] = show
                                storeVerifiedShow(query, show)
                                send(snapshot()) // A podcast appears as soon as it is verified.
                            } else {
                                Log.w(TAG, "No verified, playable podcast card for '$query'")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Podcast category search failed: $query", e)
                        }
                    }
                }
            }.awaitAll()
        }
        send(snapshot())
    }

    suspend fun getYouTubePodcastsFeed(): com.music.spotui.data.entity.PodcastFeedModel {
        var last = com.music.spotui.data.entity.PodcastFeedModel()
        getYouTubePodcastsFeedFlow().collect { last = it }
        return last
    }
}