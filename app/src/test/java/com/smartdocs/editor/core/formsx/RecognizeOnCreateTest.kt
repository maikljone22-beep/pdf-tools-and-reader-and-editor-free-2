package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert: form field recognition when a PDF is created. */
class RecognizeOnCreateTest {
    @Test fun namesAreUniqueOverTheDocument() {
        val used = HashSet<String>()
        assertEquals("Name", FieldRecognition.uniqueName("Name", used))
        assertEquals("Name_2", FieldRecognition.uniqueName("Name", used))
        assertEquals("Name_3", FieldRecognition.uniqueName("Name", used))
        assertEquals("field", FieldRecognition.uniqueName("", used))
    }

    @Test fun aDocumentWithFieldsIsLeftAlone() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            FxEditor.create(d, FieldSpec("existing", FxType.TEXT, listOf(FxWidget(0, FxRect(50f, 700f, 250f, 720f)))))
            assertEquals(0, FieldRecognition.recognizeAll(d))
            assertEquals(1, d.documentCatalog.acroForm.fields.size)
        }
        PDDocument().use { d -> d.addPage(PDPage()); assertEquals(0, FieldRecognition.recognizeAll(d)) }   // an empty page: nothing to find
    }
}
