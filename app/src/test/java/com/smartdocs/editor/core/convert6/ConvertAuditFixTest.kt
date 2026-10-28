package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.files.FileNames
import com.smartdocs.editor.core.office5.CsvIO
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.tools5.EpubReader
import com.smartdocs.editor.core.tools5.HtmlBits
import com.smartdocs.editor.core.tools5.TextHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** JVM tests of the convert-audit fixes (gaps/convert-audit/FIXED.md). */
class ConvertAuditFixTest {

    // ---- page ranges (B17 / A-M12)
    @Test fun arabicDigitRanges() {
        assertEquals(listOf(0, 1, 2, 3, 4), PdfOps.parseRange("١-٥", 10))
        assertEquals(listOf(0, 2, 6), PdfOps.parseRange("١، ٣؛ ۷", 10))
        assertEquals(listOf(1, 2), PdfOps.parseRange("2–3", 10))
        assertTrue(PdfOps.rangeSyntaxOk("١-٣، ٥"))
        assertTrue(PdfOps.rangeSyntaxOk(""))
        assertFalse(PdfOps.rangeSyntaxOk("1-3,a"))
        assertTrue(PdfOps.parseRange("20-30", 5).isEmpty())
    }

    // ---- numbers in spreadsheets (B13 / C17)
    @Test fun strictCellNumbers() {
        listOf("NaN", "Infinity", "12d", "0x1p3", "1e400", "1,5", "007", "0501234567", "1234567890123456").forEach { assertNull(it, Csv6.cellNumber(it)) }
        assertEquals("1250", Csv6.cellNumber("1,250"))
        assertEquals("12", Csv6.cellNumber("١٢"))
        assertEquals("0.5", Csv6.cellNumber("0.5"))
        assertEquals("-3", Csv6.cellNumber("-3"))
        val x = Xml2003.write(listOf("s" to listOf(listOf("NaN", "007", "1,250", "12d"))), rtl = false)
        assertTrue(x.contains("<Data ss:Type=\"String\">NaN</Data>"))
        assertTrue(x.contains("<Data ss:Type=\"String\">007</Data>"))
        assertTrue(x.contains("<Data ss:Type=\"Number\">1250</Data>"))
        assertTrue(x.contains("<Data ss:Type=\"String\">12d</Data>"))
        val ods = ByteArrayOutputStream().also { OdfWriter.ods(listOf("s" to listOf(listOf("0501234567", "42"))), it, false) }.toByteArray()
        val content = unzip(ods).getValue("content.xml")
        assertTrue(content.contains("office:value-type=\"string\"><text:p>0501234567"))
        assertTrue(content.contains("office:value=\"42\""))
    }

    // ---- CSV formula injection (B22 / C18)
    @Test fun csvInjectionGuard() {
        assertEquals("'=SUM(A1)", Csv6.defuse("=SUM(A1)"))
        assertEquals("'@cmd", Csv6.defuse("@cmd"))
        assertEquals("-5", Csv6.defuse("-5"))
        assertEquals("+12", Csv6.defuse("+12"))
        assertTrue(Csv6.write(listOf(listOf("=HYPERLINK(\"x\")"))).startsWith("\"'=HYPERLINK"))
        assertTrue(CsvIO.keepAsText("=1+1")); assertTrue(CsvIO.keepAsText("007")); assertTrue(CsvIO.keepAsText("3-4"))
        assertFalse(CsvIO.keepAsText("12.5")); assertFalse(CsvIO.keepAsText("2024-01-15"))
    }

    // ---- file names (B12 / B32 / A-L3 / C44)
    @Test fun fileNames() {
        val long = "ملخص".repeat(60)
        val c = FileNames.clean(long)
        assertTrue(c.toByteArray().size <= FileNames.MAX_BASE_BYTES)
        assertTrue(long.startsWith(c))
        assertEquals("a_b_c", FileNames.clean("a/b:c"))
        val dir = kotlin.io.path.createTempDirectory("cvf").toFile()
        try {
            File(dir, "x.pdf").writeText("1")
            assertEquals("x (2).pdf", FileNames.unique(dir, "x", "pdf").name)
        } finally { dir.deleteRecursively() }
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            assertEquals("page_001.html", HtmlSet.pageName(0))
        } finally { Locale.setDefault(old) }
    }

    // ---- batches (A-H5 / B02 / B03 / C1)
    @Test fun batchGoesOnAfterAFailure() = runBlocking {
        val a = File("a.pdf"); val b = File("b.pdf"); val c = File("c.pdf")
        val cleaned = ArrayList<File>()
        val o = Cv6Batch.each(listOf(a, b, c), onFailure = { f, _ -> cleaned += f }) { _, f ->
            when (f) { b -> throw Cv6NeedPassword(f, false); c -> throw IllegalStateException("x"); else -> f.name }
        }
        assertEquals(listOf("a.pdf"), o.done.map { it.second })
        assertEquals(2, o.failures.size)
        assertEquals(listOf(b), o.locked.map { it.file })
        assertEquals(listOf(b, c), cleaned)
        assertEquals("a.pdf: one\nb.pdf: two", Cv6Batch.infoLines(listOf("a.pdf" to "one", "b.pdf" to "two", "c.pdf" to null)))
        assertEquals("only", Cv6Batch.infoLines(listOf("a.pdf" to "only")))
        assertTrue(Cv6Batch.failureLines(o.failures) { "E" }!!.contains("✗ c.pdf: E"))
        try { Cv6Batch.each(listOf(a)) { _, _ -> throw CancellationException("stop") }; fail() } catch (e: CancellationException) { }
    }

    // ---- error classification (A-H8 / B16 / C12)
    @Test fun errorsAreClassified() {
        assertEquals(Cv6Err.OUT_OF_MEMORY, Cv6Errors.classify(OutOfMemoryError()).kind)
        assertEquals(Cv6Err.DAMAGED, Cv6Errors.classify(IllegalArgumentException("not a compound file")).kind)
        assertEquals(Cv6Err.DAMAGED, Cv6Errors.classify(Exception("not a zip package")).kind)
        assertEquals(404, Cv6Errors.classify(java.io.IOException("HTTP 404 Not Found")).code)
        assertEquals(Cv6Err.NETWORK, Cv6Errors.classify(java.net.UnknownHostException("x")).kind)
        assertEquals(Cv6Err.DISK_FULL, Cv6Errors.classify(java.io.IOException("write failed: ENOSPC (No space left on device)")).kind)
        assertEquals(Cv6Err.WRONG_PASSWORD, Cv6Errors.classify(Cv6NeedPassword(File("x.pdf"), true)).kind)
        assertEquals(Cv6Err.USER, Cv6Errors.classify(RuntimeException(UserMessage("نص"))).kind)
        assertEquals(Cv6Err.TIMEOUT, Cv6Errors.classify(java.io.IOException("timeout")).kind)
        assertEquals(Cv6Err.EMPTY, Cv6Errors.classify(IllegalStateException("empty PDF")).kind)
        assertEquals(Cv6Err.UNKNOWN, Cv6Errors.classify(IllegalStateException("whatever")).kind)
    }

    // ---- entities (C19)
    @Test fun invalidEntitiesDoNotThrow() {
        assertEquals("a�b�c�", HtmlFlow.decode("a&#2000000;b&#x110000;c&#0;"))
        assertEquals("ا", HtmlFlow.decode("&#1575;"))
        assertTrue(HtmlFlow.plain("<p>&#99999999999;</p>").isNotEmpty())
        HtmlFlow.parse("<p>&#x1E8480; ok</p>")
    }

    // ---- Markdown (C34 / C35 / A-M9 / A-M8)
    @Test fun markdownLinksImagesClipboard() {
        val r = MdFlow.inline("[x](javascript:alert(document.cookie)) [w](https://en.wikipedia.org/wiki/A_(B))")
        assertNull(r.first { it.text == "x" }.link)
        assertEquals("https://en.wikipedia.org/wiki/A_(B)", r.first { it.text == "w" }.link)
        assertFalse(FlowHtml.write(MdFlow.parse("[x](javascript:alert(1))")).contains("javascript"))
        val png = PngEncoder.encode(1, 1, intArrayOf(0xFFFF0000.toInt()))
        val img = MdFlow.parse("![pic](data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png) + ")").blocks.single()
        assertTrue(img is FBlock.Image)
        assertFalse(MdFlow.looksLikeMarkdown("ملاحظات\n1. البند الأول\nالسطر الثاني\n5*3*2 = 30\nemail: a_b_c@x.com"))
        assertTrue(MdFlow.looksLikeMarkdown("# عنوان\n\n- أ\n- ب"))
        assertTrue(MdFlow.looksLikeMarkdown("| a | b |\n|---|---|\n| 1 | 2 |"))
        val kept = MdFlow.parse("سطر 1\nسطر 2", hardBreaks = true).blocks.single() as FBlock.Para
        assertTrue(Text6.plain(kept.runs).contains("\n"))
        // the same parser for md → PDF and any → PDF: a table is a table
        val f = File.createTempFile("notes", ".md"); f.writeText("| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n\nTitle\n=====\n")
        try {
            val doc = ToPdf6.readWordLike(f, Kind6.MARKDOWN)
            assertTrue(doc.blocks.any { it is FBlock.Table }); assertTrue(doc.blocks.any { it is FBlock.Heading })
            assertTrue(FlowHtml.write(doc).contains("☑"))
        } finally { f.delete() }
        assertEquals(null, Text6.safeLink("vbscript:x")); assertEquals("mailto:a@b", Text6.safeLink("mailto:a@b")); assertEquals("#x", Text6.safeLink("#x"))
    }

    // ---- XML page range (B04) and XPS viewbox (B05)
    @Test fun xmlRangeAndXps() {
        val xml = PdfXml.write("t", emptyMap(), listOf(Triple(4, 595f, 842f), Triple(6, 595f, 842f)), emptyList(),
            listOf(PdfXml.Line(4, "five", 1f, 2f, 10f, ""), PdfXml.Line(6, "seven", 1f, 2f, 10f, "")))
        assertTrue(xml.contains("<page number=\"5\"")); assertTrue(xml.contains(">five</line>")); assertTrue(xml.contains(">seven</line>"))
        assertEquals("0,0,1652,2339", XpsWriter.viewbox(XpsWriter.Page(byteArrayOf(1), 595f, 842f, 1652, 2339)))
    }

    // ---- TIFF streaming (A-H1 / A-M16 / C7 / C23) on generated files
    private fun tiffPage(w: Int, h: Int, comp: Int, data: ByteArray, photo: Int = 1, spp: Int = 1, tables: ByteArray? = null): Triple<Int, List<Triple<Int, Int, Long>>, ByteArray> {
        val tags = arrayListOf(Triple(256, 4, w.toLong()), Triple(257, 4, h.toLong()), Triple(258, 3, 8L), Triple(259, 3, comp.toLong()), Triple(262, 3, photo.toLong()),
            Triple(277, 3, spp.toLong()), Triple(278, 4, h.toLong()), Triple(279, 4, data.size.toLong()))
        return Triple(comp, tags, data)
    }

    private fun tiff(pages: List<Pair<List<Triple<Int, Int, Long>>, ByteArray>>, tables: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { for (k in 0 until 4) out.write(((v shr (8 * k)) and 0xFF).toInt()) }
        out.write('I'.code); out.write('I'.code); u16(42); u32(8)
        var off = 8L
        pages.forEachIndexed { i, (tags0, data) ->
            val tags = (tags0 + Triple(273, 4, 0L) + (if (tables != null) listOf(Triple(347, 7, 0L)) else emptyList())).sortedBy { it.first }
            val ifdSize = 2 + tags.size * 12 + 4
            val tablesOff = off + ifdSize
            val dataOff = tablesOff + (tables?.size ?: 0)
            val next = if (i == pages.lastIndex) 0L else dataOff + data.size
            u16(tags.size)
            tags.forEach { (t, type, v0) ->
                u16(t); u16(type)
                val count = if (t == 347) tables!!.size.toLong() else 1L
                u32(count)
                val v = when (t) { 273 -> dataOff; 347 -> tablesOff; else -> v0 }
                if (type == 3) { u16(v.toInt()); u16(0) } else u32(v)
            }
            u32(next)
            tables?.let { out.write(it) }
            out.write(data)
            off = next
        }
        return out.toByteArray()
    }

    @Test fun tiffPagesOneAtATimeIncludingJpeg() {
        val grey = ByteArray(40 * 20) { (it % 256).toByte() }
        // a complete JPEG stream (SOI … EOI) as the strip of a compression-7 page
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 3, 1, 0xFF.toByte(), 0xDA.toByte(), 5, 0xFF.toByte(), 0xD9.toByte())
        val p1 = tiffPage(40, 20, 1, grey)
        val p2 = tiffPage(16, 8, 7, jpeg, photo = 6, spp = 3)
        val bytes = tiff(listOf(p1.second to p1.third, p2.second to p2.third))
        val f = File.createTempFile("scan", ".tif"); f.writeBytes(bytes)
        try {
            TiffDecoder.open(f).use { t ->
                assertEquals(2, t.pageCount)
                val a = TiffDecoder.decodePage(t, 0, maxDim = 10)
                assertEquals(10, a.width); assertEquals(5, a.height); assertEquals(40, a.srcWidth)
                assertEquals(0xFF000000.toInt() or 0x000000, a.argb!![0])
                val b = TiffDecoder.decodePage(t, 1)
                assertNull(b.argb)
                assertEquals(jpeg.toList(), b.jpeg!!.single().bytes.toList())
            }
        } finally { f.delete() }
        // strips that share JPEGTables become complete JPEG streams
        val tables = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 3, 7, 0xFF.toByte(), 0xD9.toByte())
        val strip = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 9, 0xFF.toByte(), 0xD9.toByte())
        val m = TiffDecoder.mergeJpeg(tables, strip)
        assertEquals(listOf(0xFF, 0xD8, 0xFF, 0xDB, 0, 3, 7, 0xFF, 0xDA, 9, 0xFF, 0xD9), m.map { it.toInt() and 0xFF })
    }

    @Test fun tiffStreamWriterRoundTrip() {
        val f = File.createTempFile("out", ".tif")
        try {
            val a = Raster(3, 2, IntArray(6) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }, 300)
            TiffWriter.Stream(f, TiffColor.GRAY).use { s -> s.add(a); s.add(a); s.add(a) }
            val pages = TiffDecoder.decode(f.readBytes())
            assertEquals(3, pages.size)
            pages.forEach { p -> assertEquals(3, p.width); assertEquals(0xFFFFFFFF.toInt(), p.argb!![0]); assertEquals(0xFF000000.toInt(), p.argb!![1]); assertEquals(300f, p.dpiX) }
        } finally { f.delete() }
        val ccitt = TiffWriter.ccitt(byteArrayOf(1, 2, 3), 8, 4, -1, false)
        TiffDecoder.open(TiffDecoder.BytesSource(ccitt)).use { assertTrue(TiffDecoder.decodePage(it, 0).ccitt) }
    }

    @Test fun postScriptPageBoxes() {
        val sw = java.io.StringWriter()
        PsWriter.Stream(sw, false, "d", 2, null).use { s -> s.page(PsWriter.Page(byteArrayOf(1), 1, 1, 595f, 842f)); s.page(PsWriter.Page(byteArrayOf(1), 1, 1, 842f, 595f)) }
        val ps = sw.toString()
        assertTrue(ps.contains("%%PageBoundingBox: 0 0 595 842")); assertTrue(ps.contains("%%PageBoundingBox: 0 0 842 595"))
        assertTrue(ps.contains("%%Trailer\n%%BoundingBox: 0 0 842 842"))
        assertEquals(floatArrayOf(0f, 0f, 28f, 15.75f).toList(), OdfWriter.letterbox(0f, 28f, 15.75f).toList())
        val lb = OdfWriter.letterbox(595f / 842f, 28f, 15.75f)
        assertEquals(15.75f, lb[3], 0.01f); assertTrue(lb[0] > 0f)
    }

    // ---- sniffing (A-H11 / C20 / A-M11 / A-L4)
    @Test fun legacyOfficeVariants() {
        assertEquals(Kind6.XLS, FileKind6.detect("statement.xls", "<html><body><table>".toByteArray()))
        assertEquals(FileKind6.Legacy.HTML, FileKind6.legacyVariant("<html><table><tr><td>1</td></tr></table>".toByteArray()))
        assertEquals(FileKind6.Legacy.TEXT, FileKind6.legacyVariant("a\tb\n1\t2\n".toByteArray()))
        assertEquals(FileKind6.Legacy.XML_SPREADSHEET, FileKind6.legacyVariant("<?xml version=\"1.0\"?><Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\">".toByteArray()))
        assertEquals(FileKind6.Legacy.XML_WORD, FileKind6.legacyVariant("<?xml version=\"1.0\"?><w:wordDocument xmlns:w=\"http://schemas.microsoft.com/office/word/2003/wordml\">".toByteArray()))
        assertEquals(FileKind6.Legacy.CFB, FileKind6.legacyVariant(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())))
        assertTrue(FileKind6.isEncryptedPackage(listOf("EncryptionInfo", "EncryptedPackage")))
        assertEquals(Kind6.HTML, FileKind6.byExtension("page.mht"))
        val sheets = LegacyVariants.sheets(FileKind6.Legacy.HTML, "<table><tr><td>a</td><td>1</td></tr></table>", "s")
        assertEquals(listOf(listOf("a", "1")), sheets.single().second)
        val tsv = LegacyVariants.sheets(FileKind6.Legacy.TEXT, "a\tb\n1\t2\n", "s")
        assertEquals(listOf("1", "2"), tsv.single().second[1])
        val ml = LegacyVariants.spreadsheetMl("<Workbook><Worksheet ss:Name=\"S1\"><Table><Row><Cell><Data ss:Type=\"String\">x &amp; y</Data></Cell><Cell ss:Index=\"3\"><Data>2</Data></Cell></Row></Table></Worksheet></Workbook>")
        assertEquals("S1" to listOf(listOf("x & y", "", "2")), ml.single())
        assertEquals("one\ntwo", LegacyVariants.wordMlText("<w:p><w:r><w:t>one</w:t></w:r></w:p><w:p><w:t>two</w:t></w:p>"))
        assertTrue(OdfReader.hasDoctype("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]><x/>".toByteArray()))
    }

    // ---- HTML / e-mail / books (A-M6 / A-M7 / A-M10)
    @Test fun untrustedHtmlIsSanitised() {
        val s = HtmlBits.sanitize("<p onclick=\"x()\">a</p><img src=x onerror=alert(1)><script>evil()</script><a href=\"javascript:alert(1)\">l</a><meta http-equiv=\"refresh\" content=\"0;url=http://x\"><iframe src=\"http://x\"></iframe>")
        listOf("onclick", "onerror", "script", "javascript:", "refresh", "iframe").forEach { assertFalse(it, s.contains(it, ignoreCase = true)) }
        val body = TextHtml.body("English first\nسطر عربي")
        assertEquals(2, Regex("dir=\"auto\"").findAll(body).count())
    }

    @Test fun epubKeepsCssAndDecodesTitle() {
        val f = File.createTempFile("book", ".epub")
        try {
            ZipOutputStream(f.outputStream()).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OEBPS/c.opf\"/></rootfiles></container>")
                put("OEBPS/c.opf", "<package><metadata><dc:title>كتاب &amp; تجربة</dc:title></metadata><manifest><item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>")
                put("OEBPS/s.css", "p{text-align:center}")
                put("OEBPS/a.xhtml", "<html><head><link rel=\"stylesheet\" href=\"s.css\"/><style>h1{color:red}</style></head><body><p onclick=\"x()\">نص</p><img src=\"missing.png\" onerror=\"x()\"/></body></html>")
            }
            val b = EpubReader.readBook(f)
            assertEquals("كتاب & تجربة", b.title)
            assertTrue(b.css.contains("text-align:center")); assertTrue(b.css.contains("color:red"))
            assertFalse(b.chapters.single().contains("onclick")); assertFalse(b.chapters.single().contains("onerror"))
            assertTrue(b.rtl)
        } finally { f.delete() }
    }

    // ---- writers keep pictures (C36) and split mixed runs (C38)
    @Test fun portablePicturesAndRuns() {
        val png = PngEncoder.encode(2, 2, IntArray(4) { 0xFF00FF00.toInt() })
        assertEquals("png", Images6.sniffExt(png))
        val bmp = BmpWriter.write(Raster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 72))
        val back = BmpDecoder.decode(bmp)!!
        assertEquals(0xFFFF0000.toInt(), back.third[0]); assertEquals(0xFF0000FF.toInt(), back.third[1])
        val n = PortableImage.normalize(bmp, "bmp", setOf("jpg", "png"))!!
        assertEquals("png", n.second)
        val epub = ByteArrayOutputStream().also { EpubWriter.write(FlowDoc("t", listOf(FBlock.Image(bmp, "bmp"))), it, id = "x") }.toByteArray()
        assertTrue(unzip(epub).keys.any { it.endsWith(".png") })
        val segs = FlowDocx.directionSegments("نص مع English 123 كلمة")
        assertTrue(segs.contains("English 123" to false))
        assertTrue(segs.first().second)
    }

    // ---- pictures, long image, catalog (A-L11 / B06 / C15)
    @Test fun imageDecisions() {
        val dpi = Images6.pageDpi(4000, 3000, null)
        val p = Images6.place(4000, 3000, PageSize6.IMAGE, Orient6.AUTO, Fit6.CONTAIN, 0f, dpi)
        assertEquals(841.7f, p.pageW, 1f)
        assertEquals(300f, Images6.pageDpi(100, 100, 300f), 0.01f)
        assertEquals(72f, Images6.jfifDpi(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 1, 0, 72))!!, 0.01f)
        val parts = Images6.longParts(List(100) { 822 }, 581, Images6.maxSide(ImgFmt.WEBP), 60_000_000L)
        assertTrue(parts.all { r -> r.sumOf { 822 } <= 16383 })
        assertEquals(100, parts.sumOf { it.count() })
        assertTrue(Images6.renderScale(595f, 842f, 300, 4_000_000L) < 300f / 72f)
    }

    @Test fun arabicSearch() {
        fun ids(q: String) = Cv6Catalog.search(q) { "" }.map { it.id }
        assertTrue(ids("وورد").contains("pdf_docx"))
        assertTrue(ids("اكسل").contains("pdf_xlsx")); assertTrue(ids("إكسل").contains("excel_pdf"))
        assertTrue(ids("بوربوينت").contains("ppt_pdf"))
        assertTrue(ids("كتاب").contains("pdf_epub"))
        assertTrue(ids("ضغط").contains("pdf_compress"))
        assertTrue(ids("صورة").size > 5)
        assertTrue(Cv6Catalog.byId("extract_images")!!.inputs.contains(Kind6.ODS))
    }

    // ---- outputs journal (C2 / C11) and security sniffing (item 8 / C45)
    @Test fun journalAndSniffs() {
        val dir = kotlin.io.path.createTempDirectory("cvf").toFile()
        try {
            val j = OutputJournal(File(dir, "j.txt"))
            val a = File(dir, "a.pdf").apply { writeText("a") }; val b = File(dir, "b.pdf").apply { writeText("b") }
            j.add(a); j.add(b)
            j.finish(listOf(a, b), listOf(a))
            assertTrue(a.exists()); assertFalse(b.exists()); assertTrue(j.pending().isEmpty())
            val c = File(dir, "c.pdf").apply { writeText("c") }
            j.add(c); assertEquals(1, j.purge()); assertFalse(c.exists())
            val enc = File(dir, "e.pdf").apply { writeText("%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<</Encrypt 5 0 R>>\nstartxref\n0\n%%EOF") }
            assertTrue(Cv6Security.mayBeEncrypted(enc))
            val plain = File(dir, "p.pdf").apply { writeText("%PDF-1.4\ntrailer<<>>\nstartxref\n0\n%%EOF") }
            assertFalse(Cv6Security.mayBeEncrypted(plain))
            val sig = File(dir, "s.pdf").apply { writeText("%PDF-1.4 /ByteRange [0 1 2 3]") }
            assertTrue(Cv6Security.hasSignature(sig)); assertFalse(Cv6Security.hasSignature(plain))
        } finally { dir.deleteRecursively() }
        assertEquals(com.smartdocs.editor.core.security.DocAction.SAVE_UNPROTECTED, Cv6Security.actionFor("pdf_pdfa"))
        assertEquals(com.smartdocs.editor.core.security.DocAction.MODIFY, Cv6Security.actionFor("pdf_gray"))
        assertTrue(Cv6Security.allowed("pdf_gray", null))
    }

    private fun unzip(b: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        java.util.zip.ZipInputStream(b.inputStream()).use { z -> var e = z.nextEntry; while (e != null) { out[e.name] = z.readBytes().toString(Charsets.UTF_8); e = z.nextEntry } }
        return out
    }
}
