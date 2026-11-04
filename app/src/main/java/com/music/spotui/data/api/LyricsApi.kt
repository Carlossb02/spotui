package com.music.spotui.data.api

import android.util.Log
import com.music.spotui.MyApplication
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.LyricsCachePref
import com.music.spotui.lyrics.LyricsMatcher
import com.music.spotui.lyrics.LyricsText
import com.music.spotui.lyrics.ExtraLyricsSources
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Spotify identity first, then LRCLIB; KuGou and Lyrics.ovh extend missing coverage. */
object LyricsApi {
    private const val BASE = "https://lrclib.net/api"
    private const val UA = "SpotuiSpotifyClone (https://github.com/)"
    private class Miss(val at: Long = System.currentTimeMillis())
    private const val MISS_RETRY_MS = 120_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracks = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()

    fun registerTracks(songs: List<SongsModel>) {
        songs.filter { it.spotifyTrackId.matches(Regex("[A-Za-z0-9]{22}")) }
            .forEach { tracks[it.spotifyTrackId] = it }
    }

    private fun prefix(title: String, artist: String) =
        "lyrics-v4|${LyricsMatcher.normalize(title)}|${LyricsMatcher.normalize(artist)}|"

    private fun exactId(title: String, artist: String, album: String, durationSec: Int, trackId: String): String {
        if (trackId.matches(Regex("[A-Za-z0-9]{22}"))) return trackId
        val candidates = tracks.values.filter {
            LyricsMatcher.normalize(it.title) == LyricsMatcher.normalize(title) &&
                LyricsMatcher.normalize(it.singer) == LyricsMatcher.normalize(artist) &&
                (album.isBlank() || LyricsMatcher.normalize(it.album) == LyricsMatcher.normalize(album)) &&
                (durationSec <= 0 || it.durationMs <= 0 || kotlin.math.abs(it.durationMs / 1000 - durationSec) <= 2)
        }.map { it.spotifyTrackId }.distinct()
        return candidates.singleOrNull().orEmpty()
    }

    fun removeFromCache(title: String, artist: String) {
        val prefix = prefix(title, artist)
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        LyricsCachePref.removeMatchingPrefix(MyApplication.instance, prefix)
    }

    fun prefetch(title: String, artist: String, album: String, durationSec: Int = 0, trackId: String = "") {
        if (title.isNotBlank()) prefetchScope.launch { fetch(title, artist, album, durationSec, trackId) }
    }

    suspend fun fetch(title: String, artist: String, album: String, durationSec: Int, trackId: String = ""): Lyrics? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null
        val id = exactId(title, artist, album, durationSec, trackId)
        val key = prefix(title, artist) + "${LyricsMatcher.normalize(album)}|${durationSec.coerceAtLeast(0)}|$id"
        locks.getOrPut(key) { Mutex() }.withLock {
            when (val cached = cache[key]) {
                is Lyrics -> return@withLock cached
                is Miss -> if (System.currentTimeMillis() - cached.at < MISS_RETRY_MS) return@withLock null
            }
            val ctx = MyApplication.instance
            LyricsCachePref.get(ctx, key)?.let { cache[key] = it; return@withLock it }
            val track = LyricsMatcher.Track(title, artist, album, durationSec)
            val result = fromSpotify(id) ?: coroutineScope {
                val cleaned = LyricsMatcher.searchTitle(title)
                val primaryArtist = artist.substringBefore(",").trim()
                val exact = async { getExact(track, cleaned, primaryArtist) }
                val search = async { searchUrl("$BASE/search?track_name=${enc(cleaned)}&artist_name=${enc(primaryArtist)}", track) }
                val direct = exact.await()
                val found = search.await()
                // Score across both sources: a correct plain transcription wins over a weak synced one.
                listOfNotNull(direct, found).maxByOrNull { it.first }?.second
                    ?: searchUrl("$BASE/search?track_name=${enc(cleaned)}", track)?.second
            }
            val resolved = result ?: expandedFallback(track)
            cache[key] = resolved ?: Miss()
            if (resolved != null) LyricsCachePref.put(ctx, key, resolved)
            resolved
        }
    }

    private suspend fun fromSpotify(trackId: String): Lyrics? {
        if (trackId.isBlank()) return null
        return try {
            SpotifyTokenProvider.ensureToken(MyApplication.instance)
            com.metrolist.spotify.Spotify.lyrics(trackId).getOrNull()?.let { sp ->
                Lyrics(sp.lines.map { LyricLine(it.startMs, it.words) }, sp.synced).takeUnless { it.isEmpty }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.d("LyricsApi", "Spotify lyrics unavailable: ${failure.message}")
            null
        }
    }

    private suspend fun expandedFallback(track: LyricsMatcher.Track): Lyrics? = coroutineScope {
        val pending = mutableListOf(
            async { searchUrl("$BASE/search?q=${enc("${LyricsMatcher.plainQueryTitle(track.title)} ${track.artist}")}", track)?.second },
            async { ExtraLyricsSources.kuGou(track, ::httpGet) },
            async { ExtraLyricsSources.lyricsOvh(track, ::httpGet) },
        )
        try {
            while (pending.isNotEmpty()) {
                val (finished, lyrics) = kotlinx.coroutines.selects.select<Pair<Deferred<Lyrics?>, Lyrics?>> {
                    pending.forEach { task -> task.onAwait { task to it } }
                }
                pending.remove(finished)
                if (lyrics != null) return@coroutineScope lyrics
            }
            null
        } finally { pending.forEach { it.cancel() } }
    }

    private fun candidate(o: JSONObject, track: LyricsMatcher.Track): Pair<Double, Lyrics>? {
        val lyrics = parse(o) ?: return null
        val identity = LyricsMatcher.Candidate(
            title = o.optString("trackName"), artist = o.optString("artistName"),
            album = o.optString("albumName"), durationSec = o.optDouble("duration", 0.0).toInt(),
            synced = lyrics.synced,
        )
        val strict = LyricsMatcher.score(track, identity)
        val timestampsValid = !lyrics.synced || track.durationSec <= 0 ||
            lyrics.lines.all { it.timeMs <= track.durationSec * 1000L + 5_000L }
        if (strict != null && timestampsValid) return strict to lyrics
        val plainScore = LyricsMatcher.plainScore(track, identity) ?: return null
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?.let { LyricsText.plain(it) }
            ?: LyricsText.plain(lyrics.lines.joinToString("\n") { it.text }) ?: return null
        return (plainScore - 25) to plain
    }

    private fun getExact(track: LyricsMatcher.Track, title: String, artist: String): Pair<Double, Lyrics>? {
        val url = buildString {
            append("$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
            if (track.album.isNotBlank()) append("&album_name=${enc(track.album)}")
            if (track.durationSec > 0) append("&duration=${track.durationSec}")
        }
        val body = httpGet(url) ?: return null
        return runCatching { candidate(JSONObject(body), track) }.getOrNull()
    }

    private fun searchUrl(url: String, track: LyricsMatcher.Track): Pair<Double, Lyrics>? {
        val arr = httpGet(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { runCatching { candidate(it, track) }.getOrNull() }
        }.maxByOrNull { it.first }
    }

    private fun parse(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val lines = plain.split("\n").map { LyricLine(0L, it) }
        return Lyrics(lines, synced = false)
    }

    // LRC: each line is "[mm:ss.xx] text" (a line may carry multiple timestamps).
    private val lrcTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split("\n")) {
            val tags = lrcTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LyricLine(min * 60_000 + sec * 1_000 + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(spec: String): String? = runCatching {
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) {
                Log.d("LyricsApi", "HTTP ${conn.responseCode} for $spec")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrElse { Log.e("LyricsApi", "request failed", it); null }
}
