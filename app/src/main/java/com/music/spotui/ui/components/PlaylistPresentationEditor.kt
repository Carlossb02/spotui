package com.music.spotui.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
    cover: String,
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

    PlaylistEditingSheet(
        title = "Descripción y portada local",
        cover = pickedCover?.toString() ?: cover,
        saving = saving,
        saveEnabled = description != saved.description || pickedCover != null || resetCover,
        onDismiss = onDismiss,
        onEditCover = { picker.launch(arrayOf("image/*")) },
        onSave = {
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
        fields = {
            PlaylistEditingField(description ?: defaultDescription, "Descripción",
                { if (it.length <= 3_000) description = it }, !saving, multiline = true)
        },
        extras = {
            if (description != null) TextButton(onClick = { description = null }, enabled = !saving) {
                Text("Usar descripción original")
            }
            if (!resetCover && (saved.coverUri.isNotBlank() || pickedCover != null)) {
                TextButton(onClick = { pickedCover = null; resetCover = true }, enabled = !saving) {
                    Text("Usar portada original")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        },
    )
}
