package com.music.spotui.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

val LocalCollectionInset = staticCompositionLocalOf { 0.dp }

internal fun collectionSideInsetDp(availableWidthDp: Float, tablet: Boolean): Float =
    if (tablet) ((availableWidthDp - 720f) / 2f).coerceAtLeast(0f) else 0f

/** Only list content is inset. The screen background, toolbar and scroll surface stay full width. */
@Composable
fun CollectionContentViewport(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    BoxWithConstraints(modifier) {
        val inset = collectionSideInsetDp(maxWidth.value, tablet).dp
        CompositionLocalProvider(LocalCollectionInset provides inset) { content(this@BoxWithConstraints) }
    }
}
