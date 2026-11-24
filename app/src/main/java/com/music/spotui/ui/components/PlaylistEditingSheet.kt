package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

private val SheetSurface = Color(0xFF21242B)
private val FieldSurface = Color(0xFF333740)
private val SaveColor = Color(0xFF79E6A6)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
internal fun PlaylistEditingSheet(
    title: String,
    cover: String,
    saving: Boolean,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onEditCover: (() -> Unit)? = null,
    fields: @Composable ColumnScope.() -> Unit,
    extras: @Composable ColumnScope.() -> Unit = {},
) {
    val isSaving = rememberUpdatedState(saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !isSaving.value },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        sheetMaxWidth = 760.dp,
        containerColor = SheetSurface,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text("Cancelar", color = if (saving) Color.Gray else Color.White)
                }
                Text(
                    title, Modifier.weight(1f).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                )
                TextButton(
                    onClick = onSave, enabled = saveEnabled && !saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = SaveColor,
                        disabledContentColor = SaveColor.copy(alpha = .35f)),
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                val coverSize = if (maxWidth >= 480.dp) 120.dp else 68.dp
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(coverSize).clip(RoundedCornerShape(12.dp))
                                .background(FieldSurface)
                                .then(if (onEditCover != null) Modifier.clickable(
                                    enabled = !saving, onClickLabel = "Cambiar portada", onClick = onEditCover,
                                ) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cover.isBlank()) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.LightGray)
                            } else {
                                GlideImage(cover, "Portada de la lista", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            if (onEditCover != null) {
                                Icon(Icons.Default.Edit, null, Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                    .background(SheetSurface, RoundedCornerShape(8.dp)).padding(4.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = fields)
                    }
                    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SaveColor)
                    extras()
                }
            }
        }
    }
}

@Composable
internal fun PlaylistEditingField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    multiline: Boolean = false,
) {
    TextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = !multiline,
        minLines = if (multiline) 2 else 1, maxLines = if (multiline) 5 else 1,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = FieldSurface, unfocusedContainerColor = FieldSurface,
            disabledContainerColor = FieldSurface, focusedTextColor = Color.White,
            unfocusedTextColor = Color.White, focusedLabelColor = SaveColor,
            unfocusedLabelColor = Color.LightGray, cursorColor = SaveColor,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun PlaylistEditingAction(
    icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) Color.LightGray else Color.Gray)
        Text(label, color = if (enabled) Color.White else Color.Gray)
    }
}
