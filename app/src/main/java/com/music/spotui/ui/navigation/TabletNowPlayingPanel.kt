package com.music.spotui.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.music.spotui.ui.screens.PlayerScreen
import com.music.spotui.ui.viewmodel.PlayerViewModel

/**
 * Same Now Playing experience as the mobile full-screen player, rendered inside
 * the tablet's persistent right pane. It shares PlayerViewModel and SongPlayer;
 * only the visual presentation differs. No second audio engine is created.
 */
@Composable
fun TabletNowPlayingPanel(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onCollapse: () -> Unit,
    playerViewModel: PlayerViewModel,
) {
    androidx.compose.foundation.layout.Box(modifier) {
        PlayerScreen(navController, embedded = true, onCollapse = onCollapse, playerViewModel = playerViewModel)
    }
}
