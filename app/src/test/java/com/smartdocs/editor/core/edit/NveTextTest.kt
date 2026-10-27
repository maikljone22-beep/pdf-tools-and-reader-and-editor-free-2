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
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NVE-text: rich text writer, text helpers, kerning removal and form rewriting, round-tripped through saved PDFs. */
class NveTextTest {
    private val arabic = { PDType1Font.HELVETICA as com.tom_roush.pdfbox.pdmodel.font.PDFont }

    /** Writes [text] with [style] on a fresh page, saves, reloads and returns the page content tokens. */
    private fun write(text: String, style: EditTextStyle, width: Float = 400f): List<Any> {
        val f = File.createTempFile("nvet", ".pdf").also { it.deleteOnExit() }
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            RichWriter.write(d, p, Affine.identity(), null, text, style, 50f, 50f + width, 700f, false, arabic, null)
            d.save(f)
        }
        return PDDocument.load(f).use { d -> PDFStreamParser(d.getPage(0)).let { it.parse(); it.tokens } }
    }

    private fun ops(tokens: List<Any>) = tokens.filterIsInstance<Operator>().map { it.name }

    /** Operand right before the first [op]. */
    private fun operandOf(tokens: List<Any>, op: String): Float? {
        val i = tokens.indexOfFirst { it is Operator && it.name == op }
        return if (i > 0) (tokens[i - 1] as? COSNumber)?.floatValue() else null
    }

    private fun shownText(tokens: List<Any>): String = buildString {
        tokens.forEach { t ->
            when (t) {
                is COSString -> append(String(t.bytes, Charsets.ISO_8859_1))
                is COSArray -> for (i in 0 until t.size()) (t.getObject(i) as? COSString)?.let { append(String(it.bytes, Charsets.ISO_8859_1)) }
            }
        }
    }

    @Test fun plainStyleIsNotRich() {
        assertFalse(RichText.isRich(EditTextStyle()))
        assertTrue(RichText.isRich(EditTextStyle(align = TextAlign.JUSTIFY)))
        assertTrue(RichText.isRich(EditTextStyle(runs = listOf(RunStyle(0, 1, bold = true)))))
        assertTrue(RichText.isRich(EditTextStyle(fontPath = "asset:x.ttf")))
    }

    @Test fun spacingScalingRiseAreWritten() {
        val t = write("Hello world", EditTextStyle(size = 12f, charSpacing = 1.5f, hScale = 80f, rise = 3f, wordSpacing = 4f))
        assertEquals(1.5f, operandOf(t, "Tc")!!, 0.001f)
        assertEquals(80f, operandOf(t, "Tz")!!, 0.001f)
        assertEquals(3f, operandOf(t, "Ts")!!, 0.001f)
        // word spacing is a TJ adjustment after the space (Tw would be ignored by multi-byte fonts)
        val tj = t.filterIsInstance<COSArray>().first()
        val num = (0 until tj.size()).map { tj.getObject(it) }.filterIsInstance<COSNumber>().single().floatValue()
        assertEquals(-4f * 1000f / 12f, num, 0.01f)
        assertEquals("Hello world", shownText(t))
    }

    @Test fun strokeAndInvisibleRenderingModes() {
        assertEquals(2f, operandOf(write("Ab", EditTextStyle(stroke = true, strokeColor = 0xFF0000, strokeWidth = 0.7f)), "Tr")!!, 0f)
        assertEquals(1f, operandOf(write("Ab", EditTextStyle(stroke = true, fillText = false)), "Tr")!!, 0f)
        assertEquals(3f, operandOf(write("Ab", EditTextStyle(invisible = true, charSpacing = 0.1f)), "Tr")!!, 0f)
    }

    @Test fun underlineAndStrikeAreLines() {
        val t = write("Under", EditTextStyle(underline = true, strike = true))
        assertEquals(2, ops(t).count { it == "S" })
        assertTrue(ops(t).contains("l"))
    }

    @Test fun perRunStylesSplitRunsAndFonts() {
        val text = "plain bold"
        val t = write(text, EditTextStyle(size = 10f, runs = listOf(RunStyle(6, 10, bold = true, size = 14f, script = Script.SUPER))))
        val sizes = t.indices.filter { t[it] is Operator && (t[it] as Operator).name == "Tf" }.map { (t[it - 1] as COSNumber).floatValue() }
        assertTrue(sizes.contains(10f))
        assertTrue(sizes.any { kotlin.math.abs(it - 14f * 0.65f) < 0.01f })        // superscript is smaller
        val fonts = t.indices.filter { t[it] is Operator && (t[it] as Operator).name == "Tf" }.map { (t[it - 2] as COSName).name }.toSet()
        assertEquals(2, fonts.size)                                                    // regular + bold standard fonts
        assertTrue(ops(t).contains("Ts"))
    }

    @Test fun bulletsNumbersAndIndent() {
        val t = write("one\ntwo", EditTextStyle(list = ListKind.NUMBER, numbering = NumberFormat.UPPER_ROMAN, indent = 10f))
        val s = shownText(t)
        assertTrue(s, s.contains("I. one") && s.contains("II. two"))
        val b = shownText(write("x\ny", EditTextStyle(list = ListKind.BULLET, bullet = "\u2022")))
        assertEquals(2, b.count { it == '\u0095' })          // WinAnsi code of the bullet
    }

    @Test fun justifyFillsTheLine() {
        val style = EditTextStyle(size = 12f, align = TextAlign.JUSTIFY)
        val text = "aaa bbb ccc ddd eee fff ggg hhh iii jjj kkk lll mmm nnn ooo ppp"
        val fonts = RichFonts(null, style, arabic, null)
        val lines = RichLayout(fonts, style).layout(text, 150f, false)
        assertTrue(lines.size > 1)
        assertTrue(lines.last().lastOfPara)
        // all lines but the last get justification gaps (TJ arrays with numbers)
        val t = write(text, style, 150f)
        val arrays = t.filterIsInstance<COSArray>().filter { a -> (0 until a.size()).any { a.getObject(it) is COSNumber } }
        assertEquals(lines.size - 1, arrays.size)
    }

    @Test fun rotationIsAppliedToTheBox() {
        val t = write("Rot", EditTextStyle(rotation = 90f))
        assertTrue(ops(t).count { it == "cm" } >= 2)
    }

    @Test fun verticalTextStacksGlyphs() {
        val t = write("ABC", EditTextStyle(vertical = true))
        assertEquals(3, ops(t).count { it == "Tj" })
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
        val n = out[0] as COSArray
        assertEquals(2, n.size())
        assertNull(TextEdit5.unkern(listOf(COSArray().apply { add(COSString("A")) }, Operator.getOperator("TJ"))))
    }

    @Test fun formRewriteIsCopyOnWrite() {
        val f = File.createTempFile("nvef", ".pdf").also { it.deleteOnExit() }
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val form = PDFormXObject(d)
            form.bBox = PDRectangle(0f, 0f, 200f, 50f)
            form.resources = com.tom_roush.pdfbox.pdmodel.PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
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
            d.save(f)
        }
    }

    @Test fun overflowSplitsLines() {
        val style = EditTextStyle(size = 10f)
        val fonts = RichFonts(null, style, arabic, null)
        val lines = RichLayout(fonts, style).layout("one two three four five six seven eight nine ten", 40f, false)
        assertTrue(lines.size >= 4)
        val (head, tail) = TextEdit5.splitLines(lines, style, 10f + 12.5f)     // room for two lines
        assertEquals(lines[0].logical + " " + lines[1].logical, head)
        assertEquals("one two three four five six seven eight nine ten", "$head $tail")
    }

    @Test fun digits() {
        assertEquals("\u0661\u0662\u0663", NveDigits.convert("123", NveDigits.Mode.ARABIC_INDIC))
        assertEquals("123", NveDigits.convert("\u06F1\u06F2\u06F3", NveDigits.Mode.LATIN))
        assertEquals("abc 12", NveDigits.convert("abc 12", NveDigits.Mode.AUTO))
        assertEquals("\u0628 \u0661", NveDigits.convert("\u0628 1", NveDigits.Mode.AUTO))
    }
}
