package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * audit 5 E-4 / E-6 / E-7: page operations keep the attributes a page inherits from its /Pages ancestors (resources,
 * media box, rotation), imported pages bring their form fields, deleted pages take their fields along.
 */
class PageInheritTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tagKey = COSName.getPDFName("SDTest")

    /**
     * [n] pages under an intermediate /Pages node that holds the /Resources (a font), the /MediaBox and the /Rotate;
     * the pages themselves have none of them. Each page carries a test tag "<tag><i>".
     */
    private fun nested(name: String, n: Int, w: Float, h: Float, rot: Int, tag: String): File {
        val f = tmp.newFile(name)
        PDDocument().use { d ->
            repeat(n) { d.addPage(PDPage()) }
            val root = d.pages.cosObject
            val kids = root.getDictionaryObject(COSName.KIDS) as COSArray
            // a raw font dictionary as /F1: PDType1Font's standard-14 init cannot run on the JVM
            val res = PDResources(); res.cosObject.setItem(COSName.FONT, COSDictionary().apply {
                setItem(COSName.getPDFName("F1"), COSDictionary().apply { setItem(COSName.TYPE, COSName.FONT); setName(COSName.SUBTYPE, "Type1"); setName(COSName.BASE_FONT, "Helvetica") })
            })
            val inter = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES); setInt(COSName.COUNT, n); setItem(COSName.PARENT, root)
                setItem(COSName.RESOURCES, res.cosObject); setItem(COSName.MEDIA_BOX, PDRectangle(w, h).cosArray); setInt(COSName.ROTATE, rot)
                setItem(COSName.KIDS, COSArray().apply { (0 until kids.size()).forEach { add(kids.getObject(it)) } })
            }
            (0 until kids.size()).forEach { i ->
                (kids.getObject(i) as COSDictionary).apply {
                    setItem(COSName.PARENT, inter); removeItem(COSName.MEDIA_BOX); removeItem(COSName.RESOURCES); removeItem(COSName.ROTATE)
                    setString(tagKey, "$tag$i")
                }
            }
            root.setItem(COSName.KIDS, COSArray().apply { add(inter) })
            assertEquals(rot, d.getPage(0).rotation)
            d.save(f)
        }
        return f
    }

    private fun tags(d: PDDocument) = (0 until d.numberOfPages).map { d.getPage(it).cosObject.getString(tagKey) }

    /** Every page has the font resource, the size and the rotation of its source tree. */
    private fun assertInherited(d: PDDocument, pages: IntRange, w: Float, rot: Int) {
        for (i in pages) {
            val p = d.getPage(i)
            assertTrue("page $i keeps its font", p.resources?.fontNames?.any { it.name == "F1" } == true)
            assertEquals("page $i width", w, p.mediaBox.width, 0.01f)
            assertEquals("page $i rotation", rot, p.rotation)
        }
    }

    @Test fun reverseKeepsInheritedAttributes() = runBlocking<Unit> {
        val src = nested("rev.pdf", 3, 300f, 400f, 90, "a")
        val out = File(tmp.root, "rev_out.pdf")
        PageOps5.reverse(src, out, null)
        PDDocument.load(out).use { d ->
            assertEquals(listOf("a2", "a1", "a0"), tags(d))
            assertInherited(d, 0..2, 300f, 90)
        }
    }

    @Test fun moveKeepsInheritedAttributes() = runBlocking<Unit> {
        val src = nested("move.pdf", 4, 300f, 400f, 90, "a")
        val out = File(tmp.root, "move_out.pdf")
        PageOrganize.arrange(src, out, null, PageOrganize.moveOrder(4, listOf(3), 0))
        PDDocument.load(out).use { d ->
            assertEquals(listOf("a3", "a0", "a1", "a2"), tags(d))
            assertInherited(d, 0..3, 300f, 90)
        }
    }

    @Test fun interleaveKeepsInheritedAttributesOfBothFiles() = runBlocking<Unit> {
        val a = nested("ia.pdf", 2, 300f, 400f, 90, "a")
        val b = nested("ib.pdf", 2, 500f, 600f, 180, "b")
        val out = File(tmp.root, "inter_out.pdf")
        PageOps5.interleave(a, b, out, null, null, reverseB = false)
        PDDocument.load(out).use { d ->
            assertEquals(listOf("a0", "b0", "a1", "b1"), tags(d))
            assertInherited(d, 0..0, 300f, 90); assertInherited(d, 2..2, 300f, 90)
            assertInherited(d, 1..1, 500f, 180); assertInherited(d, 3..3, 500f, 180)
        }
    }

    @Test fun copyPagesBringsInheritedResources() = runBlocking<Unit> {
        val a = nested("ca.pdf", 2, 300f, 400f, 90, "a")
        val b = nested("cb.pdf", 2, 500f, 600f, 180, "b")
        val out = File(tmp.root, "copy_out.pdf")
        PageOps5.copyPages(a, out, null, b, null, listOf(1), 1)
        PDDocument.load(out).use { d ->
            assertEquals(listOf("a0", "b1", "a1"), tags(d))
            assertInherited(d, 1..1, 500f, 180)
            assertInherited(d, 0..0, 300f, 90); assertInherited(d, 2..2, 300f, 90)
        }
    }

    @Test fun arrangeThatDropsPagesKeepsInheritedAttributes() {
        val src = nested("drop.pdf", 3, 300f, 400f, 90, "a")
        PDDocument.load(src).use { d ->
            PageOps5.arrange(d, listOf(2, 0))
            assertEquals(listOf("a2", "a0"), tags(d))
            assertInherited(d, 0..1, 300f, 90)
        }
    }

    // ---------------- E-6 / E-7 form fields ----------------

    private fun withField(name: String, fieldName: String, pages: Int = 2): File {
        val f = tmp.newFile(name)
        PDDocument().use { d ->
            repeat(pages) { d.addPage(PDPage(PDRectangle.A4)) }
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            val field = PDTextField(form).apply { partialName = fieldName }
            val w = field.widgets[0]; w.rectangle = PDRectangle(10f, 10f, 80f, 20f); w.page = d.getPage(0)
            d.getPage(0).annotations.add(w); form.fields.add(field)
            d.save(f)
        }
        return f
    }

    @Test fun copiedPagesMergeTheirFieldsWithUniqueNames() = runBlocking<Unit> {
        val a = withField("fa.pdf", "name")
        val b = withField("fb.pdf", "name")
        val out = File(tmp.root, "fields_out.pdf")
        PageOps5.copyPages(a, out, null, b, null, listOf(0), 2)
        PDDocument.load(out).use { d ->
            val form = d.documentCatalog.acroForm
            assertNotNull(form)
            val names = form!!.fields.map { it.fullyQualifiedName }.sorted()
            assertEquals(listOf("name", "name_2"), names)
            // the imported widget is on the imported page and belongs to the merged field
            val imported = d.getPage(2).annotations.filterIsInstance<PDAnnotationWidget>()
            assertEquals(1, imported.size)
            assertEquals(form.getField("name_2").widgets[0].cosObject, imported[0].cosObject)
        }
    }

    @Test fun deletingAPagePrunesItsFields() {
        val a = withField("del.pdf", "gone")
        PDDocument.load(a).use { d ->
            PageOrganize.deleteIn(d, listOf(0), removeBookmarks = false)
            assertEquals(1, d.numberOfPages)
            assertNull(d.documentCatalog.acroForm?.getField("gone"))
        }
    }
}
