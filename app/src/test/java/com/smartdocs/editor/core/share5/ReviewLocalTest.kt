package com.smartdocs.editor.core.share5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLocalTest {
    @Test fun inboxKeepsNewestFirst() {
        var l = emptyList<ReviewLocal.Note>()
        repeat(5) { i -> l = ReviewLocal.push(l, ReviewLocal.Note("r$i", "T$i", i, i.toLong(), false), max = 3) }
        assertEquals(listOf("r4", "r3", "r2"), l.map { it.reviewId })
    }

    @Test fun namesAndFolders() {
        val n = ReviewLocal.archiveName("Contract: v2.pdf", 0L)
        assertTrue(n, n.startsWith("Contract_ v2 (archive 19") && !n.contains(".pdf"))
        assertEquals(listOf("a", "B", "c"), ReviewLocal.folderNames(mapOf("1" to "c", "2" to "a", "3" to "B", "4" to "a")))
        assertTrue(ReviewLocal.reportText("Tracker", listOf("x", "y"), 0L).endsWith("x\n\ny"))
        assertTrue(0 in ReviewLocal.INTERVALS && ReviewLocal.DEFAULT_INTERVAL in ReviewLocal.INTERVALS)
    }
}
