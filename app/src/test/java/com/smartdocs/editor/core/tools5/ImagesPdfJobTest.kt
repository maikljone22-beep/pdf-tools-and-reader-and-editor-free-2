package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagesPdfJobTest {
    @Test fun thousandsOfPicturesAreSplitIntoResumableChunks() {
        val c = ImagesPdfJob.chunks(1003, 40)
        assertEquals(26, c.size)
        assertEquals(0 until 40, c.first()); assertEquals(1000 until 1003, c.last())
        assertEquals(1003, c.sumOf { it.count() })
        assertEquals(emptyList<IntRange>(), ImagesPdfJob.chunks(0, 40))
    }

    @Test fun manifestRoundTrips() {
        val m = ImagesPdfJob.Manifest(List(1200) { "/data/p$it.jpg" }, true, 75, "كتاب", 7)
        assertEquals(m, ImagesPdfJob.fromJson(ImagesPdfJob.toJson(m)))
    }
}
