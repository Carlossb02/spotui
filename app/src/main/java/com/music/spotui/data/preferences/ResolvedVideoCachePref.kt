package com.music.spotui.data.preferences

import android.content.Context

private const val PREF_RESOLVED_VIDEOS = "ResolvedVideosCache"

fun getCachedVideoIds(context: Context, cacheKey: String): List<String>? {
    val raw = context.getSharedPreferences(PREF_RESOLVED_VIDEOS, Context.MODE_PRIVATE)
        .getString(cacheKey, null) ?: return null
    return raw.split(",").filter { it.isNotBlank() }
}

fun setCachedVideoIds(context: Context, cacheKey: String, videoIds: List<String>) {
    if (videoIds.isEmpty()) return
    val raw = videoIds.joinToString(",")
    context.getSharedPreferences(PREF_RESOLVED_VIDEOS, Context.MODE_PRIVATE)
        .edit()
        .putString(cacheKey, raw)
        .apply()
}

fun clearCachedVideoId(context: Context, songQuery: String) {
    val prefs = context.getSharedPreferences(PREF_RESOLVED_VIDEOS, Context.MODE_PRIVATE)
    // Keys contain the actual encoded YouTube filter and matcher version.
    // Remove only this recording's variants, including legacy key layouts.
    val editor = prefs.edit()
    prefs.all.keys.filter { it.startsWith("$songQuery|") }.forEach { editor.remove(it) }
    editor.apply()
}

fun clearAllResolvedVideos(context: Context) {
    context.getSharedPreferences(PREF_RESOLVED_VIDEOS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}
