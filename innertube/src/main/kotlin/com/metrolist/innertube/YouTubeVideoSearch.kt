package com.metrolist.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Ordinary YouTube video search, including programmes that are absent from YouTube Music. */
object YouTubeVideoSearch {
    data class Video(val id: String, val title: String, val publisher: String, val seconds: Long, val date: String)

    suspend fun find(query: String): List<Video> = runInterruptible(Dispatchers.IO) {
        NewPipeExtractor.init()
        val service = NewPipe.getService(0)
        val search = service.getSearchExtractor(query)
        search.fetchPage()
        search.initialPage.items.filterIsInstance<StreamInfoItem>().asSequence()
            .filter { (it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.POST_LIVE_STREAM) && it.duration > 0 }
            .take(15).mapNotNull { item ->
                val id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull() ?: return@mapNotNull null
                Video(id, item.name, item.uploaderName.orEmpty(), item.duration,
                    item.uploadDate?.offsetDateTime()?.toLocalDate()?.toString().orEmpty())
            }.toList()
    }
}
