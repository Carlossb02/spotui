package com.music.spotui.ui.components
import org.junit.Assert.*
import org.junit.Test
class CollectionActionMetricsTest {
    @Test fun phonesNeverShowLabelsAndTabletLabelsFitOnlyWhenThereIsRoom() {
        for (width in 288..1200) {
            val phone = collectionActionMetrics(width.toFloat(), false)
            assertFalse(phone.labels)
            val tablet = collectionActionMetrics(width.toFloat(), true)
            assertTrue(tablet.requiredWidth <= width)
            assertEquals(width >= 560, tablet.labels)
        }
    }
    @Test fun allButtonsAndTheirGapFitNarrowPhonesAndTablets() {
        for (available in 288..1200) {
            val metrics=collectionActionMetrics(available.toFloat())
            assertTrue("Controls overlap at width " + available, metrics.requiredWidth <= available)
            assertTrue(metrics.control >= 40f)
            assertTrue(metrics.gap >= 8f)
            assertTrue(metrics.itemGap >= 4f)
        }
    }
    @Test fun largerViewportsGiveTheControlsMoreRoom() {
        val phone=collectionActionMetrics(288f); val tablet=collectionActionMetrics(688f)
        assertTrue(tablet.control > phone.control)
        assertTrue(tablet.pill > phone.pill)
        assertTrue(tablet.play > phone.play)
    }
}
