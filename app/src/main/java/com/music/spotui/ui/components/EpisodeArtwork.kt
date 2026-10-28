package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.PodcastResumeStore
import com.music.spotui.ui.notification.PodcastCover

/** Progress is sourced from the service's observable store; no screen refresh or polling. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun EpisodeArtwork(
    episode: SongsModel,
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
    contentScale: ContentScale = ContentScale.Crop,
    imageOverride: String? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PodcastResumeStore.ensureLoaded(context) }
    val progress by remember(episode.url) {
        PodcastResumeStore.observeEpisode(episode.url)
    }.collectAsState()
    Box(modifier = modifier.clip(RoundedCornerShape(radius))) {
        GlideImage(
            model = imageOverride?.takeIf { it.isNotBlank() } ?: PodcastCover.preferredUri(context, episode),
            contentDescription = episode.title,
            contentScale = contentScale,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            modifier = Modifier.fillMaxSize(),
        )
        val p = progress
        if (p != null && p.fraction > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth().height(4.dp)
                    .align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.65f))
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction)
                    .background(Color(0xFF1ED760)))
            }
        }
    }
}
