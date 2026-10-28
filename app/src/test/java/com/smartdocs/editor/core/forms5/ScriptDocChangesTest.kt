package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.formsx.FieldSpec
import com.smartdocs.editor.core.formsx.FxEditor
import com.smartdocs.editor.core.formsx.FxRect
import com.smartdocs.editor.core.formsx.FxSigLock
import com.smartdocs.editor.core.formsx.FxSigLocks
import com.smartdocs.editor.core.formsx.FxType
import com.smartdocs.editor.core.formsx.FxWidget
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-forms-3: addScript / setLock / addField of form scripts are written on save (8.84). */
class ScriptDocChangesTest {
    @Test fun changesAreWrittenIntoTheDocument() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            FxEditor.create(d, FieldSpec("sig", FxType.SIGNATURE, listOf(FxWidget(0, FxRect(50f, 50f, 200f, 90f)))))
            val ch = ScriptDocChanges(
                scripts = mapOf("init" to "var x = 1;"),
                locks = mapOf("sig" to ScriptDocChanges.lockOf("Include", listOf("name", "date"))),
                // signature fields: no standard-font metrics needed on the JVM
                fields = listOf(ScriptDocChanges.NewField("added", FxType.SIGNATURE, 0, FxRect(50f, 700f, 250f, 720f)),
                    ScriptDocChanges.NewField("offpage", FxType.SIGNATURE, 5, FxRect(0f, 0f, 10f, 10f))),
            )
            assertEquals(3, ch.apply(d))
            assertTrue(DocScripts.named(d).any { it.first == "init" && it.second.contains("var x") })
            val sig = d.documentCatalog.getAcroForm(null).getField("sig").cosObject
            val lock = FxSigLocks.read(sig.getDictionaryObject(COSName.getPDFName("Lock")) as COSDictionary)
            assertEquals(FxSigLock.Mode.INCLUDE, lock.mode); assertEquals(listOf("name", "date"), lock.fields)
            assertNotNull(d.documentCatalog.getAcroForm(null).getField("added"))
            assertNull(d.documentCatalog.getAcroForm(null).getField("offpage"))
        }
    }

    @Test fun parsing() {
        assertEquals(FxType.COMBO, ScriptDocChanges.typeOf("combobox"))
        assertNull(ScriptDocChanges.typeOf("weird"))
        assertEquals(FxRect(1f, 2f, 3f, 4f), ScriptDocChanges.rectOf("1,2,3,4"))
        assertNull(ScriptDocChanges.rectOf("1,2,1,4"))
        assertEquals(FxSigLock.Mode.ALL, ScriptDocChanges.lockOf("All", emptyList()).mode)
        assertTrue(ScriptDocChanges().isEmpty)
    }
}
