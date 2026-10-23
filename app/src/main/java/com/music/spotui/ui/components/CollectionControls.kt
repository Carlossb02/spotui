package com.music.spotui.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A shared, gesture-driven search reveal for ordinary playlists and Liked Songs.
 * The list itself owns the gesture; this component does not intercept clicks or flings.
 * Both the pull offset and the expanded state are held together so no detached overlay
 * can wind up under the device status bar.
 */
class CollectionSearchState internal constructor(
    private val listState: LazyListState,
    private val densityScale: Float
) {
    var expanded by mutableStateOf(false)
        private set
    var pullPx by mutableFloatStateOf(0f)
        private set
    private var isPulling by mutableStateOf(false)
    private var upwardScrollPx = 0f
    // Called only for a deliberate upward drag; not for programmatic list changes.
    var onDismissByScroll: (() -> Unit)? = null

    private val maxPullPx get() = 78f * densityScale
    private val revealThresholdPx get() = 44f * densityScale

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.Drag) return Offset.Zero
            if (expanded) {
                // A short list may not consume any scroll distance; in that case
                // the remaining drag distance must still be able to close search.
                val upwardDelta = minOf(consumed.y, available.y)
                if (upwardDelta < 0f) {
                    upwardScrollPx += -upwardDelta
                    if (upwardScrollPx >= 32f * densityScale) {
                        close()
                        onDismissByScroll?.invoke()
                    }
                } else if (consumed.y > 0f) upwardScrollPx = 0f
                return Offset.Zero
            }
            val atTop = !listState.canScrollBackward
            if (atTop && available.y > 0f) {
                isPulling = true
                pullPx = (pullPx + available.y * .85f).coerceAtMost(maxPullPx)
            } else if (isPulling && available.y < 0f) {
                pullPx = (pullPx + available.y).coerceAtLeast(0f)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (isPulling) finishPull()
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (isPulling) finishPull()
            return Velocity.Zero
        }
    }

    private fun finishPull() {
        if (pullPx >= revealThresholdPx) expanded = true
        isPulling = false
        pullPx = 0f
    }

    fun close() {
        expanded = false
        pullPx = 0f
        isPulling = false
        upwardScrollPx = 0f
    }

    fun reveal() { expanded = true; upwardScrollPx = 0f }

    internal fun searchHeight(query: String): Dp = when {
        expanded || query.isNotBlank() -> 76.dp
        isPulling -> (pullPx / densityScale).dp.coerceIn(0.dp, 76.dp)
        else -> 0.dp
    }

    internal val dragging: Boolean get() = isPulling
}

@Composable
fun rememberCollectionSearchState(listState: LazyListState, key: Any): CollectionSearchState {
    val density = LocalDensity.current.density
    return remember(listState, key, density) { CollectionSearchState(listState, density) }
}

@Composable
fun CollectionPullSearch(
    state: CollectionSearchState,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Buscar en la lista"
) {
    val targetHeight = state.searchHeight(query)
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(durationMillis = if (state.dragging) 0 else 210),
        label = "collectionSearchReveal"
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Box(
        modifier = Modifier.fillMaxWidth().height(animatedHeight).clipToBounds(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                height = 52.dp
            )
            Box(
                modifier = Modifier.size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onQueryChange("")
                        keyboard?.hide()
                        focusManager.clearFocus()
                        state.close()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar búsqueda", tint = Color.White)
            }
        }
    }
}

@Composable
fun CollectionSortRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Sort, contentDescription = null, tint = Color(0xFFD9D9D9), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Text(label, color = Color(0xFFECECEC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Cambiar orden", tint = Color(0xFFB3B3B3), modifier = Modifier.size(18.dp))
        }
    }
}

/** A compact, fully working ordering sheet, shared across both collection screens.
 * Tapping the selected option again reverses its direction.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CollectionSortSheet(
    selected: String,
    descending: Boolean,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val choices = listOf(
        "DATE" to "Fecha de incorporación",
        "TITLE" to "Título",
        "ARTIST" to "Artista",
        "ALBUM" to "Álbum"
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF202020)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
            Text("Ordenar por", color = Color.White, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            Text("Pulsa de nuevo la opción seleccionada para invertir el orden",
                color = Color(0xFFB3B3B3), fontSize = 12.sp,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
            HorizontalDivider(color = Color(0xFF383838))
            choices.forEach { (key, title) ->
                val active = selected == key
                Row(modifier = Modifier.fillMaxWidth().clickable { onSelected(key) }
                    .padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp,
                        color = if (active) Color(0xFF1ED760) else Color.White,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    if (active) {
                        Icon(if (descending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (descending) "Descendente" else "Ascendente",
                            tint = Color(0xFF1ED760), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

fun collectionSortLabel(option: String, descending: Boolean): String = when (option) {
    "DATE" -> if (descending) "Recientes" else "Más antiguas"
    "TITLE" -> if (descending) "Título (Z–A)" else "Título (A–Z)"
    "ARTIST" -> if (descending) "Artista (Z–A)" else "Artista (A–Z)"
    "ALBUM" -> if (descending) "Álbum (Z–A)" else "Álbum (A–Z)"
    else -> "Recientes"
}
