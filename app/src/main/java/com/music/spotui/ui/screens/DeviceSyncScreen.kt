package com.music.spotui.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.navigation.NavController
import com.music.spotui.sync.GoogleDeviceSync
import com.music.spotui.sync.SyncOptions
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Context.syncActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.syncActivity()
    else -> null
}

/** End-user account controls, reused directly in Settings. No developer configuration is exposed. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun GoogleAccountSettings(onOpenDetails: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by GoogleDeviceSync.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val green = Color(0xFF1ED760)
    val secondary = Color(0xFFB3B3B3)

    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (_: GetCredentialCancellationException) {
                // Closing the account chooser is an ordinary user action.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = GoogleDeviceSync.status.value.error ?: if (GoogleDeviceSync.status.value.email.isBlank())
                    "No se pudo iniciar sesión con Google. Inténtalo de nuevo."
                else "No se pudo completar el inicio de sesión. Inténtalo de nuevo."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status.email.isBlank()) {
            Button(
                onClick = {
                    action {
                        val activity = context.syncActivity() ?: error("No se pudo abrir Google.")
                        GoogleDeviceSync.signIn(activity)
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = Color.Black),
            ) {
                Icon(Icons.Default.AccountCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar sesión con Google")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape)) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = green, modifier = Modifier.fillMaxSize())
                    if (status.photoUrl.isNotBlank()) {
                        GlideImage(model = status.photoUrl, contentDescription = "Foto de tu cuenta de Google",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(status.email, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(status.phase, color = if (status.phase == "Sincronizado") green else secondary)
                }
            }
            val accessUnavailable = !status.accessChecked && status.phase == "Acceso sin comprobar"
            val accessColor = when {
                status.authorized -> green
                status.accessChecked -> Color(0xFFFFB4AB)
                else -> secondary
            }
            Surface(color = accessColor.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!status.accessChecked && !status.authorized && !accessUnavailable) {
                        CircularProgressIndicator(color = accessColor, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(if (status.authorized) Icons.Outlined.CheckCircle else if(accessUnavailable) Icons.Outlined.ErrorOutline else Icons.Outlined.Block,
                            contentDescription = null, tint = accessColor, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(uiLocalized(when {
                            status.authorized -> "Account authorized"
                            status.accessChecked -> "Account not authorized"
                            accessUnavailable -> "Access could not be verified"
                            else -> "Checking account access"
                        }), color = accessColor, fontWeight = FontWeight.SemiBold)
                        Text(uiLocalized(when {
                            status.authorized -> "This account can sync your devices."
                            status.accessChecked -> "Sync is disconnected. After the owner grants access, tap Check access."
                            accessUnavailable -> "Sync is disconnected. Check your connection and tap Check access."
                            else -> "Waiting for Firebase to confirm access."
                        }), color = secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!status.authorized) {
                TextButton(enabled = status.accessChecked || accessUnavailable, onClick = { GoogleDeviceSync.checkAccess() }) {
                    Text(uiLocalized("Check access"), color = green)
                }
            }
            com.music.spotui.ui.components.GoogleDriveConsentButton()
            if (status.authorized && status.driveBlocked) {
                TextButton(onClick = { GoogleDeviceSync.retryDriveConnection() }) {
                    Text(uiLocalized("Retry Drive connection"), color = green)
                }
            }
            if (!status.authorized && status.uid.isNotBlank()) {
                SelectionContainer { Text("UID: ${status.uid}", color = secondary, style = MaterialTheme.typography.bodySmall) }
            }
            if (onOpenDetails != null) {
                TextButton(onClick = onOpenDetails) { Text("Ver sincronización", color = green) }
            } else {
                if (status.pending > 0) Text("${status.pending} cambios pendientes", color = secondary)
                if (status.filesPending > 0) Text("${status.filesPending} archivos pendientes", color = secondary)
                status.progress?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = green)
                    Text("${(progress * 100).toInt()} %", color = secondary)
                }

            }
            TextButton(enabled = !busy, onClick = { action { GoogleDeviceSync.signOut() } }) {
                Text("Cerrar sesión", color = secondary)
            }
        }
        (error ?: status.error)?.let { message -> Text(message, color = Color(0xFFFFB4AB)) }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = green)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSyncScreen(navController: NavController) {
    val status by GoogleDeviceSync.status.collectAsState()
    val context=LocalContext.current
    val green = Color(0xFF1ED760)
    val secondary = Color(0xFFB3B3B3)
    var options by remember {mutableStateOf(false)}
    val selected by SyncOptions.values.collectAsState()
    if(options)AlertDialog(onDismissRequest={options=false},containerColor=Color(0xFF202020),
        title={Text(uiLocalized("Sync on this device"),color=Color.White)},
        text={Column(Modifier.fillMaxWidth().heightIn(max=460.dp).verticalScroll(rememberScrollState())) {
            SyncOptions.Category.entries.forEach {category->
                Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end=12.dp)) {
                        Text(uiLocalized(category.title),color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(uiLocalized(category.description),color=secondary,style=MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked=selected[category]!=false,onCheckedChange={SyncOptions.set(context,category,it)},
                        colors=SwitchDefaults.colors(checkedTrackColor=green,checkedThumbColor=Color.Black))
                }
            }
        }},confirmButton={TextButton(onClick={options=false}) {Text(uiLocalized("Done"),color=green)}})
    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Sincronización", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212)),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF202020)), shape = RoundedCornerShape(16.dp)) {
                GoogleAccountSettings()
            }
            TextButton(onClick={options=true},modifier=Modifier.fillMaxWidth()) {Text(uiLocalized("Choose what to sync"),color=green)}
            if (status.email.isNotBlank() && status.devices.isNotEmpty()) {
                Text("Dispositivos", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                status.devices.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF202020), RoundedCornerShape(12.dp)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        com.music.spotui.ui.components.PlaybackDeviceIcon(com.music.spotui.ui.utils.playbackDeviceKind(device.kind,device.name),if(device.online)green else secondary)
                        Column {
                            Text(device.name, color = Color.White)
                            Text(if (device.online) "Conectado" else "Desconectado", color = secondary)
                        }
                    }
                }
            }
        }
    }
}
