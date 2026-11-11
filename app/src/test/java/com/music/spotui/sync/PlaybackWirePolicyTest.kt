package com.music.spotui.sync
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackWirePolicyTest {
    private val song=SongsModel(1,"Tema de prueba","Álbum de prueba","Artista de prueba","https://i.scdn.co/image/ab67616d0000b273"+"a".repeat(32),"Artist song official Spotify search query",spotifyTrackId="a".repeat(22),durationMs=240_000,artistIds="b".repeat(22))
    private fun state()=DevicePlaybackState("00000000-1111-2222-3333-444444444444","OnePlus Nord 3 5G","11111111-2222-3333-4444-555555555555","22222222-3333-4444-5555-666666666666",0,0,240_000,true,100_000,source="Mi música",track=song)
    @Test fun continuousProgressDoesNotSendPeriodicCheckpoints() {
        val first=state().copy(duration=86_400_000);val signature=playbackWireSignature(first,1)
        for(second in 1..86_400) {
            val next=first.copy(updated=first.updated+second*1000L,position=second*1000L)
            assertFalse(shouldSendPlayback(first,next,signature,playbackWireSignature(next,1)))
        }
    }
    @Test fun pauseResumeSkipAndSeekAreSent() {
        val first=state();val signature=playbackWireSignature(first,1)
        for(next in listOf(first.copy(playing=false),first.copy(index=1),first.copy(position=20_000),first.copy(shuffle=true)))assertTrue(shouldSendPlayback(first,next,signature,playbackWireSignature(next,1)))
    }
    @Test fun coverAndTitleAreAvailableWithoutTheFullQueue() {
        val first=state();val restored=DevicePlaybackState.parse(first.json())
        assertEquals(song.title,restored.track?.title);assertEquals(song.coverUri,restored.track?.coverUri);assertFalse(first.json().has("songs"))
    }
    @Test fun descriptionsDoNotTravelInPlayerEvents() {
        val frame=state().copy(track=song.copy(description="x".repeat(100_000)))
        assertTrue(frame.json().toString().toByteArray().size<2048)
    }
    @Test fun aDayOfFourMinuteTracksAndTwoExtraControlsPerTrackHasSmallPayload() {
        val bytes=state().json().toString().toByteArray(Charsets.UTF_8).size
        val signal=JSONObject().put("id","drive-file-reference").put("rev","a".repeat(64)).toString().toByteArray().size
        val daily=360L*(3*bytes+2*signal)
        assertTrue("Payload: $daily bytes before network overhead",daily<1_000_000)
    }
}
