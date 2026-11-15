package com.music.spotui.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val collectionBackdrops = com.music.spotui.util.ExpiringLruCache<String, ImageBitmap>(12, 30 * 60_000L)

/** Small, cached colour blur. No full-resolution background bitmap or per-frame image work. */
private fun blurCollectionBitmap(source: android.graphics.Bitmap): ImageBitmap {
    val width = source.width; val height = source.height
    var pixels = IntArray(width * height).also { source.getPixels(it, 0, width, 0, 0, width, height) }
    repeat(3) {
        val horizontal = IntArray(pixels.size)
        val output = IntArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0; var g = 0; var b = 0
            for (d in -4..4) { val p = pixels[y * width + (x + d).coerceIn(0, width - 1)]; r += p ushr 16 and 255; g += p ushr 8 and 255; b += p and 255 }
            horizontal[y * width + x] = android.graphics.Color.rgb(r / 9, g / 9, b / 9)
        }
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0; var g = 0; var b = 0
            for (d in -4..4) { val p = horizontal[(y + d).coerceIn(0, height - 1) * width + x]; r += p ushr 16 and 255; g += p ushr 8 and 255; b += p and 255 }
            output[y * width + x] = android.graphics.Color.rgb(r / 9, g / 9, b / 9)
        }
        pixels = output
    }
    return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** One full-width paint layer, anchored to the scrolling collection header.
 * Rows stay transparent; centered content cannot paint a rectangular seam.
 * Scroll state is read only during drawing, without recomposing the song list.
 */
@Composable
fun Modifier.scrollingCollectionBackground(
    topColor: Color,
    baseColor: Color,
    listState: LazyListState,
    headerKey: String,
    coverUri: String = "",
): Modifier {
    val context = LocalContext.current
    val image by produceState(collectionBackdrops.get(coverUri), coverUri) {
        value = collectionBackdrops.get(coverUri)
        if (coverUri.isNotBlank() && value == null) {
            com.music.spotui.di.SongPlayer.awaitAudioStartup()
            value = withContext(Dispatchers.IO) {
                val request = com.bumptech.glide.Glide.with(context).asBitmap().load(coverUri).submit(64, 64)
                try { blurCollectionBitmap(request.get()).also { collectionBackdrops.put(coverUri, it) } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                finally { com.bumptech.glide.Glide.with(context).clear(request) }
            }
        }
    }
    return drawWithCache {
    onDrawBehind {
        drawRect(baseColor)
        val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == headerKey }
        if (header != null && header.size > 0) {
            val top = header.offset.toFloat()
            val bottom = top + header.size
            if (bottom > 0f) {
                drawRect(Brush.verticalGradient(colors = listOf(topColor.copy(alpha = .8f), baseColor), startY = top, endY = bottom))
                image?.let { bitmap ->
                    val imageBottom = top + header.size * .85f
                    val curve = Path().apply {
                        moveTo(0f, top - 100.dp.toPx()); lineTo(size.width, top - 100.dp.toPx())
                        lineTo(size.width, imageBottom - 36.dp.toPx())
                        cubicTo(size.width * .68f, imageBottom + 20.dp.toPx(), size.width * .35f, imageBottom - 72.dp.toPx(), 0f, imageBottom)
                        close()
                    }
                    clipPath(curve) {
                        drawImage(bitmap, dstOffset = IntOffset(0, (top - 100.dp.toPx()).roundToInt()),
                            dstSize = IntSize(size.width.roundToInt(), (header.size * .85f + 100.dp.toPx()).roundToInt().coerceAtLeast(1)))
                        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .12f), baseColor.copy(alpha = .4f), baseColor),
                            startY = top, endY = imageBottom))
                    }
                }
            }
        }
    }
}
}
