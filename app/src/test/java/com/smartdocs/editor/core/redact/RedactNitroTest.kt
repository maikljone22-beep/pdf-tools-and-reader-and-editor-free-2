package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nitro gaps of section 10: code sets, overlay case, sanitize search indexes and tag structure. */
class RedactNitroTest {

    @Test fun codeSetsAddRenameRemove() {
        var sets = listOf(CodeSet(CodeSets.DEFAULT_NAME, listOf("A", "B")))
        val (s2, name) = CodeSets.add(sets, "  Legal ", listOf(" x ", "", "x", "y"))
        assertEquals("Legal", name); assertEquals(listOf("x", "y"), s2.last().codes)
        val (s3, dup) = CodeSets.add(s2, "Legal")
        assertEquals("Legal 2", dup)
        sets = CodeSets.rename(s3, "Legal 2", "HR")
        assertTrue(sets.any { it.name == "HR" })
        assertEquals(sets, CodeSets.rename(sets, "HR", "Legal"))   // taken: refused
        assertEquals(sets, CodeSets.rename(sets, "HR", "  "))      // blank: refused
        sets = CodeSets.remove(sets, "HR")
        assertEquals(listOf(CodeSets.DEFAULT_NAME, "Legal"), sets.map { it.name })
        val one = listOf(CodeSet("only", emptyList()))
        assertEquals(one, CodeSets.remove(one, "only"))              // the last set stays
        assertEquals(listOf("z"), CodeSets.update(sets, "Legal", listOf("z")).first { it.name == "Legal" }.codes)
        assertEquals(sets, CodeSets.fromJson(CodeSets.toJson(sets)))
    }

    @Test fun overlayCase() {
        val s = RedactStyle(overlay = "  top secret محجوب ")
        assertEquals("top secret محجوب", s.shownOverlay())
        assertEquals("TOP SECRET محجوب", s.copy(textCase = RedactStyle.CASE_UPPER).shownOverlay())
        assertEquals("Top Secret محجوب", RedactStyle.applyCase("tOP sECRET محجوب", RedactStyle.CASE_TITLE))
        assertEquals("redacted", RedactStyle.applyCase("REDACTED", RedactStyle.CASE_LOWER))
    }

    private fun docWithIndexAndTags(): PDDocument {
        val doc = PDDocument()
        val page = PDPage(); doc.addPage(page)
        val cat = doc.documentCatalog.cosObject
        cat.setItem(COSName.getPDFName("PieceInfo"), COSDictionary().apply {
            setItem(COSName.getPDFName("AcroSearchIndex"), COSDictionary())
            setItem(COSName.getPDFName("MyApp"), COSDictionary())
        })
        val names = COSArray().apply {
            add(COSString("index.pdx")); add(COSDictionary())
            add(COSString("report.docx")); add(COSDictionary())
        }
        cat.setItem(COSName.NAMES, COSDictionary().apply { setItem(COSName.EMBEDDED_FILES, COSDictionary().apply { setItem(COSName.NAMES, names) }) })
        val p = COSDictionary().apply { setName(COSName.S, "P") }
        val h1 = COSDictionary().apply { setName(COSName.S, "H1") }
        val doc0 = COSDictionary().apply { setName(COSName.S, "Document"); setItem(COSName.K, COSArray().apply { add(h1); add(p) }) }
        cat.setItem(COSName.getPDFName("StructTreeRoot"), COSDictionary().apply { setItem(COSName.K, doc0) })
        cat.setItem(COSName.getPDFName("MarkInfo"), COSDictionary().apply { setBoolean(COSName.getPDFName("Marked"), true) })
        page.cosObject.setItem(COSName.getPDFName("StructParents"), COSInteger.get(0))
        return doc
    }

    @Test fun searchIndexIsFoundAndRemovedAlone() {
        docWithIndexAndTags().use { doc ->
            assertEquals(listOf("PieceInfo /AcroSearchIndex", "index.pdx"), SearchIndex.find(doc))
            val r = Sanitizer.clean(doc, setOf(SanitizeItem.SEARCH_INDEX))
            assertEquals(2, r[SanitizeItem.SEARCH_INDEX])
            val piece = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("PieceInfo")) as COSDictionary
            assertTrue(piece.containsKey(COSName.getPDFName("MyApp")))       // other private data stays
            assertTrue(SearchIndex.find(doc).isEmpty())
            val left = Sanitizer.collectNames(((doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as COSDictionary)
                .getDictionaryObject(COSName.EMBEDDED_FILES) as COSDictionary))
            assertEquals(listOf("report.docx"), left)                       // other attachments stay
        }
    }

    @Test fun structureIsCountedAndRemoved() {
        docWithIndexAndTags().use { doc ->
            val (n, details) = Structure.count(doc)
            assertEquals(3, n); assertTrue(details.contains("H1: 1"))
            assertFalse(SanitizeItem.STRUCTURE in SanitizeItem.DEFAULTS)
            val r = Sanitizer.clean(doc, setOf(SanitizeItem.STRUCTURE))
            assertEquals(3, r[SanitizeItem.STRUCTURE])
            val cat = doc.documentCatalog.cosObject
            assertFalse(cat.containsKey(COSName.getPDFName("StructTreeRoot")))
            assertFalse(cat.containsKey(COSName.getPDFName("MarkInfo")))
            assertFalse(doc.getPage(0).cosObject.containsKey(COSName.getPDFName("StructParents")))
        }
    }

    @Test fun indexFileNames() {
        assertTrue(SearchIndex.isIndexFile("Index.PDX")); assertTrue(SearchIndex.isIndexFile("x.ixa"))
        assertFalse(SearchIndex.isIndexFile("index.pdf")); assertFalse(SearchIndex.isIndexFile("notes.txt"))
    }
}
