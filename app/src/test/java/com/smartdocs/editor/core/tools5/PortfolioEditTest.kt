package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage D (Foxit 7): portfolio folders, fields, parts editing, sort; PDF Binder projects. */
class PortfolioEditTest {
    private fun att(name: String, size: Int = 3) = Attachment(name, Embed.mimeOf(name), ByteArray(size) { 1 })

    private fun portfolio(block: (PDDocument) -> Unit) = PDDocument().use { d -> d.addPage(PDPage()); block(d) }

    @Test fun keysAndFolders() {
        assertEquals("a.pdf", PortfolioEdit.keyFor(0, "a.pdf"))
        assertEquals("<3>a.pdf", PortfolioEdit.keyFor(3, "a.pdf"))
        assertEquals(3 to "a.pdf", PortfolioEdit.splitKey("<3>a.pdf"))
        assertEquals(0 to "<x>.pdf", PortfolioEdit.splitKey("<x>.pdf"))
        portfolio { d ->
            assertFalse(PortfolioEdit.read(d).isPortfolio)
            val a = PortfolioEdit.ensureFolder(d, "Contracts/2026")
            val b = PortfolioEdit.ensureFolder(d, "Contracts")
            assertEquals(a, PortfolioEdit.ensureFolder(d, "Contracts/2026"))
            assertTrue(a != b && a > 0 && b > 0)
            assertEquals(listOf("Contracts", "Contracts/2026"), PortfolioEdit.read(d).folders)
            assertTrue(PortfolioEdit.read(d).isPortfolio)
        }
    }

    @Test fun addRenameMoveRemove() {
        portfolio { d ->
            PortfolioEdit.add(d, listOf(att("cover.pdf")))
            val k = PortfolioEdit.add(d, listOf(att("lease.pdf", 10)), "Contracts").single()
            assertTrue(k.startsWith("<") && k.endsWith(">lease.pdf"))
            var info = PortfolioEdit.read(d)
            assertEquals(mapOf("cover.pdf" to "", "lease.pdf" to "Contracts"), info.items.associate { it.name to it.folder })
            PortfolioEdit.setInitial(d, k)
            val k2 = PortfolioEdit.rename(d, k, "lease-2026.pdf")
            info = PortfolioEdit.read(d)
            assertEquals(k2, info.initial)                       // the initial file follows the rename
            assertEquals("Contracts", info.items.first { it.key == k2 }.folder)
            PortfolioEdit.setDescription(d, k2, "Signed copy")
            val k3 = PortfolioEdit.move(d, k2, "")
            assertEquals("lease-2026.pdf", k3)
            // a name already in the folder gets a suffix
            assertEquals("cover_2.pdf", PortfolioEdit.rename(d, k3, "cover.pdf"))
            assertEquals("Signed copy", PortfolioEdit.read(d).items.first { it.key == "cover_2.pdf" }.description)
            assertEquals(1, PortfolioEdit.remove(d, listOf("cover_2.pdf")))
            assertEquals(listOf("cover.pdf"), PortfolioEdit.read(d).items.map { it.key })
            assertNull(PortfolioEdit.read(d).initial)
        }
    }

    @Test fun viewSortFieldsAndValues() {
        portfolio { d ->
            PortfolioEdit.add(d, listOf(att("b10.pdf", 5), att("b2.pdf", 50), att("a.pdf", 20)))
            PortfolioEdit.setView(d, PfView.TILES)
            PortfolioEdit.setFields(d, listOf(PfField("FileName", "Name", "F"), PfField("fAmount", "Amount", "N", visible = false), PfField("fClient", "Client", "S")))
            PortfolioEdit.setValue(d, "b10.pdf", "fAmount", "300"); PortfolioEdit.setValue(d, "b2.pdf", "fAmount", "40")
            PortfolioEdit.setValue(d, "a.pdf", "fAmount", "1200.5"); PortfolioEdit.setValue(d, "a.pdf", "fClient", "Noor")
            PortfolioEdit.setSort(d, "fAmount", false)
            val info = PortfolioEdit.read(d)
            assertEquals(PfView.TILES, info.view)
            assertEquals(listOf("FileName", "fAmount", "fClient"), info.fields.map { it.key })
            assertFalse(info.fields[1].visible)
            assertEquals("fAmount", info.sortKey); assertFalse(info.ascending)
            assertEquals("Noor", info.items.first { it.key == "a.pdf" }.values["fClient"])
            assertEquals(listOf("a.pdf", "b10.pdf", "b2.pdf"), PortfolioEdit.sorted(info.items, info.sortKey, info.ascending).map { it.key })
            assertEquals(listOf("a.pdf", "b2.pdf", "b10.pdf"), PortfolioEdit.sorted(info.items, "F", true).map { it.key })
            assertEquals(listOf("b2.pdf", "a.pdf", "b10.pdf"), PortfolioEdit.sorted(info.items, "Size", false).map { it.key })
        }
    }

    @Test fun binderProjectRoundTrip() {
        val dir = File("/tmp/binder")
        val p = BinderProject("Case | 7", CombineOpts(bookmarkPerFile = true, keepBookmarks = false, headings = true, toc = true, tocTitle = "Index", tocLevels = 3),
            listOf(CombineEntry(File(dir, "a.pdf"), "Contract", "1-3"), CombineEntry(File(dir, "photo.jpg"), "Photo", "", attach = true)))
        val back = BinderProject.decode(p.encode(), dir)
        assertEquals(p, back)
    }
}
