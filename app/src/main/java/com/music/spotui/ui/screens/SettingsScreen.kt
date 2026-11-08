package com.music.spotui.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.music.spotui.R
import com.music.spotui.data.BatteryOptimizationHelper
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.components.DefaultAppPrompt
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.util.BackupHelper
import com.music.spotui.util.DefaultLinkHelper
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

private val SettingsGreen = Color(0xFF1ED760)
private val SettingsSecondary = Color(0xFFB3B3B3)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    // Resolve UI text during composition; event handlers only consume strings.
    val folderSavedText = uiLocalized("Backup folder saved.")
    val folderSavedWithBackupText = uiLocalized("Backup folder saved. A backup has been created.")
    val cacheClearedText = uiLocalized("Playback cache cleared.")
    val cacheClearFailedText = uiLocalized("Could not clear playback cache. Try again.")
    var clearingCache by remember { mutableStateOf(false) }
    var showRepoEditor by remember { mutableStateOf(false) }
    var lastCrossfadeMs by remember {
        mutableStateOf(getCrossfadeMs(context).takeIf { it > 0 } ?: 6000)
    }

    var appLang by remember { mutableStateOf(com.music.spotui.data.preferences.getAppLanguage(context)) }
    var wifiQ by remember { mutableStateOf(getWifiQuality(context)) }
    var cellQ by remember { mutableStateOf(getCellularQuality(context)) }
    var dlQ by remember { mutableStateOf(getDownloadQuality(context)) }
    var crossfadeMs by remember { mutableStateOf(getCrossfadeMs(context).toFloat()) }
    var videoFallback by remember { mutableStateOf(isVideoFallbackEnabled(context)) }
    var autoPlay by remember { mutableStateOf(isAutoPlayEnabled(context)) }
    var normalizationMode by remember { mutableStateOf(com.music.spotui.data.preferences.getAudioNormalizationMode(context)) }
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
    var backupAfterFolder by remember { mutableStateOf(false) }
    var enableAutoAfterFolder by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var isBackingUp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val manualRequested = backupAfterFolder
        val autoRequested = enableAutoAfterFolder
        backupAfterFolder = false
        enableAutoAfterFolder = false
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            BackupPref.setDirectoryUri(context, uri.toString())
            backupDirUri = uri.toString()
            folderName = BackupHelper.getFolderDisplayName(context, uri.toString())
            if (autoRequested) {
                BackupPref.setAutoBackupEnabled(context, true)
                isAutoBackup = true
            }
            scope.launch {
                if (manualRequested) {
                    isBackingUp = true
                    try {
                        val (_, message) = BackupHelper.performManualBackup(context)
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    } finally { isBackingUp = false }
                } else {
                    val copied = BackupHelper.performAutoBackup(context)
                    val message = if (copied) folderSavedWithBackupText else folderSavedText
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
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
                    appLang = com.music.spotui.data.preferences.getAppLanguage(context)
                    wifiQ = getWifiQuality(context)
                    cellQ = getCellularQuality(context)
                    dlQ = getDownloadQuality(context)
                    crossfadeMs = getCrossfadeMs(context).toFloat()
                    videoFallback = isVideoFallbackEnabled(context)
                    autoPlay = isAutoPlayEnabled(context)
                    normalizationMode = com.music.spotui.data.preferences.getAudioNormalizationMode(context)
                    equalizerEnabled = com.music.spotui.data.preferences.isEqualizerEnabled(context)
                    equalizerPreset = com.music.spotui.data.preferences.getEqualizerPreset(context)
                    equalizerGains = com.music.spotui.data.preferences.getEqualizerBandGains(context)
                    updateRepoUrl = getUpdateRepoUrl(context)
                    backupDirUri = BackupPref.getDirectoryUri(context)
                    folderName = BackupHelper.getFolderDisplayName(context, backupDirUri)
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
                batteryOptExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimization(context)
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

    var showDevicesSheet by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings), color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiLocalized("Back"), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground),
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        val horizontalInset = ((maxWidth - 840.dp) / 2f).coerceAtLeast(0.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp + horizontalInset, end = 16.dp + horizontalInset, top = 8.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "general") {
                SettingsSection(uiLocalized("General")) {
                    SettingsChoiceRow(
                        icon = Icons.Default.Language,
                        title = stringResource(R.string.language),
                        value = if (appLang == "es") "Español" else "English",
                        selected = appLang,
                        options = listOf("es", "en"),
                        label = { if (it == "es") "Español" else "English" },
                        description = { "" },
                    ) {
                        appLang = it
                        setAppLanguage(context, it)
                    }
                    SettingsDivider()
                    SettingsActionRow(Icons.Default.Link, uiLocalized("Open Spotify links"),
                        if (isDefaultLinkHandler) uiLocalized("Spotify links open in Spotui.")
                        else uiLocalized("Choose Spotui as the default app for Spotify links.")) {
                        if (isDefaultLinkHandler) DefaultLinkHelper.openSpotuiDefaultSettings(context)
                        else showDefaultGuide = true
                    }
                }
            }
            item(key = "playback") {
                SettingsSection(uiLocalized("Playback")) {
                    SettingsSwitchRow(Icons.Default.PlayCircleOutline,
                        uiLocalized("Resume on startup"), uiLocalized("Play the last track when you open the app."), autoPlay) {
                        autoPlay = it; setAutoPlayEnabled(context, it)
                    }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Default.SwapHoriz,
                        uiLocalized("Crossfade"), uiLocalized("Blend the end of a song into the beginning of the next."), crossfadeMs > 0f) { enabled ->
                        if (enabled) crossfadeMs = lastCrossfadeMs.toFloat()
                        else { lastCrossfadeMs = crossfadeMs.toInt().coerceAtLeast(1000); crossfadeMs = 0f }
                        setCrossfadeMs(context, crossfadeMs.toInt())
                    }
                    if (crossfadeMs > 0f) {
                        Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                            Text("${(crossfadeMs / 1000f).toInt()} s", color = SettingsGreen, fontWeight = FontWeight.SemiBold)
                            Slider(value = crossfadeMs.coerceIn(1000f, CROSSFADE_MAX_MS.toFloat()),
                                onValueChange = { crossfadeMs = it },
                                onValueChangeFinished = {
                                    lastCrossfadeMs = crossfadeMs.toInt()
                                    setCrossfadeMs(context, lastCrossfadeMs)
                                }, valueRange = 1000f..CROSSFADE_MAX_MS.toFloat(), steps = (CROSSFADE_MAX_MS / 1000) - 2,
                                colors = settingsSliderColors())
                        }
                    }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Default.VideoLibrary,
                        uiLocalized("Search videos as a fallback"),
                        uiLocalized("Try YouTube videos when no matching song is found in YouTube Music."), videoFallback) {
                        videoFallback = it; setVideoFallbackEnabled(context, it)
                    }
                }
            }
            item(key = "sound") {
                SettingsSection(uiLocalized("Sound")) {
                    val modes = listOf(NormalizationMode.OFF, NormalizationMode.SUAVE, NormalizationMode.YOUTUBE_METADATA)
                    SettingsChoiceRow(Icons.AutoMirrored.Filled.VolumeUp,
                        uiLocalized("Volume normalization"), normalizationLabel(normalizationMode), normalizationMode, modes,
                        label = { normalizationLabel(it) }, description = { normalizationDescription(it) }) {
                        normalizationMode = it; setAudioNormalizationMode(context, it)
                    }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Default.Equalizer,
                        stringResource(R.string.equalizer), uiLocalized("Adjust bass, midrange and treble, or choose a preset."), equalizerEnabled) {
                        equalizerEnabled = it; setEqualizerEnabled(context, it)
                    }
            if (equalizerEnabled) {
                Spacer(Modifier.height(8.dp))

                // Interactive Frequency Response Curve Visualizer
                EqualizerFrequencyCurve(
                    gains = equalizerGains,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Grouped Grid Preset Selector
                GroupedPresetGrid(
                    selectedPreset = equalizerPreset,
                    onPresetSelected = { preset ->
                        equalizerPreset = preset
                        if (preset != "Custom") {
                            val gains = com.music.spotui.audio.EqualizerAudioProcessor.PRESETS[preset]
                                ?: FloatArray(com.music.spotui.audio.EqualizerAudioProcessor.BAND_COUNT) { 0f }
                            equalizerGains = gains
                            com.music.spotui.data.preferences.setEqualizerPreset(context, preset)
                        } else {
                            com.music.spotui.data.preferences.setEqualizerPreset(context, "Custom")
                            equalizerGains = com.music.spotui.data.preferences.getEqualizerBandGains(context)
                        }
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // 6 Band Frequency Sliders
                val bandLabels = remember { com.music.spotui.audio.EqualizerAudioProcessor.FREQUENCY_LABELS }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1E24))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until com.music.spotui.audio.EqualizerAudioProcessor.BAND_COUNT) {
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
                                    color = if (gainValue != 0f) SettingsGreen else Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Slider(
                                value = gainValue,
                                onValueChange = { newGain ->
                                    val newGains = equalizerGains.copyOf()
                                    if (i < newGains.size) newGains[i] = newGain
                                    equalizerGains = newGains
                                    equalizerPreset = "Custom"
                                    com.music.spotui.data.preferences.setEqualizerPreset(context, "Custom")
                                    com.music.spotui.data.preferences.setEqualizerBandGains(context, newGains)
                                },
                                valueRange = -12f..12f,
                                colors = SliderDefaults.colors(
                                    thumbColor = SettingsGreen,
                                    activeTrackColor = SettingsGreen,
                                    inactiveTrackColor = Color(0xFF383840)
                                )
                            )
                        }
                    }
                }
            }
                }
            }
            item(key = "quality") {
                SettingsSection(uiLocalized("Audio quality")) {
                    QualityPicker(Icons.Default.Wifi, uiLocalized("Wi-Fi streaming"), wifiQ) { wifiQ = it; setWifiQuality(context, it) }
                    SettingsDivider()
                    QualityPicker(Icons.Default.SignalCellularAlt, uiLocalized("Mobile streaming"), cellQ) { cellQ = it; setCellularQuality(context, it) }
                    SettingsDivider()
                    QualityPicker(Icons.Default.Download, uiLocalized("Downloads"), dlQ) { dlQ = it; setDownloadQuality(context, it) }
                }
            }
            item(key = "devices") {
                SettingsSection(uiLocalized("Devices and system")) {
                    SettingsActionRow(Icons.Default.Headphones, uiLocalized("Audio output"),
                        com.music.spotui.ui.utils.AudioDeviceHelper.getCurrentAudioRouteName(context)) { showDevicesSheet = true }
                    SettingsDivider()
                    SettingsActionRow(Icons.Default.BatteryChargingFull, uiLocalized("Background playback"),
                        if (batteryOptExempt) uiLocalized("Unrestricted battery use is enabled.")
                        else uiLocalized("Manage Android battery restrictions for Spotui.")) {
                        batteryOptLauncher.launch(BatteryOptimizationHelper.buildAppSettingsIntent(context))
                    }
                }
            }
            item(key = "library") {
                SettingsSection(uiLocalized("Library and storage")) {
                    SettingsActionRow(Icons.Default.History, uiLocalized("Listening history"), uiLocalized("Revisa tu música, tus podcasts y tus estadísticas de escucha.")) {
                        navController.navigate(Routes.History.route)
                    }
                    SettingsDivider()
                    SettingsActionRow(Icons.Default.Folder, uiLocalized("Local files"), uiLocalized("Browse music imported from this device.")) {
                        navController.navigate(Routes.LocalFiles.route)
                    }
                    SettingsDivider()
                    SettingsActionRow(Icons.Default.DeleteOutline, uiLocalized("Clear playback cache"),
                        uiLocalized("Borra el audio temporal y vuelve a buscar las fuentes de reproducción. Conserva descargas, enlaces alternativos y cambios de metadatos."),
                        enabled = !clearingCache, busy = clearingCache) {
                        clearingCache = true
                        scope.launch {
                            try {
                                val result = runCatching { com.music.spotui.di.SongPlayer.clearPlaybackCache(context) }
                                result.exceptionOrNull()?.let { android.util.Log.w("Settings", "Cache cleanup failed", it) }
                                android.widget.Toast.makeText(context, if (result.isSuccess) cacheClearedText else cacheClearFailedText,
                                    android.widget.Toast.LENGTH_SHORT).show()
                            } finally { clearingCache = false }
                        }
                    }
                }
            }
            item(key = "backup") {
                SettingsSection(uiLocalized("Backups")) {
                    SettingsActionRow(Icons.Default.FolderOpen, uiLocalized("Backup folder"),
                        folderName.takeIf { !backupDirUri.isNullOrBlank() } ?: uiLocalized("Choose where to save your backups."),
                        enabled = !isBackingUp && !isRestoring) { dirPickerLauncher.launch(null) }
                    SettingsDivider()
                    SettingsActionRow(Icons.Default.CloudUpload, uiLocalized("Back up now"),
                        uiLocalized("Save your settings and library to the selected folder."), busy = isBackingUp,
                        enabled = !isBackingUp && !isRestoring) {
                        if (backupDirUri.isNullOrBlank()) { backupAfterFolder = true; dirPickerLauncher.launch(null) }
                        else {
                            isBackingUp = true
                            scope.launch {
                                try {
                                    val (_, message) = BackupHelper.performManualBackup(context)
                                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                } finally { isBackingUp = false }
                            }
                        }
                    }
                    SettingsDivider()
                    SettingsActionRow(Icons.Default.Restore, uiLocalized("Restore backup"),
                        uiLocalized("Import settings and library from a backup file."), busy = isRestoring,
                        enabled = !isBackingUp && !isRestoring) { restoreFileLauncher.launch(arrayOf("application/json", "*/*")) }
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Default.CloudSync, uiLocalized("Automatic backups"),
                        if (backupDirUri.isNullOrBlank()) uiLocalized("Choose a folder to enable automatic backups.")
                        else uiLocalized("Keep a backup in your selected folder."),
                        isAutoBackup && !backupDirUri.isNullOrBlank(), enabled = !isRestoring && !isBackingUp) { enabled ->
                        if (enabled && backupDirUri.isNullOrBlank()) { enableAutoAfterFolder = true; dirPickerLauncher.launch(null) }
                        else {
                            isAutoBackup = enabled; BackupPref.setAutoBackupEnabled(context, enabled)
                            if (enabled) scope.launch { BackupHelper.performAutoBackup(context) }
                        }
                    }
                }
            }
            item(key = "updates") {
                SettingsSection(uiLocalized("Updates")) {
                    SettingsActionRow(Icons.Default.SystemUpdate, uiLocalized("Update source"),
                        uiLocalized("GitHub repository used to check for new versions."), expanded = showRepoEditor) { showRepoEditor = !showRepoEditor }
                    if (showRepoEditor) {
                        OutlinedTextField(value = updateRepoUrl, onValueChange = {
                            updateRepoUrl = it; setUpdateRepoUrl(context, it)
                        }, label = { Text(uiLocalized("Repository URL")) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = SettingsGreen, unfocusedBorderColor = Color(0xFF555555), cursorColor = SettingsGreen),
                            trailingIcon = {
                                IconButton(onClick = { updateRepoUrl = DEFAULT_UPDATE_REPO_URL; resetUpdateRepoUrl(context) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = uiLocalized("Restore default repository"), tint = SettingsGreen)
                                }
                            })
                    }
                }
            }
            item(key = "account") {
                SettingsSection(uiLocalized("Account")) {
                    SettingsActionRow(Icons.AutoMirrored.Filled.Logout, uiLocalized("Log out"), uiLocalized("Sign out of your Spotify account on this device.")) {
                        com.music.spotui.data.api.SpotifySession.setSpDc(context, "")
                        com.music.spotui.data.api.Api.HomeCache.clear()
                        navController.navigate(Routes.Login.route) { popUpTo(0) { inclusive = true } }
                    }
                }
            }
        }
        }
    }
    if (showDefaultGuide) DefaultAppPrompt(forceShow = true, onDismiss = {
        showDefaultGuide = false
        isDefaultLinkHandler = DefaultLinkHelper.isAppDefaultLinkHandler(context)
    })
    if (showDevicesSheet) com.music.spotui.ui.components.DevicesSheet(context = context, onDismiss = { showDevicesSheet = false })
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF181818)), content = content)
    }
}

@Composable
private fun SettingsLeadingIcon(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SettingsGreen.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = SettingsGreen, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), color = Color(0xFF2A2A2A)) }

@Composable
private fun SettingsRowText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 12.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = SettingsSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SettingsActionRow(icon: ImageVector, title: String, subtitle: String,
    enabled: Boolean = true, busy: Boolean = false, expanded: Boolean? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsLeadingIcon(icon); Spacer(Modifier.width(12.dp))
        SettingsRowText(title, subtitle, Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = SettingsGreen, strokeWidth = 2.dp)
        else Icon(if (expanded == true) Icons.Default.KeyboardArrowUp else if (expanded == false) Icons.Default.KeyboardArrowDown
            else Icons.Default.ChevronRight, contentDescription = null, tint = SettingsSecondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SettingsSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsLeadingIcon(icon); Spacer(Modifier.width(12.dp))
        SettingsRowText(title, subtitle, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SettingsGreen,
                checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF535353), uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
private fun <T> SettingsChoiceRow(icon: ImageVector, title: String, value: String, selected: T,
    options: List<T>, label: @Composable (T) -> String, description: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    SettingsActionRow(icon, title, value, onClick = { choosing = true })
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, containerColor = Color(0xFF202020),
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton,
                        onClick = { onSelect(option); choosing = false }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowText(label(option), description(option), Modifier.weight(1f))
                        RadioButton(selected = option == selected, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = SettingsGreen, unselectedColor = SettingsSecondary))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(uiLocalized("Close"), color = SettingsGreen) } })
}

@Composable
private fun QualityPicker(icon: ImageVector, title: String, selected: StreamQuality, onSelect: (StreamQuality) -> Unit) {
    SettingsChoiceRow(icon, title, qualityLabel(selected), selected, StreamQuality.entries.toList(),
        label = { qualityLabel(it) }, description = { when (it) {
            StreamQuality.LOW -> uiLocalized("Uses less data and storage.")
            StreamQuality.NORMAL -> uiLocalized("Balances audio quality and data use.")
            StreamQuality.HIGH -> uiLocalized("Uses the best available compressed audio quality.")
        } }, onSelect = onSelect)
}

@Composable
private fun qualityLabel(quality: StreamQuality): String = uiLocalized(when (quality) {
    StreamQuality.LOW -> "Low"
    StreamQuality.NORMAL -> "Normal"
    StreamQuality.HIGH -> "High"
})

@Composable
private fun normalizationLabel(mode: NormalizationMode): String = uiLocalized(when (mode) {
    NormalizationMode.OFF -> "Off"
    NormalizationMode.SUAVE, NormalizationMode.BALANCED_BETA -> "Gentle"
    NormalizationMode.YOUTUBE_METADATA -> "YouTube volume level"
})

@Composable
private fun normalizationDescription(mode: NormalizationMode): String = uiLocalized(when (mode) {
    NormalizationMode.OFF -> "Keep the original track volume."
    NormalizationMode.SUAVE, NormalizationMode.BALANCED_BETA -> "Reduce loud tracks without compressing the audio."
    NormalizationMode.YOUTUBE_METADATA -> "Use YouTube volume information to reduce differences between tracks."
})

@Composable
private fun settingsSliderColors() = SliderDefaults.colors(thumbColor = SettingsGreen, activeTrackColor = SettingsGreen,
    inactiveTrackColor = Color(0xFF404040))

private val PRESET_CATEGORIES: Map<String, List<String>> = mapOf(
    "Básico" to listOf("Flat", "Custom"),
    "Bajos y Agudos" to listOf("Bass Booster", "Bass Reducer", "Treble Booster", "Treble Reducer", "Loudness", "Deep"),
    "Géneros" to listOf("Acoustic", "Classical", "Dance", "Electronic", "Hip-Hop", "Jazz", "Latin", "Pop", "R&B", "Rock"),
    "Voz e Instrumentos" to listOf("Vocal Booster", "Spoken Word", "Piano", "Lounge", "Small Speakers")
)

@Composable
private fun GroupedPresetGrid(
    selectedPreset: String,
    onPresetSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = { isExpanded = !isExpanded },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1E1E24),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = uiLocalized("Preset actual"),
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = uiLocalized(selectedPreset),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SettingsGreen.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = uiLocalized("Choose a preset"),
                            color = SettingsGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Colapsar presets" else "Expandir presets",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (isExpanded) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF18181E))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for ((category, presetList) in PRESET_CATEGORIES) {
                    Column {
                        Text(
                            text = uiLocalized(category),
                            color = SettingsGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        val chunkedRows = presetList.chunked(2)
                        for (rowPresets in chunkedRows) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (preset in rowPresets) {
                                    val isSelected = (preset == selectedPreset)
                                    Surface(
                                        onClick = {
                                            onPresetSelected(preset)
                                            isExpanded = false
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) SettingsGreen else Color(0xFF28282E),
                                        contentColor = if (isSelected) Color.Black else Color.White,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = uiLocalized(preset),
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (rowPresets.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun EqualizerFrequencyCurve(
    gains: FloatArray,
    modifier: Modifier = Modifier
) {
    val processor = remember { com.music.spotui.audio.EqualizerAudioProcessor() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141418))
            .padding(12.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        val dBValues = floatArrayOf(12f, 6f, 0f, -6f, -12f)
        for (dB in dBValues) {
            val lineY = centerY - (dB / 12f) * (height / 2.3f)
            drawLine(
                color = if (dB == 0f) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                start = Offset(0f, lineY),
                end = Offset(width, lineY),
                strokeWidth = if (dB == 0f) 1.5f else 1f
            )
        }

        processor.setBandGains(gains)

        val numPoints = 100
        val minFreq = 20.0
        val maxFreq = 20000.0
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)

        val path = Path()
        val fillPath = Path()
        val points = ArrayList<Offset>(numPoints)

        for (i in 0 until numPoints) {
            val fraction = i.toFloat() / (numPoints - 1)
            val logFreq = logMin + fraction * (logMax - logMin)
            val freqHz = 10.0.pow(logFreq).toFloat()

            val responseDb = processor.getFrequencyResponse(freqHz).coerceIn(-12f, 12f)
            val x = fraction * width
            val y = centerY - (responseDb / 12f) * (height / 2.3f)
            points.add(Offset(x, y))
        }

        if (points.isNotEmpty()) {
            path.moveTo(points[0].x, points[0].y)
            fillPath.moveTo(points[0].x, centerY)
            fillPath.lineTo(points[0].x, points[0].y)

            for (i in 1 until points.size) {
                val p1 = points[i - 1]
                val p2 = points[i]
                val controlX = (p1.x + p2.x) / 2f
                path.cubicTo(controlX, p1.y, controlX, p2.y, p2.x, p2.y)
                fillPath.cubicTo(controlX, p1.y, controlX, p2.y, p2.x, p2.y)
            }

            val lastP = points.last()
            fillPath.lineTo(lastP.x, centerY)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SettingsGreen.copy(alpha = 0.35f),
                        SettingsGreen.copy(alpha = 0.02f)
                    )
                )
            )

            drawPath(
                path = path,
                color = SettingsGreen,
                style = Stroke(
                    width = 3.5f,
                    cap = StrokeCap.Round
                )
            )

            val bandFreqs = com.music.spotui.audio.EqualizerAudioProcessor.FREQUENCIES
            for (f in bandFreqs) {
                val logF = log10(f.toDouble())
                val fraction = ((logF - logMin) / (logMax - logMin)).toFloat()
                val x = fraction * width
                val responseDb = processor.getFrequencyResponse(f).coerceIn(-12f, 12f)
                val y = centerY - (responseDb / 12f) * (height / 2.3f)

                drawCircle(color = SettingsGreen, radius = 5.5f, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 2.5f, center = Offset(x, y))
            }
        }
    }
}
