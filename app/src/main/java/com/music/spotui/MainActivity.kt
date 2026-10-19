package com.music.spotui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?){

        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(com.music.spotui.data.preferences.getAppLanguage(this))
        )

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

            SpotuiTheme {
                // A surface container using the 'background' color from the theme
                    App()

                // New-release check (GitHub): prompts Upgrade / Dismiss / Don't show again.
                com.music.spotui.ui.components.UpdatePrompt()

                // Default link handler prompt: helps user enable open.spotify.com defaults.
                com.music.spotui.ui.components.DefaultAppPrompt()
            }
        }

        // Experimental Spotify web-player engine: attach its hidden WebView deferred
        // via postDelayed so cold-start UI inflation is unblocked and ANR is prevented.
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
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

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}



