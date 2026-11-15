package com.music.spotui.data.preferences

import android.content.Context

internal const val RELEASE_NEWS_PREFS = "release_news_seen_v1"
internal enum class ReleaseKind(val key: String) { MUSIC("music"), PODCASTS("podcasts") }

internal fun effectiveReleaseCheck(seen:Long,resetAt:Long):Long=if(seen>=resetAt)seen else 0L
internal fun lastReleaseCheck(context: Context, kind: ReleaseKind): Long {
    val prefs=context.getSharedPreferences(RELEASE_NEWS_PREFS,0)
    return effectiveReleaseCheck(prefs.getLong(kind.key,0),prefs.getLong(kind.key+"_reset",0))
}
internal fun resetReleaseChecks(context:Context) {
    val now=System.currentTimeMillis()
    check(context.getSharedPreferences(RELEASE_NEWS_PREFS,0).edit()
        .putLong("music_reset",now).putLong("podcasts_reset",now).commit())
    com.music.spotui.sync.GoogleDeviceSync.requestSync()
}

/** A slower/offline device must never move the shared read watermark backwards. */
@Synchronized
internal fun markReleasesChecked(context: Context, kind: ReleaseKind, checkedAt: Long) {
    val prefs = context.getSharedPreferences(RELEASE_NEWS_PREFS, 0)
    val latest = maxOf(prefs.getLong(kind.key, 0), checkedAt)
    if (latest != prefs.getLong(kind.key, 0)) {
        check(prefs.edit().putLong(kind.key, latest).commit())
        com.music.spotui.sync.GoogleDeviceSync.requestSync()
    }
}
