package com.music.spotui.ui.visibility

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-only visibility. Never uses this flag to stop or pause the audio service. */
object UiVisibility {
    var isVisible by mutableStateOf(true)
        private set

    fun update(visible: Boolean) {
        isVisible = visible
    }
}
