package com.music.spotui.ui.components

import com.music.spotui.util.uiLocalized

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.spotui.data.BatteryOptimizationHelper

private var hasPromptedThisSession = false


@Composable
fun BatteryOptimizationPrompt() {
    val context = LocalContext.current
    var isExempt by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimization(context))
    }
    var showDialog by remember {
        mutableStateOf(!isExempt && !hasPromptedThisSession)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimization(context)
                if (isExempt) showDialog = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!showDialog || isExempt) return

    val (mfgName, mfgTip) = remember { BatteryOptimizationHelper.getManufacturerTips() ?: (null to null) }

    AlertDialog(
        onDismissRequest = {
            hasPromptedThisSession = true
            showDialog = false
        },
        containerColor = Color(0xFF24242A),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text(
                text = uiLocalized("Reproducción en segundo plano"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column {
                Text(
                    text = "Para que la música continúe reproduciéndose sin pausas cuando la pantalla esté bloqueada, tengas el ahorro de batería activado o utilices otras aplicaciones, desactiva la optimización de batería para Spotui.",
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC)
                )
                if (!mfgTip.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Consejo para $mfgName:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1DB954)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = mfgTip,
                        fontSize = 12.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    hasPromptedThisSession = true
                    showDialog = false
                    runCatching {
                        val reqIntent = BatteryOptimizationHelper.buildRequestOptimizationIntent(context)
                        context.startActivity(reqIntent)
                    }.onFailure {
                        runCatching {
                            val appSettingsIntent = BatteryOptimizationHelper.buildAppSettingsIntent(context)
                            context.startActivity(appSettingsIntent)
                        }
                    }
                }
            ) {
                Text(uiLocalized("Permitir sin restricciones"), color = Color(0xFF1DB954), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    hasPromptedThisSession = true
                    showDialog = false
                }
            ) {
                Text(uiLocalized("Más tarde"), color = Color(0xFF888888))
            }
        }
    )
}
