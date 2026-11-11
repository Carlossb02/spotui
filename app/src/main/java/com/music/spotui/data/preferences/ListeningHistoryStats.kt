package com.music.spotui.data.preferences

internal enum class HistoryKind { ALL, MUSIC, PODCASTS }
/** Use recorded origin first; recognize older episode URLs without guessing from titles. */
internal fun HistoryEntry.isPodcast(): Boolean = when (mediaType) {
    "podcast" -> true
    "music" -> false
    else -> isHistoryEpisodeSource(url) || isHistoryEpisodeSource(spotifyTrackId)
}
internal fun isHistoryEpisodeSource(source: String): Boolean {
    val value = source.trim().substringBefore('|').lowercase(java.util.Locale.ROOT)
    return value.startsWith("yt_ep:") || value.startsWith("episode:") ||
        value.startsWith("spotify:episode:") ||
        Regex("^https?://open\\.spotify\\.com/(?:intl-[^/]+/)?episode/").containsMatchIn(value)
}
internal fun HistoryEntry.historySpotifyId(): String = spotifyTrackId.ifBlank {
    if (url.startsWith("spotify:track:")) url.removePrefix("spotify:track:").substringBefore('|') else ""
}.removePrefix("spotify:track:").removePrefix("track:").substringBefore('?').trim()
internal fun HistoryEntry.recordingKey(): String = historySpotifyId().takeIf { it.isNotBlank() }?.let { "spotify:$it" }
    ?: url.substringBefore('|').ifBlank { "$songId:$title:$singer" }
internal data class HistoryRanking(val name: String, val entries: List<HistoryEntry>) {
    val plays get() = entries.sumOf { it.playCount }
    val latest get() = entries.maxBy { it.ts }
}
/** Historical counts and new engine-counted sessions share the same period summaries. */
internal class ListeningHistoryStats(val entries: List<HistoryEntry>) {
    val music = entries.filterNot { it.isPodcast() }
    val podcasts = entries.filter { it.isPodcast() }
    val musicPlays = music.sumOf { it.playCount }
    val podcastPlays = podcasts.sumOf { it.playCount }
    val legacyPlays = entries.filterNot { it.qualified }.sumOf { it.playCount }
    private val knownDurations = music.filter { it.durationMs > 0 }.associate { it.recordingKey() to it.durationMs }
    val musicMinutes = music.sumOf { (it.durationMs.takeIf { ms -> ms > 0 } ?: knownDurations[it.recordingKey()] ?: 0L) * it.playCount } / 60_000L
    val missingDurations = music.filter { it.durationMs <= 0L }.sumOf { it.playCount }
    val artists = music.groupBy { it.singer.substringBefore(',').trim() }.ranked()
    val tracks = music.groupBy { it.recordingKey() }.values.map { HistoryRanking(it.first().title, it) }.sortedByDescending { it.plays }.take(5)
    val shows = podcasts.groupBy { it.album.ifBlank { it.singer }.ifBlank { "Podcast" } }.ranked()
    fun filtered(kind: HistoryKind) = when (kind) { HistoryKind.ALL -> entries; HistoryKind.MUSIC -> entries.filterNot { it.isPodcast() }; HistoryKind.PODCASTS -> entries.filter { it.isPodcast() } }
    private fun Map<String, List<HistoryEntry>>.ranked() = filterKeys { it.isNotBlank() }.map { HistoryRanking(it.key, it.value) }.sortedByDescending { it.plays }.take(5)
}
