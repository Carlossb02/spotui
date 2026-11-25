package com.music.spotui.providers

import com.music.spotui.MyApplication
import com.music.spotui.data.entity.PodcastModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Popular programmes in Spain and their manually selected YouTube feeds. */
internal object SpanishPodcastChart {
    data class Source(
        val channelId: String?,
        val playlistId: String?,
        val streams: Boolean,
        val titleContains: String?,
        val minSeconds: Long,
    )

    data class Entry(val show: PodcastModel, val category: String?, val source: Source?)

    private val entries by lazy {
        val rows = MyApplication.instance.assets.open("spanish_podcast_chart.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("shows") }
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val link = row.optString("youtube")
            val channel = channelLink.matchEntire(link)
            val playlist = playlistLink.matchEntire(link)
            Entry(
                PodcastModel(
                    // Keep the previous chart key so existing subscriptions still work.
                    id = "spotify:show:${row.getString("id")}",
                    name = row.getString("name"),
                    publisher = row.getString("publisher"),
                    coverUri = row.getString("artwork"),
                ),
                row.optString("category").takeIf(String::isNotBlank),
                if (channel != null || playlist != null) Source(
                    channelId = channel?.groupValues?.get(1),
                    playlistId = playlist?.groupValues?.get(1),
                    streams = channel?.groupValues?.get(2) == "streams",
                    titleContains = row.optString("episodeTitle").takeIf(String::isNotBlank),
                    minSeconds = row.optLong("minSeconds", if (playlist == null) 480 else 0),
                ) else null,
            )
        }
    }

    suspend fun search(query: String): List<PodcastModel> = withContext(Dispatchers.IO) {
        entries.asSequence().filter { it.source != null }.map(Entry::show)
            .filter { PodcastDiscoveryPolicy.relevantShow(query, it.name) }
            .sortedByDescending { PodcastDiscoveryPolicy.showTitleMatches(query, it.name) }
            .toList()
    }

    suspend fun find(id: String): Entry? = withContext(Dispatchers.IO) {
        entries.firstOrNull { it.show.id == id }
    }

    suspend fun homeSections(): Map<String, List<PodcastModel>> = withContext(Dispatchers.IO) {
        entries.filter { it.category != null && it.source != null }
            .groupBy({ checkNotNull(it.category) }, Entry::show)
    }

    private val channelLink = Regex("""https://www\.youtube\.com/channel/(UC[A-Za-z0-9_-]{22})/(videos|streams)""")
    private val playlistLink = Regex("""https://www\.youtube\.com/playlist\?list=(PL[A-Za-z0-9_-]{10,40})""")
}
