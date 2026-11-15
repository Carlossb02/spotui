package com.music.spotui.data.api

import org.junit.Assert.*
import org.junit.Test

class ExplicitContentStoreTest {
    @Test fun oldLocalRowsCanRecoverTheirImmutableSpotifyIdFromThePlaybackQuery() {
        val song=com.music.spotui.data.entity.SongsModel(1,"Don't Leave","","Snakehips","","spotify:track:0Zx8khUcEfCFK2AEoIhC92|Don't Leave Snakehips")
        assertEquals("0Zx8khUcEfCFK2AEoIhC92",ratingSpotifyId(song))
        assertEquals("",ratingSpotifyId(song.copy(url="Don't Leave Snakehips")))
    }
    private val id="0Zx8khUcEfCFK2AEoIhC92"
    private fun html(entity:String)="""<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script></html>"""
    @Test fun exactPublicSpotifyRecordingSuppliesTheMissingExplicitFlag() {
        assertEquals(true,publicSpotifyExplicit(html("""{"id":"$id","name":"Don't Leave","isExplicit":true}"""),id))
        assertEquals(false,publicSpotifyExplicit(html("""{"id":"$id","isExplicit":false}"""),id))
    }
    @Test fun missingDataAndDifferentRecordingsNeverBecomeCleanEvidence() {
        assertNull(publicSpotifyExplicit(html("""{"id":"$id"}"""),id))
        assertNull(publicSpotifyExplicit(html("""{"id":"other","isExplicit":true}"""),id))
        assertNull(publicSpotifyExplicit("<html>Not available</html>",id))
    }
}
