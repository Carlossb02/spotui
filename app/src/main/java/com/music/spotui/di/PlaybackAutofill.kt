package com.music.spotui.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.music.spotui.data.api.Api
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.providers.PodcastProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Background-only radio extension; strictly independent of music matching/audio resolution. */
internal object PlaybackAutofill {
    private const val TAG = "SpotuiAutofill"
    private val attempted = ConcurrentHashMap<String, Long>()

    /** Called only when the final queued item is currently playing. */
    suspend fun suggest(context: Context, seed: SongsModel, queue: List<SongsModel>): List<SongsModel> {
        val key = seed.url
        val now = SystemClock.elapsedRealtime()
        val previous = attempted[key]
        val retryWindow = if (seed.url.startsWith("yt_ep:")) 45_000L else 90_000L
        if (previous != null && now - previous < retryWindow) return emptyList()
        attempted[key] = now
        val known = queue.mapTo(HashSet()) { it.url.substringBefore('|') }
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(25_000L) {
                    if (seed.url.startsWith("yt_ep:") || seed.url.startsWith("episode:")) {
                        // Match by the show, never blindly by episode title.
                        val knownShow = PodcastProvider.knownShowForEpisode(seed.url)
                        // The exact show ID wins. A recent card may have only a
                        // creator/publisher string; that is NOT itself a show ID.
                        val candidates = listOfNotNull(knownShow?.second,
                            seed.album.takeUnless { it.isBlank() || it == "Podcast" },
                            seed.singer.takeIf { it.isNotBlank() }).distinct()
                        var show = knownShow?.let { (id, name) ->
                            com.music.spotui.data.entity.PodcastModel(
                                id = id, name = name, publisher = "", coverUri = "")
                        } ?: PodcastProvider.findShowByExactEpisode(seed.url, seed.title)
                        if (show == null) {
                            for (candidate in candidates) {
                                show = PodcastProvider.findShowForAutofill(candidate)
                                if (show != null) break
                            }
                        }
                        val verifiedShow = show ?: return@withTimeoutOrNull emptyList()
                        Log.i(TAG, "Podcast queue: show=${verifiedShow.id}, episode=${seed.url.substringBefore('|')}")
                        PodcastProvider.getShowEpisodesFlow(verifiedShow.id, verifiedShow.name)
                            .first { it.isNotEmpty() }
                            .filter { it.url.substringBefore('|') !in known }
                            .distinctBy { it.url.substringBefore('|') }
                            .take(8)
                    } else {
                        val spotifyId = seed.spotifyTrackId.takeIf { it.isNotBlank() }
                            ?: return@withTimeoutOrNull emptyList()
                        val results = Api(context.applicationContext).getRecommendations(listOf(spotifyId))
                            .filter { it.url.substringBefore('|') !in known && it.spotifyTrackId != spotifyId }
                            .distinctBy { it.spotifyTrackId.ifBlank { it.url } }
                        // Preserve recommendation ordering as far as possible while
                        // limiting same-artist streaks. Never use this for matching.
                        val selected = mutableListOf<SongsModel>()
                        val artistCount = HashMap<String, Int>()
                        results.forEach { song ->
                            val artist = song.singer.substringBefore(',').trim().lowercase()
                            if ((artistCount[artist] ?: 0) < 2 && selected.size < 12) {
                                artistCount[artist] = (artistCount[artist] ?: 0) + 1
                                selected.add(song)
                            }
                        }
                        selected
                    }
                }.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w(TAG, "Autofill unavailable; existing queue preserved", error)
                emptyList()
            }
        }
    }
}
