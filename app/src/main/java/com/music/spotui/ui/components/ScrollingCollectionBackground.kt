package com.music.spotui.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** One full-width paint layer, anchored to the scrolling collection header.
 * Rows stay transparent; centered content cannot paint a rectangular seam.
 * Scroll state is read only during drawing, without recomposing the song list.
 */
fun Modifier.scrollingCollectionBackground(
    topColor: Color,
    baseColor: Color,
    listState: LazyListState,
    headerKey: String,
): Modifier = drawWithCache {
    onDrawBehind {
        drawRect(baseColor)
        val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == headerKey }
        if (header != null && header.size > 0) {
            val top = header.offset.toFloat()
            val bottom = top + header.size
            if (bottom > 0f) drawRect(Brush.verticalGradient(
                colors = listOf(topColor, baseColor), startY = top, endY = bottom))
        }
    }
}
