package com.music.spotui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import kotlinx.coroutines.flow.first

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.music.spotui.ui.navigation.MainBottomNavigation
import com.music.spotui.ui.navigation.TabletNowPlayingPanel
import com.music.spotui.ui.navigation.MyNavHost
import com.music.spotui.ui.navigation.Routes


@RequiresApi(Build.VERSION_CODES.S)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    val bottomBarState = rememberSaveable { (mutableStateOf(true)) }
    val bottomBarPlayerState = rememberSaveable { (mutableStateOf(true)) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val playerViewModel: com.music.spotui.ui.viewmodel.PlayerViewModel = hiltViewModel()
    val currentSongId by playerViewModel.currentSongId
    val currentQueue by playerViewModel.queue
    val currentPodcastId = currentQueue.firstOrNull { it.id == currentSongId }
        ?.url?.takeIf { it.startsWith("yt_ep:") }
        ?.removePrefix("yt_ep:")?.substringBefore('|')
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    val foregroundLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(currentPodcastId) {
        com.music.spotui.ui.screens.PodcastVideoSession.stopIfDifferent(currentPodcastId)
    }
    // One lightweight audio/video clock for the application, independent of any
    // particular player layout. Does not write Compose state every frame.
    LaunchedEffect(foregroundLifecycle) {
        while (true) {
            com.music.spotui.ui.screens.PodcastVideoSession.sync(
                foregroundLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            )
            kotlinx.coroutines.delay(
                if (com.music.spotui.ui.screens.PodcastVideoSession.activeVideoId != null) 750L else 1500L
            )
        }
    }
    val playerState by playerViewModel.currentSongTitle
    var lastRoute by remember { mutableStateOf<String?>(null) }
    // Incremented each time the user re-taps the Search bottom-nav icon while
    // already on the search route, so SearchScreen can focus its text field.
    var searchFocusTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(currentRoute, playerState) {
        if (currentRoute != Routes.Player.route) {
            bottomBarState.value = when (currentRoute) {
                Routes.Login.route, Routes.Queue.route -> false
                else -> true
            }
            bottomBarPlayerState.value = when (currentRoute) {
                Routes.Login.route, Routes.Queue.route -> false
                else -> playerState.isNotEmpty()
            }
        }
        lastRoute = currentRoute
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(navController) {
        // Process any cold-start deep link intent that launched the app
        com.music.spotui.util.DeepLinkHandler.consumePendingUri()?.let { uri ->
            com.music.spotui.util.DeepLinkHandler.processUri(
                uri,
                context,
                navController,
                playerViewModel,
                scope
            )
        }
        // Process any new deep link intent while the app is active
        com.music.spotui.util.DeepLinkHandler.deepLinkFlow.collect { uri ->
            com.music.spotui.util.DeepLinkHandler.processUri(
                uri,
                context,
                navController,
                playerViewModel,
                scope
            )
        }
    }

    // Persist only the tablet mini/side choice, never the full-screen player route.
    // SharedPreferences survive process death; no media service state is touched.
    val tabletDisplayPrefs = remember(context) {
        context.getSharedPreferences("tablet_player_layout", android.content.Context.MODE_PRIVATE)
    }
    var tabletPlayerExpanded by rememberSaveable {
        mutableStateOf(tabletDisplayPrefs.getBoolean("side_panel_expanded", true))
    }
    fun setTabletPanelExpanded(expanded: Boolean) {
        tabletPlayerExpanded = expanded
        tabletDisplayPrefs.edit().putBoolean("side_panel_expanded", expanded).apply()
    }
    val tabletStarting = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    // Navigation Compose may restore a dialog (full-screen player) after process death.
    // The initial tablet destination must be mini/side, not that restored dialog.
    LaunchedEffect(navController, tabletStarting) {
        if (tabletStarting) {
            // Wait until Navigation has installed/restored its graph before inspecting it.
            navController.currentBackStackEntryFlow.first()
            kotlinx.coroutines.delay(100L)
            if (navController.currentDestination?.route == Routes.Player.route) {
                navController.popBackStack()
            }
        }
    }
    val isTabletDevice = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Spotify's 2026 adaptive tablet pattern: browse and playback side-by-side.
        // A narrow portrait tablet keeps the familiar handset navigation instead
        // of squeezing song rows into a permanent left rail.
        val availableWidth = maxWidth
        val isWideTablet = isTabletDevice && availableWidth >= 840.dp
        val showTabletPlayerPanel = isWideTablet && currentRoute != Routes.Login.route &&
            tabletPlayerExpanded && playerState.isNotBlank() && currentRoute != Routes.Player.route
        if (isWideTablet && currentRoute != Routes.Login.route) {
            val showPlayerPanel = showTabletPlayerPanel
            Row(modifier = Modifier.fillMaxSize().background(Color(0xFF080808))) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color(0xFF080808),
                        bottomBar = {
                            MainBottomNavigation(
                                navController = navController,
                                bottomBarState = bottomBarState,
                                bottomBarPlayerState = bottomBarPlayerState,
                                onSearchReselected = { searchFocusTrigger++ },
                                tabletInlineMiniPlayer = true,
                                showMiniPlayer = !showPlayerPanel,
                                onExpandTabletPlayer = if (!showPlayerPanel && playerState.isNotBlank()) {
                                    { setTabletPanelExpanded(true) }
                                } else null
                            )
                        }
                    ) { _ ->
                        MyNavHost(
                            navHostController = navController,
                            searchFocusTrigger = searchFocusTrigger
                        )
                    }

                }
                val panelWidth = (availableWidth * 0.38f).coerceIn(312.dp, 440.dp)
                AnimatedVisibility(
                    visible = showPlayerPanel,
                    enter = expandHorizontally(
                        expandFrom = Alignment.End,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
                    ) + fadeIn(tween(240)),
                    exit = shrinkHorizontally(
                        shrinkTowards = Alignment.End,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                    ) + fadeOut(tween(250))
                ) {
                    TabletNowPlayingPanel(
                        navController = navController,
                        modifier = Modifier.width(panelWidth).fillMaxHeight(),
                        onCollapse = { setTabletPanelExpanded(false) },
                        playerViewModel = playerViewModel
                    )
                }
            }
            BackHandler(enabled = showTabletPlayerPanel) {
                setTabletPanelExpanded(false)
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    MainBottomNavigation(
                        navController = navController,
                        bottomBarState = bottomBarState,
                        bottomBarPlayerState,
                        onSearchReselected = { searchFocusTrigger++ },
                        tabletInlineMiniPlayer = isTabletDevice
                    )
                }
            ) {
                MyNavHost(navHostController = navController, searchFocusTrigger = searchFocusTrigger)
            }
        }
        // Keep one tiny visual surface in the activity so hiding the video or
        // moving between mini/fullscreen does not tear down its decoder.
        com.music.spotui.ui.screens.PodcastStandbyVideoSurface(currentPodcastId)
    }
}
