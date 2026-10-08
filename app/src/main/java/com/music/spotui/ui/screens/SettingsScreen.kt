package com.music.spotui.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.music.spotui.R
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import com.music.spotui.data.preferences.BackupPref
import com.music.spotui.util.BackupHelper
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavController
import com.music.spotui.data.BatteryOptimizationHelper
import com.music.spotui.data.preferences.CROSSFADE_MAX_MS
import com.music.spotui.data.preferences.StreamQuality
import com.music.spotui.data.preferences.getCellularQuality
import com.music.spotui.data.preferences.getCrossfadeMs
import com.music.spotui.data.preferences.setCrossfadeMs
import com.music.spotui.data.preferences.getDownloadQuality
import com.music.spotui.data.preferences.isVideoFallbackEnabled
import com.music.spotui.data.preferences.isAutoPlayEnabled
import com.music.spotui.data.preferences.getWifiQuality
import com.music.spotui.data.preferences.setCellularQuality
import com.music.spotui.data.preferences.setDownloadQuality
import com.music.spotui.data.preferences.setAutoPlayEnabled
import com.music.spotui.data.preferences.setVideoFallbackEnabled
import com.music.spotui.data.preferences.setWifiQuality
import com.music.spotui.data.preferences.getUpdateRepoUrl
import com.music.spotui.data.preferences.setUpdateRepoUrl
import com.music.spotui.data.preferences.resetUpdateRepoUrl
import com.music.spotui.data.preferences.DEFAULT_UPDATE_REPO_URL
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.music.spotui.ui.components.DefaultAppPrompt
import com.music.spotui.util.DefaultLinkHelper
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current

    var appLang by remember { mutableStateOf(com.music.spotui.data.preferences.getAppLanguage(context)) }
    var wifiQ by remember { mutableStateOf(getWifiQuality(context)) }
    var cellQ by remember { mutableStateOf(getCellularQuality(context)) }
    var dlQ by remember { mutableStateOf(getDownloadQuality(context)) }
    var crossfadeMs by remember { mutableStateOf(getCrossfadeMs(context).toFloat()) }
    var videoFallback by remember { mutableStateOf(isVideoFallbackEnabled(context)) }
    var autoPlay by remember { mutableStateOf(isAutoPlayEnabled(context)) }
    var audioNormalization by remember { mutableStateOf(com.music.spotui.data.preferences.isAudioNormalizationEnabled(context)) }
    var equalizerEnabled by remember { mutableStateOf(com.music.spotui.data.preferences.isEqualizerEnabled(context)) }
    var equalizerPreset by remember { mutableStateOf(com.music.spotui.data.preferences.getEqualizerPreset(context)) }
    var equalizerGains by remember { mutableStateOf(com.music.spotui.data.preferences.getEqualizerBandGains(context)) }
    var batteryOptExempt by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimization(context)) }
    var updateRepoUrl by remember { mutableStateOf(getUpdateRepoUrl(context)) }
    var isDefaultLinkHandler by remember { mutableStateOf(DefaultLinkHelper.isAppDefaultLinkHandler(context)) }
    var showDefaultGuide by remember { mutableStateOf(false) }

    var backupDirUri by remember { mutableStateOf(BackupPref.getDirectoryUri(context)) }
    var folderName by remember(backupDirUri) { mutableStateOf(BackupHelper.getFolderDisplayName(context, backupDirUri)) }
    var isAutoBackup by remember { mutableStateOf(BackupPref.isAutoBackupEnabled(context)) }
    var isRestoring by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            BackupPref.setDirectoryUri(context, uri.toString())
            backupDirUri = uri.toString()
            folderName = BackupHelper.getFolderDisplayName(context, uri.toString())
            scope.launch {
                val autoOk = BackupHelper.performAutoBackup(context)
                val msg = if (autoOk) "Backup folder set to $folderName (Auto-backup created)" else "Backup folder set to $folderName"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val restoreFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isRestoring = true
            scope.launch {
                val (success, message) = BackupHelper.restoreFromFileUri(context, uri)
                isRestoring = false
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                if (success) {
                    wifiQ = getWifiQuality(context)
                    cellQ = getCellularQuality(context)
                    dlQ = getDownloadQuality(context)
                    crossfadeMs = getCrossfadeMs(context).toFloat()
                    videoFallback = isVideoFallbackEnabled(context)
                    autoPlay = isAutoPlayEnabled(context)
                    audioNormalization = com.music.spotui.data.preferences.isAudioNormalizationEnabled(context)
                    equalizerEnabled = com.music.spotui.data.preferences.isEqualizerEnabled(context)
                    equalizerPreset = com.music.spotui.data.preferences.getEqualizerPreset(context)
                    equalizerGains = com.music.spotui.data.preferences.getEqualizerBandGains(context)
                    updateRepoUrl = getUpdateRepoUrl(context)
                    backupDirUri = BackupPref.getDirectoryUri(context)
                    isAutoBackup = BackupPref.isAutoBackupEnabled(context)
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultLinkHandler = DefaultLinkHelper.isAppDefaultLinkHandler(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimization(context)
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(26.dp)
                            .clickable { navController.popBackStack() }
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        var showDevicesSheet by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 200.dp)
        ) {
            SectionTitle(stringResource(R.string.language))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A20))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App Language / Idioma", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (appLang == "es") "Español" else "English", color = Color(0xFF1ED760), fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = {
                            appLang = "en"
                            com.music.spotui.data.preferences.setAppLanguage(context, "en")
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (appLang == "en") AppPalette else Color(0xFF28282E),
                        contentColor = if (appLang == "en") Color.Black else Color.White
                    ) {
                        Text("EN", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Surface(
                        onClick = {
                            appLang = "es"
                            com.music.spotui.data.preferences.setAppLanguage(context, "es")
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (appLang == "es") AppPalette else Color(0xFF28282E),
                        contentColor = if (appLang == "es") Color.Black else Color.White
                    ) {
                        Text("ES", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.devices_bluetooth))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showDevicesSheet = true }
                    .background(Color(0xFF1A1A20))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.audio_output_devices), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        com.music.spotui.ui.utils.AudioDeviceHelper.getCurrentAudioRouteName(context),
                        color = Color(0xFF1ED760),
                        fontSize = 12.sp,
                    )
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_devices),
                    contentDescription = "Devices",
                    tint = Color(0xFF1ED760),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.background_playback))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        batteryOptLauncher.launch(BatteryOptimizationHelper.buildAppSettingsIntent(context))
                    }
                    .background(Color(0xFF1A1A20))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.battery_optimization), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (batteryOptExempt) "Exempt — app won't be killed" else "Not exempt — tap to change",
                        color = if (batteryOptExempt) Color(0xFF81C784) else Color(0xFFB3B3B3),
                        fontSize = 12.sp,
                    )
                }
                if (batteryOptExempt) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Enabled",
                        tint = AppPalette,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.link_handling))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        if (isDefaultLinkHandler) {
                            DefaultLinkHelper.openSpotuiDefaultSettings(context)
                        } else {
                            showDefaultGuide = true
                        }
                    }
                    .background(Color(0xFF1A1A20))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Open Spotify links by default", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isDefaultLinkHandler) "Spotui handles Spotify URLs by default" else "Not default — tap to open setup guide",
                        color = if (isDefaultLinkHandler) Color(0xFF81C784) else Color(0xFFB3B3B3),
                        fontSize = 12.sp,
                    )
                }
                if (isDefaultLinkHandler) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Enabled",
                        tint = AppPalette,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open Settings",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            SectionTitle(stringResource(R.string.audio_quality))
            QualityPicker(
                title = stringResource(R.string.streaming_wifi),
                selected = wifiQ,
            ) { wifiQ = it; setWifiQuality(context, it) }

            QualityPicker(
                title = stringResource(R.string.streaming_cellular),
                selected = cellQ,
            ) { cellQ = it; setCellularQuality(context, it) }

            QualityPicker(
                title = stringResource(R.string.download_quality),
                selected = dlQ,
            ) { dlQ = it; setDownloadQuality(context, it) }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        com.music.spotui.di.SongPlayer.clearCaches(context)
                        android.widget.Toast.makeText(context, "Stream cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .background(Color(0xFF1E1E24))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.clear_cache), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Unlocks all cached streams and forces re-resolution", color = Color.Gray, fontSize = 11.sp)
                }
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Clear cache",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.audio_normalization))
            SettingsSwitchRow(
                title = stringResource(R.string.normalize_volume),
                subtitle = stringResource(R.string.normalize_desc),
                checked = audioNormalization,
            ) {
                audioNormalization = it
                com.music.spotui.data.preferences.setAudioNormalizationEnabled(context, it)
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.equalizer))
            SettingsSwitchRow(
                title = stringResource(R.string.equalizer),
                subtitle = stringResource(R.string.equalizer_desc),
                checked = equalizerEnabled,
            ) {
                equalizerEnabled = it
                com.music.spotui.data.preferences.setEqualizerEnabled(context, it)
            }

            if (equalizerEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Preset",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                val presets = remember { com.music.spotui.audio.EqualizerAudioProcessor.PRESETS.keys.toList() + "Custom" }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    items(presets) { preset ->
                        val isSelected = (preset == equalizerPreset)
                        Surface(
                            onClick = {
                                equalizerPreset = preset
                                if (preset != "Custom") {
                                    val gains = com.music.spotui.audio.EqualizerAudioProcessor.PRESETS[preset]
                                        ?: floatArrayOf(0f, 0f, 0f, 0f, 0f)
                                    equalizerGains = gains
                                    com.music.spotui.data.preferences.setEqualizerPreset(context, preset)
                                } else {
                                    com.music.spotui.data.preferences.setEqualizerPreset(context, "Custom")
                                    equalizerGains = com.music.spotui.data.preferences.getEqualizerBandGains(context)
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) AppPalette else Color(0xFF28282E),
                            contentColor = if (isSelected) Color.Black else Color.White,
                        ) {
                            Text(
                                text = preset,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                val bandLabels = remember { com.music.spotui.audio.EqualizerAudioProcessor.FREQUENCY_LABELS }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1E24))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until 5) {
                        val gainValue = equalizerGains.getOrElse(i) { 0f }
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(bandLabels[i], color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (gainValue > 0) "+${String.format("%.1f", gainValue)} dB" else "${String.format("%.1f", gainValue)} dB",
                                    color = if (gainValue != 0f) AppPalette else Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Slider(
                                value = gainValue,
                                onValueChange = { newGain ->
                                    val newGains = equalizerGains.copyOf()
                                    newGains[i] = newGain
                                    equalizerGains = newGains
                                    equalizerPreset = "Custom"
                                    com.music.spotui.data.preferences.setEqualizerPreset(context, "Custom")
                                    com.music.spotui.data.preferences.setEqualizerBandGains(context, newGains)
                                },
                                valueRange = -12f..12f,
                                colors = SliderDefaults.colors(
                                    thumbColor = AppPalette,
                                    activeTrackColor = AppPalette,
                                    inactiveTrackColor = Color(0xFF383840)
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.matching))
            SettingsSwitchRow(
                title = stringResource(R.string.allow_video_fallback),
                subtitle = "Use regular YouTube videos only after Music song results fail",
                checked = videoFallback,
            ) {
                videoFallback = it
                setVideoFallbackEnabled(context, it)
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.playback))
            SettingsSwitchRow(
                title = stringResource(R.string.autoplay_startup),
                subtitle = "Resume playing the last track when the app opens",
                checked = autoPlay,
            ) {
                autoPlay = it
                setAutoPlayEnabled(context, it)
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.crossfade))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.crossfade), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (crossfadeMs <= 0f) "Off" else "${(crossfadeMs / 1000f).let { String.format("%.0f", it) }}s",
                    color = if (crossfadeMs <= 0f) Color(0xFFB3B3B3) else AppPalette,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Slider(
                value = crossfadeMs,
                onValueChange = { crossfadeMs = it },
                onValueChangeFinished = { setCrossfadeMs(context, crossfadeMs.toInt()) },
                valueRange = 0f..CROSSFADE_MAX_MS.toFloat(),
                steps = (CROSSFADE_MAX_MS / 1000) - 1,
                colors = SliderDefaults.colors(
                    thumbColor = AppPalette,
                    activeTrackColor = AppPalette,
                    inactiveTrackColor = Color(0xFF333333),
                ),
            )
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.updates))
            Text(
                stringResource(R.string.update_repo),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = updateRepoUrl,
                onValueChange = {
                    updateRepoUrl = it
                    setUpdateRepoUrl(context, it)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = AppPalette,
                    unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = AppPalette,
                    focusedPlaceholderColor = Color(0xFF666666),
                    unfocusedPlaceholderColor = Color(0xFF666666),
                ),
                placeholder = { Text("https://github.com/Owner/Repo") },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset",
                        tint = if (updateRepoUrl != DEFAULT_UPDATE_REPO_URL) AppPalette else Color(0xFF444444),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clickable {
                                updateRepoUrl = DEFAULT_UPDATE_REPO_URL
                                resetUpdateRepoUrl(context)
                            }
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.backup_restore))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !isBackingUp) {
                            if (backupDirUri.isNullOrBlank()) {
                                dirPickerLauncher.launch(null)
                            } else {
                                isBackingUp = true
                                scope.launch {
                                    val (success, message) = BackupHelper.performManualBackup(context)
                                    isBackingUp = false
                                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .background(Color(0xFF1A1A20))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.back_up_now), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                isBackingUp -> "Creating backup…"
                                backupDirUri.isNullOrBlank() -> "Tap to choose folder"
                                else -> "Folder: $folderName"
                            },
                            color = if (backupDirUri.isNullOrBlank()) Color(0xFFFFB74D) else Color(0xFFB3B3B3),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    if (isBackingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = AppPalette,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = "Backup",
                            tint = AppPalette,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (!backupDirUri.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1A1A20))
                            .clickable(enabled = !isBackingUp) { dirPickerLauncher.launch(null) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = "Folder",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !isRestoring) {
                        restoreFileLauncher.launch(arrayOf("application/json", "*/*"))
                    }
                    .background(Color(0xFF1A1A20))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.restore_from_file), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isRestoring) "Restoring…" else "Import settings from backup file",
                        color = Color(0xFFB3B3B3),
                        fontSize = 12.sp,
                    )
                }
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AppPalette,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = "Restore",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SettingsSwitchRow(
                title = stringResource(R.string.automatic_backup),
                subtitle = if (backupDirUri.isNullOrBlank()) "Automatically backup on app open" else "Auto-backup to $folderName",
                checked = isAutoBackup,
            ) { enabled ->
                if (enabled && backupDirUri.isNullOrBlank()) {
                    dirPickerLauncher.launch(null)
                } else {
                    isAutoBackup = enabled
                    BackupPref.setAutoBackupEnabled(context, enabled)
                    if (enabled) {
                        scope.launch { BackupHelper.performAutoBackup(context) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.troubleshooting))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.music.spotui.di.SongPlayer.clearCaches(context)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Session & stream caches reset",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .background(Color(0xFF1A1A20))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.reset_session), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Clears session tokens and resolved stream caches",
                        color = Color(0xFFB3B3B3),
                        fontSize = 12.sp,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reset",
                    tint = AppPalette,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.account))
            Text(
                text = stringResource(R.string.log_out),
                color = Color(0xFFE57373),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        com.music.spotui.data.api.SpotifySession.setSpDc(context, "")
                        com.music.spotui.data.api.Api.HomeCache.clear()
                        navController.navigate(com.music.spotui.ui.navigation.Routes.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                    .padding(vertical = 14.dp)
            )
            Spacer(Modifier.height(40.dp))
        }

        if (showDefaultGuide) {
            DefaultAppPrompt(
                forceShow = true,
                onDismiss = {
                    showDefaultGuide = false
                    isDefaultLinkHandler = DefaultLinkHelper.isAppDefaultLinkHandler(context)
                }
            )
        }

        if (showDevicesSheet) {
            com.music.spotui.ui.components.DevicesSheet(
                context = context,
                onDismiss = { showDevicesSheet = false }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = AppPalette,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppPalette,
                uncheckedThumbColor = Color(0xFFB3B3B3),
                uncheckedTrackColor = Color(0xFF333333),
            ),
        )
    }
}

@Composable
private fun QualityPicker(
    title: String,
    selected: StreamQuality,
    onSelect: (StreamQuality) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        StreamQuality.values().forEach { q ->
            val isSel = q == selected
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(q) }
                        .background(if (isSel) Color(0xFF1A1A20) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(q.label, color = Color.White, fontSize = 15.sp)
                        Text(q.detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
                    }
                    if (isSel) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = AppPalette,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
