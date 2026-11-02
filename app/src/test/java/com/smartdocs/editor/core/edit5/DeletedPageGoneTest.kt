package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Hostile review E3: a deleted page is not written into the saved file through the tag tree or a destination. */
class DeletedPageGoneTest {
    @Test fun deletedPageContentIsNotSaved() {
        val doc = PDDocument()
        val keep = PDPage(); val secret = PDPage()
        doc.addPage(keep); doc.addPage(secret)
        PDPageContentStream(doc, secret, PDPageContentStream.AppendMode.OVERWRITE, false).use { cs -> cs.addComment("SECRET-PAGE-MARKER") }
        // a tag-tree element and a named destination that point at the secret page
        val elem = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.PG, secret.cosObject) }
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")); setItem(COSName.K, COSArray().apply { add(elem) }) }
        doc.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT, root)
        val dests = COSDictionary().apply { setItem("sec", COSArray().apply { add(secret.cosObject); add(COSName.getPDFName("Fit")) }) }
        doc.documentCatalog.cosObject.setItem(COSName.DESTS, dests)
        doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, COSArray().apply { add(secret.cosObject); add(COSName.getPDFName("Fit")) })

        PageOrganize.deleteIn(doc, listOf(1))
        val out = ByteArrayOutputStream(); doc.save(out); doc.close()
        val bytes = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertFalse("the deleted page's content was still written", bytes.contains("SECRET-PAGE-MARKER"))
        PDDocument.load(out.toByteArray()).use { d -> assertTrue(d.numberOfPages == 1) }
    }
}
