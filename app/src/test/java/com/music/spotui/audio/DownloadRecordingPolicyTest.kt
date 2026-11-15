package com.music.spotui.audio
import com.music.spotui.data.preferences.AlternativeStream
import org.junit.Assert.*
import org.junit.Test
class DownloadRecordingPolicyTest {
    @Test fun manualVideoWinsOverTheEarlierAutomaticRecording() {
        val alternative=AlternativeStream("youtube","BufrmkPmi2g")
        val id=DownloadRecordingPolicy.videoId(alternative,"23-YmTPGSMo")
        assertEquals("BufrmkPmi2g",id)
        assertEquals("https://www.youtube.com/watch?v=BufrmkPmi2g",DownloadRecordingPolicy.exactQuery("title search",id))
    }
    @Test fun localFileCannotFallThroughToTheAutomaticVideo() {
        assertNull(DownloadRecordingPolicy.videoId(AlternativeStream("local","file:///chosen.flac"),"23-YmTPGSMo"))
        assertEquals("BufrmkPmi2g",DownloadRecordingPolicy.videoId(null,"BufrmkPmi2g"))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidManualChoiceNeverSilentlyUsesAnotherRecording() {
        DownloadRecordingPolicy.videoId(AlternativeStream("youtube","invalid"),"BufrmkPmi2g")
    }
}
