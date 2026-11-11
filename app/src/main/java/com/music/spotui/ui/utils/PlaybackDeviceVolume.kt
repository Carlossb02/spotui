package com.music.spotui.ui.utils

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Media-stream volume only; normalization, equalization and crossfade gains stay independent. */
internal object PlaybackDeviceVolume {
    fun read(context:Context):Float = runCatching {
        val audio=context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if(audio.isVolumeFixed)return@runCatching -1f
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if(max<=0)-1f else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/max
    }.getOrDefault(-1f)
    fun set(context:Context,fraction:Float) {
        require(fraction.isFinite())
        val audio=context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        check(!audio.isVolumeFixed){"Este dispositivo tiene un volumen fijo."}
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        check(max>0){"Este dispositivo no permite cambiar el volumen multimedia."}
        val value=(fraction.coerceIn(0f,1f)*max).roundToInt()
        if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=value)audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0)
    }
}
