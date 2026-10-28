package com.smartdocs.editor.core.view5

/**
 * tab-view-core V-13: build order of the reflow reader. The page being read comes first; every chunk is a run of
 * consecutive pages (never wrapping from the last page to the first), so a chunk's page range — what
 * [PageGraphics.images] scans — is at most [size] pages. Pure (unit-tested).
 */
object ReflowChunks {
    fun order(startPage: Int, pageCount: Int, size: Int = 12): List<IntRange> {
        if (pageCount <= 0) return emptyList()
        val n = size.coerceAtLeast(1)
        val start = startPage.coerceIn(0, pageCount - 1)
        fun runs(from: Int, until: Int): List<IntRange> = (from until until step n).map { a -> a until minOf(a + n, until) }
        return runs(start, pageCount) + runs(0, start)
    }
}
