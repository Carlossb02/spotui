package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.publicationTime
import com.music.spotui.di.SongPlayer
import com.music.spotui.util.ExpiringLruCache
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val releaseDetailsCache=ExpiringLruCache<String,com.metrolist.spotify.models.SpotifyAlbum>(64,30*60_000L)
private var activeReleasePreview: kotlinx.coroutines.Job? = null

@Composable
internal fun FollowingMusicReleaseCard(album:AlbumsModel,saved:Boolean,loading:Boolean,
    onOpen:()->Unit,onMore:()->Unit,onSave:()->Unit,onPlay:()->Unit) {
    val context=LocalContext.current
    var background by remember(album.coverUri){mutableStateOf(Color(0xFF282828))}
    var details by remember(album.spotifyAlbumId){mutableStateOf(releaseDetailsCache.get(album.spotifyAlbumId))}
    val scope = rememberCoroutineScope()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var previewJob by remember(album.spotifyAlbumId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var previewLoading by remember(album.spotifyAlbumId) { mutableStateOf(false) }
    var previewFailed by remember(album.spotifyAlbumId) { mutableStateOf(false) }
    var previewing by remember {mutableStateOf(false)}
    LaunchedEffect(album.coverUri) {
        SongPlayer.awaitAudioStartup()
        if(album.coverUri.isNotBlank())com.music.spotui.di.Palette().extractSecondColorFromCoverUrl(context,album.coverUri) {
            background=lerp(it,Color.Black,0.60f)
        }
    }
    LaunchedEffect(album.spotifyAlbumId) {
        if(album.spotifyAlbumId.isBlank() || details!=null)return@LaunchedEffect
        SongPlayer.awaitAudioStartup()
        details=withContext(Dispatchers.IO) { loadReleaseDetails(context, album.spotifyAlbumId) }
    }
    DisposableEffect(lifecycle, album.spotifyAlbumId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) previewJob?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            previewJob?.cancel()
        }
    }

    fun togglePreview() {
        if (previewJob?.isActive == true) {
            previewJob?.cancel()
            return
        }
        val previous = activeReleasePreview
        previous?.cancel()
        previewFailed = false
        previewLoading = true
        previewJob = scope.launch {
            val ownedJob = coroutineContext[kotlinx.coroutines.Job]
            var player: ExoPlayer? = null
            var resume: (() -> Unit)? = null
            try {
                previous?.join()
                val release = details ?: withContext(Dispatchers.IO) { loadReleaseDetails(context, album.spotifyAlbumId) }
                if (release == null) {
                    previewFailed = true
                    return@launch
                }
                details = release
                val direct = release.tracks?.items.orEmpty().firstNotNullOfOrNull { it.previewUrl?.takeIf(String::isNotBlank) }
                val engine = ExoPlayer.Builder(context).build().also {
                    player = it
                    it.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                }

                suspend fun playSample(uri: String, startMs: Long): Boolean {
                    val started = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    val ended = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    val events = object : androidx.media3.common.Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                previewLoading = false
                                previewing = true
                                started.complete(true)
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == androidx.media3.common.Player.STATE_ENDED) ended.complete(true)
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            started.complete(false)
                            ended.complete(false)
                        }
                    }
                    engine.addListener(events)
                    val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs).setEndPositionMs(startMs + 30_000L).build(),
                    ).build()
                    if (resume == null) resume = SongPlayer.pauseForCollectionStories()
                    return try {
                        engine.setMediaItem(item)
                        engine.prepare()
                        engine.play()
                        kotlinx.coroutines.withTimeoutOrNull(8_000L) { started.await() } == true &&
                            kotlinx.coroutines.withTimeoutOrNull(45_000L) { ended.await() } == true
                    } finally {
                        engine.removeListener(events)
                        engine.stop()
                        previewing = false
                    }
                }

                if (direct != null && playSample(direct, 0L)) return@launch
                previewLoading = true
                val fallback = withContext(Dispatchers.IO) {
                    val song = com.music.spotui.data.api.Api(context.applicationContext).previewTrack(release)
                        ?: return@withContext null
                    val audio = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                        SongPlayer.resolveCollectionStory(song, context.applicationContext)
                    } ?: return@withContext null
                    audio to ((song.durationMs.toLong() - 30_000L) / 2).coerceAtLeast(0L)
                }
                previewFailed = fallback == null || !playSample(fallback.first, fallback.second)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("FollowingMusicPreview", "Preview could not start", error)
                previewFailed = true
            } finally {
                player?.release()
                // Switching samples must first restore the audio the old sample paused.
                resume?.invoke()
                if (activeReleasePreview === ownedJob) activeReleasePreview = null
                previewLoading = false
                previewing = false
            }
        }.also { activeReleasePreview = it }
    }

    val songs=details?.tracks?.items.orEmpty()
    val count=details?.totalTracks?.takeIf {it>0} ?: songs.size
    val artist=details?.artists?.takeIf {it.isNotEmpty()}?.joinToString(", "){it.name} ?: album.artists
    val published=publicationTime(album.time,System.currentTimeMillis())
    val date=published?.let {com.music.spotui.data.relativePublicationTime(it)}.orEmpty()
    Card(shape=RoundedCornerShape(12.dp),colors=CardDefaults.cardColors(containerColor=background),
        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).clickable(onClick=onOpen)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val wide=maxWidth>650.dp
            val cover=if(wide)210.dp else 112.dp
            Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                GlideImage(model=album.coverUri,contentDescription=album.name,contentScale=ContentScale.Crop,
                    modifier=Modifier.size(cover).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f).height(cover),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(artist,color=Color.White,fontSize=if(wide)17.sp else 14.sp,fontWeight=FontWeight.Bold,
                                maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(album.name+if(date.isNotBlank())" · $date" else "",color=Color(0xFFCCCCCC),
                                fontSize=if(wide)16.sp else 12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        IconButton(onClick=onMore,modifier=Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert,uiLocalized("Options"),tint=Color(0xFFCCCCCC),modifier=Modifier.size(20.dp))
                        }
                    }
                    if(wide || count>0)Row(verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(songs.any {it.explicit})com.music.spotui.ui.components.ExplicitBadge()
                    Text(if(count>0)"$count ${uiLocalized(if(count==1)"song" else "songs")}"+
                        songs.take(12).joinToString(" · ",prefix=if(songs.isEmpty())"" else " · "){it.name}
                        else uiLocalized(if(album.type=="single")"Single" else "Album"),color=Color(0xFFCCCCCC),
                        fontSize=if(wide)15.sp else 11.sp,maxLines=if(wide)2 else 1,overflow=TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        val sampleLabel=uiLocalized(when {
                            previewLoading || previewing -> "Stop preview"
                            previewFailed -> "Retry preview"
                            album.type=="single" -> "Preview single"
                            else -> "Preview album"
                        })
                        Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha=0.28f))
                            .clickable(enabled=album.spotifyAlbumId.isNotBlank(), onClick=::togglePreview)
                            .padding(horizontal=if(wide)12.dp else 6.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
                            if (previewLoading) CircularProgressIndicator(Modifier.size(20.dp), color=Color.White, strokeWidth=2.dp)
                            else Icon(if(previewing)Icons.Default.VolumeUp else Icons.Default.VolumeOff,sampleLabel,
                                tint=Color.White.copy(alpha=0.85f),modifier=Modifier.size(20.dp))
                            if(wide){Spacer(Modifier.width(6.dp));Text(sampleLabel,color=Color.White.copy(alpha=0.8f),fontSize=14.sp)}
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick=onSave,modifier=Modifier.size(if(wide)44.dp else 32.dp)) {
                            Icon(if(saved)Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                                uiLocalized(if(saved)"Saved" else "Save"),tint=Color.White,modifier=Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(if(wide)12.dp else 4.dp))
                        IconButton(onClick={activeReleasePreview?.cancel();onPlay()},enabled=!loading,
                            modifier=Modifier.size(if(wide)48.dp else 36.dp).clip(CircleShape).background(Color.White)) {
                            if(loading)CircularProgressIndicator(Modifier.size(20.dp),color=Color.Black,strokeWidth=2.dp)
                            else Icon(Icons.Default.PlayArrow,uiLocalized("Play release"),tint=Color.Black,modifier=Modifier.size(30.dp))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadReleaseDetails(context: android.content.Context, albumId: String): com.metrolist.spotify.models.SpotifyAlbum? {
    if (albumId.isBlank()) return null
    releaseDetailsCache.get(albumId)?.let { return it }
    if (!SpotifyTokenProvider.ensureToken(context)) return null
    return com.metrolist.spotify.Spotify.album(albumId).getOrNull()?.also { releaseDetailsCache.put(albumId, it) }
}