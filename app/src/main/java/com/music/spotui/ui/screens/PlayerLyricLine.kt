package com.music.spotui.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.music.spotui.di.SongPlayer
import com.music.spotui.lyrics.currentLyricLine
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.delay
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.Constraints

@Composable
internal fun CurrentPlayerLyricLine(
    title: String, artist: String, album: String, trackId: String, durationSec: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isPlayerLyricLineEnabled(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("settings_prefs", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = isPlayerLyricLineEnabled(context)
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
    val lyricHeight=56.dp*androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(lyricHeight).padding(horizontal = 25.dp)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AnimatedContent(targetState = line.orEmpty(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "currentLyric") { text ->
            val shown = text.replace('\n', ' ')
            val width = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val size = remember(shown, width, baseStyle, measurer) {
                (17 downTo 4).firstOrNull { font ->
                    !measurer.measure(shown, baseStyle.copy(fontSize = font.sp, lineHeight = (font * 22f / 17).sp,
                        fontWeight = FontWeight.Bold), maxLines = 2, constraints = Constraints(maxWidth = width)).hasVisualOverflow
                } ?: 4
            }
            Text(shown, color = Color.White, fontSize = size.sp,lineHeight=(size * 22f / 17).sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Clip)
        }
    }
}
