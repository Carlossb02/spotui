package com.music.spotui.di

/** Return the current occurrence to restart it, or the preceding queue occurrence. */
internal fun previousPlaybackIndex(index:Int,size:Int,positionMs:Long,repeatAll:Boolean):Int? {
    if(size<=0)return null
    val current=index.coerceIn(0,size-1)
    if(positionMs>=3_000L)return current
    if(current>0)return current-1
    return if(repeatAll)size-1 else current
}
