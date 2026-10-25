package com.music.spotui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.Rational
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import com.music.spotui.ui.screens.PodcastVideoPipState
import com.music.spotui.ui.screens.PodcastVideoSession
import com.music.spotui.ui.screens.PodcastPipContent
import com.music.spotui.di.SongPlayer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.music.spotui.ui.notification.PlaybackService
import com.music.spotui.ui.theme.SpotuiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pipAutoEnterEnabled = false
    private var lastPipVideoSize: Pair<Int, Int>? = null

    /** Configure system PiP only for a currently playing video podcast. */
    private fun canEnterPodcastPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            PodcastVideoSession.activeVideoId != null &&
            PodcastVideoSession.player != null

    private fun refreshPodcastPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // Automatic background PiP only when playing; the explicit PiP control
        // also works while paused, showing the last decoded video frame.
        val canEnter = canEnterPodcastPip() && SongPlayer.isPlaying()
        val size = PodcastVideoSession.player?.videoSize?.let { it.width to it.height }
        if (canEnter == pipAutoEnterEnabled && size == lastPipVideoSize) return
        val w = size?.first ?: 16
        val h = size?.second ?: 9
        val ratio = (if (w > 0 && h > 0) w.toFloat() / h else 16f / 9f)
            .coerceIn(0.45f, 2.2f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Enter explicitly from onUserLeaveHint: pre-compose the PiP video first.
            builder.setAutoEnterEnabled(false)
            builder.setSeamlessResizeEnabled(true)
        }
        runCatching { setPictureInPictureParams(builder.build()) }
            .onSuccess {
                pipAutoEnterEnabled = canEnter
                lastPipVideoSize = size
            }
            .onFailure { android.util.Log.w("SpotuiPiP", "Could not configure PiP", it) }
    }

    /** Also exposed as a control in the video overlay. Never starts a second player. */
    fun enterPodcastPictureInPicture() {
        if (!canEnterPodcastPip()) {
            PodcastVideoSession.finishPipHandoff()
            return
        }
        PodcastVideoPipState.isInPictureInPicture = true
        refreshPodcastPipParams()
        val entered = runCatching { enterPictureInPictureMode() }
            .onFailure { android.util.Log.w("SpotuiPiP", "Unable to enter PiP", it) }
            .isSuccess
        if (!entered) {
            PodcastVideoPipState.isInPictureInPicture = false
            PodcastVideoSession.finishPipHandoff()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Handoff irrespective of whether video is mini, lateral or fullscreen.
        // Android's automatic entry would capture the rest of the app on some Xiaomi devices.
        if (canEnterPodcastPip() && SongPlayer.isPlaying()) enterPodcastPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PodcastVideoPipState.isInPictureInPicture = isInPictureInPictureMode
        PodcastVideoSession.finishPipHandoff()
        if (!isInPictureInPictureMode) refreshPodcastPipParams()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?){

        super.onCreate(savedInstanceState)
        com.music.spotui.data.preferences.SpotuiLanguage.initialize(this)

        // Ask for notification permission (Android 13+) so the media notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Connect a MediaController to bind PlaybackService. MediaSessionService
        // automatically promotes itself to a foreground service when playback begins.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()


        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightNavigationBars = false
        setContent {
            // ComponentActivity does not install AppCompat locales into Compose resources.
            // A localized Context + Configuration lets all stringResource callers refresh live.
            val appLanguage = com.music.spotui.data.preferences.SpotuiLanguage.current
            val parentContext = LocalContext.current
            val systemConfiguration = LocalConfiguration.current
            val localeConfiguration = remember(appLanguage, systemConfiguration) {
                Configuration(systemConfiguration).apply {
                    val locale = java.util.Locale.forLanguageTag(appLanguage)
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
            }
            val localizedContext = remember(parentContext, localeConfiguration) {
                object : ContextWrapper(parentContext) {
                    private val translated = parentContext.createConfigurationContext(localeConfiguration)
                    override fun getResources(): Resources = translated.resources
                    override fun getAssets(): android.content.res.AssetManager = translated.assets
                    override fun getTheme(): Resources.Theme = translated.theme
                }
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localeConfiguration
            ) {
            SpotuiTheme {
                // Keep the original Compose navigation and audio session mounted while
                // Android displays only the video surface in its PiP window.
                Box(Modifier.fillMaxSize()) {
                    // Keep the NavController, back stack and Compose view-model state mounted
                    // during PiP. Merely hide its drawing; never recreate the whole app.
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (PodcastVideoPipState.isInPictureInPicture) 0f else 1f
                    }) { App() }
                    if (PodcastVideoPipState.isInPictureInPicture) {
                        // PiP displays video regardless of current mini/lateral/full layout.
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            if (PodcastVideoSession.player != null) {
                                PodcastPipContent(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                // Apply initial video availability immediately; polling below
                // only tracks changes to play/pause that are not Compose state.
                LaunchedEffect(PodcastVideoSession.activeVideoId, PodcastVideoSession.player) {
                    refreshPodcastPipParams()
                }
                // Poll only for play/video state changes, not video frames.
                LaunchedEffect(PodcastVideoSession.activeVideoId) {
                    while (PodcastVideoSession.activeVideoId != null) {
                        refreshPodcastPipParams()
                        delay(1_500L)
                    }
                }

                // No prompts or other UI should cover the tiny PiP video window.
                if (!PodcastVideoPipState.isInPictureInPicture) {
                    com.music.spotui.ui.components.UpdatePrompt()
                    com.music.spotui.ui.components.DefaultAppPrompt()
                    com.music.spotui.ui.components.BatteryOptimizationPrompt()
                }
            }
            } // Language CompositionLocalProvider
        }

        // Experimental Spotify web-player engine: attach its hidden WebView deferred
        // via postDelayed so cold-start UI inflation is unblocked and ANR is prevented.
        // A hidden Spotify WebView has a large memory footprint and is tied to
        // the Activity window. Do not allocate it when the experimental engine
        // is disabled (the default), especially on memory-constrained devices.
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed &&
                com.music.spotui.di.SongPlayer.webPlayerEnabled &&
                com.music.spotui.data.preferences.isWebPlaybackEnabled(this)
            ) {
                com.music.spotui.di.SpotifyWebPlayer.attach(this)
            }
        }, 2000L)

        // Perform background auto-backup if a backup directory is configured (deferred to avoid blocking cold-start).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000L)
            com.music.spotui.util.BackupHelper.performAutoBackup(applicationContext)
        }

        // Handle initial deep link intent if launched via Spotify link
        com.music.spotui.util.DeepLinkHandler.handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.music.spotui.util.DeepLinkHandler.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        com.music.spotui.ui.visibility.UiVisibility.update(true)
        com.music.spotui.ui.notification.PlaybackLifecycleDiagnostics.reportPreviousExit(this)
        com.music.spotui.ui.notification.PlaybackLifecycleDiagnostics.record(this, "activity_started")
    }

    override fun onPause() {
        // Suspend purely decorative playback BEFORE Android detaches the Activity
        // surfaces. Podcast PiP and the MediaSession audio player are independent
        // of this UI-only flag and must remain intact.
        com.music.spotui.ui.visibility.UiVisibility.update(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        com.music.spotui.ui.visibility.UiVisibility.update(true)
    }

    override fun onStop() {
        // Free optional UI-only video decoders while Google or another app is
        // foreground. The audio ExoPlayer belongs to PlaybackService and stays alive.
        com.music.spotui.ui.visibility.UiVisibility.update(false)
        com.music.spotui.ui.notification.PlaybackLifecycleDiagnostics.record(this, "activity_stopped")
        super.onStop()
    }

    override fun onDestroy() {
        PodcastVideoPipState.isInPictureInPicture = false
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}



