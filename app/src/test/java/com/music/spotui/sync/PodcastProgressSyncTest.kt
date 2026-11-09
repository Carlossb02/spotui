package com.music.spotui.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PodcastProgressSyncTest {
    private val key="spotui_podcast_resume_v1:episodes"
    private fun tree(updated:Long,position:Long,stamp:Long,duration:Long=100_000):SyncTree {
        val value=JSONObject("""{"type":"string","value":[{"id":1,"url":"yt_ep:one","position":$position,"duration":$duration,"updated":$updated}]}""")
        return SyncTree.changed(emptyMap(),SyncTree.flatten(value),SyncStamp(stamp,0,"device$stamp"))
    }
    private fun episode(tree:SyncTree)=(tree.value() as JSONObject).getJSONArray("value").getJSONObject(0)
    @Test fun importingAnOlderDeviceCannotOverwriteNewerProgress(){
        val recent=tree(500,40_000,10);val imported=tree(100,10_000,20)
        val result=episode(PodcastProgressSync.merge(key,recent,imported))
        assertEquals(500,result.getLong("updated"));assertEquals(40_000,result.getLong("position"))
    }
    @Test fun intentionalBackwardSeekWinsIfItIsNewer(){
        val forward=tree(100,70_000,20);val backward=tree(500,5_000,10)
        assertEquals(5_000,episode(PodcastProgressSync.merge(key,forward,backward)).getLong("position"))
    }
    @Test fun progressAndDurationComeFromTheSameSample(){
        val a=tree(500,40_000,10,200_000);val b=tree(100,10_000,20,100_000)
        assertEquals(200_000,episode(PodcastProgressSync.merge(key,a,b)).getLong("duration"))
    }
    @Test fun twoDevicesConvergeInEitherOrder(){
        val a=tree(100,70_000,20);val b=tree(500,5_000,10)
        assertEquals(PodcastProgressSync.merge(key,a,b),PodcastProgressSync.merge(key,b,a))
    }
    @Test fun exactClockTiesHaveADeterministicWinner(){
        val a=tree(500,10_000,10);val b=tree(500,40_000,10)
        assertEquals(PodcastProgressSync.merge(key,a,b),PodcastProgressSync.merge(key,b,a))
    }
    @Test fun ordinaryLibraryDocumentsKeepExistingConflictRules(){
        val a=tree(500,40_000,10);val b=tree(100,10_000,20)
        assertEquals(a.merge(b),PodcastProgressSync.merge("LocalPlaylists:playlists",a,b))
    }
}
