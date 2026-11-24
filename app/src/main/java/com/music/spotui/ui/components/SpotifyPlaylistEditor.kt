package com.music.spotui.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.api.SpotifyPlaylistEditorApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SpotifyPlaylistEditor(
    id: String,
    localId: String?,
    onSaved: suspend (name: String?, description: String?, changedCover: Boolean) -> Unit,
    onOpenSpotify: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember(id) { mutableStateOf<SpotifyPlaylistEditorApi.Details?>(null) }
    var name by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var description by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var cover by rememberSaveable(id) { mutableStateOf<Uri?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) cover = picked
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var coverFailed by remember { mutableStateOf(false) }
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
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Editar lista sincronizada") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (original == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (original != null) {
                    OutlinedTextField(
                        name ?: original.name, { name = it }, label = { Text("Nombre") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving,
                    )
                    OutlinedTextField(
                        description ?: original.description, { description = it }, label = { Text("Descripción") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !saving,
                    )
                    TextButton(enabled = !saving, onClick = { coverPicker.launch("image/*") }) {
                        Text(if (cover == null) "Elegir portada" else "Cambiar portada seleccionada")
                    }
                    if (cover != null) {
                        TextButton(enabled = !saving, onClick = { cover = null }) { Text("Quitar selección") }
                    }
                }
                if (original != null) {
                    TextButton(onClick = onOpenSpotify, enabled = !saving) {
                        Text(if (coverFailed) "Abrir portada en Spotify" else "Privacidad en Spotify")
                    }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (original == null) TextButton(onClick = { loadAttempt++ }) { Text("Reintentar") }
                }
            }
        },
        confirmButton = {
            if (original != null) TextButton(
                enabled = !saving && newName?.isBlank() != true && (newName != null || newDescription != null || cover != null),
                onClick = {
                    saving = true
                    error = null
                    coverFailed = false
                    val selectedCover = cover
                    scope.launch {
                        try {
                            val result = SpotifyPlaylistEditorApi.save(
                                context, id, original.ownerId, localId, newName, newDescription, selectedCover,
                            )
                            val savedCover = selectedCover != null && result.coverError == null
                            if (newName != null || newDescription != null || savedCover) {
                                onSaved(newName, newDescription, savedCover)
                            }
                            if (result.coverError == null) {
                                onDismiss()
                            } else {
                                details = original.copy(
                                    name = newName ?: original.name,
                                    description = newDescription ?: original.description,
                                )
                                error = if (newName != null || newDescription != null) {
                                    "Se guardaron los demás cambios. ${result.coverError}"
                                } else result.coverError
                                coverFailed = true
                            }
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
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cerrar") } },
    )
}
