package com.music.spotui.data.preferences

import android.content.Context

internal enum class LyricPosition(val label:String) {CENTER("Centered"),LEFT("Left"),RIGHT("Right")}
internal enum class LyricSize(val label:String,val sp:Int) {SMALL("Small",14),MEDIUM("Medium",17),LARGE("Large",22)}
internal enum class LyricColorMode(val label:String) {
    WHITE("White"),CUSTOM("Choose a color"),VIDEO_CONTRAST("Video contrast"),
    AURORA("Aurora drift"),OCEAN("Ocean waves"),SUNSET("Sunset glow"),RAINBOW("Rainbow flow"),NEON("Neon pulse")
}
internal data class LyricLineAppearance(val position:LyricPosition=LyricPosition.CENTER,
    val size:LyricSize=LyricSize.MEDIUM,val color:LyricColorMode=LyricColorMode.WHITE,val customColor:Int=-1)
internal fun getLyricLineAppearance(context:Context):LyricLineAppearance {
    val p=context.getSharedPreferences("settings_prefs",0)
    return LyricLineAppearance(
        runCatching {LyricPosition.valueOf(p.getString("player_lyric_position","CENTER")!!)}.getOrDefault(LyricPosition.CENTER),
        runCatching {LyricSize.valueOf(p.getString("player_lyric_size","MEDIUM")!!)}.getOrDefault(LyricSize.MEDIUM),
        runCatching {LyricColorMode.valueOf(p.getString("player_lyric_color","WHITE")!!)}.getOrDefault(LyricColorMode.WHITE),
        p.getInt("player_lyric_custom_color",-1))
}
internal fun setLyricLineAppearance(context:Context,appearance:LyricLineAppearance) {
    context.getSharedPreferences("settings_prefs",0).edit().putString("player_lyric_position",appearance.position.name)
        .putString("player_lyric_size",appearance.size.name).putString("player_lyric_color",appearance.color.name)
        .putInt("player_lyric_custom_color",appearance.customColor).apply()
}
