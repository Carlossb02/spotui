package com.music.spotui.ui.screens

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.ui.PlayerView
import com.music.spotui.data.preferences.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume

internal object VideoLyricContrast {val luminance=MutableStateFlow(0.0)}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun SampleLyricVideoContrast(view:PlayerView) {
    val context=LocalContext.current
    fun wanted()=isPlayerLyricLineEnabled(context) && getLyricLineAppearance(context).color==LyricColorMode.VIDEO_CONTRAST
    var enabled by remember {mutableStateOf(wanted())}
    DisposableEffect(context) {
        val prefs=context.getSharedPreferences("settings_prefs",0)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,_->enabled=wanted()}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    LaunchedEffect(view,enabled) {
        if(!enabled)return@LaunchedEffect
        while(isActive) {
            if(view.isAttachedToWindow && view.player?.isPlaying==true)sample(view)
            delay(1000)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun sample(view:PlayerView)=suspendCancellableCoroutine<Unit> {continuation->
    val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
    fun finish(success:Boolean) {
        if(success && continuation.isActive) {
            val pixels=IntArray(1024);bitmap.getPixels(pixels,0,32,0,0,32,32)
            var r=0L;var g=0L;var b=0L
            pixels.forEach {r+=android.graphics.Color.red(it);g+=android.graphics.Color.green(it);b+=android.graphics.Color.blue(it)}
            VideoLyricContrast.luminance.value=androidx.core.graphics.ColorUtils.calculateLuminance(
                android.graphics.Color.rgb((r/1024).toInt(),(g/1024).toInt(),(b/1024).toInt()))
        }
        bitmap.recycle()
        if(continuation.isActive)continuation.resume(Unit)
    }
    try {when(val surface=view.videoSurfaceView) {
        is TextureView->{surface.getBitmap(bitmap);finish(true)}
        is SurfaceView->if(surface.holder.surface.isValid)PixelCopy.request(surface,bitmap,{finish(it==PixelCopy.SUCCESS)},Handler(Looper.getMainLooper())) else finish(false)
        else->finish(false)
    }}catch(_:Exception){finish(false)}
}
