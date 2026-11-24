package com.music.spotui.ui.components

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

internal val LocalGlassBackdrop = staticCompositionLocalOf<ImageBitmap?> { null }
private class GlassCoordinates { var value: LayoutCoordinates? = null }

/** A small shared, genuinely blurred cover is refracted by the lens shader.
 * No screen capture, full-size intermediate textures or continuously running animation. */
@Composable
internal fun Modifier.liquidCollectionGlass(accent: Color): Modifier {
    val image = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val viewportWidth = with(density) { config.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)
    val coords = remember { GlassCoordinates() }
    return clip(RoundedCornerShape(50)).onGloballyPositioned { coords.value = it }.drawWithCache {
        val bitmap = image?.asAndroidBitmap()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val lens = if (bitmap != null && Build.VERSION.SDK_INT >= 33) runCatching { makeLensShader(bitmap, size.width, size.height, accent) }.getOrNull() else null
        val shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        if (lens == null) paint.shader = shader
        onDrawBehind {
            val x = coords.value?.takeIf { it.isAttached }?.positionInRoot()?.x ?: 0f
            if (lens != null && Build.VERSION.SDK_INT >= 33) {
                lens.setFloatUniform("originX", x / viewportWidth)
                lens.setFloatUniform("viewportWidth", viewportWidth)
                paint.shader = lens
                drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, size.height / 2, size.height / 2, paint) }
            } else {
                if (bitmap != null && shader != null) {
                    val matrix = Matrix()
                    val scale = viewportWidth / bitmap.width
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(-x, -bitmap.height * scale * .65f)
                    shader.setLocalMatrix(matrix)
                    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, size.height / 2, size.height / 2, paint) }
                }
                drawRect(accent.copy(alpha = if (bitmap == null) .30f else .20f))
                drawRect(Color.Black.copy(alpha = .32f))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .18f), Color.Transparent, Color.White.copy(alpha = .04f))))
            }
            // A narrow specular rim stays distinct over bright and dark artwork.
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .65f), accent.copy(alpha = .18f), Color.White.copy(alpha = .32f))),
                topLeft = androidx.compose.ui.geometry.Offset(.6.dp.toPx(), .6.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 1.2.dp.toPx(), size.height - 1.2.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
        }
    }
}

@RequiresApi(33)
private fun makeLensShader(bitmap: android.graphics.Bitmap, width: Float, height: Float, accent: Color): android.graphics.RuntimeShader =
    android.graphics.RuntimeShader("""
        uniform shader backdrop;
        uniform float2 bounds;
        uniform float2 sourceSize;
        uniform float3 tint;
        uniform float originX;
        uniform float viewportWidth;
        half4 main(float2 p) {
            float r = bounds.y * 0.5;
            float2 q = p - bounds * 0.5;
            float2 v = float2(q.x - clamp(q.x, -bounds.x * 0.5 + r, bounds.x * 0.5 - r), q.y);
            float d = length(v) / max(r, 1.0);
            float edge = smoothstep(0.42, 1.0, d);
            float2 normal = v / max(length(v), 0.01);
            float2 uv = float2(originX + p.x / viewportWidth, 0.65 + p.y / viewportWidth);
            uv += normal * edge * edge * 0.045;
            half3 scene = backdrop.eval(clamp(uv, 0.0, 1.0) * sourceSize).rgb;
            float topLight = max(0.0, -normal.y) * edge;
            float rim = pow(edge, 5.0);
            half3 color = mix(scene * 0.48, half3(tint), 0.20);
            color += half3(0.065 + topLight * 0.11 + rim * 0.09);
            return half4(color, 1.0);
        }
    """).apply {
        setInputShader("backdrop", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        setFloatUniform("bounds", width, height)
        setFloatUniform("sourceSize", bitmap.width.toFloat(), bitmap.height.toFloat())
        setFloatUniform("tint", accent.red, accent.green, accent.blue)
    }
