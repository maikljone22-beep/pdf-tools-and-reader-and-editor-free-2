package com.smartdocs.editor.core.edit5

import android.graphics.RectF
import com.smartdocs.editor.core.edit.OutlineModel
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix/links-bookmarks: GoToE, media / sound actions, title clean-up, tag bookmarks, recent URLs, web capture, defaults. */
class LinksBookmarksTest {

    private fun doc(pages: Int) = PDDocument().apply { repeat(pages) { addPage(PDPage()) } }
    private fun bm(title: String, page: Int, level: Int = 0) = E5Bookmark(OutlineModel.newKey(), title, page, level)

    private fun wav(rate: Int, channels: Int, bits: Int, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(channels); i32(rate); i32(rate * channels * bits / 8); i16(channels * bits / 8); i16(bits)
        out.write("data".toByteArray()); i32(data.size); out.write(data)
        return out.toByteArray()
    }

    // ---------------- GoToE ----------------
    @Test fun goToERoundTrip() {
        val d = doc(1)
        val list = listOf(
            NavAction.GoToE("report.pdf", 2, view = DestView(DestFit.FITH, top = 500f), newWindow = false),
            NavAction.GoToE("annex.pdf", named = "chapter2"),
        )
        val head = NavActions.write(d, list, null)!!
        assertEquals("GoToE", head.getNameAsString(COSName.S))
        val t = head.getDictionaryObject(COSName.getPDFName("T")) as COSDictionary
        assertEquals("C", t.getNameAsString(COSName.getPDFName("R")))
        assertEquals("report.pdf", t.getString(COSName.N))
        assertEquals(list, NavActions.read(d, head))
    }

    @Test fun goToEWithOuterFileIsKept() {
        val a = COSDictionary().apply {
            setName(COSName.S, "GoToE"); setString(COSName.F, "other.pdf")
            setItem(COSName.getPDFName("T"), COSDictionary().apply { setName(COSName.getPDFName("R"), "C"); setString(COSName.N, "x.pdf") })
        }
        assertTrue(NavActions.decode(null, a, 0) is NavAction.Kept)
    }

    // ---------------- media / sound ----------------
    @Test fun parseWavAndSamples() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val w = NavMedia.parseWav(wav(8000, 1, 16, pcm))!!
        assertEquals(8000, w.rate); assertEquals(1, w.channels); assertEquals(16, w.bits)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03), NavMedia.pdfSamples(w))   // big-endian in the PDF
        assertNull(NavMedia.parseWav("not a wav file at all, just some text of 44+ bytes ......".toByteArray()))
    }

    @Test fun soundActionRoundTrip() {
        val f = File.createTempFile("lbm", ".wav")
        try {
            val pcm = ByteArray(200) { (it * 3).toByte() }
            f.writeBytes(wav(11025, 1, 16, pcm))
            val d = doc(1)
            val head = NavActions.write(d, listOf(NavAction.Media("a.wav", file = f.absolutePath, sound = true, mime = "audio/wav")), null)!!
            assertEquals("Sound", head.getNameAsString(COSName.S))
            val read = NavActions.read(d, head).single() as NavAction.Media
            assertTrue(read.sound); assertEquals(0, read.index)
            val (_, mime, bytes) = NavMedia.extract(head)!!
            assertEquals("audio/wav", mime)
            // the decoded WAV has the same 16-bit samples
            val back = NavMedia.parseWav(bytes)!!
            assertEquals(11025, back.rate)
            assertArrayEquals(pcm, back.data)
            // written again without a new file: the original action is kept
            val again = NavActions.write(d, listOf(read), head)!!
            assertEquals("Sound", again.getNameAsString(COSName.S))
        } finally { f.delete() }
    }

    @Test fun renditionActionRoundTrip() {
        val f = File.createTempFile("lbm", ".mp3")
        try {
            f.writeBytes(ByteArray(64) { it.toByte() })
            val d = doc(1)
            val head = NavActions.write(d, listOf(NavAction.Media(f.name, file = f.absolutePath, mime = "audio/mpeg")), null)!!
            assertEquals("Rendition", head.getNameAsString(COSName.S))
            assertEquals(NavMedia.PLAY_SCRIPT, head.getString(COSName.JS))
            assertNull(head.getDictionaryObject(COSName.getPDFName("OP")))
            val read = NavActions.read(d, head).single() as NavAction.Media
            assertEquals(f.name, read.name); assertEquals("audio/mpeg", read.mime)
            val (name, _, bytes) = NavMedia.extract(head)!!
            assertEquals(f.name, name)
            assertArrayEquals(f.readBytes(), bytes)
        } finally { f.delete() }
    }

    // ---------------- auto bookmark titles ----------------
    @Test fun formatTitle() {
        val s = AutoBmSettings()
        assertEquals("Intro", AutoBookmarks.formatTitle("Intro", s))
        assertEquals("INTRO TO PDF", AutoBookmarks.formatTitle("Intro to PDF", s.copy(titleCase = TitleCase.UPPER)))
        assertEquals("Intro To Pdf", AutoBookmarks.formatTitle("intro TO pdf", s.copy(titleCase = TitleCase.TITLE)))
        assertEquals("Intro to pdf", AutoBookmarks.formatTitle("INTRO TO PDF", s.copy(titleCase = TitleCase.SENTENCE)))
        assertEquals("[ 12 Results ]", AutoBookmarks.formatTitle("1.2 — Results!", s.copy(alnumOnly = true, prefix = "[ ", suffix = " ]")))
        assertEquals("Guide PDF", AutoBookmarks.formatTitle("The Guide to the PDF", s.copy(stopWords = "the, to")))
        // a title that would become empty keeps its text
        assertEquals("—", AutoBookmarks.formatTitle("—", s.copy(alnumOnly = true)))
        // Arabic letters count as alphanumeric
        assertEquals("الفصل الأول", AutoBookmarks.formatTitle("الفصل: الأول", s.copy(alnumOnly = true)))
    }

    @Test fun titleOptionsInJson() {
        val s = AutoBmSettings(titleCase = TitleCase.LOWER, prefix = "> ", suffix = "!", alnumOnly = true, stopWords = "a, an")
        assertEquals(s, AutoBookmarks.fromJson(AutoBookmarks.toJson(s)))
        val b = AutoBookmarks.toBookmarks(listOf(TextLine("THE END", 0, 20f, 700f) to 0), s.copy(stopWords = "the"))
        assertEquals("> end!", b.single().title)
    }

    // ---------------- tag bookmarks ----------------
    private fun elem(type: String, page: COSDictionary?, vararg kids: Any, title: String? = null) = COSDictionary().apply {
        setName(COSName.TYPE, "StructElem"); setName(COSName.S, type)
        if (page != null) setItem(COSName.getPDFName("Pg"), page)
        if (title != null) setString(COSName.T, title)
        setItem(COSName.K, COSArray().apply { kids.forEach { k -> add(if (k is Int) COSInteger.get(k.toLong()) else k as COSDictionary) } })
    }

    @Test fun tagElementsAndLevels() {
        val d = doc(2)
        val p0 = d.getPage(0).cosObject; val p1 = d.getPage(1).cosObject
        val h1 = elem("H1", p0, 0, title = "One")
        val sect = elem("Sect", p1, elem("Heading2", null, 3, title = "Two"), elem("P", null, 4))
        val root = COSDictionary().apply {
            setName(COSName.TYPE, "StructTreeRoot")
            setItem(COSName.K, elem("Document", null, h1, elem("P", p0, 1), sect))
            setItem(COSName.getPDFName("RoleMap"), COSDictionary().apply { setName("Heading2", "H2") })
        }
        val e = TagBookmarks.elements(root, setOf("H1", "H2", "Sect"), TagBookmarks.roleMap(root))
        assertEquals(listOf("H1", "Sect", "H2"), e.map { it.type })
        assertEquals(listOf(0, 0, 1), e.map { it.level })
        assertEquals(listOf(0), e[0].mcids)
        assertTrue(e[2].page === p1)
        assertEquals(1, TagBookmarks.levelOf("H2", 5)); assertEquals(2, TagBookmarks.levelOf("Caption", 2))
    }

    @Test fun tagBookmarksOfDocument() {
        val d = doc(2)
        val p1 = d.getPage(1).cosObject
        d.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, COSDictionary().apply {
            setName(COSName.TYPE, "StructTreeRoot")
            setItem(COSName.K, elem("Document", null, elem("H1", d.getPage(0).cosObject, title = "Start"), elem("H3", p1, title = "Deep")))
        })
        val b = TagBookmarks.bookmarks(d, TagBookmarks.DEFAULT)
        assertEquals(listOf("Start", "Deep"), b.map { it.title })
        assertEquals(listOf(0, 1), b.map { it.page })
        assertEquals(listOf(0, 1), b.map { it.level })   // H3 under H1 is normalised to level 1
        assertTrue(TagBookmarks.bookmarks(doc(1), TagBookmarks.DEFAULT).isEmpty())
    }

    // ---------------- recent URLs ----------------
    @Test fun recentUrls() {
        var l = emptyList<String>()
        l = RecentUrls.push(l, "https://a.com")
        l = RecentUrls.push(l, "https://b.com/")
        l = RecentUrls.push(l, "https://A.com/")
        assertEquals(listOf("https://A.com/", "https://b.com/"), l)
        assertEquals(l, RecentUrls.push(l, "https://"))
        assertEquals(3, (1..5).fold(emptyList<String>()) { acc, i -> RecentUrls.push(acc, "https://$i.com", max = 3) }.size)
        assertEquals(listOf("https://b.com/"), RecentUrls.matching(l, "b.co"))
        assertEquals(l, RecentUrls.matching(l, "https://"))
    }

    // ---------------- web capture ----------------
    @Test fun webUrlHelpers() {
        assertEquals("https://example.com/a", WebCapture.key("HTTPS://Example.COM/a/#top"))
        assertEquals("example.com", WebCapture.host("https://www.Example.com:8080/x"))
        assertEquals("example.com_report.pdf", WebCapture.fileName("https://example.com/docs/report.pdf?x=1"))
        assertEquals(".html", WebCapture.extFor("text/html; charset=utf-8", "page"))
        assertEquals("", WebCapture.extFor("text/html", "page.php"))
        val links = listOf(
            PageLinkInfo(1, 0, LinkSpec(RectF(), LinkKind.URI, uri = "https://b.com"), true),
            PageLinkInfo(0, 0, LinkSpec(RectF(), LinkKind.URI, uri = "https://a.com/"), true),
            PageLinkInfo(0, 1, LinkSpec(RectF(), LinkKind.MAIL, uri = "mailto:x@y.z"), true),
            PageLinkInfo(2, 0, LinkSpec(RectF(), LinkKind.URI, uri = "https://A.com"), true),
        )
        val c = WebCapture.collect(links)
        assertEquals(listOf("https://a.com/", "https://b.com"), c.map { it.url })
        assertEquals(listOf(0, 2), c[0].pages)
        val bms = WebCapture.bookmarks(listOf(Triple("https://a.com/x", "X", 3), Triple("https://a.com/y", "Y", 5), Triple("https://b.org", "B", 6)), byDomain = true)
        assertEquals(listOf("a.com", "X", "Y", "b.org", "B"), bms.map { it.title })
        assertEquals(listOf(0, 1, 1, 0, 1), bms.map { it.level })
    }

    @Test fun appendWebPages() {
        val dir = createTempDirectory()
        try {
            val src = File(dir, "src.pdf"); val part = File(dir, "part.pdf"); val out = File(dir, "out.pdf")
            doc(1).use { d ->
                val link = PDAnnotationLink()
                link.cosObject.setItem(COSName.A, COSDictionary().apply { setName(COSName.S, "URI"); setString(COSName.URI, "https://Example.com/page#x") })
                d.getPage(0).annotations = mutableListOf(link)
                d.save(src)
            }
            doc(2).use { it.documentInformation.title = "Example page"; it.save(part) }
            val n = kotlinx.coroutines.runBlocking { WebCapture.append(src, out, null, listOf("https://example.com/page" to part), bookmarks = true, byDomain = true, relink = true) }
            assertEquals(2, n)
            PDDocument.load(out).use { d ->
                assertEquals(3, d.numberOfPages)
                val a = d.getPage(0).annotations.single().cosObject
                assertNull(a.getDictionaryObject(COSName.A))
                val dest = a.getDictionaryObject(COSName.DEST) as COSArray
                assertTrue(dest.getObject(0) === d.getPage(1).cosObject)
                val o = OutlineStore.read(d)
                assertEquals(listOf("example.com", "Example page"), o.map { it.title })
                assertEquals(1, o[1].page)
            }
        } finally { dir.deleteRecursively() }
    }

    private fun createTempDirectory(): File = File.createTempFile("lbm", "").let { it.delete(); it.mkdirs(); it }

    // ---------------- bookmark defaults / current view ----------------
    @Test fun bookmarkDefaults() {
        val b = bm("A", 2).copy(top = 600f, left = 30f)
        val d = BookmarkDefaults.Defaults(DestView(DestFit.XYZ, zoom = 1.5f), color = 0xC62828, bold = true)
        val r = BookmarkDefaults.applyTo(b, d)
        assertEquals(DestView(DestFit.XYZ, 30f, 600f, 1.5f), r.view)
        assertEquals(0xC62828, r.color); assertTrue(r.bold)
        assertEquals(DestView(DestFit.FITH, top = 600f), BookmarkDefaults.applyTo(b, d.copy(view = DestView(DestFit.FITH))).view)
        assertEquals(DestFit.FIT, BookmarkDefaults.applyTo(b, d.copy(view = DestView(DestFit.FIT))).fit)
    }

    @Test fun retargetToCurrentView() {
        val v = DestView(DestFit.XYZ, null, 400f, 2f)
        val plain = BookmarkDefaults.retarget(bm("A", 0), 4, v)
        assertEquals(4, plain.page); assertEquals(v, plain.view); assertTrue(plain.actions.isEmpty())
        val withJs = bm("B", 0).copy(actions = listOf(NavAction.Js("x()"), NavAction.GoTo(1)))
        val r = BookmarkDefaults.retarget(withJs, 3, v)
        assertEquals(listOf(NavAction.Js("x()"), NavAction.GoTo(3, v)), r.actions)
        val onlyUri = BookmarkDefaults.retarget(bm("C", 0).copy(actions = listOf(NavAction.Uri("https://a"))), 2, v)
        assertEquals(NavAction.GoTo(2, v), onlyUri.actions.first())
        assertNotNull(onlyUri.actions.getOrNull(1))
    }

    @Test fun gotoeInBookmarkXml() {
        val l = listOf(bm("A", 0).copy(actions = listOf(NavAction.GoToE("x.pdf", 1, "n1"))))
        val back = BookmarkXml.read(BookmarkXml.write(l).byteInputStream(), 3)
        assertEquals(NavAction.GoToE("x.pdf", 1, "n1"), back.single().actions.single())
        assertEquals("x", COSString("x").string)
    }
}
