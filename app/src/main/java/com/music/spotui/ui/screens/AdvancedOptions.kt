package com.music.spotui.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.music.spotui.data.api.*
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedOptionsScreen(navController: androidx.navigation.NavController) {
    Scaffold(containerColor = Color(0xFF121212), topBar = {
        TopAppBar(title = { Text(uiLocalized("Advanced options"), color = Color.White) },
            navigationIcon = { IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, uiLocalized("Back"), tint = Color.White)
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)))
    }) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 168.dp)) {
            item { Column { AdvancedOptionsContent() } }
        }
    }
}

@Composable
internal fun AdvancedOptionsContent() {
    val context=LocalContext.current
    val player:PlayerViewModel=hiltViewModel()
    val scope=rememberCoroutineScope()
    var enabled by remember {mutableStateOf(isPreloadEnabled(context))}
    var wifiOnly by remember {mutableStateOf(isPreloadWifiOnly(context))}
    var count by remember {mutableStateOf(getPreloadSongCount(context))}
    var report by remember {mutableStateOf<String?>(null)}
    var title by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    fun task(name:String,action:suspend ()->String) {
        if(busy)return
        title=name;busy=true
        scope.launch {try {report=withContext(Dispatchers.IO){action()}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){report=error.message ?: "Error"} finally {busy=false}}
    }
    fun current()=player.queue.value.getOrNull(player.currentSongIndex.value)
    fun refreshFeeds()=FollowingReleaseFeed.get(context).refresh(SubscribedPodcastPref.getSubscribedPodcasts(context),
        LibraryContentMemory.artists(context).orEmpty(),force=true)
    SettingsSwitchRow(Icons.Default.Download,uiLocalized("Preload upcoming audio"),
        uiLocalized("Cache audio bytes for the next tracks after the current track is ready."),enabled) {
        enabled=it;setPreloadEnabled(context,it);SongPlayer.refreshQueuePreload(context)
    }
    SettingsDivider()
    SettingsChoiceRow(Icons.Default.QueueMusic,uiLocalized("Upcoming tracks to preload"),count.toString(),count,listOf(1,2,3,5),
        label={it.toString()},description={uiLocalized("Up to 32 MB per track, within the 256 MB playback cache.")}) {
        count=it;setPreloadSongCount(context,it);SongPlayer.refreshQueuePreload(context)
    }
    SettingsSwitchRow(Icons.Default.Wifi,uiLocalized("Preload on Wi-Fi only"),uiLocalized("Avoid background audio downloads on metered networks."),wifiOnly) {
        wifiOnly=it;setPreloadWifiOnly(context,it);SongPlayer.refreshQueuePreload(context)
    }
    SettingsDivider()
    val preloadTitle=uiLocalized("Audio and preload diagnostics")
    SettingsActionRow(Icons.Default.BugReport,preloadTitle,uiLocalized("Show buffered time, cached bytes and queued work."),enabled=!busy,busy=busy) {
        task(preloadTitle){SongPlayer.preloadReport(context)}
    }
    val matchTitle=uiLocalized("Test current track matching")
    SettingsActionRow(Icons.Default.ManageSearch,matchTitle,uiLocalized("Inspect candidates without changing playback or the manual stream."),enabled=!busy && current()!=null) {
        val song=current() ?: return@SettingsActionRow
        task(matchTitle){SongPlayer.debugMatch(song,context)}
    }
    val resetTitle=uiLocalized("Reset followed release checks")
    val resetText=uiLocalized("Read markers reset. Followed artists and podcasts were kept.")
    SettingsActionRow(Icons.Default.RestartAlt,resetTitle,uiLocalized("Show unread music and podcast release notices again. Keeps your follows."),enabled=!busy) {
        task(resetTitle){resetReleaseChecks(context);refreshFeeds();resetText}
    }
    val refreshTitle=uiLocalized("Refresh followed releases now")
    val refreshingText=uiLocalized("Release discovery started in the background.")
    SettingsActionRow(Icons.Default.Sync,refreshTitle,uiLocalized("Recheck the feeds without marking them as read."),enabled=!busy) {
        task(refreshTitle){refreshFeeds();refreshingText}
    }
    val coversTitle=uiLocalized("Recalculate recovered artwork")
    val coversText=uiLocalized("Recovered artwork will be checked again. Your own cover edits were kept.")
    SettingsActionRow(Icons.Default.Image,coversTitle,uiLocalized("Use the exact playable stream again and refresh its thumbnail."),enabled=!busy) {
        task(coversTitle){ResolvedSongArtwork.invalidateAll(context);coversText}
    }
    val lyricTitle=uiLocalized("Fetch missing download lyrics")
    val lyricText=uiLocalized("Lyrics lookup started for downloaded music.")
    SettingsActionRow(Icons.Default.Subtitles,lyricTitle,uiLocalized("Save available lyrics for offline use without downloading audio again."),enabled=!busy) {
        task(lyricTitle){SongPlayer.completeDownloadedLyrics(context);lyricText}
    }
    report?.let {text->AlertDialog(onDismissRequest={report=null},title={Text(title)},text={
        SelectionContainer {Text(text,Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))}
    },confirmButton={TextButton(onClick={report=null}){Text(uiLocalized("Close"))}})}
}
