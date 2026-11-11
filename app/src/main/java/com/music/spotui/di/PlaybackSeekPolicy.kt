package com.music.spotui.di

/** A metadata alias is not a decoder identity. Never seek an outgoing source during resolution. */
internal fun canSeekLoadedTrack(resolving:Boolean,hasMedia:Boolean,loadedQuery:String?,requestedQuery:String,
    loadedMediaId:String?,requestedMediaId:String?):Boolean {
    if(resolving || !hasMedia || loadedQuery.isNullOrBlank() || requestedQuery.isBlank())return false
    return loadedQuery==requestedQuery || !loadedMediaId.isNullOrBlank() && loadedMediaId !in setOf("song/0","song/-1") && loadedMediaId==requestedMediaId
}
