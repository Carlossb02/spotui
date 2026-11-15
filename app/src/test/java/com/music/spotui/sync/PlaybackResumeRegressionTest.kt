package com.music.spotui.sync

import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackResumeRegressionTest {
    @Test fun anOfflineSecondDeviceStillReceivesTheNextSavedPlaybackAndReadMarkers() {
        val own = GoogleDeviceSync.Device("phone", true, 1000, "phone", "phone")
        val tablet = GoogleDeviceSync.Device("tablet", false, 900, "tablet", "tablet")
        assertFalse(hasSyncPeer(listOf(own, tablet), "phone"))
        assertTrue(hasKnownSyncPeer(listOf(own, tablet), "phone"))
        assertFalse(hasKnownSyncPeer(listOf(own), "phone"))
    }
    private val frame = DevicePlaybackState("tablet", "Tablet", "session", "queue", 1,
        65_000, 300_000, true, 100_000)
    @Test fun aPhoneRestoresOnlyAPlaybackNewerThanItsOwn() {
        assertTrue(shouldRestorePlayback(frame, 99_000))
        assertFalse(shouldRestorePlayback(frame, 100_000))
        assertFalse(shouldRestorePlayback(frame, 101_000))
    }
    @Test fun offlineOwnerKeepsItsLastMinuteInsteadOfAdvancingToTheEndOfTheSong() {
        val saved = playbackResumeFrame(frame, false, 10_000_000)
        assertEquals(65_000L, saved.position); assertFalse(saved.playing)
        assertEquals(frame.updated, saved.updated)
    }
    @Test fun anOnlineOwnersClockStillAdvancesBetweenSmallCheckpoints() {
        val shown = playbackResumeFrame(frame, true, 105_000)
        assertEquals(70_000L, shown.position); assertTrue(shown.playing)
    }
    @Test fun checkpointBelongsToItsOwnerAndSessionAndCanSeekBackToZero() {
        fun point(owner: String = "tablet", epoch: String = "session", time: Long = 110_000, position: Long = 0) =
            JSONObject().put("owner", owner).put("epoch", epoch).put("at", time).put("position", position)
        assertEquals(frame, playbackWithCheckpoint(frame, point(owner = "phone")))
        assertEquals(frame, playbackWithCheckpoint(frame, point(epoch = "old session")))
        assertEquals(frame, playbackWithCheckpoint(frame, point(time = 90_000)))
        val changed = playbackWithCheckpoint(frame, point())
        assertEquals(0L, changed.position); assertEquals(110_000L, changed.updated)
        assertEquals(frame.queueId, changed.queueId); assertEquals(frame.index, changed.index)
    }
    @Test fun queueReplicaKeepsOrderAndManualRequestsWithoutDescriptionsOrEmbeddedImages() {
        val queue = DevicePlaybackQueue(listOf(
            SongsModel(1, "first", "album", "artist", "data:image/png;base64," + "x".repeat(100_000), "query1", description = "x".repeat(100_000)),
            SongsModel(2, "manual", "album", "artist", "https://example.com/cover", "query2")), setOf(1), listOf(0))
        val json = queue.textJson()
        assertTrue(json.toString().length < 2000)
        val restored = DevicePlaybackQueue.parse(json)
        assertEquals(listOf(1, 2), restored.songs.map { it.id })
        assertEquals(setOf(1), restored.manual); assertEquals(listOf(0), restored.original)
        assertEquals("", restored.songs[0].coverUri); assertEquals("", restored.songs[0].description)
        assertEquals("https://example.com/cover", restored.songs[1].coverUri)
    }
    @Test fun savedSessionRetainsItsOwnQueueReferenceEvenIfAnotherDevicePublishesAnotherQueue() {
        val pointer = JSONObject().put("id", "queue").put("blob", JSONObject().put("id", "drive-file").put("rev", "revision"))
        val saved = frame.copy(queuePointer = pointer.toString())
        val restored = DevicePlaybackState.parse(saved.json())
        assertEquals("queue", JSONObject(restored.queuePointer).getString("id"))
        assertEquals("drive-file", JSONObject(restored.queuePointer).getJSONObject("blob").getString("id"))
        assertEquals(saved, restored)
    }
}
