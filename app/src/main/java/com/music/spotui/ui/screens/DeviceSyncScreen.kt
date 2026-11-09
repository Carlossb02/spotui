package com.music.spotui.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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

    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { GoogleDeviceSync.acceptDriveIntent(result.data!!) }
                .onFailure { error = "No se pudo autorizar Drive. Inténtalo de nuevo." }
        }
    }
    suspend fun authorizeFiles() {
        GoogleDeviceSync.requireDriveConsent()
        val result = GoogleDeviceSync.requestDriveAuthorization()
        if (result.hasResolution()) {
            val intent = result.pendingIntent ?: error("No se pudo abrir Google Drive.")
            authorization.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        } else {
            GoogleDeviceSync.acceptDriveAuthorization(result)
        }
    }
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
                error = if (GoogleDeviceSync.status.value.email.isBlank())
                    "No se pudo iniciar sesión con Google. Inténtalo de nuevo."
                else "No se pudo completar la conexión con Drive. Inténtalo de nuevo."
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
                        authorizeFiles()
                    }
                },
                enabled = status.configured && !busy,
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
            if (status.needsDrive) {
                TextButton(enabled = !busy, onClick = { action { authorizeFiles() } }) {
                    Text("Permitir sincronización de archivos", color = green)
                }
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
                TextButton(enabled = !busy, onClick = { GoogleDeviceSync.retry() }) {
                    Text("Sincronizar ahora", color = green)
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
    val green = Color(0xFF1ED760)
    val secondary = Color(0xFFB3B3B3)
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
            if (status.email.isNotBlank() && status.devices.isNotEmpty()) {
                Text("Dispositivos", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                status.devices.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF202020), RoundedCornerShape(12.dp)).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = green)
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
