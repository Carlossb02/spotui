package com.music.spotui.ui.components

internal data class CollectionActionMetrics(val control: Float, val pill: Float, val play: Float, val gap: Float, val itemGap: Float,
    val download: Float, val queue: Float, val labels: Boolean) {
    val requiredWidth: Float get() = download + pill + queue + control + play + gap + 8 + itemGap * 2
}
internal fun collectionActionMetrics(width: Float, tablet: Boolean = false): CollectionActionMetrics = when {
    tablet && width >= 560 -> CollectionActionMetrics(44f,136f,52f,12f,8f,132f,160f,true)
    width < 320 -> CollectionActionMetrics(40f,60f,48f,12f,8f,48f,48f,false)
    width < 380 -> CollectionActionMetrics(42f,64f,50f,16f,8f,52f,52f,false)
    else -> CollectionActionMetrics(44f,68f,52f,20f,10f,56f,56f,false)
}
