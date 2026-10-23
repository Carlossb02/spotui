package com.music.spotui.di

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One audio-focus owner for the entire listening session, including overlapping
 * ExoPlayer instances. The old player's release must never abandon focus that
 * the incoming player needs in order to keep playing.
 *
 * Min SDK is 26, therefore the non-deprecated AudioFocusRequest API is used.
 * All callbacks are delivered on the main looper.
 */
internal class PlaybackAudioFocus(context: Context, private val onChange: (Int) -> Unit) {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { onChange(it) }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        .build()
    private var held = false

    fun acquire(): Boolean {
        if (held) return true
        return try {
            val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            held = granted
            if (!granted) Log.w("SpotuiTransition", "Audio focus request denied; playback cannot start")
            granted
        } catch (e: RuntimeException) {
            Log.e("SpotuiTransition", "Audio focus request failed", e)
            false
        }
    }

    fun release() {
        if (!held) return
        held = false
        runCatching { manager.abandonAudioFocusRequest(request) }
            .onFailure { Log.w("SpotuiTransition", "Audio focus release failed", it) }
    }

    /** Focus is already lost permanently; don't restart automatically. */
    fun markLost() { held = false }
}
