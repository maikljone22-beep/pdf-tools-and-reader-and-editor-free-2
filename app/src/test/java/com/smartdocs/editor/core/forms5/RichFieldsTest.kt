package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.annot.AnnotStyle
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-forms-2: rich-text fields keep /RV in step with the filled value (8.28). */
class RichFieldsTest {
    private fun form(rich: Boolean, rv: String?): Pair<PDDocument, PDAcroForm> {
        val d = PDDocument(); d.addPage(PDPage())
        val acro = PDAcroForm(d); d.documentCatalog.acroForm = acro
        val f = PDTextField(acro); f.partialName = "note"
        if (rich) f.cosObject.setInt(COSName.FF, 1 shl 25)
        rv?.let { f.cosObject.setString(COSName.getPDFName("RV"), it) }
        acro.fields.add(f)
        return d to acro
    }

    @Test fun rvFollowsTheValueAndTheChosenStyle() {
        val (d, acro) = form(true, "<body xmlns=\"http://www.w3.org/1999/xhtml\" style=\"font-size:11.0pt;font-style:italic\"><p>old text</p></body>")
        d.use {
            val f = acro.getField("note")
            assertTrue(RichFields.isRich(f))
            assertTrue(RichFields.styleOf(f.cosObject)!!.italic)
            // no fill-time style: the field's own style, new text
            assertEquals(1, RichFields.updateIn(acro, mapOf("note" to "new text"), emptyMap()))
            var rv = f.cosObject.getString(COSName.getPDFName("RV"))
            assertTrue(rv.contains("new text") && !rv.contains("old text") && rv.contains("italic"))
            // chosen while filling: bold
            RichFields.updateIn(acro, mapOf("note" to "bold one"), mapOf("note" to AnnotStyle.Rich(12f, 0, bold = true, italic = false)))
            rv = f.cosObject.getString(COSName.getPDFName("RV"))
            assertTrue(rv.contains("bold one") && rv.contains("font-weight:bold"))
            assertTrue(f.cosObject.getString(COSName.getPDFName("DS")).contains("bold"))
        }
    }

    @Test fun plainFieldsAndStylelessRichFieldsAreLeftAlone() {
        val (d, acro) = form(false, null)
        d.use { assertEquals(0, RichFields.updateIn(acro, mapOf("note" to "x"), mapOf("note" to AnnotStyle.Rich(12f, 0, true, false)))) }
        val (d2, acro2) = form(true, null)
        d2.use {
            assertEquals(0, RichFields.updateIn(acro2, mapOf("note" to "x"), emptyMap()))
            assertFalse(acro2.getField("note").cosObject.containsKey(COSName.getPDFName("RV")))
        }
    }

    @Test fun appearanceFaceAndUnderline() {
        val bold = AnnotStyle.Rich(12f, 0, bold = true, italic = false)
        assertEquals("/HeBo 10 Tf 0 g", RichFields.daFor("/Helv 10 Tf 0 g", bold))
        assertEquals("/HeBO 0 Tf 0 g", RichFields.daFor("/Helv 0 Tf 0 g", bold.copy(italic = true)))
        assertEquals("/HeOb 9 Tf 1 0 0 rg", RichFields.daFor("/Helv 9 Tf 1 0 0 rg", bold.copy(bold = false, italic = true)))
        assertEquals("/Helv 10 Tf 0 g", RichFields.daFor("/HeBo 10 Tf 0 g", bold.copy(bold = false)))   // our face switched off
        assertEquals("/TiRo 10 Tf 0 g", RichFields.daFor("/TiRo 10 Tf 0 g", bold.copy(bold = false)))   // the field's own font stays
        val content = listOf("/Tx BMC", "q", "BT", "/HeBo 12 Tf", "0 g", "2 5.5 Td", "(Hello) Tj", "ET", "Q", "EMC").joinToString("\n")
        val u = RichFields.underlined(content, 30f)!!
        assertTrue(u.startsWith(content))
        assertTrue(u.contains("2.00 4.06 m 32.00 4.06 l S"))
        assertEquals(null, RichFields.underlined("/Tx BMC EMC", 10f))
    }

    @Test fun syntheticFacesForTheArabicFont() {
        val content = listOf("/Tx BMC", "q", "BT", "/SDAr 12 Tf", "0.2 0.4 0.6 rg", "40 5.5 Td", "(abc) Tj", "ET", "Q", "EMC").joinToString("\n")
        val b = RichFields.synthetic(content, bold = true, italic = false, boxH = 20f)
        assertTrue(b.contains("BT\n2 Tr 0.400 w 0.2 0.4 0.6 RG"))      // fill + stroke in the text colour
        assertFalse(b.contains(" cm"))
        val i = RichFields.synthetic(content, bold = false, italic = true, boxH = 20f)
        assertTrue(i.contains("q 1 0 0.2 1 -2.000 0 cm\nBT"))          // shear around the middle of the box
        assertTrue(i.contains("ET\nQ"))
        val both = RichFields.synthetic(content, bold = true, italic = true, boxH = 20f)
        assertTrue(both.indexOf(" cm") < both.indexOf("2 Tr") && both.indexOf("2 Tr") < both.indexOf("Tj"))
        assertEquals(content, RichFields.synthetic(content, bold = false, italic = false, boxH = 20f))
        assertEquals("/Tx BMC EMC", RichFields.synthetic("/Tx BMC EMC", bold = true, italic = true, boxH = 20f))
    }
}
