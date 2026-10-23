package com.music.spotui.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.R

/**
 * Permanent wide-screen navigation. The handset keeps MainBottomNavigation.
 * Does not create another navigation graph or a second PlayerViewModel.
 */
@Composable
fun TabletSidebarNavigation(
    navController: NavHostController,
    onSearchReselected: () -> Unit = {}
) {
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val sidebarWidth = if (widthDp < 840) 192.dp else 224.dp
    val backstack by navController.currentBackStackEntryAsState()
    val route = backstack?.destination?.route.orEmpty()

    Column(
        modifier = Modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .background(Color(0xFF111113))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text("Spotui", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }

        listOf(
            Triple(Routes.Home, "Inicio", Routes.Home.icon),
            Triple(Routes.Search, "Buscar", Routes.Search.icon),
            Triple(Routes.Library, "Tu biblioteca", Routes.Library.icon)
        ).forEach { (destination, title, iconId) ->
            val selected = route == destination.route
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(
                        if (selected) Color(0xFF28282C) else Color.Transparent,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable {
                        if (selected && destination.route == Routes.Search.route) {
                            onSearchReselected()
                        } else if (!selected) {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(iconId),
                    contentDescription = null,
                    tint = if (selected) Color.White else Color(0xFFB3B3B3),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    title,
                    color = if (selected) Color.White else Color(0xFFB3B3B3),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = Color(0xFF2B2B2E))
        Spacer(Modifier.height(18.dp))
        Text(
            "TU MÚSICA",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFB3B3B3),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
        listOf(
            Triple("Canciones que te gustan", Routes.Liked.route, "♥"),
            Triple("Descargas", Routes.Downloads.route, "↓"),
            Triple("Cola de reproducción", Routes.Queue.route, "☷")
        ).forEach { (title, destination, symbol) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (route == destination) Color(0xFF28282C) else Color.Transparent,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable {
                        if (route != destination) navController.navigate(destination) {
                            launchSingleTop = true
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(symbol, color = Color(0xFFB3B3B3), fontSize = 18.sp)
                Spacer(Modifier.width(13.dp))
                Text(
                    title,
                    fontSize = 13.sp,
                    color = if (route == destination) Color.White else Color(0xFFB3B3B3),
                    maxLines = 1
                )
            }
        }
    }
}
