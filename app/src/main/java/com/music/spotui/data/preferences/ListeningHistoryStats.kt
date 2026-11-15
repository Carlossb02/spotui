package com.music.spotui.data.preferences

internal enum class HistoryKind { ALL, MUSIC, PODCASTS }
/** An exact episode source overrides a stale music label in legacy/synced records. */
internal fun HistoryEntry.isPodcast(): Boolean =
    isHistoryEpisodeSource(url) || isHistoryEpisodeSource(spotifyTrackId) || mediaType.trim().equals("podcast", true)
internal fun isHistoryEpisodeSource(source: String): Boolean {
    val value = source.trim().substringBefore('|').lowercase(java.util.Locale.ROOT)
    return value.startsWith("yt_ep:") || value.startsWith("episode:") ||
        value.startsWith("spotify:episode:") ||
        Regex("^https?://open\\.spotify\\.com/(?:intl-[^/]+/)?(?:embed/)?episode/").containsMatchIn(value)
}

/** Join only exact IDs already identified as episodes, never names, duration or titles. */
internal fun repairLegacyPodcastHistory(entries: List<HistoryEntry>, knownEpisodeSources: Set<String>): List<HistoryEntry> {
    val known = knownEpisodeSources.mapNotNull(::historyMediaIdentity).toSet()
    return entries.map { entry ->
        if (entry.isPodcast() || listOf(entry.url, entry.spotifyTrackId).any { historyMediaIdentity(it)?.let(known::contains) == true })
            entry.copy(mediaType = "podcast") else entry
    }
}

private fun historyMediaIdentity(source: String): String? {
    val value = source.trim().substringBefore('|')
    val youtube = when {
        value.startsWith("yt_ep:") -> value.removePrefix("yt_ep:")
        value.matches(Regex("[A-Za-z0-9_-]{11}")) -> value
        else -> Regex("^https?://(?:www\\.|music\\.)?(?:youtube\\.com/watch\\?(?:[^#]*&)?v=|youtu\\.be/)([A-Za-z0-9_-]{11})(?:[?&#/].*)?$")
            .matchEntire(value)?.groupValues?.get(1)
    }
    if (!youtube.isNullOrBlank()) return "youtube:$youtube"
    val spotify = when {
        value.startsWith("spotify:episode:") -> value.removePrefix("spotify:episode:")
        value.startsWith("episode:") -> value.removePrefix("episode:")
        value.matches(Regex("[A-Za-z0-9]{22}")) -> value
        else -> Regex("^https?://open\\.spotify\\.com/(?:intl-[^/]+/)?(?:embed/)?episode/([A-Za-z0-9]{22})(?:[?/#].*)?$")
            .matchEntire(value)?.groupValues?.get(1)
    }
    return spotify?.takeIf { it.isNotBlank() }?.let { "spotify:$it" }
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
    fun filtered(kind: HistoryKind) = when (kind) { HistoryKind.ALL -> entries; HistoryKind.MUSIC -> music; HistoryKind.PODCASTS -> podcasts }
    private fun Map<String, List<HistoryEntry>>.ranked() = filterKeys { it.isNotBlank() }.map { HistoryRanking(it.key, it.value) }.sortedByDescending { it.plays }.take(5)
}
