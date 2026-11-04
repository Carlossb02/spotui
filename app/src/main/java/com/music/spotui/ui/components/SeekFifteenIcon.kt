package com.music.spotui.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/** Open circular 15-second arrows matching the silhouette in the user's reference.
 * A full curved ring, filled solid arrowhead, large upright '15'.
 * Paths are original vectors, never a raster/watermarked source image. */
@Composable
fun SeekFifteenIcon(backwards: Boolean, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val unit = size.minDimension / 48f
        val xOffset = (size.width - 48f * unit) / 2f
        val yOffset = (size.height - 48f * unit) / 2f
        fun px(x: Float) = xOffset + (if (backwards) x else 48f - x) * unit
        fun py(y: Float) = yOffset + y * unit
        // Almost-complete round arrow with its open section at the upper left.
        // The lower arc rounds the bottom and ends before the arrow tip.
        val ring = Path().apply {
            moveTo(px(5f), py(34f))
            cubicTo(px(10f), py(46f), px(27f), py(48f), px(38f), py(39f))
            cubicTo(px(49f), py(29f), px(46f), py(15f), px(36f), py(8f))
            cubicTo(px(28f), py(2f), px(19f), py(2f), px(11f), py(7f))
        }
        drawPath(ring, color = tint,
            style = Stroke(width = 2.85f * unit, cap = StrokeCap.Round))
        val head = Path().apply {
            moveTo(px(4f), py(12.5f))
            lineTo(px(8f), py(1.5f))
            quadraticTo(px(8.6f), py(0.1f), px(9.9f), py(1.8f))
            lineTo(px(17f), py(10.5f))
            quadraticTo(px(18.2f), py(12.1f), px(16.0f), py(12f))
            lineTo(px(5.5f), py(13.5f))
            close()
        }
        drawPath(head, tint)
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                textSize = 21f * unit
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val baseline = py(26.8f) - (paint.ascent() + paint.descent()) / 2f
            canvas.nativeCanvas.drawText("15", xOffset + 24f * unit, baseline, paint)
        }
    }
}
