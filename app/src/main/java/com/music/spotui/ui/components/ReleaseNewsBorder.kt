package com.music.spotui.ui.components

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.preferences.*

internal val MusicNewsColor = Color(0xFFEF6B7B)
internal val PodcastNewsColor = Color(0xFF62AEF5)

@Composable
internal fun rememberReleaseCheck(kind:ReleaseKind):State<Long> {
    val context=LocalContext.current
    val seen=remember(context,kind){mutableStateOf(lastReleaseCheck(context,kind))}
    DisposableEffect(context,kind) {
        val prefs=context.getSharedPreferences(RELEASE_NEWS_PREFS,0)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
            if(key==kind.key || key==kind.key+"_reset")seen.value=lastReleaseCheck(context,kind)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        seen.value = lastReleaseCheck(context, kind)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return seen
}

/** Only unread releases allocate an animation; ordinary chips keep their existing style. */
internal fun Modifier.releaseNewsBorder(enabled:Boolean,color:Color):Modifier = if(!enabled)this else composed {
    val phase by rememberInfiniteTransition(label="releaseNews").animateFloat(0f,1f,
        infiniteRepeatable(tween(2200,easing=LinearEasing)),label="releaseNewsBorder")
    drawWithContent {
        drawContent()
        val inset=1.dp.toPx()
        val corner=CornerRadius(size.height/2,size.height/2)
        val dimensions=androidx.compose.ui.geometry.Size(size.width-2*inset,size.height-2*inset)
        drawRoundRect(color.copy(alpha=0.65f),Offset(inset,inset),dimensions,corner,style=Stroke(2.dp.toPx()))
        val start=-size.width+phase*size.width*3
        val glow=Brush.linearGradient(listOf(color.copy(alpha=0.2f),Color.White,color,color.copy(alpha=0.2f)),
            Offset(start,0f),Offset(start+size.width,size.height))
        drawRoundRect(glow,Offset(inset,inset),dimensions,corner,style=Stroke(2.dp.toPx()))
    }
}
