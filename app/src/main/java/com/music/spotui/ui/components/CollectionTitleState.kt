package com.music.spotui.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent

class CollectionTitleState internal constructor(private val list: LazyListState, private val headerKey: String) {
    private var titleBottom by mutableFloatStateOf(Float.POSITIVE_INFINITY)
    val collapsed by derivedStateOf {
        val header = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == headerKey }
        if (header == null) list.firstVisibleItemIndex > 0
        else header.offset + titleBottom <= list.layoutInfo.viewportStartOffset
    }
    val titleModifier: Modifier get() = Modifier.onPlaced { coordinates ->
        titleBottom = coordinates.positionInParent().y + coordinates.size.height
    }
}

@Composable
fun rememberCollectionTitleState(list: LazyListState, headerKey: String): CollectionTitleState =
    remember(list, headerKey) { CollectionTitleState(list, headerKey) }

fun collectionToolbarColor(topColor: Color, baseColor: Color): Color = lerp(topColor, baseColor, 0.55f)
