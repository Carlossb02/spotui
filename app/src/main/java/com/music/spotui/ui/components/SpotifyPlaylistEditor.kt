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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.music.spotui.data.api.SpotifyPlaylistEditorApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun SpotifyPlaylistEditor(
    id: String,
    ownerId: String,
    onSaved: suspend (name: String?, descriptionChanged: Boolean, coverChanged: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by SpotifyPlaylistEditorApi.status.collectAsState()
    var clientId by rememberSaveable { mutableStateOf(SpotifyPlaylistEditorApi.clientId(context)) }
    var connecting by remember { mutableStateOf(!SpotifyPlaylistEditorApi.isConfigured(context)) }
    var details by remember(id) { mutableStateOf<SpotifyPlaylistEditorApi.Details?>(null) }
    var name by rememberSaveable(id) { mutableStateOf("") }
    var description by rememberSaveable(id) { mutableStateOf("") }
    var editedPublic by rememberSaveable(id) { mutableStateOf<Boolean?>(null) }
    var initialized by rememberSaveable(id) { mutableStateOf(false) }
    var cover by rememberSaveable(id) { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { cover = it }

    LaunchedEffect(auth) {
        if (auth == SpotifyPlaylistEditorApi.AuthStatus.Connected) connecting = false
    }
    LaunchedEffect(id, ownerId, connecting) {
        if (connecting || details != null) return@LaunchedEffect
        try {
            val remote = SpotifyPlaylistEditorApi.details(context, id, ownerId)
            if (!initialized) {
                name = remote.name
                description = remote.description
                initialized = true
            }
            details = remote
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "No se pudo abrir la lista en Spotify."
        }
    }
    val original = details
    val nameChanged = original != null && name.trim() != original.name
    val descriptionChanged = original != null && description != original.description
    val publicChanged = editedPublic != null && editedPublic != original?.public
    val hasChanges = nameChanged || descriptionChanged || publicChanged || cover != null

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Editar lista sincronizada") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connecting) {
                    Text("Registra la URI de retorno en el panel de Spotify y autoriza tu cuenta.")
                    SelectionContainerForRedirect()
                    OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { SpotifyPlaylistEditorApi.connect(context, clientId) }) {
                        Text(if (auth == SpotifyPlaylistEditorApi.AuthStatus.Connecting) "Reabrir autorización" else "Autorizar en Spotify")
                    }
                    (auth as? SpotifyPlaylistEditorApi.AuthStatus.Failed)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                    }
                } else if (original == null && error == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (original != null) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !saving)
                    OutlinedTextField(description, { description = it }, label = { Text("Descripción") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !saving)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Mostrar en el perfil público", modifier = Modifier.weight(1f))
                        Switch(
                            checked = editedPublic ?: (original.public == true),
                            onCheckedChange = { editedPublic = it },
                            enabled = !saving && !original.collaborative && original.public != null,
                        )
                    }
                    Text("El acceso mediante enlace se gestiona en Spotify.", style = MaterialTheme.typography.bodySmall)
                    if (original.collaborative) Text("La lista es colaborativa. Gestiona la colaboración en Spotify.")
                    TextButton(onClick = { picker.launch("image/*") }, enabled = !saving) {
                        Text(if (cover == null) "Elegir una portada" else "Cambiar imagen seleccionada")
                    }
                    if (cover != null) TextButton(onClick = { cover = null }, enabled = !saving) { Text("Quitar selección") }
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!connecting) error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (original == null) TextButton(onClick = {
                        error = null
                        SpotifyPlaylistEditorApi.prepareConnection()
                        connecting = true
                    }) { Text("Conectar otra vez") }
                }
            }
        },
        confirmButton = {
            if (original != null) TextButton(
                enabled = !saving && hasChanges && name.isNotBlank(),
                onClick = {
                    saving = true
                    error = null
                    val newName = name.trim().takeIf { nameChanged }
                    val pickedCover = cover
                    val changes = JSONObject().apply {
                        newName?.let { put("name", it) }
                        if (descriptionChanged) put("description", description)
                        if (publicChanged) put("public", editedPublic)
                    }
                    scope.launch {
                        try {
                            SpotifyPlaylistEditorApi.save(context, id, ownerId, changes, pickedCover)
                            onSaved(newName, descriptionChanged, pickedCover != null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failed: Exception) {
                            error = failed.message ?: "No se pudieron guardar los cambios."
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("Guardar en Spotify") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun SelectionContainerForRedirect() {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(SpotifyPlaylistEditorApi.REDIRECT_URI, style = MaterialTheme.typography.bodySmall)
    }
}
