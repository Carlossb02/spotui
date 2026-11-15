package com.music.spotui.data

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Unknown dates stay unknown; prose and year-only labels are never recent releases. */
internal fun publicationTime(value: String, observedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val raw = value.replace('\u00a0',' ').trim().replace(Regex("\\s+")," ")
        .replace(Regex("(?i)\\s+de\\s+"), " ")
    if (raw.isBlank() || raw.length > 48) return null
    runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
    val now = Instant.ofEpochMilli(observedAt).atZone(zone)
    when (raw.lowercase(Locale.ROOT)) {
        "hoy", "today" -> return now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        "ayer", "yesterday" -> return now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val relative = Regex("(?i)^(?:(?:(?:publicad[oa]|emitido|transmitido|estrenado) )?hace (\\d+) ([\\p{L}.]+)|(?:(?:streamed|premiered|published) )?(\\d+) ([a-z.]+) ago)$").matchEntire(raw)
    if (relative != null) {
        val count = (relative.groupValues[1].ifBlank { relative.groupValues[3] }).toLongOrNull() ?: return null
        val unit = relative.groupValues[2].ifBlank { relative.groupValues[4] }.lowercase(Locale.ROOT).trimEnd('.')
        val time = when {
            unit=="s" || unit.startsWith("seg") || unit.startsWith("sec") -> now.minusSeconds(count)
            unit.startsWith("min") -> now.minusMinutes(count)
            unit=="h" || unit.startsWith("hora") || unit.startsWith("hour") || unit=="hr" -> now.minusHours(count)
            unit=="d" || unit.startsWith("día") || unit.startsWith("dia") || unit.startsWith("day") -> now.minusDays(count)
            unit.startsWith("sem") || unit.startsWith("week") || unit=="wk" -> now.minusWeeks(count)
            unit.startsWith("mes") || unit.startsWith("month") -> now.minusMonths(count)
            unit.startsWith("año") || unit.startsWith("ano") || unit.startsWith("year") -> now.minusYears(count)
            else -> return null
        }
        return time.toInstant().toEpochMilli()
    }
    // YouTube's episode cards also use labels such as "13 nov" without a year.
    val dayMonth=Regex("(?i)^(\\d{1,2}) ([\\p{L}.]+)(?: (\\d{4}))?$").matchEntire(raw)
    if(dayMonth!=null) {
        val months=listOf(listOf("ene","jan"),listOf("feb"),listOf("mar"),listOf("abr","apr"),listOf("may"),
            listOf("jun"),listOf("jul"),listOf("ago","aug"),listOf("sep","set"),listOf("oct"),listOf("nov"),listOf("dic","dec"))
        val label=dayMonth.groupValues[2].lowercase(Locale.ROOT)
        val month=months.indexOfFirst {names->names.any(label::startsWith)}+1
        val year=dayMonth.groupValues[3].toIntOrNull()
        if(month>0)runCatching {
            var date=LocalDate.of(year ?: now.year,month,dayMonth.groupValues[1].toInt())
            if(year==null && date>now.toLocalDate())date=date.minusYears(1)
            date.atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull()?.let {return it}
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
