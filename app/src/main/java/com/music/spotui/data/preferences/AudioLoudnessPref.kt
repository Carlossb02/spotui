package com.music.spotui.data.preferences

import android.content.Context
import org.json.JSONObject

/** Loudness belongs to the exact encoded source, never just its Spotify title. */
object AudioLoudnessPref {
    private const val PREF = "YouTubeLoudnessV1"
    private fun key(query: String, sourceUrl: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$query|$sourceUrl".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun put(context: Context, query: String, sourceUrl: String, gainDb: Double?) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (gainDb == null || !gainDb.isFinite()) {
            prefs.edit().remove(key(query, sourceUrl)).apply()
            return
        }
        val data = JSONObject().put("source", sourceUrl).put("gain", gainDb)
        prefs.edit().putString(key(query, sourceUrl), data.toString()).apply()
    }

    fun get(context: Context, query: String, sourceUrl: String): Double? = runCatching {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key(query, sourceUrl), null)
            ?: return@runCatching null
        val data = JSONObject(raw)
        if (data.optString("source") != sourceUrl) return@runCatching null
        data.optDouble("gain", Double.NaN).takeIf { it.isFinite() }
    }.getOrNull()
}
