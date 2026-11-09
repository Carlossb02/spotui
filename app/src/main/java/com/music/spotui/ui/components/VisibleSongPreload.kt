package com.music.spotui.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/** Warm the rows the user can actually tap, after scrolling settles. Matching runs unchanged. */
@OptIn(FlowPreview::class)
@Composable
internal fun VisibleSongPreload(state: LazyListState, rows: List<Pair<String, SongsModel>>) {
    val context = LocalContext.current
    val byKey = remember(rows) { rows.toMap() }
    LaunchedEffect(state, rows) {
        snapshotFlow {
            if (state.isScrollInProgress) null else {
                state.layoutInfo.visibleItemsInfo.mapNotNull { byKey[it.key] }.take(3)
                    .ifEmpty { rows.take(2).map { it.second } }
            }
        }.filterNotNull().distinctUntilChanged().debounce(120L).collect { visible ->
            SongPlayer.registerTracksForResolution(visible)
            SongPlayer.prefetchList(visible.map { it.url }, context, count = 3)
        }
    }
}
