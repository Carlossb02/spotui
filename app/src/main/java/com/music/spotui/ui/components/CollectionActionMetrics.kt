package com.music.spotui.ui.components

internal data class CollectionActionMetrics(val control: Float, val pill: Float, val play: Float, val gap: Float, val itemGap: Float) {
    val requiredWidth: Float get() = control * 3 + pill + play + gap + 8 + itemGap * 2
}
internal fun collectionActionMetrics(width: Float): CollectionActionMetrics = when {
    width < 320 -> CollectionActionMetrics(40f,96f,48f,8f,4f)
    width < 380 -> CollectionActionMetrics(40f,100f,50f,8f,6f)
    else -> CollectionActionMetrics(42f,104f,52f,12f,6f)
}
