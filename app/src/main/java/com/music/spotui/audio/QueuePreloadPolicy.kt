package com.music.spotui.audio

internal object QueuePreloadPolicy {
    const val MAX_TRACK_BYTES=32L*1024*1024
    fun next(queue:List<String>,current:Int,count:Int):List<String> =
        if(current !in queue.indices)emptyList() else queue.drop(current+1).filter(String::isNotBlank)
            .filterNot {it==queue[current]}.distinct().take(count.coerceIn(1,5))
    fun targetBytes(contentLength:Long?,durationMs:Int,bitrate:Int):Long =
        contentLength?.takeIf {it>0}?.coerceAtMost(MAX_TRACK_BYTES)
            ?: if(durationMs>0 && bitrate>0)(durationMs.toLong()*bitrate/8000+128*1024).coerceIn(512*1024,MAX_TRACK_BYTES)
            else 8L*1024*1024
}
