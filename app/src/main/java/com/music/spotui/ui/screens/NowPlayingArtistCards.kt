package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.GlideImage
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyArtistOverview
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.util.ExpiringLruCache
import com.music.spotui.util.SharedWork
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val artistCardCache = ExpiringLruCache<String, SpotifyArtistOverview>(64, 30 * 60_000L)
private val artistCardWork = SharedWork<String, SpotifyArtistOverview?>(CoroutineScope(SupervisorJob() + Dispatchers.IO))
private data class PlayingArtist(val id: String, val name: String)
private val artistCreditsCache=ExpiringLruCache<String,List<PlayingArtist>>(128,6*60*60_000L)

@Composable
internal fun NowPlayingArtistCards(
    artistNames: String, artistIds: String, trackId: String, navController: NavController,
) {
    val context = LocalContext.current
    val creditKey="$trackId|$artistNames|$artistIds"
    var artists by remember(creditKey) { mutableStateOf(artistCreditsCache.get(creditKey) ?: run {
        val ids=artistIds.split(',').map(String::trim)
        artistNames.split(',').map(String::trim).filter(String::isNotBlank).mapIndexed {index,name->PlayingArtist(ids.getOrNull(index).orEmpty(),name)}
    }) }
    val profiles = remember { mutableStateMapOf<String, SpotifyArtistOverview>() }
    var creditsRevision by remember(creditKey){mutableIntStateOf(0)}
    // Optional exact-credit repair must not hold up profiles already known by ID
    // or the independent name fallback, and never waits for the lyrics resolver.
    LaunchedEffect(creditKey) {
        if(trackId.matches(Regex("[A-Za-z0-9]{22}")) && artists.any {it.id.isBlank()}) {
            SongPlayer.awaitAudioStartup()
            val credits=withContext(Dispatchers.IO) {withTimeoutOrNull(5000){
                if(SpotifyTokenProvider.ensureToken(context))Spotify.track(trackId).getOrNull()?.artists else null
            }}
            if(!credits.isNullOrEmpty()) {
                artists=credits.map {PlayingArtist(it.id.orEmpty(),it.name)}.distinctBy {it.id.ifBlank {it.name}}
                artistCreditsCache.put(creditKey,artists);creditsRevision++
            }
        }
    }
    LaunchedEffect(trackId, artistNames, artistIds,creditsRevision) {
        SongPlayer.awaitAudioStartup()
        if(artists.isNotEmpty() && artists.all {it.id.isNotBlank() && artistCardCache.get(it.id)!=null})return@LaunchedEffect
        if (!withContext(Dispatchers.IO) { SpotifyTokenProvider.ensureToken(context) }) return@LaunchedEffect
        artistCreditsCache.put(creditKey,artists)
        val permits = Semaphore(2)
        artists.forEach { credited ->
            launch {
                val artist = if (credited.id.isNotBlank()) credited else {
                    val exact = withContext(Dispatchers.IO) {
                        Spotify.search(credited.name, types = listOf("artist"), limit = 5).getOrNull()
                            ?.artists?.items?.firstOrNull { it.name.equals(credited.name, ignoreCase = true) }
                    } ?: return@launch
                    PlayingArtist(exact.id, exact.name).also { resolved ->
                        artists = artists.map { if (it == credited) resolved else it }
                        artistCreditsCache.put(creditKey,artists)
                    }
                }
                val profile = artistCardCache.get(artist.id) ?: permits.withPermit {
                    artistCardWork.await(artist.id) {
                        artistCardCache.get(artist.id) ?: Spotify.artistOverview(artist.id).getOrNull()
                            ?.also { artistCardCache.put(artist.id, it) }
                    }
                }
                if (profile != null) profiles[artist.id] = profile
            }
        }
    }
    if (artists.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 32.dp) * if (artists.size > 1) 0.92f else 1f
        val detailsHeight=240.dp*androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(artists, key = { it.id.ifBlank { it.name } }) { artist ->
                val profile = profiles[artist.id] ?: artistCardCache.get(artist.id)
                val open = { navController.navigate(artistRoute(artist.name, artist.id)) }
                Column(Modifier.width(cardWidth).height(cardWidth/1.5f+detailsHeight).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF282828)).clickable(onClick = open)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Color(0xFF202020))) {
                        val image = profile?.headerImages?.firstOrNull()?.url
                            ?: profile?.avatarImages?.firstOrNull()?.url
                        if (!image.isNullOrBlank()) {
                            GlideImage(model = image, contentDescription = artist.name,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                requestBuilderTransform={it.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)})
                        } else {
                            Icon(Icons.Default.Person, null, tint = Color(0xFF666666),
                                modifier = Modifier.size(80.dp).align(Alignment.Center))
                        }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.1f)))))
                        Text(uiLocalized("About the artist"), color = Color.White, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile?.name?.ifBlank { artist.name } ?: artist.name, color = Color.White,
                                fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (profile?.verified == true) {
                                Icon(Icons.Default.CheckCircle, uiLocalized("Verified artist"), tint = Color(0xFFC4F0C8),
                                    modifier = Modifier.padding(horizontal = 6.dp).size(20.dp))
                            }
                            ArtistFollowPill(artist.id, artist.name)
                        }
                        profile?.monthlyListeners?.let { count ->
                            Text("${java.text.NumberFormat.getIntegerInstance().format(count)} ${uiLocalized("monthly listeners")}",
                                color = Color(0xFFB3B3B3), fontSize = 15.sp)
                        }
                        profile?.biography?.takeIf(String::isNotBlank)?.let { biography ->
                            com.music.spotui.ui.components.ArtistBiographyText(raw = biography,
                                color = Color(0xFFB3B3B3), fontSizeSp = 15f, maxLines = 3)
                            Text(uiLocalized("Show more"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistFollowPill(id: String, name: String) {
    val context = LocalContext.current
    var following by remember(id) { mutableStateOf(isArtistFollowed(context, id)) }
    var changedHere by remember(id) { mutableStateOf(false) }
    LaunchedEffect(context, id) {
        val cachedFollow = withContext(Dispatchers.IO) {
            com.music.spotui.data.api.LibraryContentMemory.artists(context)?.any { it.id == id } == true
        }
        if (!changedHere) following = isArtistFollowed(context, id) || cachedFollow
    }
    DisposableEffect(context, id) {
        val prefs = context.getSharedPreferences("FollowedArtists", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            following = isArtistFollowed(context, id)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Text(if (following) uiLocalized("Following") else uiLocalized("Follow"), color = if(following)Color.Black else Color.White,
        fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(50))
            .background(if(following)Color(0xFF1ED760) else Color.Transparent)
            .border(1.dp, if(following)Color(0xFF1ED760) else Color(0xFF888888), RoundedCornerShape(50))
            .clickable(enabled = id.isNotBlank()) {
                changedHere = true
                val next = !following
                if (next) addFollowedArtist(context, id, name) else removeFollowedArtist(context, id)
                following = next
                SpotifySync.setArtistFollowed(context, id, next)
            }.padding(horizontal = 16.dp, vertical = 8.dp))
}
