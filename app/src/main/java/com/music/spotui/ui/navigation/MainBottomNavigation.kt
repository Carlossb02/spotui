package com.music.spotui.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.ui.components.MiniPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class NoRippleInteractionSource : MutableInteractionSource {
    override val interactions: Flow<Interaction> = emptyFlow()
    override suspend fun emit(interaction: Interaction) {}
    override fun tryEmit(interaction: Interaction) = true
}

/**
 * Three real Spotui destinations only. Wide Android tablets use Spotify's
 * inline bottom-bar pattern: navigation and mini player are siblings, rather
 * than stacking the mini player above the navigation.
 */
@Composable
fun MainBottomNavigation(
    navController: NavHostController,
    bottomBarState: MutableState<Boolean>,
    bottomBarPlayerState: MutableState<Boolean>,
    onSearchReselected: () -> Unit = {},
    tabletInlineMiniPlayer: Boolean = false,
    onExpandTabletPlayer: (() -> Unit)? = null,
    showMiniPlayer: Boolean = true,
) {
    AnimatedVisibility(
        visible = bottomBarState.value,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black, Color.Black)
                )
            )
        ) {
            if (tabletInlineMiniPlayer) {
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val miniVisible = bottomBarPlayerState.value && showMiniPlayer
                    val gap = 8.dp
                    val usableWidth = maxWidth
                    val miniWidth = minOf(338.dp, usableWidth * 0.44f)
                    val navWidth = if (miniVisible) {
                        minOf(430.dp, (usableWidth - miniWidth - gap).coerceAtLeast(180.dp))
                    } else minOf(430.dp, usableWidth)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        NavigationTabs(
                            navController = navController,
                            modifier = Modifier.width(navWidth).height(62.dp)
                                .background(Color(0xFF202020), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                            onSearchReselected = onSearchReselected
                        )
                        AnimatedVisibility(visible = miniVisible) {
                            Box(Modifier.width(miniWidth)) {
                                // The dock and miniplayer are a single centered group.
                                MiniPlayer(navController, onExpandPlayer = onExpandTabletPlayer,
                                    tabletLayout = true)
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    AnimatedVisibility(
                        visible = bottomBarPlayerState.value && showMiniPlayer,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) { MiniPlayer(navController) }
                    Spacer(modifier = Modifier.height(6.dp))
                    NavigationTabs(
                        navController = navController,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        onSearchReselected = onSearchReselected,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationTabs(
    navController: NavHostController,
    modifier: Modifier,
    onSearchReselected: () -> Unit,
) {
    val navItems = listOf(Routes.Home, Routes.Search, Routes.Library)
    val navStack by navController.currentBackStackEntryAsState()
    val currentRoute = navStack?.destination?.route
    val rootRoutes = listOf(Routes.Home.route, Routes.Search.route, Routes.Library.route)
    var currentTab by rememberSaveable { mutableStateOf(Routes.Home.route) }
    if (currentRoute in rootRoutes) currentTab = currentRoute!!

    NavigationBar(
        modifier = modifier,
        containerColor = Color.Transparent,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = currentTab == item.route,
                icon = {
                    Icon(painter = painterResource(item.icon), contentDescription = item.label,
                        modifier = Modifier.height(21.dp))
                },
                label = {
                    val labelText = when (item.route) {
                        Routes.Home.route -> androidx.compose.ui.res.stringResource(com.music.spotui.R.string.home)
                        Routes.Search.route -> androidx.compose.ui.res.stringResource(com.music.spotui.R.string.search)
                        Routes.Library.route -> androidx.compose.ui.res.stringResource(com.music.spotui.R.string.library)
                        else -> item.label
                    }
                    Text(labelText, color = if (currentTab == item.route) Color.White else Color.Gray,
                        fontSize = 11.sp, maxLines = 1)
                },
                onClick = {
                    if (currentRoute == item.route) {
                        if (item.route == Routes.Search.route) onSearchReselected()
                    } else {
                        if (!navController.popBackStack(item.route, inclusive = false)) {
                            navController.navigate(item.route) {
                                navController.graph.startDestinationRoute?.let { startRoute ->
                                    popUpTo(startRoute) { saveState = false }
                                }
                                launchSingleTop = true
                            }
                        }
                    }
                },
                alwaysShowLabel = true,
                interactionSource = NoRippleInteractionSource(),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    unselectedIconColor = Color.Gray,
                    indicatorColor = Color.Transparent
                ),
            )
        }
    }
}
