package com.music.spotui.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.music.spotui.sync.GoogleDeviceSync
import com.music.spotui.util.uiLocalized
import kotlinx.coroutines.launch

/** Authorization UI opens only after a user's gesture, never from a background worker. */
@Composable
fun GoogleDriveConsentButton() {
    val account by GoogleDeviceSync.status.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    var authorizationUid by rememberSaveable {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {result->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null)runCatching {GoogleDeviceSync.acceptDriveIntent(result.data!!,authorizationUid)}.onFailure {error=it.message}
        busy=false
    }
    if(account.authorized && account.needsDrive) {
        TextButton(enabled=!busy,onClick={scope.launch {
            busy=true;error=null;authorizationUid=account.uid
            try {
                val result=GoogleDeviceSync.requestDriveAuthorization()
                if(result.hasResolution()) {
                    val pending=result.pendingIntent?:error("Google no pudo abrir la autorización.")
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                }else {GoogleDeviceSync.acceptDriveAuthorization(result,authorizationUid);busy=false}
            }catch(c:kotlinx.coroutines.CancellationException){busy=false;throw c}
            catch(t:Exception){busy=false;error=t.message?:"No se pudo autorizar Google Drive."}
        }}) {Text(uiLocalized(if(busy)"Connecting…" else "Authorize Google Drive"),color=Color(0xFF1ED760))}
        error?.let {Text(it,color=Color(0xFFFFB4AB))}
    }
}

@Composable
fun GoogleDriveConsentAlert() {
    val account by GoogleDeviceSync.status.collectAsState()
    var dismissed by rememberSaveable(account.uid){mutableStateOf(false)}
    LaunchedEffect(account.needsDrive){if(!account.needsDrive)dismissed=false}
    if(account.authorized && account.needsDrive && !dismissed) {
        AlertDialog(onDismissRequest={dismissed=true},containerColor=Color(0xFF202020),
            title={Text(uiLocalized("Allow device sync"),color=Color.White)},
            text={Text(uiLocalized("Authorize Google Drive to sync your app data using less Firebase. Audio files and downloads stay on this device."),color=Color(0xFFB3B3B3))},
            confirmButton={GoogleDriveConsentButton()},
            dismissButton={TextButton(onClick={dismissed=true}){Text(uiLocalized("Later"),color=Color(0xFFB3B3B3))}})
    }
}
