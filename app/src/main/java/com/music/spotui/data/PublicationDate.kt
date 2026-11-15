package com.music.spotui.data

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Unknown dates stay unknown; prose and year-only labels are never recent releases. */
internal fun publicationTime(value: String, observedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val raw = value.trim().replace(Regex("(?i)\\s+de\\s+"), " ")
    if (raw.isBlank() || raw.length > 48) return null
    runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
    val now = Instant.ofEpochMilli(observedAt).atZone(zone)
    when (raw.lowercase(Locale.ROOT)) {
        "hoy", "today" -> return now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        "ayer", "yesterday" -> return now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val relative = Regex("(?i)^(?:(?:publicad[oa] )?hace (\\d+) ([\\p{L}]+)|(\\d+) ([a-z]+) ago)$").matchEntire(raw)
    if (relative != null) {
        val count = (relative.groupValues[1].ifBlank { relative.groupValues[3] }).toLongOrNull() ?: return null
        val unit = relative.groupValues[2].ifBlank { relative.groupValues[4] }.lowercase(Locale.ROOT)
        val time = when {
            unit.startsWith("seg") || unit.startsWith("second") -> now.minusSeconds(count)
            unit.startsWith("min") -> now.minusMinutes(count)
            unit.startsWith("hora") || unit.startsWith("hour") -> now.minusHours(count)
            unit.startsWith("día") || unit.startsWith("dia") || unit.startsWith("day") -> now.minusDays(count)
            unit.startsWith("semana") || unit.startsWith("week") -> now.minusWeeks(count)
            unit.startsWith("mes") || unit.startsWith("month") -> now.minusMonths(count)
            unit.startsWith("año") || unit.startsWith("ano") || unit.startsWith("year") -> now.minusYears(count)
            else -> return null
        }
        return time.toInstant().toEpochMilli()
    }
    for (locale in listOf(Locale.forLanguageTag("es"), Locale.ENGLISH)) {
        for (pattern in listOf("yyyy-MM-dd", "yyyy/M/d", "d MMM yyyy", "d MMMM yyyy", "MMM d, yyyy", "MMMM d, yyyy", "MMM d yyyy")) {
            runCatching { LocalDate.parse(raw, DateTimeFormatter.ofPattern(pattern, locale)).atStartOfDay(zone).toInstant().toEpochMilli() }
                .getOrNull()?.let { return it }
        }
    }
    return null
}

internal fun withinLastMonth(publishedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    publishedAt in Instant.ofEpochMilli(now).atZone(zone).minusMonths(1).toInstant().toEpochMilli()..now

internal fun hasUnreadReleases(publications: List<Long>, checkedAt: Long): Boolean =
    publications.any { it > checkedAt }

internal fun stablePublicationTime(previous:Long, label:String, observedAt:Long):Long? =
    previous.takeIf {it>0} ?: publicationTime(label,observedAt)
