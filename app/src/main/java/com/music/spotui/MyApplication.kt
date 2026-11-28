package com.music.spotui

import android.app.Application
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@HiltAndroidApp
class MyApplication : Application(){
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmup: Job? = null
    @Volatile private var warmed = false

    companion object {
        // For non-DI singletons (LyricsApi) that need a Context to refresh the token.
        @JvmStatic
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Remove jobs persisted by old APKs, including the retired periodic job.
        val scheduler = getSystemService(android.app.job.JobScheduler::class.java)
        listOf(231702, 231703, com.music.spotui.data.api.SpotifyPendingWrites.JOB_ID)
            .forEach { scheduler?.cancel(it) }
        // Surface Spotify REST/GQL logs to logcat for diagnosis.
        com.metrolist.spotify.Spotify.logger = { level, msg ->
            android.util.Log.d("SpotifyREST", "[$level] $msg")
        }
        com.metrolist.spotify.SpotifyCanvas.setLogger { level, msg ->
            android.util.Log.d("SpotifyCanvas", "[$level] $msg")
        }
        // Required by the ported YouTube streaming flow (cipher/PoToken WebViews).
        CipherDeobfuscator.initialize(this)

        // Locale + visitorData must be set or the player can't mint a PoToken,
        // and googlevideo rejects the stream URL with HTTP 403 (tracks stuck at 0:00).
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it.isNotBlank() } ?: "US",
            hl = locale.language.takeIf { it.isNotBlank() } ?: "en",
        )
        // YouTube playback runs anonymously; age-gated official audio falls back
        // to matching normal YouTube uploads instead of requiring sign-in.
        YouTube.cookie = null
    }

    fun activityVisible() {
        com.music.spotui.sync.GoogleDeviceSync.install(this)
        com.music.spotui.sync.GoogleDeviceSync.onForeground()
        com.music.spotui.data.api.SpotifyPendingWrites.setVisible(this, true)
        if (warmed || warmup?.isActive == true) return
        warmup = appScope.launch {
            delay(800L)
            com.music.spotui.di.SongPlayer.awaitAudioStartup()
            YouTube.visitorData = YouTube.visitorData().getOrNull() ?: YouTube.visitorData
            warmed = true
        }
    }

    fun activityHidden() {
        warmup?.cancel()
        com.music.spotui.data.api.SpotifyPendingWrites.setVisible(this, false)
        com.music.spotui.sync.GoogleDeviceSync.onBackground()
    }
}
