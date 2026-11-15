package com.music.spotui.audio

import com.music.spotui.data.preferences.AlternativeStream

internal object DownloadRecordingPolicy {
    fun videoId(alternative:AlternativeStream?,selectedId:String?):String? {
        if(alternative?.isLocal==true)return null
        if(alternative?.isYouTube==true) {
            require(alternative.value.matches(Regex("[A-Za-z0-9_-]{11}"))) {"Invalid alternative YouTube ID"}
            return alternative.value
        }
        return selectedId?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{11}"))}
    }
    fun exactQuery(original:String,videoId:String?)=videoId?.let {"https://www.youtube.com/watch?v=$it"} ?: original
}
