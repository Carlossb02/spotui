package com.music.spotui.ui.screens

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.music.spotui.data.preferences.setYoutubeCookie
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.delay

/**
 * Optional YouTube sign-in. A full-screen WebView loads YouTube's real login
 * page; once the session cookies (specifically SAPISID) are set, we capture the
 * cookie string, hand it to the InnerTube client, and persist it. This unlocks
 * age-restricted / login-required videos on the YouTube fallback engine.
 *
 * Uses cookie auth only — no microG / Google Play Services required.
 */
private const val YT_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"

@Composable
fun YoutubeLoginScreen(navController: NavController, onDone: () -> Unit) {
    var captured by remember { mutableStateOf(false) }

    val finish: () -> Unit = {
        navController.navigate(Routes.Home.route) {
            popUpTo(Routes.YoutubeLogin.route) { inclusive = true }
        }
        onDone()
    }

    // Poll for the logged-in YouTube cookies. SAPISID appears on google.com the
    // moment sign-in completes, regardless of which page the WebView lands on.
    LaunchedEffect(Unit) {
        while (!captured) {
            delay(1000)
            val cookie = youtubeCookie()
            if (cookie != null && cookie.contains("SAPISID")) {
                captured = true
                YouTube.cookie = cookie
                setYoutubeCookie(navController.context, cookie)
                finish()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 44.dp),
            factory = { ctx ->
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                WebView(ctx).apply {
                    cm.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}
                        override fun onPageFinished(view: WebView?, url: String?) {}
                    }
                    loadUrl(YT_LOGIN_URL)
                }
            },
        )

        // Top bar: title on the left, a Skip action on the right (YouTube login is
        // optional — used only to unlock age-restricted tracks).
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)
                .background(Color.Black).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sign in to YouTube (optional)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Skip",
                color = Color(0xFF1ED760),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.clickable { finish() },
            )
        }
    }
}

/** Collect the current YouTube/Google cookie string for InnerTube auth. */
private fun youtubeCookie(): String? {
    val cm = CookieManager.getInstance()
    // SAPISID lives on the google.com domain; the music.youtube.com cookies carry
    // VISITOR_INFO etc. Merge both so the InnerTube auth header has what it needs.
    val google = cm.getCookie("https://www.google.com").orEmpty()
    val ytm = cm.getCookie("https://music.youtube.com").orEmpty()
    val merged = listOf(google, ytm).filter { it.isNotBlank() }.joinToString("; ")
    return merged.takeIf { it.isNotBlank() }
}
