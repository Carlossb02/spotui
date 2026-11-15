package com.music.spotui.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowingRefreshContainer(enabled:Boolean=true,refreshing:Boolean,onRefresh:()->Unit,
    modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    if(enabled)PullToRefreshBox(isRefreshing=refreshing,onRefresh=onRefresh,modifier=modifier,content=content)
    else Box(modifier,content=content)
}
