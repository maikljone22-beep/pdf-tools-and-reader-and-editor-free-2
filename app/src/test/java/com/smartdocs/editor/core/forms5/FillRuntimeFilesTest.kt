package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.edit5.PageTemplates
import com.smartdocs.editor.core.forms.XfaSupport
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** File round trips of the fill-time runtime: XFA row removal, field visibility, page templates rename / replace. */
class FillRuntimeFilesTest {

    private fun tmp(name: String) = File.createTempFile("frt_", "_$name").apply { deleteOnExit() }

    private fun xfaDoc(rows: Int): File {
        val f = tmp("xfa.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage())
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            val data = buildString {
                append("<xfa:datasets xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\"><xfa:data><form1>")
                for (i in 1..rows) append("<row><a>v$i</a></row>")
                append("</form1></xfa:data></xfa:datasets>")
            }
            val st = d.document.createCOSStream()
            st.createOutputStream().use { it.write(data.toByteArray()) }
            form.cosObject.setItem(COSName.XFA, COSArray().apply { add(COSString("datasets")); add(st) })
            d.save(f)
        }
        return f
    }

    private fun datasets(f: File): String = PDDocument.load(f).use { d ->
        val arr = d.documentCatalog.acroForm.cosObject.getDictionaryObject(COSName.XFA) as COSArray
        (arr.getObject(1) as COSStream).createInputStream().use { String(it.readBytes()) }
    }

    @Test fun xfaRemoveInstanceKeepsAtLeastOne() = runBlocking<Unit> {
        val src = xfaDoc(3)
        val out = tmp("out.pdf")
        XfaSupport.removeInstance(src, "form1", "row", -1, out)
        val x = datasets(out)
        assertTrue(x.contains("v1")); assertTrue(x.contains("v2")); assertFalse(x.contains("v3"))
        val out2 = tmp("out2.pdf")
        XfaSupport.removeInstance(out, "form1", "row", 0, out2)
        val y = datasets(out2)
        assertFalse(y.contains("v1")); assertTrue(y.contains("v2"))
        try { XfaSupport.removeInstance(out2, "form1", "row", -1, tmp("out3.pdf")); fail("the last row must stay") } catch (_: IllegalStateException) {}
    }

    @Test fun hiddenFieldsAreWrittenToWidgetFlags() = runBlocking<Unit> {
        val f = tmp("vis.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage())
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            val parent = PDNonTerminalField(form); parent.partialName = "addr"
            fun text(name: String, y: Float): PDTextField {
                val t = PDTextField(form); t.partialName = name
                val w = PDAnnotationWidget(); w.rectangle = PDRectangle(10f, y, 100f, 20f); w.page = d.getPage(0)
                t.widgets = listOf(w); d.getPage(0).annotations.add(w)
                return t
            }
            val street = text("street", 10f); val city = text("city", 40f)
            parent.children = listOf(street, city)
            street.cosObject.setItem(COSName.PARENT, parent.cosObject); city.cosObject.setItem(COSName.PARENT, parent.cosObject)
            val other = text("other", 70f)
            other.widgets.first().isHidden = true
            form.fields = listOf(parent, other)
            d.save(f)
        }
        FieldVisibility.applyTo(f, null, mapOf("addr" to true, "other" to false))
        PDDocument.load(f).use { d ->
            val acro = d.documentCatalog.acroForm
            assertTrue((acro.getField("addr.street") as PDTextField).widgets.first().isHidden)
            assertTrue((acro.getField("addr.city") as PDTextField).widgets.first().isHidden)
            assertFalse((acro.getField("other") as PDTextField).widgets.first().isHidden)
        }
    }

    @Test fun pageTemplatesRenameAndReplace() = runBlocking<Unit> {
        val src = tmp("tpl.pdf")
        PDDocument().use { d -> repeat(3) { d.addPage(PDPage()) }; d.save(src) }
        val a = tmp("a.pdf"); PageTemplates.save(src, a, null, 0, "First", hide = false)
        val b = tmp("b.pdf"); PageTemplates.save(a, b, null, 1, "Hidden", hide = true)
        val c = tmp("c.pdf"); PageTemplates.rename(b, c, null, "First", "Cover")
        var list = PageTemplates.list(c, null)
        assertEquals(setOf("Cover", "Hidden"), list.map { it.name }.toSet())
        assertEquals(0, list.first { it.name == "Cover" }.pageIndex)
        val e = tmp("e.pdf"); PageTemplates.replaceWithPage(c, e, null, "Cover", 1)
        list = PageTemplates.list(e, null)
        assertEquals(1, list.first { it.name == "Cover" }.pageIndex)
        val g = tmp("g.pdf"); PageTemplates.replaceWithPage(e, g, null, "Hidden", 0)
        list = PageTemplates.list(g, null)
        assertFalse(list.first { it.name == "Hidden" }.visible)
        assertEquals(2, PDDocument.load(g).use { it.numberOfPages })
    }
}
