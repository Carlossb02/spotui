package com.music.spotui.ui.notification

/** Lifetime of a run opened from the Activity. A service bind cannot open a run. */
internal object ManualPlaybackRun {
    @Volatile var opened = false
        private set
    @Volatile var visible = false
        private set
    var foregroundGeneration = 0L
        private set

    fun show() {
        foregroundGeneration++
        opened = true
        visible = true
    }

    fun hide() { visible = false }

    fun close() {
        visible = false
        opened = false
    }
}
