package com.music.spotui.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.api.SpotifyPlaylistEditorApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SpotifyPlaylistEditor(
    id: String,
    localId: String?,
    cover: String,
    onSaved: suspend (name: String?, description: String?) -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember(id) { mutableStateOf<SpotifyPlaylistEditorApi.Details?>(null) }
    var name by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var description by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(id, localId, loadAttempt) {
        error = null
        try {
            details = SpotifyPlaylistEditorApi.load(context, id, localId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "No se pudo abrir la lista en Spotify."
        }
    }
    val original = details
    val newName = name?.trim()?.takeIf { it != original?.name }
    val newDescription = description?.takeIf { it != original?.description }
    PlaylistEditingSheet(
        title = "Nombre e información",
        cover = cover,
        saving = saving,
        saveEnabled = original != null && newName?.isBlank() != true && (newName != null || newDescription != null),
        onDismiss = onDismiss,
        onSave = save@{
            val ownerId = original?.ownerId ?: return@save
            saving = true
            error = null
            scope.launch {
                try {
                    SpotifyPlaylistEditorApi.save(context, id, ownerId, localId, newName, newDescription)
                    onSaved(newName, newDescription)
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
            if (original != null) {
                PlaylistEditingField(name ?: original.name, "Nombre", { name = it }, !saving)
                PlaylistEditingField(description ?: original.description, "Descripción", { description = it }, !saving, multiline = true)
            } else if (error == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        extras = {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (original == null) TextButton(onClick = { loadAttempt++ }) { Text("Reintentar") }
            }
            if (original != null) {
                PlaylistEditingAction(Icons.Default.DeleteOutline, "Eliminar lista", !saving, onRemoveFromLibrary)
            }
        },
    )
}
