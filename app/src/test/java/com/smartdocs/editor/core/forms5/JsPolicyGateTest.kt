package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.FormCalc
import com.smartdocs.editor.core.security.JsPolicy
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Run JavaScript in PDF documents" off: neither the AF pattern emulation nor the Rhino runtime runs a script.
 */
class JsPolicyGateTest {

    @After fun restore() { JsPolicy.enabled = true }

    private fun formDoc(): PDDocument {
        val doc = PDDocument()
        val page = PDPage(); doc.addPage(page)
        val acro = PDAcroForm(doc); doc.documentCatalog.acroForm = acro
        acro.defaultAppearance = "/Helv 0 Tf 0 g"
        // getAcroForm() runs PdfBox's default fix-up, which loads the standard 14 fonts (Android assets) when /DR lacks
        // Helv / ZaDb: plain dictionaries keep that off the JVM
        acro.cosObject.setItem(COSName.DR, COSDictionary().apply {
            setItem(COSName.FONT, COSDictionary().apply { setItem(COSName.getPDFName("Helv"), COSDictionary()); setItem(COSName.getPDFName("ZaDb"), COSDictionary()) })
        })
        fun field(name: String, y: Float, scripts: Map<String, String>) {
            val f = PDTextField(acro); f.partialName = name
            val w = f.widgets[0]; w.rectangle = PDRectangle(50f, y, 200f, 20f); w.page = page
            page.annotations.add(w); acro.fields.add(f)
            if (scripts.isNotEmpty()) f.cosObject.setItem(COSName.AA, COSDictionary().apply {
                scripts.forEach { (k, js) -> setItem(COSName.getPDFName(k), PDActionJavaScript(js).cosObject) }
            })
        }
        field("a", 700f, mapOf("V" to "AFRange_Validate(true, 0, true, 10);"))
        field("b", 660f, emptyMap())
        field("total", 620f, mapOf("C" to "AFSimple_Calculate(\"SUM\", new Array (\"a\", \"b\"));", "F" to "AFNumber_Format(2, 0, 0, 0, \"\", true);"))
        return doc
    }

    @Test fun scriptsLoadWhenEnabled() {
        JsPolicy.enabled = true
        formDoc().use { doc ->
            assertTrue(FormCalc.load(doc).scripts.containsKey("total"))
        }
        val s = FormScripts(FormJs(listOf(JsField("x", "text", "")), mapOf("x" to FieldJs(validate = "event.rc = false;"))))
        assertTrue(s.available())
        assertTrue(s.commit("x", "1", emptyMap()).ran)
    }

    @Test fun nothingRunsWhenDisabled() {
        JsPolicy.enabled = false
        formDoc().use { doc ->
            val calc = FormCalc.load(doc)
            assertTrue(calc.scripts.isEmpty())
            assertTrue(calc.order.isEmpty())
        }
        // the runtime refuses to start even when handed scripts directly
        val s = FormScripts(FormJs(listOf(JsField("x", "text", "")), mapOf("x" to FieldJs(validate = "event.rc = false;"))))
        assertFalse(s.available())
        assertFalse(s.commit("x", "1", emptyMap()).ran)
    }
}
