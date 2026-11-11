package com.music.spotui.sync

import org.junit.Assert.*
import org.junit.Test

class SyncTrafficPolicyTest {
    @Test fun cachedProcessAloneIsNotOnline(){assertFalse(syncRuntimeAvailable(false,false,false))}
    @Test fun visibleUiIsOnline(){assertTrue(syncRuntimeAvailable(true,false,false))}
    @Test fun backgroundPlaybackIsOnline(){assertTrue(syncRuntimeAvailable(false,true,false))}
    @Test fun activePerceptibleServiceIsOnline(){assertTrue(syncRuntimeAvailable(false,false,true))}
    @Test fun ownPresenceIsNotAPeer(){assertFalse(hasSyncPeer(listOf(GoogleDeviceSync.Device("Me",true,1,"me")),"me"))}
    @Test fun offlinePeersNeverEnablePlaybackSync(){assertFalse(hasSyncPeer(listOf(GoogleDeviceSync.Device("Other",false,1,"other")),"me"))}
    @Test fun anOnlineOtherDeviceEnablesPlaybackSync(){assertTrue(hasSyncPeer(listOf(GoogleDeviceSync.Device("Other",true,1,"other")),"me"))}
    @Test fun duplicateIdempotentCommandsAreCollapsed(){val gate=PlaybackControlCoalescer()
        assertTrue(gate.accept("play",0,"","epoch",0));assertFalse(gate.accept("play",0,"","epoch",200));assertTrue(gate.accept("play",0,"","epoch",1500))}
    @Test fun pauseAfterPlayAndNewSeeksAreNeverLost(){val gate=PlaybackControlCoalescer()
        assertTrue(gate.accept("play",0,"","epoch",0));assertTrue(gate.accept("pause",0,"","epoch",10))
        assertTrue(gate.accept("seek",1000,"","epoch",20));assertTrue(gate.accept("seek",2000,"","epoch",30))}
    @Test fun repeatedNextCommandsKeepTheirOrder(){val gate=PlaybackControlCoalescer();repeat(10){assertTrue(gate.accept("next",0,"","epoch",it.toLong()))}}
    @Test fun newOwnershipDoesNotReuseOldDuplicateSuppression(){val gate=PlaybackControlCoalescer()
        assertTrue(gate.accept("play",0,"","first",0));assertTrue(gate.accept("play",0,"","second",1))}
    @Test fun aSessionFromBeforeDeviceReconnectionIsNotLive(){
        val frame=DevicePlaybackState("peer","Peer","e","q",0,0,1000,true,1000)
        assertFalse(playbackOwnerLive(frame,listOf(GoogleDeviceSync.Device("Peer",true,10000,"peer"))))
        assertTrue(playbackOwnerLive(frame.copy(updated=11000),listOf(GoogleDeviceSync.Device("Peer",true,10000,"peer"))))
    }
}
