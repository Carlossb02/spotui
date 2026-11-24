package com.music.spotui.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** The collection paints this background once. Each lens reuses its GPU display list. */
internal class CollectionGlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf<Offset?>(null)
}

@Composable
internal fun rememberCollectionGlassBackdrop(): CollectionGlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { CollectionGlassBackdrop(layer) }
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<CollectionGlassBackdrop?> { null }

@Composable
internal fun Modifier.liquidCollectionGlass(accent: Color, interaction: InteractionSource): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val sampleLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf<Offset?>(null) }
    val pressed by interaction.collectIsPressedAsState()
    val pressure = animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 95 else 210), label = "Glass press")
    return clip(RoundedCornerShape(50))
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val padding = 8.dp.toPx()
            val pixelSize = IntSize(ceil(size.width + 2 * padding).toInt(), ceil(size.height + 2 * padding).toInt())
            val shader = if (Build.VERSION.SDK_INT >= 33) {
                runCatching { lensShader(size, padding, 1.6.dp.toPx(), accent) }.getOrNull()
            } else null
            var lastPressure = Float.NaN
            onDrawBehind {
                val amount = pressure.value
                val source = backdrop?.origin
                val destination = origin
                val hasBackdrop = backdrop != null && source != null && destination != null &&
                    backdrop.layer.size != IntSize.Zero && drawContext.canvas.nativeCanvas.isHardwareAccelerated
                if (hasBackdrop && backdrop != null && source != null && destination != null) {
                    val relative = destination - source
                    sampleLayer.record(size = pixelSize) {
                        translate(padding - relative.x, padding - relative.y) { drawLayer(backdrop.layer) }
                    }
                    if (shader != null && Build.VERSION.SDK_INT >= 33 && lastPressure != amount) {
                        shader.setFloatUniform("pressure", amount)
                        sampleLayer.renderEffect = android.graphics.RenderEffect
                            .createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
                        lastPressure = amount
                    }
                    translate(-padding, -padding) { drawLayer(sampleLayer) }
                }
                if (shader == null || !hasBackdrop) {
                    drawRect(accent.copy(alpha = .24f + amount * .13f))
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .15f + amount * .09f),
                        Color.Transparent, Color.Black.copy(alpha = .23f))))
                }
                val inset = .6.dp.toPx()
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = .61f + amount * .16f),
                        accent.copy(alpha = .26f), Color.White.copy(alpha = .30f))),
                    topLeft = Offset(inset, inset),
                    size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(2 * inset),
                )
            }
        }
}

@RequiresApi(33)
private fun lensShader(size: Size, padding: Float, blur: Float, accent: Color): RuntimeShader =
    RuntimeShader("""
        uniform shader backdrop;
        uniform float2 bounds;
        uniform float pad;
        uniform float blur;
        uniform float3 tint;
        uniform float pressure;

        half4 main(float2 p) {
            float r = max(bounds.y * 0.5, 1.0);
            float2 center = float2(pad) + bounds * 0.5;
            float2 q = p - center;
            float core = max(0.0, bounds.x * 0.5 - r);
            float2 v = float2(q.x - clamp(q.x, -core, core), q.y);
            float edge = smoothstep(0.35, 1.0, length(v) / r);
            float2 normal = v / max(length(v), 0.01);
            float2 samplePoint = p - normal * edge * edge * pad * (0.58 + 0.34 * pressure);

            half3 scene = backdrop.eval(samplePoint).rgb * 0.40;
            scene += backdrop.eval(samplePoint + float2(blur, 0.0)).rgb * 0.15;
            scene += backdrop.eval(samplePoint - float2(blur, 0.0)).rgb * 0.15;
            scene += backdrop.eval(samplePoint + float2(0.0, blur)).rgb * 0.15;
            scene += backdrop.eval(samplePoint - float2(0.0, blur)).rgb * 0.15;
            float2 light = normalize(float2(-0.64 + 0.95 * pressure, -1.0));
            float gloss = pow(max(0.0, dot(light, normal)), 4.0) * edge;
            half3 color = mix(scene * 0.86, half3(tint), 0.22 + 0.08 * pressure);
            color += half3(0.07 + gloss * (0.18 + 0.10 * pressure));
            return half4(color, 1.0);
        }
    """).apply {
        setFloatUniform("bounds", size.width, size.height)
        setFloatUniform("pad", padding)
        setFloatUniform("blur", blur)
        setFloatUniform("tint", accent.red, accent.green, accent.blue)
        setFloatUniform("pressure", 0f)
    }
