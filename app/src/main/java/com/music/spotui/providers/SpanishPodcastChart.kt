package com.music.spotui.providers

import com.metrolist.spotify.models.SpotifyImage
import com.metrolist.spotify.models.SpotifyShow
import com.music.spotui.MyApplication
import com.music.spotui.data.entity.PodcastModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bundled Spotify Spain chart, in rank order. Only the top 30 have a home category. */
internal object SpanishPodcastChart {
    private data class Entry(val show: SpotifyShow, val category: String?)

    private val entries by lazy {
        val rows = MyApplication.instance.assets.open("spanish_podcast_chart.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONArray("shows") }
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            Entry(
                SpotifyShow(
                    id = id,
                    uri = "spotify:show:$id",
                    name = row.getString("name"),
                    publisher = row.getString("publisher"),
                    images = listOf(SpotifyImage(row.getString("artwork"))),
                ),
                row.optString("category").takeIf(String::isNotBlank),
            )
        }
    }

    suspend fun search(query: String): List<PodcastModel> = withContext(Dispatchers.IO) {
        entries.asSequence().map(Entry::show)
            .filter { PodcastDiscoveryPolicy.relevantShow(query, it.name) }
            .sortedByDescending { PodcastDiscoveryPolicy.showTitleMatches(query, it.name) }
            .map(SpotifyPodcastEpisodes::asModel).toList()
    }

    suspend fun show(id: String): SpotifyShow? = withContext(Dispatchers.IO) {
        entries.firstOrNull { it.show.id == id }?.show
    }

    suspend fun homeSections(): Map<String, List<PodcastModel>> = withContext(Dispatchers.IO) {
        entries.filter { it.category != null }
            .groupBy({ checkNotNull(it.category) }, { SpotifyPodcastEpisodes.asModel(it.show) })
    }
}
