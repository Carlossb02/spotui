package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One source of truth for resumable episodes, shared by the service and every Compose screen.
 * Positions are sampled from the playing audio engine, never from UI lifecycle callbacks.
 */
data class PodcastResumeEntry(
    val episode: SongsModel,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0L)
        (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
}

object PodcastResumeStore {
    private const val PREFS = "spotui_podcast_resume_v1"
    private const val KEY = "episodes"
    private const val SEEN_KEY = "manually_seen"
    private var manuallySeen: Map<String, Long> = emptyMap()
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    private val lock = Any()
    private var loaded = false
    private var lastDiskWrite = 0L
    private var dirty = false
    private val _entries = MutableStateFlow<List<PodcastResumeEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    // Membership/order is independent of playback progress. Collecting this in the
    // carousel never invalidates its LazyRow for each 1-second position sample.
    private val _visibleKeys = MutableStateFlow<List<String>>(emptyList())
    val visibleKeys = _visibleKeys.asStateFlow()
    private val entryFlows = mutableMapOf<String, MutableStateFlow<PodcastResumeEntry?>>()

    fun observeEpisode(url: String): kotlinx.coroutines.flow.StateFlow<PodcastResumeEntry?> = synchronized(lock) {
        val key = episodeKey(url)
        entryFlows.getOrPut(key) {
            MutableStateFlow(_entries.value.firstOrNull { episodeKey(it.episode.url) == key })
        }.asStateFlow()
    }

    private fun publish(next: List<PodcastResumeEntry>) {
        _entries.value = next
        val keys = next.map { episodeKey(it.episode.url) }
        if (_visibleKeys.value != keys) _visibleKeys.value = keys
        entryFlows.forEach { (key, flow) ->
            val value = next.firstOrNull { episodeKey(it.episode.url) == key }
            if (flow.value != value) flow.value = value
        }
    }

    fun isEpisode(url: String) = url.startsWith("yt_ep:") || url.startsWith("episode:")
    fun episodeKey(url: String) = if (url.startsWith("yt_ep:")) url.substringBefore('|') else url

    private fun finished(position: Long, duration: Long): Boolean =
        duration > 0L && position >= 0L &&
            (position.toDouble() / duration >= 0.99 || duration - position < 60_000L)

    fun ensureLoaded(context: Context) = synchronized(lock) {
        if (loaded) return@synchronized
        val array = runCatching {
            JSONArray(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seenArray = runCatching { JSONArray(prefs.getString(SEEN_KEY, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        manuallySeen = (0 until seenArray.length()).mapNotNull { i ->
            runCatching {
                val item = seenArray.getJSONObject(i)
                item.getString("key") to item.getLong("at")
            }.getOrNull()
        }.filter { it.second in (now - RETENTION_MS)..now }.toMap()
        val restored = (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val s = SongsModel(
                    id = o.getInt("id"), title = o.getString("title"),
                    album = o.optString("album"), singer = o.optString("singer"),
                    coverUri = o.optString("cover"), url = o.getString("url"),
                    durationMs = o.optInt("episodeDuration", 0),
                    description = o.optString("description"),
                    releaseDate = o.optString("releaseDate")
                )
                PodcastResumeEntry(s, o.getLong("position"), o.getLong("duration"), o.getLong("updated"))
            }.getOrNull()
        }
        publish(restored.filter { isEpisode(it.episode.url) &&
            episodeKey(it.episode.url) !in manuallySeen &&
            it.lastPlayedAt in (now - RETENTION_MS)..now &&
            it.positionMs > 0 && !finished(it.positionMs, it.durationMs) }
            .distinctBy { episodeKey(it.episode.url) }.sortedByDescending { it.lastPlayedAt })
        loaded = true
    }

    fun record(context: Context, episode: SongsModel, positionMs: Long, durationMs: Long, playing: Boolean) {
        if (!isEpisode(episode.url) || positionMs < 0L) return
        synchronized(lock) {
            ensureLoaded(context)
            if (episodeKey(episode.url) in manuallySeen) return
            val now = System.currentTimeMillis()
            val old = _entries.value.firstOrNull { episodeKey(it.episode.url) == episodeKey(episode.url) }
            val duration = durationMs.takeIf { it > 0 } ?: episode.durationMs.toLong().takeIf { it > 0 }
                ?: old?.durationMs ?: return
            // Do not add an episode that has never started or refresh its age while paused.
            val moved = old == null || kotlin.math.abs(positionMs - old.positionMs) >= 500L
            if ((!playing && !moved) || (positionMs == 0L && old == null)) return
            val remaining = _entries.value.filter { now - it.lastPlayedAt < RETENTION_MS && episodeKey(it.episode.url) != episodeKey(episode.url) }
            val next = if (finished(positionMs, duration)) remaining else {
                val entry = PodcastResumeEntry(episode, positionMs, duration, if (moved) now else old?.lastPlayedAt ?: now)
                (listOf(entry) + remaining).sortedByDescending { it.lastPlayedAt }
            }
            if (next != _entries.value) {
                publish(next)
                dirty = true
            }
            if (dirty && (finished(positionMs, duration) || now - lastDiskWrite >= 5_000L)) persist(context, now)
        }
    }

    /** Explicit user action. Keep a persisted tombstone to avoid the currently playing
     *  service re-adding the episode during the next progress update. */
    fun markAsSeen(context: Context, episodeUrl: String) = synchronized(lock) {
        ensureLoaded(context)
        val key = episodeKey(episodeUrl)
        val now = System.currentTimeMillis()
        manuallySeen = (manuallySeen + (key to now))
            .filterValues { now - it < RETENTION_MS }
        publish(_entries.value.filter { episodeKey(it.episode.url) != key })
        dirty = true
        persist(context, now)
    }

    /** A manual 'seen' mark applies to the CURRENT listening session, not forever.
     * A new explicit start of the same recording allows it back into Continue
     * Listening; retries/rebuffering must NOT call this method. */
    fun beginNewListening(context: Context, episodeUrl: String) = synchronized(lock) {
        if (!isEpisode(episodeUrl)) return@synchronized
        ensureLoaded(context)
        if (episodeKey(episodeUrl) in manuallySeen) {
            manuallySeen = manuallySeen - episodeKey(episodeUrl)
            dirty = true
            persist(context, System.currentTimeMillis())
        }
    }

    /** Flush a last seek/position on track changes or service shutdown. */
    fun flush(context: Context) = synchronized(lock) {
        if (dirty) persist(context, System.currentTimeMillis())
    }

    private fun persist(context: Context, now: Long) {
        val json = JSONArray()
        _entries.value.forEach { e ->
            json.put(JSONObject().apply {
                put("id", e.episode.id); put("title", e.episode.title)
                put("album", e.episode.album); put("singer", e.episode.singer)
                put("cover", e.episode.coverUri); put("url", e.episode.url)
                put("episodeDuration", e.episode.durationMs)
                put("description", e.episode.description); put("releaseDate", e.episode.releaseDate)
                put("position", e.positionMs); put("duration", e.durationMs)
                put("updated", e.lastPlayedAt)
            })
        }
        val seen = JSONArray()
        manuallySeen.forEach { (key, time) ->
            seen.put(JSONObject().put("key", key).put("at", time))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString())
            .putString(SEEN_KEY, seen.toString()).apply()
        lastDiskWrite = now
        dirty = false
    }
}
