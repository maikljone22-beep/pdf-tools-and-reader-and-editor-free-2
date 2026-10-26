package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKind6Test {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() } + ByteArray(32)

    @Test fun magicBeatsExtension() {
        assertEquals(Kind6.PDF, FileKind6.detect("report.docx", "%PDF-1.7".toByteArray()))
        assertEquals(Kind6.JPG, FileKind6.detect("x.png", b(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(Kind6.PNG, FileKind6.detect("x", b(0x89, 0x50, 0x4E, 0x47)))
        assertEquals(Kind6.TIFF, FileKind6.detect("scan", b(0x49, 0x49, 0x2A, 0x00)))
        assertEquals(Kind6.RTF, FileKind6.detect("a.doc", "{\\rtf1\\ansi".toByteArray()))
        assertEquals(Kind6.WEBP, FileKind6.detect("a", "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray()))
    }

    @Test fun heicBrand() {
        val head = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(16)
        assertEquals(Kind6.HEIC, FileKind6.detect("IMG_1.bin", head))
    }

    @Test fun zipPackages() {
        val zip = b(0x50, 0x4B, 0x03, 0x04)
        assertEquals(Kind6.DOCX, FileKind6.detect("a.zip", zip, setOf("word/document.xml", "[Content_Types].xml")))
        assertEquals(Kind6.XLSX, FileKind6.detect("a", zip, setOf("xl/workbook.xml")))
        assertEquals(Kind6.PPTX, FileKind6.detect("a", zip, setOf("ppt/presentation.xml")))
        assertEquals(Kind6.ODT, FileKind6.detect("a.zip", zip, setOf("content.xml"), "application/vnd.oasis.opendocument.text"))
        assertEquals(Kind6.EPUB, FileKind6.detect("book", zip, setOf("META-INF/container.xml")))
        assertEquals(Kind6.ZIP, FileKind6.detect("a.zip", zip, setOf("readme.txt")))
    }

    @Test fun oleAndText() {
        val ole = b(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        assertEquals(Kind6.XLS, FileKind6.detect("a.xls", ole))
        assertEquals(Kind6.UNKNOWN, FileKind6.detect("a.bin", ole))
        assertEquals(Kind6.DOC, FileKind6.oleKind(listOf("Root Entry", "WordDocument", "1Table")))
        assertEquals(Kind6.XLS, FileKind6.oleKind(listOf("Workbook")))
        assertEquals(Kind6.HTML, FileKind6.detect("page", "<!DOCTYPE html><html>".toByteArray()))
        assertEquals(Kind6.EML, FileKind6.detect("msg", "From: a@b.c\nSubject: hi\n".toByteArray()))
        assertEquals(Kind6.MARKDOWN, FileKind6.detect("notes.md", "# Title".toByteArray()))
    }

    @Test fun mimesAlwaysAllowOctetStream() {
        val m = FileKind6.mimes(setOf(Kind6.MARKDOWN, Kind6.EPUB))
        assertTrue("application/octet-stream" in m)
        assertTrue("application/epub+zip" in m)
    }
}

class ImageNamingTest {

    @Test fun sanitizeKeepsArabicDropsIllegal() {
        assertEquals("الشكل 3 الطاقة الكيميائية", ImageNaming.sanitize("الشكل 3: الطاقة الكيميائية"))
        assertEquals("a b c", ImageNaming.sanitize("a/b\\c"))
        assertEquals("name", ImageNaming.sanitize("  ..name.. "))
        val rlm = 0x200F.toChar()
        assertEquals("abc", ImageNaming.sanitize("a" + rlm + "bc"))
        assertEquals("_CON", ImageNaming.sanitize("CON"))
    }

    @Test fun sanitizeCutsAtWord() {
        val s = ImageNaming.sanitize("one two three four five six seven", max = 15)
        assertTrue(s.length <= 15)
        assertFalse(s.endsWith(" "))
        assertEquals("one two three", s)
    }

    @Test fun dedupeCaseInsensitive() {
        assertEquals(listOf("a.png", "A (2).png", "a (3).png", "b.jpg"), ImageNaming.dedupe(listOf("a.png", "A.png", "a.png", "b.jpg")))
    }

    @Test fun baseNames() {
        assertEquals("doc_p3_2", ImageNaming.baseName(NameMode.PAGE_INDEX, "doc", 3, 2, "Im1", "cap"))
        assertEquals("Im1", ImageNaming.baseName(NameMode.RESOURCE, "doc", 3, 2, "Im1", "cap"))
        assertEquals("doc_p3_2", ImageNaming.baseName(NameMode.CAPTION, "doc", 3, 2, "Im1", "  "))
        assertEquals("cap", ImageNaming.baseName(NameMode.CAPTION, "doc", 3, 2, null, "cap"))
    }

    @Test fun captionPrefersFigureLines() {
        val img = floatArrayOf(100f, 100f, 300f, 250f)
        val lines = listOf(
            TextLine("نص عادي تحت الصورة", 100f, 255f, 300f, 265f),
            TextLine("الشكل 3: الطاقة الكيميائية", 120f, 280f, 280f, 292f),
            TextLine("Header", 0f, 10f, 500f, 20f),
        )
        assertEquals("الشكل 3: الطاقة الكيميائية", ImageNaming.caption(lines, img[0], img[1], img[2], img[3]))
        // without a caption-like line the nearest line below wins
        assertEquals("نص عادي تحت الصورة", ImageNaming.caption(lines.take(1) + lines.last(), img[0], img[1], img[2], img[3]))
        // lines that do not overlap horizontally are ignored
        assertNull(ImageNaming.caption(listOf(TextLine("Figure 1", 400f, 255f, 500f, 265f)), img[0], img[1], img[2], img[3]))
        assertTrue(ImageNaming.looksLikeCaption("Fig. 2 Results"))
    }
}

class Images6Test {

    @Test fun formatDecisions() {
        assertEquals(ImgFmt.JPG, Images6.outFormat("jpeg", false, null))
        assertEquals(ImgFmt.PNG, Images6.outFormat("heic", true, null))
        assertEquals(ImgFmt.JPG, Images6.outFormat("heic", false, null))
        assertEquals(ImgFmt.PNG, Images6.outFormat("tif", true, null))
        assertEquals(ImgFmt.WEBP, Images6.outFormat("png", true, ImgFmt.WEBP))
    }

    @Test fun sizes() {
        assertEquals(1024 to 768, Images6.fitWithin(4032, 3024, 1024, 1024))
        assertEquals(100 to 50, Images6.fitWithin(100, 50, 1024, 1024))
        assertEquals(50 to 25, Images6.scale(100, 50, 50))
        assertEquals(4, Images6.sampleSize(4000, 3000, 1000))
        assertEquals(1, Images6.sampleSize(800, 600, 1000))
    }

    @Test fun placement() {
        val p = Images6.place(2000, 1000, PageSize6.A4, Orient6.AUTO, Fit6.CONTAIN, 0f)
        assertTrue(p.pageW > p.pageH)            // landscape picture → landscape page
        assertEquals(p.pageW, p.w, 0.01f)
        assertEquals((p.pageH - p.h) / 2f, p.y, 0.01f)
        val img = Images6.place(960, 480, PageSize6.IMAGE, Orient6.AUTO, Fit6.CONTAIN, 10f, 96f)
        assertEquals(720f + 20f, img.pageW, 0.01f)
        val cover = Images6.place(1000, 1000, PageSize6.A4, Orient6.PORTRAIT, Fit6.COVER, 0f)
        assertTrue(cover.w >= cover.pageW - 0.01f && cover.h >= cover.pageH - 0.01f)
    }

    @Test fun nUp() {
        assertEquals(1 to 2, Images6.grid(2, false))
        assertEquals(2 to 2, Images6.grid(4, true))
        val cells = Images6.nUpCells(4, 600f, 800f, 20f, 10f, rtl = true)
        assertEquals(4, cells.size)
        assertTrue(cells[0][0] > cells[1][0])    // RTL: first cell on the right
        assertTrue(cells[0][1] > cells[2][1])    // first row on top
        val (w, hs) = Images6.stitch(listOf(600f to 800f, 600f to 800f), 1200, 1_000_000L)
        assertTrue(w.toLong() * hs.sum() <= 1_000_000L)
    }

    @Test fun sniffAndDims() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 1, 0, 0, 0, 0, 0x80.toByte(), 8, 6, 0, 0, 0)
        assertEquals("png", Images6.sniffExt(png))
        assertEquals(256 to 128, ImageDims.of(png))
        assertEquals("image/jp2", Images6.mimeOf("jp2"))
    }
}

class Cv6CatalogTest {
    @Test fun idsAndGroups() {
        val ids = Cv6Catalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Cv6Group.entries.forEach { g -> assertTrue(g.name, Cv6Catalog.inGroup(g).isNotEmpty()) }
        assertNotNull(Cv6Catalog.byId("pdf_docx"))
        assertTrue(Cv6Catalog.forKind(Kind6.PDF).any { it.id == "pdf_epub" })
        assertTrue(Cv6Catalog.forKind(Kind6.TIFF).any { it.id == "images_pdf" })
    }

    @Test fun searchNormalisesArabic() {
        val labels = mapOf("pdf_docx" to "PDF إلى Word", "word_pdf" to "Word إلى PDF")
        val r = Cv6Catalog.search("الى word") { labels[it.id] ?: "" }
        assertTrue(r.any { it.id == "pdf_docx" })
        assertTrue(Cv6Catalog.search("epub") { "" }.any { it.id == "pdf_epub" })
    }

    @Test fun recents() {
        assertEquals(listOf("b", "a", "c"), Cv6Catalog.pushRecent(listOf("a", "b", "c"), "b"))
        assertEquals(3, Cv6Catalog.pushRecent(listOf("a", "b", "c"), "d", max = 3).size)
    }
}
