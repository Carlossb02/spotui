package com.music.spotui.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimization(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun buildRequestOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun buildAppSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun getManufacturerTips(): Pair<String, String>? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> "Xiaomi / MIUI" to
            "Settings > Apps > Manage apps > Spotidroid > Battery saver > No restrictions"
        "huawei", "honor" -> "Huawei / EMUI" to
            "Settings > Apps > Apps > Spotidroid > Battery > Allow background activity"
        "samsung" -> "Samsung / One UI" to
            "Settings > Apps > Spotidroid > Battery > Allow background activity"
        "oneplus", "realme" -> "OnePlus / ColorOS" to
            "Settings > Apps > Spotidroid > Battery usage > Allow background activity"
        "oppo" -> "OPPO / ColorOS" to
            "Settings > Apps > Spotidroid > Battery usage > Allow background activity"
        "vivo" -> "Vivo / Funtouch" to
            "Settings > Apps > Spotidroid > Battery > Background restriction > Unrestricted"
        "sony" -> "Sony / Xperia" to
            "Settings > Apps > Spotidroid > Battery > Battery optimization > Don't optimize"
        "nothing" -> "Nothing OS" to
            "Settings > Apps > Spotidroid > Battery > Unrestricted"
        else -> null
    }
}
