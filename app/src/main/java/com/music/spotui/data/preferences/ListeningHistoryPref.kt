package com.music.spotui.data.preferences

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

/** Runtime projection. Disk stores one metadata record and counters per track/month, not a play log. */
data class HistoryEntry(
    val ts: Long, val songId: Int, val title: String, val singer: String,
    val album: String = "", val image: String = "", val url: String = "",
    val durationMs: Long = 0L, val spotifyTrackId: String = "",
    val qualified: Boolean = false, val eventId: String = "", val playCount: Int = 1,
    val mediaType: String = "",
)
private val _listeningHistoryVersion = MutableStateFlow(0L)
private val _deviceListeningHistoryVersion = MutableStateFlow(0L)
internal val deviceListeningHistoryVersion = _deviceListeningHistoryVersion.asStateFlow()
private fun deviceHistoryChanged() {
    _deviceListeningHistoryVersion.update { it + 1L }
    _listeningHistoryVersion.update { it + 1L }
}
val listeningHistoryVersion = _listeningHistoryVersion.asStateFlow()
internal val historyClearGeneration = java.util.concurrent.atomic.AtomicLong(0L)
private val historyLock = Any()
private data class Bucket(val track: HistoryEntry, val plays: Int = 0, val legacy: Int = 0,
    val qualifiedAt: Long = 0L, val legacyAt: Long = 0L, val lastEvent: String = "")
private data class Month(val buckets: List<Bucket> = emptyList(), val legacyImported: Boolean = false)
private fun directory(context: Context) = File(context.filesDir, "listening_history_v2").apply { mkdirs() }
private fun monthOf(ts: Long) = YearMonth.from(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())).toString()
internal fun HistoryEntry.storageKey() = "${monthOf(ts)}:${recordingKey()}:$qualified"
private fun metadata(o: JSONObject) = HistoryEntry(o.optLong("ts"), o.optInt("songId", -1), o.optString("title"),
    o.optString("singer"), o.optString("album"), o.optString("image"), o.optString("url"),
    o.optLong("durationMs"), o.optString("spotifyTrackId"), mediaType = o.optString("mediaType"))
private fun read(file: File): Month {
    if (!file.exists() && !File(file.path + ".bak").exists()) return Month()
    val root = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    val a = root.getJSONArray("tracks")
    return Month((0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Bucket(metadata(o), o.optInt("plays"), o.optInt("legacy"), o.optLong("qualifiedAt"), o.optLong("legacyAt"), o.optString("lastEvent"))
    }.groupBy { it.track.recordingKey() }.values.map { group ->
        val latest = group.maxBy { it.track.ts }
        val duration = group.firstOrNull { it.track.durationMs > 0 }?.track?.durationMs ?: 0L
        latest.copy(track = latest.track.copy(durationMs = duration, spotifyTrackId = latest.track.historySpotifyId()),
            plays = group.sumOf { it.plays }, legacy = group.sumOf { it.legacy },
            qualifiedAt = group.maxOf { it.qualifiedAt }, legacyAt = group.maxOf { it.legacyAt },
            lastEvent = group.maxBy { it.qualifiedAt }.lastEvent)
    }, root.optBoolean("legacyImported"))
}
private fun write(file: File, month: Month) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        val a = JSONArray()
        month.buckets.forEach { b -> val e = b.track
            a.put(JSONObject().put("ts", e.ts).put("songId", e.songId).put("title", e.title).put("singer", e.singer)
                .put("album", e.album).put("image", e.image).put("url", e.url).put("durationMs", e.durationMs)
                .put("spotifyTrackId", e.spotifyTrackId).put("mediaType", e.mediaType).put("plays", b.plays).put("legacy", b.legacy)
                .put("qualifiedAt", b.qualifiedAt).put("legacyAt", b.legacyAt).put("lastEvent", b.lastEvent))
        }
        stream.write(JSONObject().put("tracks", a).put("legacyImported", month.legacyImported).toString().toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
}
/** Import old starts once per month. They remain visible, but cannot verify 30 seconds retrospectively. */
private fun migrate(context: Context) {
    val prefs = context.getSharedPreferences("ListeningHistory", Context.MODE_PRIVATE)
    val raw = prefs.getString("entries", null) ?: return
    val a = JSONArray(raw)
    val old = (0 until a.length()).map { metadata(a.getJSONObject(it)) }
    old.groupBy { monthOf(it.ts) }.forEach { (month, entries) ->
        val file = File(directory(context), "$month.json")
        val current = read(file)
        if (!current.legacyImported) {
            val buckets = current.buckets.associateBy { it.track.recordingKey() }.toMutableMap()
            entries.groupBy { it.recordingKey() }.forEach { (key, rows) ->
                val latest = rows.maxBy { it.ts }
                val bucket = buckets[key] ?: Bucket(latest)
                buckets[key] = bucket.copy(legacy = bucket.legacy + rows.size, legacyAt = maxOf(bucket.legacyAt, latest.ts))
            }
            write(file, Month(buckets.values.toList(), legacyImported = true))
        }
    }
    prefs.edit().remove("entries").apply()
}
private fun files(context: Context): List<File> = directory(context).listFiles().orEmpty()
    .map { if (it.name.endsWith(".json.bak")) File(it.path.removeSuffix(".bak")) else it }
    .filter { it.name.matches(Regex("\\d{4}-\\d{2}\\.json")) }.distinctBy { it.name }.sortedByDescending { it.name }
private fun prepare(context: Context) {
    runCatching { migrate(context) }.onFailure { Log.w("ListeningHistory", "History migration deferred", it) }
}
fun listeningHistoryMonths(context: Context): List<YearMonth> = synchronized(historyLock) {
    prepare(context)
    (files(context).mapNotNull { runCatching { YearMonth.parse(it.nameWithoutExtension) }.getOrNull() } +
        com.music.spotui.sync.CloudHistory.projection.orEmpty().map { YearMonth.from(Instant.ofEpochMilli(it.ts).atZone(ZoneId.systemDefault())) }).distinct().sortedDescending()
}
/** One row per song/month; no timestamps for individual plays, video files, or audio are stored here. */
internal fun getDeviceListeningHistory(context: Context, year: Int? = null, month: Int? = null, limit: Int = 500): List<HistoryEntry> = synchronized(historyLock) {
    prepare(context)
    val result = mutableListOf<HistoryEntry>()
    for (file in files(context)) {
        val period = YearMonth.parse(file.nameWithoutExtension)
        if (year != null && period.year != year || month != null && period.monthValue != month) continue
        val stored = runCatching { read(file) }.onFailure { Log.w("ListeningHistory", "Could not read history month", it) }.getOrNull() ?: continue
        val rows = stored.buckets.flatMap { b -> listOfNotNull(
            b.track.copy(ts = b.qualifiedAt, qualified = true, playCount = b.plays).takeIf { b.plays > 0 },
            b.track.copy(ts = b.legacyAt, qualified = false, playCount = b.legacy).takeIf { b.legacy > 0 }) }
        result += rows.sortedByDescending { it.ts }
        if (result.size >= limit) break
    }
    val durations = context.getSharedPreferences("spotui_duration_cache", Context.MODE_PRIVATE)
    result.sortedByDescending { it.ts }.take(limit).map { entry ->
        val id = entry.historySpotifyId()
        entry.copy(spotifyTrackId = id, durationMs = entry.durationMs.takeIf { it > 0 }
            ?: id.takeIf { it.isNotBlank() }?.let { durations.getInt(it, 0).toLong().takeIf { ms -> ms > 0 } } ?: 0L)
    }
}
fun getListeningHistory(context: Context, year: Int? = null, month: Int? = null, limit: Int = 500): List<HistoryEntry> {
    val synced = com.music.spotui.sync.CloudHistory.projection ?: return getDeviceListeningHistory(context, year, month, limit)
    return synced.asSequence().filter { e ->
        val period = YearMonth.from(Instant.ofEpochMilli(e.ts).atZone(ZoneId.systemDefault()))
        (year == null || period.year == year) && (month == null || period.monthValue == month)
    }.take(limit).toList()
}
internal fun notifySyncedHistoryChanged() { _listeningHistoryVersion.update { it + 1L } }
internal fun storeQualifiedListening(context: Context, entry: HistoryEntry, expectedClearGeneration: Long) = synchronized(historyLock) {
    if (!entry.qualified || entry.title.isBlank() || entry.eventId.isBlank() || expectedClearGeneration != historyClearGeneration.get()) return@synchronized
    migrate(context)
    val file = File(directory(context), "${monthOf(entry.ts)}.json")
    val month = read(file)
    val buckets = month.buckets.associateBy { it.track.recordingKey() }.toMutableMap()
    val key = entry.recordingKey()
    val old = buckets[key] ?: Bucket(entry)
    buckets[key] = old.copy(track = entry.copy(durationMs = entry.durationMs.takeIf { it > 0 } ?: old.track.durationMs),
        plays = old.plays + if (old.lastEvent == entry.eventId) 0 else 1,
        qualifiedAt = maxOf(old.qualifiedAt, entry.ts), lastEvent = entry.eventId)
    write(file, month.copy(buckets = buckets.values.toList()))
    deviceHistoryChanged()
}
fun removeListeningHistory(context: Context, entry: HistoryEntry) = synchronized(historyLock) {
    com.music.spotui.sync.CloudHistory.requestDelete(context, entry)
    migrate(context)
    val file = File(directory(context), "${monthOf(entry.ts)}.json")
    val month = read(file)
    val updated = month.buckets.map { b -> if (b.track.recordingKey() != entry.recordingKey()) b
        else if (entry.qualified) b.copy(plays = 0) else b.copy(legacy = 0) }.filter { it.plays > 0 || it.legacy > 0 }
    write(file, month.copy(buckets = updated))
    deviceHistoryChanged()
}
fun clearListeningHistory(context: Context) = synchronized(historyLock) {
    com.music.spotui.sync.CloudHistory.requestDelete(context)
    historyClearGeneration.incrementAndGet()
    directory(context).listFiles().orEmpty().forEach { check(!it.exists() || it.delete()) { "Could not clear listening history" } }
    context.getSharedPreferences("ListeningHistory", Context.MODE_PRIVATE).edit().remove("entries").apply()
    deviceHistoryChanged()
}
