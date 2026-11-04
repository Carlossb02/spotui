package com.music.spotui.lyrics

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics

internal object LyricsText {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val header = Regex("""\[(?:ar|ti|al|by|offset|length|re|ve):[^\]]*]""", RegexOption.IGNORE_CASE)

    fun synced(lrc: String): Lyrics? {
        val lines = lrc.lineSequence().flatMap { raw ->
            val tags = timestamp.findAll(raw).toList()
            val text = tags.lastOrNull()?.let { raw.substring(it.range.last + 1).trim() }.orEmpty()
            tags.asSequence().map { m ->
                val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + fraction, text)
            }
        }.sortedBy { it.timeMs }.toList()
        return lines.takeIf { it.any { line -> line.text.any(Char::isLetter) } }?.let { Lyrics(it, true) }
    }

    fun plain(text: String): Lyrics? {
        val cleaned = text.replace(timestamp, "").replace(header, "").trim()
        if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true) || !cleaned.any(Char::isLetter)) return null
        return Lyrics(cleaned.lines().map { LyricLine(0L, it.trim()) }, false)
    }
}
