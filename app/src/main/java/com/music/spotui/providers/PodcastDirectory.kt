package com.music.spotui.providers

import android.net.Uri
import com.music.spotui.data.entity.PodcastModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Search fallback for programmes missing from the Spotify response. */
internal object PodcastDirectory {
    private const val PREFIX = "directory_podcast:"

    suspend fun search(query: String): List<PodcastModel> = runInterruptible(Dispatchers.IO) {
        val terms = listOfNotNull(query, PodcastDiscoveryPolicy.broaderQuery(query)).distinct()
        val found = linkedMapOf<String, PodcastModel>()
        for (term in terms) {
            val address = "https://itunes.apple.com/search?term=${Uri.encode(term)}&country=ES&media=podcast&entity=podcast&limit=15"
            val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_500
                readTimeout = 3_500
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) continue
                val rows = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).optJSONArray("results") }
                for (index in 0 until (rows?.length() ?: 0)) {
                    val row = rows?.optJSONObject(index) ?: continue
                    if (row.optString("kind") != "podcast") continue
                    val title = row.optString("collectionName").trim()
                    val publisher = row.optString("artistName").trim()
                    val directoryId = row.optLong("collectionId")
                    if (directoryId <= 0 || publisher.isBlank() ||
                        !PodcastDiscoveryPolicy.relevantShow(query, title)) continue
                    val id = "$PREFIX$directoryId:${Uri.encode(title)}:${Uri.encode(publisher)}"
                    found.putIfAbsent(directoryId.toString(), PodcastModel(id, title, publisher, ""))
                }
            } catch (_: java.io.IOException) {
                // The other spelling can still succeed.
            } finally {
                connection.disconnect()
            }
            if (found.isNotEmpty()) break
        }
        found.values.toList().sortedByDescending { PodcastDiscoveryPolicy.showTitleMatches(query, it.name) }
    }

    fun show(id: String): PodcastModel? {
        if (!id.startsWith(PREFIX)) return null
        val fields = id.removePrefix(PREFIX).split(':', limit = 3)
        if (fields.size != 3 || fields[0].toLongOrNull()?.let { it > 0 } != true) return null
        val title = Uri.decode(fields[1])
        val publisher = Uri.decode(fields[2])
        return if (title.isBlank() || publisher.isBlank()) null else PodcastModel(id, title, publisher, "")
    }
}
