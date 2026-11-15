package com.music.spotui.sync

/** Only Drive discovery uses this cadence; Firebase remains listener/event driven. */
internal fun driveDiscoveryInterval(foreground:Boolean,libraryVisible:Boolean):Long = when {
    foreground && libraryVisible -> 2_000L
    foreground -> 10_000L
    else -> 30_000L
}
