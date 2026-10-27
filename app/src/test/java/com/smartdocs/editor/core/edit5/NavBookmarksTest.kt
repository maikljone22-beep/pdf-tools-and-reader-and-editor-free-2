package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.forms5.JsField
import com.smartdocs.editor.core.security.JsPolicy
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** nav-bookmarks: destinations, action lists, outline helpers, XML, auto bookmarks / links, link geometry, document JavaScript. */
class NavBookmarksTest {

    @After fun restore() { JsPolicy.enabled = true }

    private fun doc(pages: Int) = PDDocument().apply { repeat(pages) { addPage(PDPage()) } }
    private fun bm(title: String, page: Int, level: Int) = E5Bookmark(OutlineModel.newKey(), title, page, level)

    // ---------------- destination views ----------------
    @Test fun destViewsRoundTrip() {
        val d = doc(1)
        val views = listOf(
            DestView(DestFit.XYZ, 10f, 700f, 1.5f), DestView(DestFit.FIT), DestView(DestFit.FITH, top = 500f), DestView(DestFit.FITV, left = 72f),
            DestView(DestFit.FITB), DestView(DestFit.FITBH, top = 400f), DestView(DestFit.FITR, rect = listOf(10f, 20f, 300f, 400f)),
        )
        for (v in views) {
            val a = Dests.array(d.getPage(0).cosObject, v)
            assertEquals(v, Dests.view(a))
        }
        // FitR without a rectangle falls back to Fit
        assertEquals("Fit", Dests.array(d.getPage(0).cosObject, DestView(DestFit.FITR)).getName(1))
        d.close()
    }

    // ---------------- action lists ----------------
    @Test fun actionListRoundTripKeepsUnknownTypes() {
        val d = doc(3)
        val submit = COSDictionary().apply { setName(COSName.S, "SubmitForm"); setString(COSName.F, "https://x.example/s") }
        val orig = COSDictionary().apply { setName(COSName.S, "Named"); setName(COSName.N, "NextPage"); setItem(COSName.NEXT, submit) }
        val read = NavActions.read(d, orig)
        assertEquals(listOf(NavAction.Named("NextPage"), NavAction.Kept("SubmitForm", 1)), read)
        val list = listOf(NavAction.Uri("https://example.com"), read[1], NavAction.GoTo(2, DestView(DestFit.FITH, top = 100f)),
            NavAction.Js(NavActions.printScript(2, 3)), NavAction.GoToR("other.pdf", named = "chap2"))
        val head = NavActions.write(d, list, orig)!!
        val back = NavActions.read(d, head)
        assertEquals(5, back.size)
        assertEquals(NavAction.Uri("https://example.com"), back[0])
        assertEquals(NavAction.Kept("SubmitForm", 1), back[1])
        assertEquals("https://x.example/s", NavActions.flatten(head)[1].getString(COSName.F))
        assertEquals(NavAction.GoTo(2, DestView(DestFit.FITH, top = 100f)), back[2])
        assertEquals(Triple("print", 2, 3), NavActions.pageScript((back[3] as NavAction.Js).script))
        assertEquals("chap2", (back[4] as NavAction.GoToR).named)
        assertEquals(Triple("extract", 1, 4), NavActions.pageScript(NavActions.extractScript(1, 4)))
        assertNull(NavActions.pageScript("app.alert(1)"))
        d.close()
    }

    @Test fun outlineWithActionsAndViews() {
        val d = doc(4)
        val list = listOf(
            bm("A", 0, 0).copy(fit = DestFit.FITR, fitR = listOf(1f, 2f, 3f, 4f), color = 0xFF0000, bold = true),
            bm("B", -1, 1).copy(actions = listOf(NavAction.Uri("https://a.b"), NavAction.Named("LastPage"))),
            bm("C", 3, 0).copy(fit = DestFit.FITV, left = 50f, open = true),
        )
        OutlineStore.write(d, list)
        val r = OutlineStore.read(d)
        assertEquals(listOf("A", "B", "C"), r.map { it.title })
        assertEquals(listOf(0, 1, 0), r.map { it.level })
        assertEquals(DestFit.FITR, r[0].fit); assertEquals(listOf(1f, 2f, 3f, 4f), r[0].fitR); assertEquals(0xFF0000, r[0].color); assertTrue(r[0].bold)
        assertEquals(listOf(NavAction.Uri("https://a.b"), NavAction.Named("LastPage")), r[1].actions)
        assertEquals(DestFit.FITV, r[2].fit); assertEquals(50f, r[2].left)
        // write back: the read list (with orig indexes) round-trips
        OutlineStore.write(d, r)
        assertEquals(r.map { it.title to it.actions }, OutlineStore.read(d).map { it.title to it.actions })
        d.close()
    }

    // ---------------- outline helpers ----------------
    @Test fun sectionsCurrentSearch() {
        val l = listOf(bm("Intro", 0, 0), bm("Part 1", 2, 0), bm("1.1", 3, 1), bm("1.2", 5, 1), bm("Part 2", 8, 0))
        assertEquals(0..1, OutlineNav.sectionPages(l, 0, 10))
        assertEquals(2..7, OutlineNav.sectionPages(l, 1, 10))
        assertEquals(3..4, OutlineNav.sectionPages(l, 2, 10))
        assertEquals(5..7, OutlineNav.sectionPages(l, 3, 10))
        assertEquals(8..9, OutlineNav.sectionPages(l, 4, 10))
        assertEquals(3, OutlineNav.current(l.map { it.page }, 6))
        assertEquals(-1, OutlineNav.current(listOf(4, 5), 2))
        assertEquals(setOf(1, 3), OutlineNav.search(l.map { it.title }, l.map { it.level }, "1.2"))
        // Arabic: hamza / diacritics folded
        assertEquals(setOf(0), OutlineNav.search(listOf("الفصل الأوّل"), listOf(0), "الاول"))
    }

    @Test fun deletePagesAndInsertForPage() {
        val l = listOf(bm("Intro", 0, 0), bm("Part 1", 2, 0), bm("1.1", 3, 1), bm("Part 2", 5, 0))
        val after = OutlineNav.afterDelete(l, setOf(2, 3, 4))
        assertEquals(listOf("Intro" to 0, "Part 2" to 2), after.map { it.title to it.page })
        val ins = OutlineNav.insertForPage(l, 4, bm("New", 4, 0))
        assertEquals(listOf("Intro", "Part 1", "1.1", "New", "Part 2"), ins.map { it.title })
        assertEquals(1, ins[3].level)
        assertEquals("New", OutlineNav.insertForPage(emptyList(), 2, bm("New", 2, 3)).single().title)
        assertEquals(listOf("x", "y"), OutlineNav.insert(listOf(bm("y", 0, 0)), listOf(bm("x", 0, 0)), AutoInsert.BEFORE).map { it.title })
    }

    // ---------------- XML ----------------
    @Test fun bookmarkXmlRoundTrip() {
        val l = listOf(
            bm("Intro & <start>", 0, 0).copy(color = 0xC62828, bold = true, open = true, zoom = 1.25f),
            bm("Child \"quoted\"", 1, 1).copy(italic = true),
            bm("Web", -1, 1).copy(actions = listOf(NavAction.Uri("https://example.com/?a=1&b=2"), NavAction.Js("app.alert('x');\nvar y = 1;"))),
            bm("Last", 4, 0).copy(fit = DestFit.FITH, top = 300f),
        )
        val xml = BookmarkXml.write(l)
        val r = BookmarkXml.read(xml.byteInputStream(), 5)
        assertEquals(l.map { it.title }, r.map { it.title })
        assertEquals(l.map { it.level }, r.map { it.level })
        assertEquals(l.map { it.page }, r.map { it.page })
        assertEquals(0xC62828, r[0].color); assertTrue(r[0].bold); assertTrue(r[0].open); assertEquals(1.25f, r[0].zoom, 0.001f)
        assertTrue(r[1].italic)
        assertEquals(l[2].actions, r[2].actions)
        assertEquals(DestFit.FITH, r[3].fit); assertEquals(300f, r[3].top)
    }

    // ---------------- auto bookmarks ----------------
    private fun line(t: String, page: Int, size: Float, top: Float, font: String = "Body", color: Int? = 0, left: Float? = 72f) = TextLine(t, page, size, top, font, color, left)

    private val sample = buildList {
        for (p in 0 until 5) {
            add(line("Chapter ${p + 1} Overview", p, 20f, 780f, "Helv-Bold"))
            add(line("${p + 1}.1 Details", p, 14f, 700f, "Helv-Bold", color = 0x1565C0, left = 90f))
            repeat(8) { add(line("body text line number $it with enough letters", p, 10f, 650f - it * 14f)) }
            add(line("Running header", p, 14f, 820f))
        }
    }

    @Test fun autoBookmarksLevelsAndFilters() {
        val s = AutoBmSettings(levels = listOf(AutoBmLevel(), AutoBmLevel()))
        val h = AutoBookmarks.headings(sample, s)
        assertEquals(10, h.size)
        assertEquals(listOf("Chapter 1 Overview" to 0, "1.1 Details" to 1, "Chapter 2 Overview" to 0, "2.1 Details" to 1),
            h.take(4).map { it.first.text to it.second })
        // colour rule on level 2 and leading numbers removed
        val c = AutoBookmarks.headings(sample, s.copy(levels = listOf(AutoBmLevel(), AutoBmLevel(color = 0x1565C0, colorTolerance = 5)), leadingNumbers = LeadingNumbers.STRIP))
        assertEquals("Details", c[1].first.text)
        // fixed size + mask + keyword exclude
        val m = AutoBookmarks.headings(sample, s.copy(levels = listOf(AutoBmLevel(size = 20f, mask = "Chapter #*", exclude = "3"))))
        assertEquals(listOf("Chapter 1 Overview", "Chapter 2 Overview", "Chapter 4 Overview", "Chapter 5 Overview"), m.map { it.first.text })
        // indent rule
        assertEquals(5, AutoBookmarks.headings(sample, s.copy(levels = listOf(AutoBmLevel(size = 14f, indent = 90f, indentTolerance = 2f)))).size)
        // numbered headings only
        assertEquals(5, AutoBookmarks.headings(sample, s.copy(leadingNumbers = LeadingNumbers.REQUIRE)).size)
        // created bookmarks: view and collapse
        val b = AutoBookmarks.toBookmarks(h, s.copy(view = DestView(DestFit.XYZ, zoom = 2f), collapse = true))
        assertEquals(2f, b[0].zoom, 0f); assertEquals(780f, b[0].top); assertFalse(b[0].open)
        assertTrue(AutoBookmarks.maskMatches("A?C*", "abcdef"))
        assertFalse(AutoBookmarks.maskMatches("#.#", "a.1"))
        assertEquals("Intro", AutoBookmarks.stripLeadingNumber("1.2.3 Intro"))
        assertEquals("مقدمة", AutoBookmarks.stripLeadingNumber("٣. مقدمة"))
    }

    @Test fun autoBookmarkSettingsFile() {
        val s = AutoBmSettings(levels = listOf(AutoBmLevel(size = 18f, font = "Bold", color = 0xFF0000, indent = 40f, mask = "Ch*", include = "a,b"), AutoBmLevel()),
            minRatio = 1.3f, leadingNumbers = LeadingNumbers.STRIP, exactText = true, view = DestView(DestFit.FITH), insert = AutoInsert.BEFORE, collapse = true, pages = "2-5")
        assertEquals(s, AutoBookmarks.fromJson(AutoBookmarks.toJson(s)))
        assertEquals(AutoBmSettings(), AutoBookmarks.fromJson("{}"))
    }

    // ---------------- auto links ----------------
    @Test fun autoPageAndKeywordLinks() {
        val text = "Contents\nIntroduction ........ 3\nChapter one 12\n٥ الفصل الثاني\nno number here\n"
        val hits = AutoLinks.tocNumbers(text, 20)
        assertEquals(listOf(2, 11, 4), hits.map { it.target })
        assertEquals("3", text.substring(hits[0].range))
        assertEquals("12", text.substring(hits[1].range))
        assertEquals(listOf(4), AutoLinks.tocNumbers("Intro 3", 20, offset = 2).map { it.target })
        assertEquals("Intro 3", "Intro 3".substring(AutoLinks.tocNumbers("Intro 3", 20, wholeLine = true)[0].range))
        assertTrue(AutoLinks.tocNumbers("Intro 30", 20).isEmpty())
        val x = "As shown (see page 7) and p. 2, also انظر الصفحة 4."
        assertEquals(listOf(6, 1, 3), AutoLinks.crossRefs(x, 10).map { it.target })
        assertEquals("see page 7", x.substring(AutoLinks.crossRefs(x, 10)[0].range))
        assertEquals(listOf(0..2, 14..16), AutoLinks.keyword("PDF tools and PDF, PDFs", "pdf", matchCase = false, wholeWord = true).let { listOf(it[0], it[1]) })
        assertEquals(2, AutoLinks.keyword("PDF tools and PDF, PDFs", "pdf", matchCase = false, wholeWord = true).size)
        assertEquals(3, AutoLinks.keyword("PDF tools and PDF, PDFs", "PDF", matchCase = true, wholeWord = false).size)
    }

    // ---------------- link geometry ----------------
    @Test fun alignDistributeSize() {
        val a = LinkGeom.Box(10f, 100f, 60f, 120f); val b = LinkGeom.Box(30f, 50f, 100f, 80f); val c = LinkGeom.Box(200f, 10f, 220f, 20f)
        assertEquals(listOf(10f, 10f), LinkGeom.align(listOf(a, b), LinkGeom.Align.LEFT).map { it.left })
        assertEquals(listOf(120f, 120f), LinkGeom.align(listOf(a, b), LinkGeom.Align.TOP).map { it.top })
        assertEquals(35f, LinkGeom.align(listOf(a, b), LinkGeom.Align.CENTER_H)[1].cx, 0.001f)
        val d = LinkGeom.distribute(listOf(c, a, b), LinkGeom.Spread.HORIZONTAL)
        // outermost stay, gaps equal
        assertEquals(10f, d[1].left); assertEquals(220f, d[0].right)
        assertEquals(d[2].left - d[1].right, d[0].left - d[2].right, 0.001f)
        val s = LinkGeom.sameSize(listOf(a, b), LinkGeom.Size.BOTH)[1]
        assertEquals(50f, s.width, 0f); assertEquals(20f, s.height, 0f); assertEquals(80f, s.top, 0f)
        val q = LinkGeom.mapQuads(floatArrayOf(10f, 120f, 60f, 120f, 10f, 100f, 60f, 100f), a, a.moved(5f, -10f))
        assertEquals(15f, q[0], 0f); assertEquals(110f, q[1], 0f)
    }

    // ---------------- named destinations ----------------
    @Test fun namedDestinationsEditAndRename() {
        val d = doc(3)
        // an old-style /Dests entry and a link that uses it
        d.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply { setItem(COSName.getPDFName("old"), Dests.array(d.getPage(1).cosObject, DestView(DestFit.FIT))) })
        val link = PDAnnotationLink().apply { cosObject.setItem(COSName.DEST, COSString("old")) }
        d.getPage(0).annotations = mutableListOf(link)
        assertEquals(listOf(NamedDest("old", 1, DestView(DestFit.FIT))), NamedDests.list(d))
        val changed = NamedDests.apply(d, listOf(NamedDests.Op.Set("b-new", 2, DestView(DestFit.FITH, top = 10f)), NamedDests.Op.Rename("old", "a-renamed")))
        assertEquals(listOf("a-renamed", "b-new"), NamedDests.list(d).map { it.name })
        assertNull(d.documentCatalog.cosObject.getDictionaryObject(COSName.DESTS))
        assertEquals("a-renamed", (d.getPage(0).annotations[0].cosObject.getDictionaryObject(COSName.DEST) as COSString).string)
        assertEquals(1, changed.size)
        NamedDests.apply(d, listOf(NamedDests.Op.Delete("b-new")))
        assertEquals(listOf("a-renamed"), NamedDests.list(d).map { it.name })
        assertEquals(listOf("z" to 0, "a" to 2), NamedDests.sorted(listOf(NamedDest("a", 2), NamedDest("z", 0)), byPage = true).map { it.name to it.page })
        assertEquals("Dest 3", NamedDests.freeName(listOf("Dest 1", "x"), "Dest"))
        d.close()
    }

    // ---------------- document JavaScript ----------------
    @Test fun documentScriptsStore() {
        val d = doc(1)
        // a non-JavaScript document action stays
        d.documentCatalog.cosObject.setItem(COSName.AA, COSDictionary().apply { setItem(COSName.getPDFName("WP"), COSDictionary().apply { setName(COSName.S, "Named"); setName(COSName.N, "Print") }) })
        DocJsStore.write(d, DocScripts(listOf("zeta" to "var z = 1;", "alpha" to "function f() { return 2; }"), mapOf(DocTrigger.WILL_SAVE to "app.alert('saving');")))
        val r = DocJsStore.read(d)
        assertEquals(listOf("alpha", "zeta"), r.scripts.map { it.first })
        assertEquals("app.alert('saving');", r.triggers[DocTrigger.WILL_SAVE])
        val aa = d.documentCatalog.cosObject.getDictionaryObject(COSName.AA) as COSDictionary
        assertEquals("Named", (aa.getDictionaryObject(COSName.getPDFName("WP")) as COSDictionary).getNameAsString(COSName.S))
        DocJsStore.write(d, DocScripts(emptyList(), emptyMap()))
        assertTrue(DocJsStore.read(d).scripts.isEmpty())
        assertNotNull(d.documentCatalog.cosObject.getDictionaryObject(COSName.AA))       // the Named action is kept
        assertEquals(6, DocJsStore.lineOffset("ab\ncd\nef", 3))
        assertEquals(0, DocJsStore.lineOffset("ab", 1))
        assertNull(DocJsStore.syntaxError("function f(a) { return a + 1; }"))
        assertNotNull(DocJsStore.syntaxError("function f( {"))
        d.close()
    }

    @Test fun consoleRunsInSandbox() {
        val fields = listOf(JsField("total", "text", value = "42"))
        val r = DocJsConsole.run(fields, listOf("function twice(x) { return x * 2; }"), 3, "a.pdf",
            "console.println('pages ' + this.numPages); app.alert('hi'); twice(Number(this.getField('total').value));")
        assertEquals(listOf("pages 3"), r.log)
        assertEquals(listOf("hi"), r.alerts)
        assertEquals("84", r.value)
        assertNull(r.error)
        val e = DocJsConsole.run(emptyList(), emptyList(), 1, "a.pdf", "nosuch();")
        assertNotNull(e.error)
        // no Java access from scripts
        assertNotNull(DocJsConsole.run(emptyList(), emptyList(), 1, "a.pdf", "java.lang.System.exit(0)").error)
        val t = DocJsConsole.run(emptyList(), emptyList(), 1, "a.pdf", "while (true) {}", millis = 200)
        assertNotNull(t.error)
        JsPolicy.enabled = false
        assertTrue(runCatching { DocJsConsole.run(emptyList(), emptyList(), 1, "a.pdf", "1") }.exceptionOrNull() is DocJsConsole.Disabled)
    }

}
