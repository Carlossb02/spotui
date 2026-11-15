package com.music.spotui.audio

/** A small codec preference must never make a low-bitrate stream win in High mode. */
internal object AudioFormatPreference {
    fun bitrate(average:Int?,peak:Int,contentLength:Long?,durationMs:Long?):Int {
        average?.takeIf {it>0}?.let {return it}
        if(peak>0)return peak
        if(contentLength!=null && contentLength>0 && durationMs!=null && durationMs>0)
            return (contentLength.toDouble()*8_000/durationMs).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        return 0
    }

    fun score(bitrate: Int, mimeType: String, low: Boolean): Long {
        val kbps = bitrate.coerceAtLeast(0).toLong() / 1000
        // Unknown bitrate must not beat a known low-data format by looking like zero.
        if (bitrate<=0)return -1_000_000L
        if (low) return -kbps * 10
        val opus = mimeType.contains("opus", ignoreCase = true)
        // Modest efficiency allowance, rather than the old 10,000-point codec bonus.
        val effective = if (opus) kbps * 5 / 4 else kbps
        return effective * 10 + if (opus) 5 else 0
    }
}
