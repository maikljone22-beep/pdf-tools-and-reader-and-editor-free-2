package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FxArabicTest {
    private val font = listOf("src/main/assets/fonts/NotoNaskhArabic-Regular.ttf", "app/src/main/assets/fonts/NotoNaskhArabic-Regular.ttf").map(::File).first { it.exists() }

    @Before fun setUp() { FxAppearance.arabicFontSource = { font.inputStream() } }
    @After fun tearDown() { FxAppearance.arabicFontSource = null }

    @Test fun arabicTextPicksTheEmbeddedFace() {
        assertEquals(FxFont.ARABIC, FxAppearance.fontFor(FxFont.HELVETICA, "إرسال"))
        assertEquals(FxFont.TIMES, FxAppearance.fontFor(FxFont.TIMES, "Send"))
        assertEquals(FxFont.ZAPF, FxAppearance.fontFor(FxFont.ZAPF, "إرسال"))
    }

    /**
     * PdfBox-Android finds the Identity-H cmap only on a device (Android assets), so the JVM cannot embed the face:
     * this checks the fallback — the field is still written, with a standard font, and nothing throws.
     * The embedded-face drawing is checked on a device.
     */
    @Test fun withoutTheArabicFaceTheFieldFallsBackToAStandardFont() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            val spec = FieldSpec(name = "b", type = FxType.BUTTON, widgets = listOf(FxWidget(0, FxRect(100f, 100f, 220f, 130f))), caption = "إرسال الطلب")
            val name = FxEditor.create(d, spec)
            val w = FxEditor.field(d, name)!!.widgets.first().cosObject
            val n = ((w.getDictionaryObject(COSName.AP) as COSDictionary).getDictionaryObject(COSName.N)) as COSStream
            val content = n.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue(content, content.contains("/Helv"))
            assertEquals(FxFont.HELVETICA, FxEditor.read(d, FxEditor.field(d, name)!!).font)
        }
    }
}
