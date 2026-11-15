package com.music.spotui.ui.screens

import kotlin.math.abs

/** Only the silent visual clock is corrected; the soundtrack is authoritative. */
internal object PodcastVideoClockPolicy {
    fun recover(driftMs: Long, sinceSeekMs: Long, sinceReadyMs: Long) =
        abs(driftMs) > 1_200 && sinceSeekMs > 5_000 && sinceReadyMs > 1_000
    fun speed(driftMs: Long) = when {
        driftMs > 600 -> 1.08f
        driftMs > 180 -> 1.03f
        driftMs < -600 -> 0.92f
        driftMs < -180 -> 0.97f
        else -> 1f
    }
}
