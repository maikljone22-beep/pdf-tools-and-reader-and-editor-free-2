package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.nve.shell.ScanPreset
import com.smartdocs.editor.core.nve.shell.Customization
import com.smartdocs.editor.core.nve.shell.TrustPolicy
import com.smartdocs.editor.core.nve.shell.FeatureGuides
import com.smartdocs.editor.core.nve.shell.Favorites
import com.smartdocs.editor.core.nve.shell.PdfRepair
import com.smartdocs.editor.core.nve.shell.RecoveryNames
import com.smartdocs.editor.core.nve.shell.ToolbarModel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Shell work (fork G): favourites, toolbar model, recovery names, PDF repair and feature-guide generation. */
class NveShellTest {
    private fun tmp(name: String) = File.createTempFile("nve_$name", ".pdf").also { it.deleteOnExit() }

    @Test fun favouritesToggleMoveLimit() {
        var l = Favorites.parse(" a, b ,a,,c")
        assertEquals(listOf("a", "b", "c"), l)
        l = Favorites.toggle(l, "b"); assertEquals(listOf("a", "c"), l)
        l = Favorites.toggle(l, "d"); assertEquals(listOf("a", "c", "d"), l)
        l = Favorites.move(l, 2, 0); assertEquals(listOf("d", "a", "c"), l)
        assertEquals(l, Favorites.move(l, 0, 9))
        var full = (1..Favorites.MAX).map { "t$it" }
        full = Favorites.toggle(full, "x"); assertEquals(Favorites.MAX, full.size); assertFalse("x" in full)
        assertEquals("d,a,c", Favorites.format(l))
        assertTrue(Favorites.removeAll().isEmpty())
    }

    @Test fun toolbarOrderAndVisibility() {
        val o = ToolbarModel.order(listOf("TOOLS", "BOGUS", "VIEW", "TOOLS"))
        assertEquals("TOOLS", o[0]); assertEquals("VIEW", o[1]); assertEquals(ToolbarModel.DEFAULT_TABS.size, o.size); assertFalse("BOGUS" in o)
        assertEquals(listOf("TOOLS"), ToolbarModel.visible(o, o.toSet()))
        assertFalse("EDIT" in ToolbarModel.visible(o, setOf("EDIT")))
    }

    @Test fun recoveryNamesAndRetention() {
        assertEquals(RecoveryNames.key("/a/b.pdf"), RecoveryNames.key("/a/b.pdf"))
        assertTrue(RecoveryNames.key("/a/b.pdf") != RecoveryNames.key("/a/c.pdf"))
        assertEquals("report (Recovered).pdf", RecoveryNames.recoveredName("report.pdf"))
        val now = 100L * 24 * 3600 * 1000
        val files = listOf("old" to now - 8L * 24 * 3600 * 1000, "new" to now - 1000, "mid" to now - 5000)
        assertEquals(listOf("old"), RecoveryNames.expired(files, now))
        assertEquals(setOf("mid", "old"), RecoveryNames.expired(files, now, keep = 1).toSet())
    }

    /** The bundled TTF (has Latin + Arabic): JVM tests must not touch the standard-14 fonts (PDType1Font static init fails). */
    private val ttf = File("src/main/assets/fonts/NotoNaskhArabic-Regular.ttf")
    private fun font(d: PDDocument) = PDType0Font.load(d, ttf)

    private fun sample(pages: Int): File {
        val f = tmp("src")
        PDDocument().use { d ->
            val font = font(d)
            repeat(pages) { i ->
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs -> cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(50f, 700f); cs.showText("Page ${i + 1}"); cs.endText() }
            }
            d.save(f)
        }
        return f
    }

    @Test fun repairRebuildsBrokenXref() {
        val src = sample(3)
        val bytes = src.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        // cut the file right after the last page object: no xref table, no trailer, no %%EOF
        val cut = text.lastIndexOf("xref").let { if (it > 0) it else bytes.size - 40 }
        val broken = tmp("broken"); broken.writeBytes(bytes.copyOf(cut))
        val out = tmp("repaired")
        val r = PdfRepair.repair(broken, out)
        assertEquals(3, r.pages)
        PDDocument.load(out).use { d ->
            assertEquals(3, d.numberOfPages)
            assertTrue(PDFTextStripper().getText(d).contains("Page 2"))
        }
        assertEquals("a (repaired).pdf", PdfRepair.repairedName("a.pdf"))
    }

    @Test fun trustPolicy() {
        assertEquals("example.com", TrustPolicy.hostOf("https://www.Example.com:8080/a?b#c"))
        assertEquals("docs.example.com", TrustPolicy.hostOf("docs.example.com/x"))
        assertEquals("mailto", TrustPolicy.hostOf("mailto:a@b.c"))
        assertEquals("b.com", TrustPolicy.hostOf("https://user@b.com/"))
        val trusted = setOf("example.com")
        assertEquals(TrustPolicy.Decision.ALLOW, TrustPolicy.link("https://docs.example.com/x", trusted, TrustPolicy.Mode.ASK))
        assertEquals(TrustPolicy.Decision.ASK, TrustPolicy.link("https://evil.com", trusted, TrustPolicy.Mode.ASK))
        assertEquals(TrustPolicy.Decision.BLOCK, TrustPolicy.link("https://evilexample.com", trusted, TrustPolicy.Mode.NEVER))
        assertEquals(TrustPolicy.Decision.ALLOW, TrustPolicy.link("https://evil.com", trusted, TrustPolicy.Mode.ALWAYS))
        assertEquals(TrustPolicy.Decision.ALLOW, TrustPolicy.script("/a.pdf", setOf("/a.pdf"), TrustPolicy.Mode.NEVER))
        assertEquals(TrustPolicy.Decision.ASK, TrustPolicy.script("/b.pdf", setOf("/a.pdf"), TrustPolicy.Mode.ASK))
        assertEquals(TrustPolicy.Decision.BLOCK, TrustPolicy.script("/b.pdf", emptySet(), TrustPolicy.Mode.NEVER))
    }

    @Test fun helpRankNpsDrive() {
        val arts = listOf("Measure distances" to "Use the measure tool to measure area and perimeter.", "Print" to "Print booklet pages.", "Stamps" to "Add stamps.")
        assertEquals(0, com.smartdocs.editor.core.nve.shell.HelpRank.rank(arts, "how do I measure area").first())
        assertTrue(com.smartdocs.editor.core.nve.shell.HelpRank.rank(arts, "zzz").isEmpty())
        val N = com.smartdocs.editor.core.nve.shell.Nps
        assertEquals(Triple(5L, 10, "great / app"), N.decode(N.encode(5L, 12, "great | app")))
        assertEquals(0, N.nps(listOf(10, 0)))
        assertEquals(100, N.nps(listOf(9, 10)))
        assertFalse(N.shouldAsk(3, 0L, 100L * 24 * 3600 * 1000, false))
        assertTrue(N.shouldAsk(9, 0L, 8L * 24 * 3600 * 1000, false))
        assertFalse(N.shouldAsk(9, 0L, 8L * 24 * 3600 * 1000, true))
        val D = com.smartdocs.editor.core.nve.shell.DriveConvert
        assertEquals(D.DOC, D.targetFor("a.DOCX")); assertEquals(D.SHEET, D.targetFor("b.csv")); assertEquals(D.SLIDES, D.targetFor("c.pptx"))
        assertEquals(null, D.targetFor("d.zip")); assertEquals("report.v2", D.googleName("report.v2.docx"))
    }

    @Test fun customizationRoundTrip() {
        val tab = com.smartdocs.editor.core.nve.shell.UserTab("u1", "Work", "work", listOf("merge", "split", "merge"))
        val c = Customization(tabOrder = listOf("TOOLS", "VIEW"), hiddenTabs = setOf("SIGN", "BOGUS"), hiddenChips = setOf("ai"), userTabs = listOf(tab, tab.copy(icon = "nope", id = "u2")),
            favorites = listOf("scan"), favoritesTitle = "Mine", customTabName = null)
        val back = Customization.fromJson(c.toJson())
        assertEquals("TOOLS", back.tabOrder.first()); assertEquals(ToolbarModel.DEFAULT_TABS.size, back.tabOrder.size)
        assertEquals(setOf("SIGN"), back.hiddenTabs); assertEquals(setOf("ai"), back.hiddenChips)
        assertEquals(listOf("merge", "split"), back.userTabs[0].tools); assertEquals("star", back.userTabs[1].icon)
        assertEquals(listOf("scan"), back.favorites); assertEquals("Mine", back.favoritesTitle); assertEquals(null, back.customTabName)
        assertEquals(back.userTabs, Customization.parseTabs(Customization.tabsJson(back.userTabs)))
        assertTrue(runCatching { Customization.fromJson("{\"format\":\"other\"}") }.isFailure)
    }

    private fun pageTexts(f: File): List<String> = PDDocument.load(f).use { d ->
        (1..d.numberOfPages).map { i -> PDFTextStripper().apply { startPage = i; endPage = i }.getText(d).trim() }
    }

    @Test fun insertMergeAndInto() {
        val a = sample(2); val b = sample(3)
        val merged = tmp("merged")
        assertEquals(5, com.smartdocs.editor.core.nve.shell.PageInsert.merge(listOf(a, b), merged))
        val out = tmp("into")
        assertEquals(5, com.smartdocs.editor.core.nve.shell.PageInsert.into(b, a, 1, out))
        assertEquals(listOf("Page 1", "Page 1", "Page 2", "Page 2", "Page 3"), pageTexts(out))
        val end = tmp("end")
        com.smartdocs.editor.core.nve.shell.PageInsert.into(b, a, -1, end)
        assertEquals(listOf("Page 1", "Page 2", "Page 3", "Page 1", "Page 2"), pageTexts(end))
    }

    @Test fun scanPresetsAndTranslations() {
        var l = ScanPreset.upsert(emptyList(), ScanPreset("Office", "A4", "GRAY", 80, true))
        l = ScanPreset.upsert(l, ScanPreset("office", "LETTER", "BW", 60, false))
        assertEquals(1, l.size); assertEquals("LETTER", l[0].paper)
        assertEquals(l, ScanPreset.upsert(l, ScanPreset("good", "A4", "BW", 50, false)))   // built-in name protected
        assertEquals(l, ScanPreset.parse(ScanPreset.format(l + ScanPreset.BUILT_IN)))
        assertEquals("office (2)", ScanPreset.copyOf(l, l[0], "office").name)
        assertEquals(null, ScanPreset.decode("bad"))
        val T = com.smartdocs.editor.core.nve.shell.TranslationHistory
        val E = com.smartdocs.editor.core.nve.shell.TranslationEntry::class
        var h = T.add(emptyList(), com.smartdocs.editor.core.nve.shell.TranslationEntry(1, "ar", "en", "مرحبا", "Hello"))
        h = T.add(h, com.smartdocs.editor.core.nve.shell.TranslationEntry(2, "ar", "en", "مرحبا", "Hi"))
        assertEquals(1, h.size); assertEquals("Hi", h[0].result)
        assertEquals(h, T.fromJson(T.toJson(h)))
        assertEquals(1, T.search(h, "hi").size); assertTrue(T.search(h, "zzz").isEmpty())
        assertNotNull(E)
    }

    @Test fun featureGuidesWritePages() {
        for (g in FeatureGuides.Guide.entries) {
            val out = tmp(g.name)
            val n = FeatureGuides.write(g, out, arabic = { d -> font(d) }, latin = { d -> font(d) })
            PDDocument.load(out).use { d ->
                assertEquals(n, d.numberOfPages)
                when (g) {
                    FeatureGuides.Guide.ORGANIZE -> assertEquals(4, n)
                    FeatureGuides.Guide.FORM -> { val f = d.documentCatalog.acroForm; assertNotNull(f); assertEquals(setOf("name", "agree"), f.fields.map { it.partialName }.toSet()) }
                    FeatureGuides.Guide.COMMENT -> assertEquals(2, d.getPage(0).annotations.size)
                    else -> assertTrue(n >= 1)
                }
            }
        }
    }
}
