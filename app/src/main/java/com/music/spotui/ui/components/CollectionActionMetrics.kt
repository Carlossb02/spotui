package com.music.spotui.ui.components

internal data class CollectionActionMetrics(val control: Float, val pill: Float, val play: Float, val gap: Float) {
    val requiredWidth: Float get() = control * 3 + pill + play + gap + 8
}
internal fun collectionActionMetrics(width: Float): CollectionActionMetrics = when {
    width < 320 -> CollectionActionMetrics(40f,96f,48f,8f)
    width < 380 -> CollectionActionMetrics(44f,104f,52f,8f)
    else -> CollectionActionMetrics(48f,112f,56f,12f)
}
