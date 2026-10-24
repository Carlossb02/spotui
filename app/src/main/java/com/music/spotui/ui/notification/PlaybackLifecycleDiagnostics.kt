package com.music.spotui.ui.notification

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Distinguishes audio-focus pauses, service stops, actual crashes and Android
 * low-memory kills. Uses the application context and stores only a small event
 * string; never keeps an Activity or a wake lock alive.
 */
object PlaybackLifecycleDiagnostics {
    private const val TAG = "SpotuiLifecycle"
    private const val PREFS = "spotui_playback_lifecycle"
    private const val LAST_EVENT = "last_event"
    private const val LAST_EVENT_TIME = "last_event_time"
    private const val LAST_EXIT_TIME = "last_exit_time"

    fun record(context: Context, event: String) {
        Log.i(TAG, event)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_EVENT, event.take(300))
            .putLong(LAST_EVENT_TIME, System.currentTimeMillis())
            .apply()
    }

    fun reportPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastExitTime = prefs.getLong(LAST_EXIT_TIME, 0L)
        runCatching {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = manager.getHistoricalProcessExitReasons(null, 0, 8)
                .filter { it.timestamp > lastExitTime && it.processName.startsWith(app.packageName) }
                .sortedBy { it.timestamp }
            exits.forEach { info ->
                Log.w(TAG, "Previous process exit: reason=${info.reason}, status=${info.status}, " +
                    "importance=${info.importance}, timestamp=${info.timestamp}; " +
                    "lastKnownEvent=${prefs.getString(LAST_EVENT, "none")}, " +
                    "lastKnownAt=${prefs.getLong(LAST_EVENT_TIME, 0L)}")
            }
            exits.lastOrNull()?.let { prefs.edit().putLong(LAST_EXIT_TIME, it.timestamp).apply() }
        }.onFailure { Log.w(TAG, "Unable to read process exit reasons", it) }
    }
}
