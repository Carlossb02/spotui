package com.music.spotui.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseNewsSyncTest {
    @Test fun resetMarkersConvergeAndOlderReadsCannotCancelTheReset() {
        val reset=PodcastProgressSync.merge("release_news_seen_v1:podcasts_reset",tree(200,10),tree(50,99))
        assertEquals(200L,checked(reset))
        assertEquals(0L,com.music.spotui.data.preferences.effectiveReleaseCheck(150,checked(reset)))
        assertEquals(300L,com.music.spotui.data.preferences.effectiveReleaseCheck(300,checked(reset)))
        assertEquals(SyncOptions.Category.PODCASTS,SyncOptions.category("release_news_seen_v1:podcasts_reset"))
        assertTrue(CloudPreferences.allowed("release_news_seen_v1","music_reset"))
    }
    private fun tree(checked:Long,stamp:Long)=SyncTree.changed(emptyMap(),
        SyncTree.flatten(JSONObject().put("type","long").put("value",checked)),SyncStamp(stamp,0,"d$stamp"))
    private fun checked(tree:SyncTree)=(tree.value() as JSONObject).getLong("value")
    @Test fun staleDeviceWithNewerClockCannotResurrectReadNews() {
        for(kind in listOf("music","podcasts"))assertEquals(500L,
            checked(PodcastProgressSync.merge("release_news_seen_v1:$kind",tree(500,10),tree(100,100))))
    }
    @Test fun devicesConvergeInEitherMergeOrder() {
        val a=tree(500,10);val b=tree(100,100);val key="release_news_seen_v1:music"
        assertEquals(PodcastProgressSync.merge(key,a,b),PodcastProgressSync.merge(key,b,a))
    }
    @Test fun repeatedImportsNeverMoveWatermarkBackwards() {
        val key="release_news_seen_v1:podcasts"
        var state=tree(100,1)
        for((checked,stamp) in listOf(500L to 2L,200L to 3L,500L to 4L,600L to 5L,100L to 6L))
            state=PodcastProgressSync.merge(key,state,tree(checked,stamp))
        assertEquals(600L,checked(state))
    }
    @Test fun twoCategoriesUseSeparateDocumentsAndSyncOptions() {
        assertEquals(SyncOptions.Category.LIBRARY,SyncOptions.category("release_news_seen_v1:music"))
        assertEquals(SyncOptions.Category.PODCASTS,SyncOptions.category("release_news_seen_v1:podcasts"))
        assertEquals(tree(100,2).merge(tree(50,3)),PodcastProgressSync.merge("settings_prefs:ordinary",tree(100,2),tree(50,3)))
    }
}
