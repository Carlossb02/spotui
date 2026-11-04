package com.music.spotui.audio

/** A small codec preference must never make a low-bitrate stream win in High mode. */
internal object AudioFormatPreference {
    fun score(bitrate: Int, mimeType: String, low: Boolean): Long {
        val kbps = bitrate.coerceAtLeast(0).toLong() / 1000
        if (low) return -kbps * 10
        val opus = mimeType.contains("opus", ignoreCase = true)
        // Modest efficiency allowance, rather than the old 10,000-point codec bonus.
        val effective = if (opus) kbps * 5 / 4 else kbps
        return effective * 10 + if (opus) 5 else 0
    }
}
