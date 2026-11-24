package com.music.spotui.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.preferences.PlaylistPresentationPref
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlaylistPresentationEditor(
    playlistId: String,
    saved: PlaylistPresentationPref.Presentation,
    defaultDescription: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by rememberSaveable { mutableStateOf(saved.description) }
    var pickedCover by rememberSaveable { mutableStateOf<Uri?>(null) }
    var resetCover by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedCover = uri
            resetCover = false
            error = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Editar descripción y portada") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Los cambios se guardan en este dispositivo.")
                OutlinedTextField(
                    value = description ?: defaultDescription,
                    onValueChange = { if (it.length <= 3_000) description = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    enabled = !saving,
                )
                if (description != null) {
                    TextButton(onClick = { description = null }, enabled = !saving) {
                        Text("Usar descripción original")
                    }
                }
                TextButton(enabled = !saving, onClick = { picker.launch(arrayOf("image/*")) }) {
                    Text(if (pickedCover != null) "Cambiar imagen seleccionada" else "Elegir portada")
                }
                if (!resetCover && (saved.coverUri.isNotBlank() || pickedCover != null)) {
                    TextButton(onClick = { pickedCover = null; resetCover = true }, enabled = !saving) {
                        Text("Usar portada original")
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && (description != saved.description || pickedCover != null || resetCover),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                PlaylistPresentationPref.saveDetails(context, playlistId, description, pickedCover, resetCover)
                            }
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "No se pudieron guardar los cambios."
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") } },
    )
}
