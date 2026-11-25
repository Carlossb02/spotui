package com.music.spotui.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp

/** Kept for backwards compatibility across collection screens. */
internal class CollectionGlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf<Offset?>(null)
}

@Composable
internal fun rememberCollectionGlassBackdrop(): CollectionGlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { CollectionGlassBackdrop(layer) }
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<CollectionGlassBackdrop?> { null }

/**
 * Clean capsule button styling using the tablet dock grey (#202020) solid background
 * and a multi-tone gradient border derived from the cover/accent color.
 */
@Composable
internal fun Modifier.liquidCollectionGlass(accent: Color, interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val pressure = animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 95 else 210), label = "Capsule press")
    return clip(RoundedCornerShape(50))
        .drawWithCache {
            val cornerRadius = CornerRadius(size.height / 2f)
            onDrawBehind {
                val amount = pressure.value

                // Solid background matching tablet dock grey (#202020), subtly lightened on press
                val bgRed = (0x20 / 255f + amount * 0.05f).coerceIn(0f, 1f)
                val bgGreen = (0x20 / 255f + amount * 0.05f).coerceIn(0f, 1f)
                val bgBlue = (0x20 / 255f + amount * 0.05f).coerceIn(0f, 1f)
                drawRoundRect(
                    color = Color(red = bgRed, green = bgGreen, blue = bgBlue, alpha = 1f),
                    cornerRadius = cornerRadius
                )

                // Outer multi-tone gradient border derived from cover/accent color
                val outerStroke = 2.2.dp.toPx()
                val insetOuter = outerStroke / 2f
                val outerGradient = Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.55f + amount * 0.20f),
                        accent.copy(alpha = 0.85f + amount * 0.15f),
                        Color.White.copy(alpha = 0.45f + amount * 0.15f),
                        accent.copy(alpha = 0.65f + amount * 0.20f),
                        accent.copy(alpha = 0.90f + amount * 0.10f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                )

                drawRoundRect(
                    brush = outerGradient,
                    topLeft = Offset(insetOuter, insetOuter),
                    size = Size((size.width - 2 * insetOuter).coerceAtLeast(0f), (size.height - 2 * insetOuter).coerceAtLeast(0f)),
                    cornerRadius = cornerRadius,
                    style = Stroke(width = outerStroke)
                )

                // Inner subtle glow border for soft finish
                val innerStroke = 1.0.dp.toPx()
                val insetInner = outerStroke
                val innerGradient = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.40f + amount * 0.15f),
                        accent.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.15f)
                    ),
                    start = Offset(size.width * 0.2f, 0f),
                    end = Offset(size.width * 0.8f, size.height)
                )
                drawRoundRect(
                    brush = innerGradient,
                    topLeft = Offset(insetInner, insetInner),
                    size = Size((size.width - 2 * insetInner).coerceAtLeast(0f), (size.height - 2 * insetInner).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius((size.height - 2 * insetInner).coerceAtLeast(0f) / 2f),
                    style = Stroke(width = innerStroke)
                )
            }
        }
}
