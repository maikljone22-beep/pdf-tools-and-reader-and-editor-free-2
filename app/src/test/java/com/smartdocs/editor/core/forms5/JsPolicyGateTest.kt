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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Run JavaScript in PDF documents" off: no field script, AF emulation, validation or document script reaches a runtime. */
class JsPolicyGateTest {

    @After fun restore() { JsPolicy.enabled = true }

    private fun formDoc(): PDDocument {
        val doc = PDDocument()
        val page = PDPage(); doc.addPage(page)
        val acro = PDAcroForm(doc); doc.documentCatalog.acroForm = acro
        acro.defaultAppearance = "/Helv 0 Tf 0 g"
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
            val m = FormModelLoader.load(doc)
            assertTrue(m.js.hasScripts)
            assertNotNull(m.widgets.first { it.name == "a" }.validate)
        }
    }

    @Test fun nothingRunsWhenDisabled() {
        JsPolicy.enabled = false
        formDoc().use { doc ->
            assertTrue(FormCalc.load(doc).scripts.isEmpty())
            val m = FormModelLoader.load(doc)
            assertFalse(m.js.hasScripts)
            assertTrue(m.calc.scripts.isEmpty())
            assertNull(m.widgets.first { it.name == "a" }.validate)
            assertTrue(m.values.containsKey("total"))       // the fields themselves stay fillable
        }
        // the runtime refuses to start even when handed scripts directly
        val s = FormScripts(FormJs(listOf(JsField("x", "text", "")), mapOf("x" to FieldJs(validate = "event.rc = false;"))))
        assertFalse(s.available())
        assertFalse(s.commit("x", "1", emptyMap()).ran)
    }
}
