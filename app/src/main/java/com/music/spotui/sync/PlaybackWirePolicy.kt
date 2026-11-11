package com.music.spotui.sync

/** A ticking clock alone never causes a transmission. */
internal fun playbackWireSignature(frame:DevicePlaybackState,queueHash:Int):String =
    "${frame.index}:${frame.playing}:${frame.shuffle}:${frame.repeat}:${frame.source}:${frame.sleepEnd}:${frame.volume}:${frame.duration}:${frame.track.hashCode()}:$queueHash"
internal fun shouldSendPlayback(previous:DevicePlaybackState,next:DevicePlaybackState,
    previousSignature:String,nextSignature:String,force:Boolean=false):Boolean =
    force || previousSignature!=nextSignature || kotlin.math.abs(next.position-previous.positionAt(next.updated))>1_500

/** Idempotent button duplicates are collapsed; deliberate next/previous presses keep their order. */
internal class PlaybackControlCoalescer {
    private var key=""
    private var at=Long.MIN_VALUE
    fun accept(action:String,position:Long,target:String,epoch:String,now:Long):Boolean {
        if(action !in setOf("play","pause","seek","volume"))return true
        val next="$action:$position:$target:$epoch"
        if(next==key && now>=at && now-at<1_000)return false
        key=next;at=now;return true
    }
}

internal fun playbackOwnerLive(frame:DevicePlaybackState,devices:List<GoogleDeviceSync.Device>):Boolean =
    devices.any {it.id==frame.owner && it.online && frame.updated>=it.lastSeen-2_000}

/** The current frame is authoritative even when a cached queue has older display metadata. */
internal fun playbackMirrorQueue(queue:DevicePlaybackQueue,frame:DevicePlaybackState):DevicePlaybackQueue {
    val track=frame.track?:return queue
    if(frame.index !in queue.songs.indices)return queue
    val current=queue.songs[frame.index]
    val cover=track.coverUri.takeUnless {it.startsWith("content://") || it.startsWith("file://") || it.startsWith("/") || it.startsWith("sync_pending:")} ?: current.coverUri
    val shown=current.copy(title=track.title,singer=track.singer,album=track.album,coverUri=cover,artistIds=track.artistIds)
    if(shown==current)return queue
    return queue.copy(songs=queue.songs.toMutableList().apply {set(frame.index,shown)})
}
