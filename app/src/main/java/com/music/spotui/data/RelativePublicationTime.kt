package com.music.spotui.data

internal fun relativePublicationTime(publishedAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - publishedAt).coerceAtLeast(0L) / 60_000L)
    val (count, unit) = when {
        minutes < 1 -> return "Ahora mismo"
        minutes < 60 -> minutes to "minuto"
        minutes < 1440 -> minutes / 60 to "hora"
        minutes < 43_200 -> minutes / 1440 to "día"
        minutes < 525_600 -> minutes / 43_200 to "mes"
        else -> minutes / 525_600 to "año"
    }
    val plural = if (count == 1L) unit else if (unit == "mes") "meses" else unit + "s"
    return "Hace $count $plural"
}
