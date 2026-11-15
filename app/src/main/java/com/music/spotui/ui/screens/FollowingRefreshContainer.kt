package com.music.spotui.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowingRefreshContainer(enabled:Boolean=true,refreshing:Boolean,onRefresh:()->Unit,
    modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    var requested by remember(enabled){mutableStateOf(false)}
    LaunchedEffect(requested,refreshing) {
        if(requested && !refreshing){kotlinx.coroutines.delay(500);requested=false}
    }
    if(enabled)PullToRefreshBox(isRefreshing=refreshing && requested,onRefresh={requested=true;onRefresh()},modifier=modifier,content=content)
    else Box(modifier,content=content)
}
