package com.metrolist.innertube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Loads the video pages of a public YouTube channel or playlist. */
object YouTubeVideoSearch {
    data class Video(val id: String, val title: String, val publisher: String, val seconds: Long, val date: String)

    fun sourcePages(channelId: String?, playlistId: String?, streams: Boolean, maxPages: Int) = flow {
        val service = runInterruptible(Dispatchers.IO) {
            NewPipeExtractor.init()
            NewPipe.getService(0)
        }
        val extractor: ListExtractor<out InfoItem> = runInterruptible(Dispatchers.IO) {
            if (playlistId != null) {
                service.getPlaylistExtractor("https://www.youtube.com/playlist?list=$playlistId")
            } else {
                val tab = if (streams) ChannelTabs.LIVESTREAMS else ChannelTabs.VIDEOS
                service.getChannelTabExtractorFromId("channel/${requireNotNull(channelId)}", tab)
            }
        }
        var page = runInterruptible(Dispatchers.IO) {
            extractor.forceLocalization(Localization("es", "ES"))
            extractor.forceContentCountry(ContentCountry("ES"))
            extractor.fetchPage()
            extractor.initialPage
        }
        repeat(maxPages) { index ->
            emit(videos(service, page.items))
            if (!page.hasNextPage() || index + 1 >= maxPages) return@flow
            val next = page.nextPage
            page = runInterruptible(Dispatchers.IO) { extractor.getPage(next) }
        }
    }

    private fun videos(service: StreamingService, items: List<InfoItem>): List<Video> =
        items.filterIsInstance<StreamInfoItem>().asSequence()
            .filter {
                (it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.POST_LIVE_STREAM) &&
                    it.duration > 0 && !it.isShortFormContent
            }
            .mapNotNull { item ->
                val id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull() ?: return@mapNotNull null
                Video(id, item.name, item.uploaderName.orEmpty(), item.duration,
                    item.uploadDate?.offsetDateTime()?.toLocalDate()?.toString()
                        ?: item.textualUploadDate.orEmpty())
            }.toList()
}
