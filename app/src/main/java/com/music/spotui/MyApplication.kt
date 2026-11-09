package com.music.spotui

import android.app.Application
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

@HiltAndroidApp
class MyApplication : Application(){
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // For non-DI singletons (LyricsApi) that need a Context to refresh the token.
        @JvmStatic
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.music.spotui.data.api.SpotifyPendingWrites.install(this)
        com.music.spotui.sync.GoogleDeviceSync.install(this)
        appScope.launch {
            // Reading an old/corrupt local snapshot is optional startup work.
            // One failed warmup must not terminate the process over the login screen.
            val tasks=listOf<Pair<String,()->Unit>>(
                "metadata" to {com.music.spotui.data.preferences.CustomMetadataPref.getAllOverrides(this@MyApplication);Unit},
                "playlists" to {com.music.spotui.data.preferences.LocalPlaylistPref.getLocalPlaylists(this@MyApplication);Unit},
                "local tracks" to {com.music.spotui.data.preferences.getLocalTracks(this@MyApplication);Unit},
                "downloads" to {com.music.spotui.data.preferences.downloadedPathForQuery(this@MyApplication,"");Unit},
                "liked songs" to {com.music.spotui.data.api.LibraryContentMemory.read(this@MyApplication,"liked");Unit},
                "local likes" to {com.music.spotui.data.api.LibraryContentMemory.seedLocalLikes(this@MyApplication)},
                "library" to {com.music.spotui.data.api.LibraryCatalogCache.read(this@MyApplication);Unit},
            )
            tasks.forEach { (name,load)->
                try {
                    load()
                } catch (cancelled:CancellationException) {
                    throw cancelled
                } catch (failure:Exception) {
                    android.util.Log.w("SpotuiStartup","Could not warm $name",failure)
                }
            }
            com.music.spotui.sync.OwnLibraryChanges.changed()
        }
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
        appScope.launch {
            YouTube.visitorData = YouTube.visitorData().getOrNull() ?: YouTube.visitorData
        }
        // YouTube playback runs anonymously; age-gated official audio falls back
        // to matching normal YouTube uploads instead of requiring sign-in.
        YouTube.cookie = null

        // Pre-warm the YouTube playback pipeline so the first tap doesn't pay for
        // cold-starting the player.js parser and BotGuard WebView (~2-4s combined).
        appScope.launch {
            runCatching { com.metrolist.innertube.NewPipeExtractor.init() }
        }
    }
}
