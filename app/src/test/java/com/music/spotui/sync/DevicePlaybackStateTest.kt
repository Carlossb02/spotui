package com.music.spotui.sync

import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class DevicePlaybackStateTest {
    private fun frame()=DevicePlaybackState("tablet","Tablet","epoch","queue",0,10_000,180_000,true,100_000,startToken="start",from="phone")
    @Test fun runningPositionUsesSharedClock(){assertEquals(13_000,frame().positionAt(103_000))}
    @Test fun pausedPositionDoesNotAdvance(){assertEquals(10_000,frame().copy(playing=false).positionAt(110_000))}
    @Test fun disconnectedClockCannotRunForever(){assertEquals(25_000,frame().positionAt(1_000_000));assertFalse(frame().fresh(1_000_000))}
    @Test fun timelineNeverExceedsDuration(){assertEquals(180_000,frame().copy(position=179_000).positionAt(103_000))}
    @Test fun onlyTheSelectedDeviceMayStartAFreshTransfer(){
        assertTrue(frame().canStart("tablet",101_000));assertFalse(frame().canStart("phone",101_000))
        assertFalse(frame().canStart("tablet",116_000));assertFalse(frame().copy(startToken="").canStart("tablet",101_000))
    }
    @Test fun openingTheAppDoesNotReplayAnAcknowledgedTransfer(){assertFalse(frame().copy(startToken="").canStart("tablet",100_500))}
    @Test fun sessionRoundTripPreservesOwnershipAndModes(){
        val frame=frame().copy(shuffle=true,repeat="ALL",source="Mi lista")
        assertEquals(frame,DevicePlaybackState.parse(frame.json()))
    }
    @Test fun queueRoundTripPreservesDuplicateOccurrencesAndManualPriority(){
        val song=SongsModel(1,"Tema","Álbum","Artista","cover","query",spotifyTrackId="id",durationMs=120_000,artistIds="artist",explicit=true)
        val queue=DevicePlaybackQueue(listOf(song,song.copy(id=2),song),setOf(1),listOf(0,2,1))
        assertEquals(queue,DevicePlaybackQueue.parse(queue.json()))
    }
}
