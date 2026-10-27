package com.smartdocs.editor.core.edit

import com.smartdocs.editor.ui.nve.NveDigits
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.NveTestFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NVE-text: rich text writer, text helpers, kerning removal and form rewriting, round-tripped through saved PDFs.
 * pdfbox-android's standard-14 and TrueType fonts need Android assets, so every font slot of the writer gets the
 * test font [NveTestFont] (no PDType1Font / PDType0Font is ever touched here).
 */
class NveTextTest {
    private val font: PDFont = NveTestFont()
    private val loader: () -> PDFont = { font }
    private val std: (FontFamily, Boolean, Boolean) -> PDFont = { _, _, _ -> font }

    /** Writes [text] with [style] on a fresh page, saves, reloads and returns the page content tokens. */
    private fun write(text: String, style: EditTextStyle, width: Float = 400f): List<Any> {
        val f = File.createTempFile("nvet", ".pdf").also { it.deleteOnExit() }
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            RichWriter.write(d, p, Affine.identity(), null, text, style, 50f, 50f + width, 700f, false, loader, loader, std)
            d.save(f)
        }
        return PDDocument.load(f).use { d -> PDFStreamParser(d.getPage(0)).let { it.parse(); it.tokens } }
    }

    private fun <T> withLayout(style: EditTextStyle, block: (RichLayout) -> T): T = block(RichLayout(RichFonts(null, style, loader, loader, std), style))

    private fun ops(tokens: List<Any>) = tokens.filterIsInstance<Operator>().map { it.name }

    /** Operands right before every [op]. */
    private fun operandsOf(tokens: List<Any>, op: String): List<Float> =
        tokens.indices.filter { tokens[it] is Operator && (tokens[it] as Operator).name == op && it > 0 }.mapNotNull { (tokens[it - 1] as? COSNumber)?.floatValue() }

    @Test fun plainStyleIsNotRich() {
        assertFalse(RichText.isRich(EditTextStyle()))
        assertTrue(RichText.isRich(EditTextStyle(align = TextAlign.JUSTIFY)))
        assertTrue(RichText.isRich(EditTextStyle(runs = listOf(RunStyle(0, 1, bold = true)))))
        assertTrue(RichText.isRich(EditTextStyle(fontPath = "asset:x.ttf")))
    }

    @Test fun spacingScalingRiseAreWritten() {
        val t = write("Hello world", EditTextStyle(size = 12f, charSpacing = 1.5f, hScale = 80f, rise = 3f, wordSpacing = 4f))
        assertEquals(1.5f, operandsOf(t, "Tc").first(), 0.001f)
        assertEquals(80f, operandsOf(t, "Tz").first(), 0.001f)
        assertEquals(3f, operandsOf(t, "Ts").first(), 0.001f)
        // word spacing is a TJ adjustment after the space (Tw would be ignored by multi-byte fonts)
        val tj = t.filterIsInstance<COSArray>().first()
        val items = (0 until tj.size()).map { tj.getObject(it) }
        assertEquals(2, items.count { it is COSString })
        assertEquals(-4f * 1000f / 12f, items.filterIsInstance<COSNumber>().single().floatValue(), 0.01f)
    }

    @Test fun strokeAndInvisibleRenderingModes() {
        assertTrue(operandsOf(write("Ab", EditTextStyle(stroke = true, strokeColor = 0xFF0000, strokeWidth = 0.7f)), "Tr").contains(2f))
        assertTrue(operandsOf(write("Ab", EditTextStyle(stroke = true, fillText = false)), "Tr").contains(1f))
        assertTrue(operandsOf(write("Ab", EditTextStyle(invisible = true)), "Tr").contains(3f))
    }

    @Test fun underlineAndStrikeAreLines() {
        val t = write("Under", EditTextStyle(underline = true, strike = true))
        assertEquals(2, ops(t).count { it == "S" })
        assertTrue(ops(t).contains("l"))
    }

    @Test fun perRunStylesSplitRuns() {
        val t = write("plain bold", EditTextStyle(size = 10f, runs = listOf(RunStyle(6, 10, bold = true, size = 14f, script = Script.SUPER))))
        val sizes = operandsOf(t, "Tf")
        assertTrue(sizes.contains(10f))
        assertTrue(sizes.any { kotlin.math.abs(it - 14f * 0.65f) < 0.01f })        // superscript is smaller
        assertTrue(operandsOf(t, "Tr").contains(2f))                                  // synthetic bold for the bold run
        assertTrue(operandsOf(t, "Ts").any { it > 0f })                               // raised
        assertEquals(2, ops(t).count { it == "BT" })
    }

    @Test fun bulletsNumbersAndIndent() {
        val lines = withLayout(EditTextStyle(list = ListKind.NUMBER, numbering = NumberFormat.UPPER_ROMAN, indent = 10f)) { it.layout("one\ntwo", 300f, false) }
        assertEquals(listOf("I. one", "II. two"), lines.map { it.logical })
        assertTrue(lines.all { it.indent == 10f })
        val b = withLayout(EditTextStyle(list = ListKind.BULLET, bullet = "\u2022")) { it.layout("x\ny", 300f, false) }
        assertTrue(b.all { it.logical.startsWith("\u2022 ") })
        // wrapped list items get a hanging indent
        val w = withLayout(EditTextStyle(size = 10f, list = ListKind.BULLET)) { it.layout("aaa bbb ccc ddd eee fff ggg hhh", 60f, false) }
        assertTrue(w.size > 1 && w[0].indent == 0f && w[1].indent > 0f)
    }

    @Test fun justifyFillsTheLine() {
        val style = EditTextStyle(size = 12f, align = TextAlign.JUSTIFY)
        val text = "aaa bbb ccc ddd eee fff ggg hhh iii jjj kkk lll mmm nnn ooo ppp"
        val lines = withLayout(style) { it.layout(text, 150f, false) }
        assertTrue(lines.size > 1)
        assertTrue(lines.last().lastOfPara)
        // every line but the last one gets justification gaps (TJ arrays with numbers)
        val t = write(text, style, 150f)
        val arrays = t.filterIsInstance<COSArray>().filter { a -> (0 until a.size()).any { a.getObject(it) is COSNumber } }
        assertEquals(lines.size - 1, arrays.size)
    }

    @Test fun rotationIsAppliedToTheBox() {
        assertTrue(ops(write("Rot", EditTextStyle(rotation = 90f))).count { it == "cm" } >= 2)
    }

    @Test fun verticalTextStacksGlyphs() {
        assertEquals(3, ops(write("ABC", EditTextStyle(vertical = true))).count { it == "Tj" })
    }

    @Test fun rtlOverrideAndParagraphSpacing() {
        val st = EditTextStyle(rtlOverride = true, paraSpacing = 6f)
        val lines = withLayout(st) { it.layout("ab\ncd", 300f, false) }
        assertTrue(lines.all { it.rtl })
        val h0 = withLayout(EditTextStyle()) { l -> l.height(l.layout("ab\ncd", 300f, false)) }
        val h1 = withLayout(st) { l -> l.height(l.layout("ab\ncd", 300f, false)) }
        assertEquals(6f, h1 - h0, 0.01f)
    }

    @Test fun changeCase() {
        assertEquals("HELLO world", RichText.changeCase("hello world", TextCase.UPPER, 0, 5))
        assertEquals("Hello World", RichText.changeCase("hELLO wORLD", TextCase.TITLE))
        assertEquals("One. Two", RichText.changeCase("ONE. TWO", TextCase.SENTENCE))
        assertEquals("abc", RichText.changeCase("ABC", TextCase.LOWER))
    }

    @Test fun runsFollowEdits() {
        val runs = listOf(RunStyle(6, 10, bold = true))
        assertEquals(listOf(RunStyle(8, 12, bold = true)), RichText.shiftRuns(runs, "plain bold", "XXplain bold"))
        assertEquals(listOf(RunStyle(6, 12, bold = true)), RichText.shiftRuns(runs, "plain bold", "plain boldYY"))       // typing at the end continues
        assertEquals(emptyList<RunStyle>(), RichText.shiftRuns(runs, "plain bold", "plain "))
        assertEquals(listOf(RunStyle(6, 8, bold = true)), RichText.shiftRuns(runs, "plain bold", "plain ld"))
        val st = EditTextStyle(runs = runs)
        assertTrue(RichText.all(st, "plain bold", 6, 10) { it.bold })
        assertFalse(RichText.all(st, "plain bold", 5, 10) { it.bold })
    }

    @Test fun markers() {
        assertEquals("c. ", RichText.marker(ListKind.NUMBER, 3, format = NumberFormat.LOWER_ALPHA))
        assertEquals("aa", RichText.alpha(27))
        assertEquals("XIV", RichText.roman(14))
        assertEquals("\u0661\u0662. ", RichText.marker(ListKind.NUMBER, 12, format = NumberFormat.ARABIC_INDIC))
        assertEquals("3) ", RichText.marker(ListKind.NUMBER, 3, format = NumberFormat.DECIMAL_PAREN))
    }

    @Test fun unkernRemovesNumbersOnly() {
        val arr = COSArray().apply { add(COSString("A")); add(com.tom_roush.pdfbox.cos.COSInteger.get(-120)); add(COSString("V")) }
        val out = TextEdit5.unkern(listOf(arr, Operator.getOperator("TJ")))!!
        assertEquals(2, (out[0] as COSArray).size())
        assertNull(TextEdit5.unkern(listOf(COSArray().apply { add(COSString("A")) }, Operator.getOperator("TJ"))))
    }

    @Test fun formRewriteIsCopyOnWrite() {
        val f = File.createTempFile("nvef", ".pdf").also { it.deleteOnExit() }
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val form = PDFormXObject(d)
            form.bBox = PDRectangle(0f, 0f, 200f, 50f)
            form.cosObject.createOutputStream().use { it.write("BT /F1 12 Tf 10 10 Td (Hi) Tj (There) Tj ET".toByteArray()) }
            val copy = TextEdit5.cloneForm(d, form)
            val rw = ContentRewriter(d, copy)
            // operators: BT Tf Td Tj Tj ET -> remove the first Tj (index 3)
            assertEquals("Tj", rw.operatorName(3))
            rw.remove(3)
            rw.commit()
            val origTokens = PDFStreamParser(form).let { it.parse(); it.tokens }
            val copyTokens = PDFStreamParser(copy).let { it.parse(); it.tokens }
            assertEquals(2, origTokens.count { it is Operator && it.name == "Tj" })
            assertEquals(1, copyTokens.count { it is Operator && it.name == "Tj" })
            assertNotNull(copy.bBox)
            assertEquals(COSName.getPDFName("Form"), copy.cosObject.getCOSName(COSName.SUBTYPE))
            d.save(f)
        }
    }

    @Test fun formOpKeysRoundTrip() {
        val k = FormOps.key(17, 42)
        assertTrue(FormOps.isForm(k))
        assertEquals(17, FormOps.doOp(k)); assertEquals(42, FormOps.formOp(k))
        assertFalse(FormOps.isForm(0)); assertFalse(FormOps.isForm(5))
    }

    @Test fun overflowSplitsLines() {
        val style = EditTextStyle(size = 10f)
        val text = "one two three four five six seven eight nine ten"
        val lines = withLayout(style) { it.layout(text, 40f, false) }
        assertTrue(lines.size >= 4)
        val (head, tail) = TextEdit5.splitLines(lines, style, 10f + 12.5f)     // room for two lines
        assertEquals(lines[0].logical + " " + lines[1].logical, head)
        assertEquals(text, "$head $tail")
    }

    @Test fun digits() {
        assertEquals("\u0661\u0662\u0663", NveDigits.convert("123", NveDigits.Mode.ARABIC_INDIC))
        assertEquals("123", NveDigits.convert("\u06F1\u06F2\u06F3", NveDigits.Mode.LATIN))
        assertEquals("abc 12", NveDigits.convert("abc 12", NveDigits.Mode.AUTO))
        assertEquals("\u0628 \u0661", NveDigits.convert("\u0628 1", NveDigits.Mode.AUTO))
    }
}
