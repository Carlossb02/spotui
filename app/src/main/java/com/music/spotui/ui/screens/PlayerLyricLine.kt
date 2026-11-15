package com.music.spotui.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.music.spotui.data.preferences.isPlayerLyricLineEnabled
import com.music.spotui.data.preferences.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.onSizeChanged
import com.music.spotui.di.SongPlayer
import com.music.spotui.lyrics.currentLyricLine
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.delay

@Composable
internal fun CurrentPlayerLyricLine(
    title: String, artist: String, album: String, trackId: String, durationSec: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isPlayerLyricLineEnabled(context)) }
    var appearance by remember {mutableStateOf(getLyricLineAppearance(context))}
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("settings_prefs", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = isPlayerLyricLineEnabled(context)
            appearance=getLyricLineAppearance(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (!enabled) return
    val vm: LyricsViewModel = hiltViewModel()
    val key = LyricsViewModel.requestKey(title, artist, album, durationSec, trackId)
    LaunchedEffect(key) { vm.load(title, artist, album, durationSec, trackId) }
    val received by vm.state.collectAsState()
    val loaded = vm.visibleState(key, received) as? LyricsViewModel.State.Loaded
    var line by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, loaded?.lyrics) {
        if(loaded?.lyrics?.synced!=true){line=null;return@LaunchedEffect}
        while (true) {
            line = currentLyricLine(loaded.lyrics, SongPlayer.getCurrentPosition().coerceAtLeast(0))
            delay(150)
        }
    }
    // Reserve a stable line area across instrumental gaps, without showing stale words.
    val font=appearance.size.sp
    val lyricHeight=(font*2.6f+12).dp*androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val luminance by VideoLyricContrast.luminance.collectAsState()
    val animated=appearance.color in setOf(LyricColorMode.AURORA,LyricColorMode.OCEAN,LyricColorMode.SUNSET,LyricColorMode.RAINBOW,LyricColorMode.NEON)
    val phase=if(animated) {
        val value by rememberInfiniteTransition(label="lyricStyle").animateFloat(0f,1f,
            infiniteRepeatable(tween(if(appearance.color==LyricColorMode.NEON)3000 else 7000,easing=LinearEasing)),label="lyricColors")
        value
    }else 0f
    val solid=when(appearance.color) {
        LyricColorMode.CUSTOM->Color(appearance.customColor)
        LyricColorMode.VIDEO_CONTRAST->if(luminance>.35)Color.Black else Color.White
        else->Color.White
    }
    val colors=when(appearance.color) {
        LyricColorMode.AURORA->listOf(Color(0xFF73FBD3),Color(0xFFD98CFF),Color(0xFF92D8FF),Color(0xFF73FBD3))
        LyricColorMode.OCEAN->listOf(Color(0xFF67F2FF),Color(0xFF559BFF),Color(0xFF85FFD8),Color(0xFF67F2FF))
        LyricColorMode.SUNSET->listOf(Color(0xFFFFD36F),Color(0xFFFF7895),Color(0xFFD9A4FF),Color(0xFFFFD36F))
        LyricColorMode.NEON->listOf(Color(0xFFB5FF4D),Color(0xFFFF76EF),Color(0xFF62FFFF),Color(0xFFB5FF4D))
        LyricColorMode.RAINBOW->(0..6).map {Color.hsv((it*60f+phase*360)%360,.6f,1f)}
        else->listOf(solid,solid)
    }
    var width by remember {mutableStateOf(500)}
    val brush=if(animated)Brush.linearGradient(colors,Offset((phase-1)*width,0f),Offset((phase+1)*width,0f))else SolidColor(solid)
    val alignment=when(appearance.position){LyricPosition.LEFT->TextAlign.Left;LyricPosition.RIGHT->TextAlign.Right;else->TextAlign.Center}
    Box(Modifier.fillMaxWidth().height(lyricHeight).padding(horizontal = 25.dp)
        .onSizeChanged {width=it.width.coerceAtLeast(1)}.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AnimatedContent(targetState = line.orEmpty(),modifier=Modifier.fillMaxWidth(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "currentLyric") { text ->
            Text(text, fontSize = font.sp,lineHeight=(font*1.3f).sp, fontWeight = FontWeight.Bold,
                style=TextStyle(brush=brush,shadow=Shadow(if(solid==Color.Black)Color.White.copy(alpha=.8f)else Color.Black,Offset.Zero,6f)),
                modifier=Modifier.fillMaxWidth().graphicsLayer {
                    if(animated){translationY=(kotlin.math.sin(phase*2*Math.PI)*2).toFloat();translationX=(kotlin.math.sin(phase*2*Math.PI)*3).toFloat()}
                },textAlign = alignment, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
