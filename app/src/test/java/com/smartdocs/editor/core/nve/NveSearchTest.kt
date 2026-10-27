package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.search.FileResult
import com.smartdocs.editor.core.search.IndexHit
import com.smartdocs.editor.core.search.PartKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Articles (threads / beads), reading order and advanced-search helpers. */
class NveSearchTest {

    private fun doc(pages: Int = 3) = PDDocument().apply { repeat(pages) { addPage(PDPage(PDRectangle.A4)) } }

    private fun roundTrip(d: PDDocument): PDDocument {
        val bos = ByteArrayOutputStream(); d.save(bos); d.close()
        return PDDocument.load(bos.toByteArray())
    }

    private fun bead(p: Int, y: Float) = NveArticles.Bead(p, floatArrayOf(50f, y - 100f, 300f, y))

    @Test fun articleRoundTrip() {
        var d = doc()
        val i = NveArticles.add(d, NveArticles.Info("Story", "Sub", "Ann", "k1"), listOf(bead(0, 800f), bead(1, 700f), bead(2, 600f)))
        assertEquals(0, i)
        d = roundTrip(d)
        val list = NveArticles.list(d)
        assertEquals(1, list.size)
        assertEquals("Story", list[0].info.title); assertEquals("Ann", list[0].info.author); assertEquals("k1", list[0].info.keywords)
        assertEquals(listOf(0, 1, 2), list[0].beads.map { it.page })
        assertEquals(800f, list[0].beads[0].rect[3], 0.01f)
        // every page lists its bead in /B; beads form a circle
        d.pages.forEach { p -> assertEquals(1, (p.cosObject.getDictionaryObject(COSName.getPDFName("B")) as COSArray).size()) }
        val t = (d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("Threads")) as COSArray).getObject(0) as COSDictionary
        val first = t.getDictionaryObject(COSName.F) as COSDictionary
        val last = first.getDictionaryObject(COSName.getPDFName("V")) as COSDictionary
        assertTrue(last.getDictionaryObject(COSName.N) === first)
        d.close()
    }

    @Test fun articleEditing() {
        var d = doc()
        NveArticles.add(d, NveArticles.Info("A"), listOf(bead(1, 500f), bead(0, 800f)))
        NveArticles.add(d, NveArticles.Info("B"), listOf(bead(2, 700f)))
        NveArticles.renumber(d, 0)
        assertEquals(listOf(0, 1), NveArticles.list(d)[0].beads.map { it.page })
        NveArticles.insertBead(d, 0, 1, bead(0, 300f))
        assertEquals(3, NveArticles.list(d)[0].beads.size)
        NveArticles.moveBead(d, 0, 2, 0)
        assertEquals(1, NveArticles.list(d)[0].beads[0].page)
        NveArticles.deleteBead(d, 0, 0)
        assertEquals(2, NveArticles.list(d)[0].beads.size)
        NveArticles.combine(d, 0, 1)
        d = roundTrip(d)
        val l = NveArticles.list(d)
        assertEquals(1, l.size); assertEquals(3, l[0].beads.size); assertEquals(2, l[0].beads.last().page)
        NveArticles.setInfo(d, 0, NveArticles.Info("New title"))
        assertEquals("New title", NveArticles.list(d)[0].info.title)
        NveArticles.delete(d, 0)
        d = roundTrip(d)
        assertTrue(NveArticles.list(d).isEmpty())
        assertNull(d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("Threads")))
        d.pages.forEach { assertNull(it.cosObject.getDictionaryObject(COSName.getPDFName("B"))) }
        d.close()
    }

    @Test fun readingOrderMerge() {
        val base = "line one\nline two\nline three\nline four"
        val ex = listOf(NveReadOrder.Extra(NveReadOrder.Kind.COMMENT, 0.6f, 1, "C"), NveReadOrder.Extra(NveReadOrder.Kind.ALT, 0.0f, 0, "A"))
        assertEquals("A\nline one\nline two\nC\nline three\nline four", NveReadOrder.merge(base, ex, NveReadOrder.Order.CONTENT))
        assertEquals("$base\nA\nC", NveReadOrder.merge(base, ex, NveReadOrder.Order.STRUCTURE))
        assertEquals("$base\nA\nC", NveReadOrder.merge(base, ex, NveReadOrder.Order.TEXT_THEN_EXTRAS))
        assertEquals(base, NveReadOrder.merge(base, emptyList(), NveReadOrder.Order.CONTENT))
    }

    @Test fun readingOrderExtras() {
        val d = doc(1)
        val page = d.getPage(0)
        val annot = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, COSName.getPDFName("Text"))
            setItem(COSName.RECT, COSArray().apply { listOf(10f, 400f, 30f, 421f).forEach { add(COSFloat(it)) } })
            setItem(COSName.CONTENTS, COSString("Check this")); setItem(COSName.T, COSString("Sam"))
        }
        val widget = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, COSName.getPDFName("Widget"))
            setItem(COSName.FT, COSName.getPDFName("Tx")); setItem(COSName.T, COSString("Name")); setItem(COSName.V, COSString("Ali"))
            setItem(COSName.RECT, COSArray().apply { listOf(10f, 700f, 200f, 720f).forEach { add(COSFloat(it)) } })
            setItem(COSName.P, page.cosObject)
        }
        page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(annot); add(widget) })
        d.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, COSDictionary().apply { setItem(COSName.FIELDS, COSArray().apply { add(widget) }) })
        val fig = COSDictionary().apply {
            setItem(COSName.S, COSName.getPDFName("Figure")); setItem(COSName.getPDFName("Pg"), page.cosObject); setItem(COSName.getPDFName("Alt"), COSString("A chart"))
        }
        d.documentCatalog.cosObject.setItem(COSName.getPDFName("StructTreeRoot"), COSDictionary().apply { setItem(COSName.K, COSArray().apply { add(fig) }) })
        val ex = NveReadOrder.extras(d, 0, NveReadOrder.Prefs(comments = true, fields = true, alt = true))
        assertEquals(3, ex.size)
        assertEquals("Comment by Sam: Check this", ex.first { it.kind == NveReadOrder.Kind.COMMENT }.text)
        assertEquals("Field Name: Ali", ex.first { it.kind == NveReadOrder.Kind.FIELD }.text)
        assertEquals("Figure: A chart", ex.first { it.kind == NveReadOrder.Kind.ALT }.text)
        val field = ex.first { it.kind == NveReadOrder.Kind.FIELD }; val comment = ex.first { it.kind == NveReadOrder.Kind.COMMENT }
        assertTrue(field.yFrac < comment.yFrac)       // the field is higher on the page
        assertTrue(NveReadOrder.extras(d, 0, NveReadOrder.Prefs(comments = false, fields = false, alt = false)).isEmpty())
        d.close()
    }

    private fun fr(name: String, path: String, total: Int, vararg hits: Pair<Int, PartKind>) =
        FileResult(path, name, 3, hits.map { (p, k) -> IndexHit(p, k, "snip, \"q\"", listOf(0..1)) }, total)

    @Test fun sortFilterCsv() {
        val a = fr("b.pdf", "/x/z/b.pdf", 2, 2 to PartKind.TEXT, 0 to PartKind.COMMENTS)
        val b = fr("A.pdf", "/x/a/A.pdf", 5, 1 to PartKind.TEXT)
        val c = fr("c.pdf", "/x/a/c.pdf", 1, -1 to PartKind.ATTACHMENT)
        val all = listOf(a, b, c)
        assertEquals(listOf(b, a, c), NveSearchCore.sort(all, NveSearchCore.Sort.RELEVANCE))
        assertEquals(listOf("A.pdf", "b.pdf", "c.pdf"), NveSearchCore.sort(all, NveSearchCore.Sort.NAME).map { it.name })
        assertEquals(listOf("A.pdf", "c.pdf", "b.pdf"), NveSearchCore.sort(all, NveSearchCore.Sort.LOCATION).map { it.name })
        assertEquals(listOf(0, 2), NveSearchCore.sort(listOf(a), NveSearchCore.Sort.LOCATION)[0].hits.map { it.page })
        val meta = mapOf("/x/z/b.pdf" to NveSearchCore.Meta(modified = 300L), "/x/a/A.pdf" to NveSearchCore.Meta(modified = 100L), "/x/a/c.pdf" to NveSearchCore.Meta(fileTime = 200L))
        assertEquals(listOf("b.pdf", "c.pdf", "A.pdf"), NveSearchCore.sort(all, NveSearchCore.Sort.DATE, meta).map { it.name })
        val f = NveSearchCore.filter(all, "", setOf(PartKind.COMMENTS))
        assertEquals(1, f.size); assertEquals(1, f[0].hits.size)
        assertEquals(listOf("A.pdf"), NveSearchCore.filter(all, "a.pdf", PartKind.entries.toSet()).map { it.name })
        val csv = NveSearchCore.csv("q", listOf(a), listOf("Query", "File", "Path", "Page", "Where", "Snippet")) { it.name }
        val lines = csv.removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertEquals("q,b.pdf,/x/z/b.pdf,3,TEXT,\"snip, \"\"q\"\"\"", lines[1])
        assertTrue(lines[2].contains(",1,COMMENTS,"))
    }

    @Test fun criteriaAndDates() {
        val created = NveSearchCore.pdfDate("D:20240131120000Z")!!
        assertEquals(1706702400000L, created)
        assertEquals(1706702400000L - 3 * 3600_000L, NveSearchCore.pdfDate("D:20240131120000+03'00'"))
        assertNull(NveSearchCore.pdfDate("garbage"))
        val m = NveSearchCore.Meta(title = "Annual Report", author = "Maya", keywords = "finance", created = created)
        assertTrue(NveSearchCore.matches(m, NveSearchCore.Criteria(author = "maya")))
        assertFalse(NveSearchCore.matches(m, NveSearchCore.Criteria(author = "omar")))
        assertTrue(NveSearchCore.matches(m, NveSearchCore.Criteria(title = "report", createdFrom = created - 1, createdTo = created + 1)))
        assertFalse(NveSearchCore.matches(m, NveSearchCore.Criteria(createdFrom = created + 1)))
        assertTrue(NveSearchCore.matches(null, NveSearchCore.Criteria()))
        assertFalse(NveSearchCore.matches(null, NveSearchCore.Criteria(title = "x")))
    }

    @Test fun indexOptions() {
        val o = NveSearchCore.IndexOptions(stopWords = setOf("the", "of"), includeNumbers = false)
        assertEquals("history rome", NveSearchCore.filterBody("the history of rome 1999", o))
        assertEquals("history Rome", NveSearchCore.stripQuery("history of Rome 2020", o))
        assertEquals("abc 12", NveSearchCore.filterBody("abc 12", NveSearchCore.IndexOptions()))
        val ex = NveSearchCore.IndexOptions(excluded = listOf("/storage/emulated/0/Private"))
        assertTrue(NveSearchCore.excluded("/storage/emulated/0/Private/a.pdf", ex))
        assertFalse(NveSearchCore.excluded("/storage/emulated/0/PrivateX/a.pdf", ex))
    }

    @Test fun attachmentContents() {
        val d = doc(1)
        val stream = d.document.createCOSStream()
        stream.createOutputStream().use { it.write("hello attachment world".toByteArray()) }
        val spec = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FILESPEC); setItem(COSName.UF, COSString("notes.txt"))
            setItem(COSName.EF, COSDictionary().apply { setItem(COSName.F, stream) })
        }
        val names = COSDictionary().apply { setItem(COSName.EMBEDDED_FILES, COSDictionary().apply { setItem(COSName.NAMES, COSArray().apply { add(COSString("notes.txt")); add(spec) }) }) }
        d.documentCatalog.cosObject.setItem(COSName.NAMES, names)
        val r = roundTrip(d)
        val t = NveSearchCore.attachmentTexts(r)
        assertEquals(1, t.size); assertEquals("notes.txt", t[0].first); assertEquals("hello attachment world", t[0].second)
        assertFalse(NveSearchCore.isPortfolio(r))
        r.close()
    }
}
