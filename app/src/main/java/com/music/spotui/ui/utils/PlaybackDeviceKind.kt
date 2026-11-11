package com.music.spotui.ui.utils

/** Stable visual identity; choosing an icon never changes an audio route. */
enum class PlaybackDeviceKind { PHONE, TABLET, HEADPHONES, SPEAKER, OTHER }

fun playbackDeviceKind(kind:String,name:String=""):PlaybackDeviceKind = when(kind.lowercase()) {
    "phone"->PlaybackDeviceKind.PHONE
    "tablet"->PlaybackDeviceKind.TABLET
    "headphones"->PlaybackDeviceKind.HEADPHONES
    "speaker"->PlaybackDeviceKind.SPEAKER
    else->if(Regex("(?i)(\\btab\\b|\\btablet\\b|\\bpad\\b|ipad|xiaomi pad|oneplus pad)").containsMatchIn(name))PlaybackDeviceKind.TABLET else PlaybackDeviceKind.PHONE
}
