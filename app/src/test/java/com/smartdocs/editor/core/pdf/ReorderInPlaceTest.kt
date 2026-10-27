package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit AN-22: reorder / move / paste keep the outline, the AcroForm, page labels and the encryption. */
class ReorderInPlaceTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Three pages told apart by their width (100, 200, 300); outline → page 2; a text field on page 3. */
    private fun sample(encrypt: Boolean): File {
        val f = tmp.newFile("sample$encrypt.pdf")
        PDDocument().use { d ->
            val pages = (1..3).map { PDPage(PDRectangle(it * 100f, 500f)).also(d::addPage) }
            val outline = PDDocumentOutline()
            outline.addLast(PDOutlineItem().apply { title = "Chapter 2"; destination = PDPageFitDestination().apply { page = pages[1] } })
            d.documentCatalog.documentOutline = outline
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            val field = PDTextField(form).apply { partialName = "name" }
            val w = field.widgets[0]; w.rectangle = PDRectangle(10f, 10f, 80f, 20f); w.page = pages[2]
            pages[2].annotations.add(w); form.fields.add(field)
            pages[0].annotations.add(PDAnnotationText().apply { contents = "note on page 1" })
            d.documentCatalog.pageLabels = PDPageLabels(d).apply { setLabelItem(0, PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_ROMAN_LOWER }) }
            if (encrypt) d.protect(StandardProtectionPolicy("owner-pw", "user-pw", AccessPermission().apply { setCanPrint(false) }).apply { encryptionKeyLength = 128; setPreferAES(true) })
            d.save(f)
        }
        return f
    }

    private fun widths(d: PDDocument) = (0 until d.numberOfPages).map { d.getPage(it).mediaBox.width.toInt() }

    @Test fun reorderKeepsOutlineFormLabelsAndEncryption() {
        val src = sample(encrypt = true)
        val out = File(tmp.root, "out.pdf")
        runBlocking { PdfOps.reorder(src, listOf(2, 0, 1), out, "user-pw") }
        PDDocument.load(out, "user-pw").use { d ->
            assertTrue("still encrypted", d.isEncrypted)
            assertEquals(false, d.currentAccessPermission.canPrint())
            assertEquals(listOf(300, 100, 200), widths(d))
            val item = d.documentCatalog.documentOutline!!.firstChild
            assertEquals("Chapter 2", item.title)
            assertEquals("outline follows its page", 200, (item.destination as PDPageFitDestination).page.mediaBox.width.toInt())
            val field = d.documentCatalog.acroForm!!.getField("name")
            assertNotNull(field)
            val widget = field.widgets[0]
            assertEquals("widget stays on its page", 300, widget.page.mediaBox.width.toInt())
            assertTrue(d.getPage(0).annotations.any { it.cosObject == widget.cosObject })
            assertNotNull("page labels kept", d.documentCatalog.pageLabels)
        }
    }

    @Test fun pasteDuplicatesWithoutSharingWidgets() {
        val src = sample(encrypt = false)
        val out = File(tmp.root, "paste.pdf")
        runBlocking { PdfOps.reorder(src, listOf(0, 1, 2, 2, 0), out) }
        PDDocument.load(out).use { d ->
            assertEquals(listOf(100, 200, 300, 300, 100), widths(d))
            assertTrue(d.getPage(3).annotations.none { it is PDAnnotationWidget })
            assertEquals(1, d.documentCatalog.acroForm!!.getField("name").widgets.size)
            assertEquals(listOf("note on page 1"), d.getPage(4).annotations.map { it.contents })
            assertEquals("copied annotation points at the copy", d.getPage(4).cosObject, d.getPage(4).annotations[0].cosObject.getDictionaryObject(COSName.P))
            assertNotNull(d.documentCatalog.documentOutline?.firstChild)
        }
    }

    @Test fun nestedTreeKeepsInheritedAttributes() {
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle(100f, 100f))); d.addPage(PDPage(PDRectangle(200f, 200f)))
            // root → intermediate /Pages (with /Rotate 90 and the resources) → the two pages
            val root = d.pages.cosObject
            val kids = root.getDictionaryObject(COSName.KIDS) as COSArray
            val inter = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES); setInt(COSName.ROTATE, 90); setInt(COSName.COUNT, 2)
                setItem(COSName.PARENT, root); setItem(COSName.KIDS, COSArray().apply { (0 until kids.size()).forEach { add(kids.getObject(it)) } })
            }
            (0 until kids.size()).forEach { (kids.getObject(it) as COSDictionary).apply { setItem(COSName.PARENT, inter); removeItem(COSName.ROTATE) } }
            root.setItem(COSName.KIDS, COSArray().apply { add(inter) })
            assertEquals(90, d.getPage(0).rotation)
            PdfOps.arrangeInPlace(d, listOf(1, 0))
            assertEquals(2, d.numberOfPages)
            assertEquals(listOf(200, 100), widths(d))
            assertEquals(listOf(90, 90), (0..1).map { d.getPage(it).rotation })
        }
    }
}
