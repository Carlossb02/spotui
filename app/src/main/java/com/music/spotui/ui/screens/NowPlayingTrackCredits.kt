package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrackCredits
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.util.ExpiringLruCache
import com.music.spotui.util.SharedWork
import kotlinx.coroutines.*

private val creditsCache = ExpiringLruCache<String, SpotifyTrackCredits>(64, 30 * 60_000L)
private val creditsWork = SharedWork<String, SpotifyTrackCredits?>(CoroutineScope(SupervisorJob() + Dispatchers.IO))

@Composable
internal fun rememberTrackCredits(trackId: String): State<SpotifyTrackCredits?> {
    val context = LocalContext.current.applicationContext
    return key(trackId) { produceState(creditsCache.get(trackId), trackId) {
        value = creditsCache.get(trackId)
        if (value == null && trackId.matches(Regex("[A-Za-z0-9]{22}"))) {
            SongPlayer.awaitAudioStartup()
            value = creditsWork.await(trackId) {
                withTimeoutOrNull(6_000) {
                    if (SpotifyTokenProvider.ensureToken(context)) Spotify.trackCredits(trackId).getOrNull()?.also { creditsCache.put(trackId, it) }
                    else null
                }
            }
        }
    }
} }

@Composable
internal fun NowPlayingTrackCredits(credits: SpotifyTrackCredits?, navController: NavController) {
    if (credits == null || credits.roles.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)
        .clip(RoundedCornerShape(16.dp)).background(Color(0xFF242424)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Créditos", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        credits.roles.forEach { role ->
            val roleName = when (role.title.lowercase()) {
                "performers", "performed by" -> "Interpretación"
                "writers", "written by" -> "Composición"
                "producers", "produced by" -> "Producción"
                else -> role.title
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(roleName, color = Color.White.copy(alpha = .65f), fontSize = 13.sp)
                role.people.forEach { person ->
                    Text(person.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().then(if (person.artistId.isNotBlank())
                            Modifier.clickable { navController.navigate(artistRoute(person.name, person.artistId)) }.padding(vertical = 4.dp)
                            else Modifier))
                }
            }
        }
        if (credits.sources.isNotEmpty()) Text("Fuente: " + credits.sources.joinToString(", "),
            color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
    }
}
