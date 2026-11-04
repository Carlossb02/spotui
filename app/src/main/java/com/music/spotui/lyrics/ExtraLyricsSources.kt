package com.music.spotui.lyrics

import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.URLEncoder

/** Keyless last-resort providers; every usable result retains its sync provenance. */
internal object ExtraLyricsSources {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun lyricsOvh(track: LyricsMatcher.Track, get: (String) -> String?): Lyrics? {
        val titles = listOf(LyricsMatcher.searchTitle(track.title), LyricsMatcher.plainQueryTitle(track.title)).distinct()
        for (title in titles) {
            val body = get("https://api.lyrics.ovh/v1/${enc(track.artist)}/${enc(title)}") ?: continue
            val text = runCatching { JSONObject(body).optString("lyrics") }.getOrNull() ?: continue
            LyricsText.plain(text)?.let { return it }
        }
        return null
    }

    suspend fun kuGou(track: LyricsMatcher.Track, get: (String) -> String?): Lyrics? {
        val keyword = "${LyricsMatcher.searchTitle(track.title)} ${track.artist}"
        val body = get("https://krcs.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(keyword)}&duration=${track.durationSec.coerceAtLeast(0).toLong() * 1000}") ?: return null
        val candidates = runCatching { JSONObject(body).optJSONArray("candidates") }.getOrNull() ?: return null
        val matches = (0 until candidates.length()).mapNotNull { i ->
            val o = candidates.optJSONObject(i) ?: return@mapNotNull null
            val candidate = LyricsMatcher.Candidate(
                o.optString("song").ifBlank { o.optString("songname") },
                o.optString("singer").ifBlank { o.optString("singername") },
                "", (o.optLong("duration", 0L) / 1000).toInt(), true,
            )
            val strict = LyricsMatcher.score(track, candidate)
            val plain = LyricsMatcher.plainScore(track, candidate) ?: return@mapNotNull null
            Triple(o, strict != null, (strict ?: plain) + if (strict != null) 100 else 0)
        }.sortedByDescending { it.third }.take(3)
        for ((item, timingMatches, _) in matches) {
            currentCoroutineContext().ensureActive()
            val id = item.optString("id"); val key = item.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue
            val payload = get("https://krcs.kugou.com/download?ver=1&client=pc&id=${enc(id)}&accesskey=${enc(key)}&fmt=lrc&charset=utf8") ?: continue
            val text = runCatching {
                val encoded = JSONObject(payload).getString("content")
                String(java.util.Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrNull() ?: continue
            val synced = LyricsText.synced(text)
            if (timingMatches && synced != null && (track.durationSec <= 0 ||
                    synced.lines.all { it.timeMs <= track.durationSec * 1000L + 5_000 })) return synced
            LyricsText.plain(text)?.let { return it }
        }
        return null
    }
}
