package com.music.spotui.data.preferences

import android.content.Context

/** Pins are local and survive restarts and normal image/cache clearing. */
object PinnedLibraryPref {
    private const val NAME = "spotui_pinned_library_v1"
    private const val KEY = "pinned_ids"

    fun get(context: Context): Set<String> =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun toggle(context: Context, id: String): Set<String> {
        if (id.isBlank()) return get(context)
        val result = get(context).toMutableSet()
        if (!result.add(id)) result.remove(id)
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, result.toSet()).apply()
        return result
    }
}
