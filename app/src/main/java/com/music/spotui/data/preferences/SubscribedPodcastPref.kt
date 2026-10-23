package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.PodcastModel
import org.json.JSONArray
import org.json.JSONObject
import com.music.spotui.providers.PodcastProvider
import com.music.spotui.ui.notification.PodcastCover
import com.music.spotui.ui.notification.PlaybackArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

object SubscribedPodcastPref {
    private const val PREF_NAME = "SubscribedPodcasts"
    private const val KEY_PODCASTS = "subscribed_podcasts_json"

    private val _revision = MutableStateFlow(0L)
    /** Lets Compose redraw Following when a newly recovered cover is saved. */
    val revision: StateFlow<Long> = _revision
    private val coverLookupSemaphore = Semaphore(2)
    private val lastCoverLookup = ConcurrentHashMap<String, Long>()
    private const val REFRESH_COOLDOWN_MS = 90_000L

    fun getSubscribedPodcasts(context: Context): List<PodcastModel> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PODCASTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<PodcastModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    PodcastModel(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        publisher = obj.optString("publisher", ""),
                        coverUri = obj.optString("coverUri", "")
                    )
                )
            }
            list.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    /**
     * Recheck YouTube only when the followed show has no durable cover.
     * No Spotify APIs are involved. Search is bounded so opening Following
     * never waits for remote requests to finish.
     */
    suspend fun recoverMissingCover(context: Context, podcast: PodcastModel) {
        if (podcast.id.isBlank() || PodcastCover.hasSavedShowCover(context, podcast.id)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastCoverLookup[podcast.id]
        if (previous != null && now >= previous && now - previous < REFRESH_COOLDOWN_MS) return
        lastCoverLookup[podcast.id] = now

        coverLookupSemaphore.withPermit {
            if (PodcastCover.hasSavedShowCover(context, podcast.id)) return@withPermit
            val candidate = withContext(Dispatchers.IO) {
                withTimeoutOrNull(16_000L) {
                    val direct = PodcastProvider.getShow(podcast.id, podcast.name)
                    if (!direct?.coverUri.isNullOrBlank()) direct else {
                        // A valid YouTube show can occasionally be missing artwork
                        // in the browse response. Find its official podcast entry.
                        val matches = PodcastProvider.searchPodcasts(podcast.name).first
                        matches.firstOrNull { it.id == podcast.id && it.coverUri.isNotBlank() }
                            ?: matches.firstOrNull {
                                it.coverUri.isNotBlank() &&
                                    PodcastProvider.podcastNameScore(podcast.name, it.name) >= 90
                            }
                    }
                }
            }
            val cover = candidate?.coverUri?.takeIf { it.isNotBlank() }
                ?: podcast.coverUri.takeIf { it.isNotBlank() }
                ?: return@withPermit

            // Persist refreshed URL without reordering followed podcasts.
            if (candidate != null && candidate.coverUri.isNotBlank()) {
                updatePodcastDetails(
                    context, podcast.id, candidate.coverUri,
                    candidate.publisher.ifBlank { podcast.publisher }
                )
            }
            PlaybackArtwork.ensure(
                context.applicationContext,
                PodcastCover.showArtworkKey(podcast.id),
                cover
            )
        }
    }

    @Synchronized
    fun updatePodcastDetails(context: Context, id: String, coverUri: String, publisher: String = "") {
        if (id.isBlank() || coverUri.isBlank()) return
        val original = getSubscribedPodcasts(context)
        var changed = false
        val refreshed = original.map { item ->
            if (item.id != id) item else {
                val next = item.copy(
                    coverUri = coverUri,
                    publisher = publisher.ifBlank { item.publisher }
                )
                if (next != item) changed = true
                next
            }
        }
        if (changed) saveList(context, refreshed)
    }

    fun isPodcastSubscribed(context: Context, podcastId: String): Boolean {
        if (podcastId.isBlank()) return false
        val cleanId = podcastId.trim()
        return getSubscribedPodcasts(context).any { it.id.trim() == cleanId }
    }

    @Synchronized
    fun addSubscribedPodcast(context: Context, podcast: PodcastModel) {
        if (podcast.id.isBlank()) return
        val current = getSubscribedPodcasts(context).toMutableList()
        current.removeAll { it.id.trim() == podcast.id.trim() }
        current.add(0, podcast)
        saveList(context, current)
        if (podcast.coverUri.isNotBlank()) {
            // Saved independent of the streaming cache so Followed podcasts retain artwork.
            com.music.spotui.ui.notification.PlaybackArtwork.ensure(
                context.applicationContext, PodcastCover.showArtworkKey(podcast.id), podcast.coverUri
            )
        }
    }

    @Synchronized
    fun removeSubscribedPodcast(context: Context, podcastId: String) {
        if (podcastId.isBlank()) return
        val current = getSubscribedPodcasts(context).toMutableList()
        current.removeAll { it.id.trim() == podcastId.trim() }
        saveList(context, current)
    }

    fun toggleSubscribedPodcast(context: Context, podcast: PodcastModel): Boolean {
        return if (isPodcastSubscribed(context, podcast.id)) {
            removeSubscribedPodcast(context, podcast.id)
            false
        } else {
            addSubscribedPodcast(context, podcast)
            true
        }
    }

    @Synchronized
    fun saveSubscribedPodcasts(context: Context, podcasts: List<PodcastModel>) {
        saveList(context, podcasts)
    }

    private fun saveList(context: Context, podcasts: List<PodcastModel>) {
        val arr = JSONArray()
        podcasts.distinctBy { it.id.trim() }.forEach { podcast ->
            val obj = JSONObject().apply {
                put("id", podcast.id)
                put("name", podcast.name)
                put("publisher", podcast.publisher)
                put("coverUri", podcast.coverUri)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PODCASTS, arr.toString())
            .apply()
        _revision.value = _revision.value + 1L
    }
}
