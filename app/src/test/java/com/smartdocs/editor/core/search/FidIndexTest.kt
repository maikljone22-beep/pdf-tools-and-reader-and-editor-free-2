package com.smartdocs.editor.core.search

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/files-index: named index catalog encoding / merge, and layer text from content streams. */
class FidIndexTest {

    @Test fun encodeDecodeRoundTrip() {
        val defs = listOf(
            IndexDef(FidIndexes.DEFAULT, "My app", listOf("content://x/tree/primary%3ADocs"), true),
            IndexDef("i1", "Work\tfiles, 2024", listOf("/storage/a,b", "content://y/tree/z"), false),
        )
        assertEquals(defs, FidIndexes.decode(FidIndexes.encode(defs)))
    }

    @Test fun defaultAlwaysFirst() {
        val d = FidIndexes.decode(FidIndexes.encode(listOf(IndexDef("i2", "B"))))
        assertEquals(FidIndexes.DEFAULT, d.first().id)
        assertEquals(listOf(FidIndexes.DEFAULT, "i2"), d.map { it.id })
        assertEquals(listOf(FidIndexes.DEFAULT), FidIndexes.decode(null).map { it.id })
        // ids with odd characters are dropped (they name database files)
        assertEquals(1, FidIndexes.decode("..%2F..%2Fx\tT\t1\t").size)
    }

    @Test fun selectionAndIds() {
        val defs = listOf(IndexDef(FidIndexes.DEFAULT), IndexDef("i1"))
        assertEquals(setOf("i1"), FidIndexes.cleanSelection(setOf("i1", "gone"), defs))
        assertEquals(setOf(FidIndexes.DEFAULT), FidIndexes.cleanSelection(setOf("gone"), defs))
        val id = FidIndexes.newId(setOf("i" + java.lang.Long.toString(5, 36)), 5)
        assertTrue(id != "i5")
        assertEquals("search_index.db", FidIndexes.dbName(FidIndexes.DEFAULT))
        assertEquals("search_index_i1.db", FidIndexes.dbName("i1"))
    }

    @Test fun mergeKeepsBestPerFile() {
        val a = listOf(FileResult("/a.pdf", "a", 1, emptyList(), 3), FileResult("/b.pdf", "b", 1, emptyList(), 1))
        val b = listOf(FileResult("/a.pdf", "a", 1, emptyList(), 5), FileResult("/c.pdf", "c", 1, emptyList(), 2))
        val m = FidIndexes.merge(listOf(a, b))
        assertEquals(listOf("/a.pdf", "/c.pdf", "/b.pdf"), m.map { it.path })
        assertEquals(5, m.first().total)
    }

    @Test fun layerTextAndNames() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            val res = PDResources(); page.resources = res
            val ocg = PDOptionalContentGroup("Notes layer")
            val props = PDOptionalContentProperties(); props.addGroup(ocg); doc.documentCatalog.ocProperties = props
            val key: COSName = res.add(ocg)
            val content = "BT (Outside) Tj ET /OC /${key.name} BDC BT (Hidden note) Tj ET EMC"
            page.setContents(PDStream(doc, content.byteInputStream()))
            val parts = FidPdfText.layers(doc)
            assertTrue(parts.any { it.page == -1 && it.text.contains("Notes layer") })
            val onPage = parts.single { it.page == 0 }
            assertEquals(PartKind.LAYER, onPage.kind)
            assertEquals("Notes layer: Hidden note", onPage.text)
        }
    }
}
