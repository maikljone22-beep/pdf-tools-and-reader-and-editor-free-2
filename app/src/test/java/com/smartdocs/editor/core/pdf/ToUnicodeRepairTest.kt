package com.smartdocs.editor.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToUnicode repair of wrongly mapped Arabic fonts (ARABIC_COPY_r5). Glyph codes, cmap code points and CMaps are the
 * real ones of the chemistry PDFs: PT Bold Heading of «المرشد_2025م_في_الكيمياء_الجزء_الاول.pdf» (font with a cmap),
 * and the same font re-subset by iLovePDF without a cmap in «المرشد2025_الكيمياء_3_ث_الفصل_الدراسي_الثاني_1.pdf».
 */
class ToUnicodeRepairTest {

    // ---------------- strategy 1: the embedded font's own cmap ----------------

    @Test fun wrongLettersFromIncrementingRangesAreFixedFromTheCmap() {
        // <0084> <0094> <0626> made glyph 0x86 (U+FE8E ALEF FINAL) extract as ب, «الكيمياء» -> «اٌىُُّبء»
        assertEquals(ToUnicodeRepair.Fix("ا", true), ToUnicodeRepair.correction("ب", listOf(0xFE8E)))
        // <00CC> <00E1> <0641>: glyph 0xD7 (U+FEDF LAM INITIAL) -> dammatan, 0xDB (MEEM INITIAL) -> kasra
        assertEquals(ToUnicodeRepair.Fix("ل", true), ToUnicodeRepair.correction("ٌ", listOf(0xFEDF)))
        assertEquals(ToUnicodeRepair.Fix("م", true), ToUnicodeRepair.correction("ِ", listOf(0xFEE3)))
        // 0xA9 SEEN ISOLATED -> ط, 0xA5 REH ISOLATED -> س («مدرس» -> «ِذسط»)
        assertEquals(ToUnicodeRepair.Fix("س", true), ToUnicodeRepair.correction("ط", listOf(0xFEB1)))
        assertEquals(ToUnicodeRepair.Fix("ر", true), ToUnicodeRepair.correction("س", listOf(0xFEAD)))
        // missing entry (0x78 HAMZA ISOLATED had none)
        assertEquals(ToUnicodeRepair.Fix("ء", true), ToUnicodeRepair.correction(null, listOf(0xFE80)))
        // bug inside a ligature range: <011B> <011E> <0644062C> gave LAM WITH MEEM INITIAL the value «لد»
        assertEquals(ToUnicodeRepair.Fix("ﳌ", true), ToUnicodeRepair.correction("لد", listOf(0xFCCC)))
    }

    @Test fun correctLigaturesAreReEncodedSoPdfiumKeepsTheirOrder() {
        // PDFium reverses a multi-letter value in RTL text: «مح» extracted «حم» («محمد» -> «حممد»)
        assertEquals(ToUnicodeRepair.Fix("ﳏ", false), ToUnicodeRepair.correction("مح", listOf(0xFCCF)))
        assertEquals(ToUnicodeRepair.Fix("ﻷ", false), ToUnicodeRepair.correction("لأ", listOf(0xFEF7)))
        assertNull(ToUnicodeRepair.correction("ﳏ", listOf(0xFCCF)))                  // already a ligature code point
    }

    @Test fun producerDecisionsThatAreNotWrongAreKept() {
        assertNull(ToUnicodeRepair.correction("ا", listOf(0xFE8D)))                  // right letter
        assertNull(ToUnicodeRepair.correction("1", listOf(0x0661)))                       // Arabic-Indic digit glyph typed as 1
        assertNull(ToUnicodeRepair.correction(")", listOf(0x0028)))                       // mirrored bracket, not Arabic
        assertNull(ToUnicodeRepair.correction("لله", listOf(0xFDF2)))      // ﷲ after an alef glyph: «لله» is right
        assertNull(ToUnicodeRepair.correction("ِّ", listOf(0x0650)))            // shadda+kasra glyph, cmap names kasra
        // Traditional Arabic (clean «الوحدة الأولى»): kasra glyph mapped to yeh — «يَضْرِبُ» extracted «ضير»
        assertEquals(ToUnicodeRepair.Fix("ِ", true), ToUnicodeRepair.correction("ي", listOf(0x0650)))
    }

    @Test fun baseLetters() {
        assertEquals("ب", ToUnicodeRepair.baseOf(0xFE91))
        assertEquals("لا", ToUnicodeRepair.baseOf(0xFEFB))
        assertEquals("ِ", ToUnicodeRepair.baseOf(0xFE7A))                             // isolated kasra: no space
        assertEquals("ـ", ToUnicodeRepair.baseOf(0x0640))                             // tatweel itself is kept
    }

    // ---------------- strategy 2: no cmap — infer the layout from the faulty CMap ----------------

    /** /ToUnicode of AVLPJR+PT Bold Heading (obj 1518), «المرشد2025_الكيمياء_3_ث_الفصل_الدراسي_الثاني_1.pdf», as stored. */
    private val ILOVEPDF_PT_BOLD_HEADING = """
        /CIDInit /ProcSet findresource begin
        24 dict begin
        begincmap
        /CMapName /Adobe-Identity-UCS def
        /CMapType 2 def
        1 begincodespacerange
        <0000> <FFFF>
        endcodespacerange
        2 beginbfchar
        <0003> <0020>
        <0005> <0022>
        endbfchar
        1 beginbfrange
        <000B> <000C> [<0029> <0028>]
        endbfrange
        10 beginbfchar
        <0011> <002E>
        <001D> <003A>
        <0023> <005D>
        <0025> <005B>
        <002E> <201C>
        <0031> <060C>
        <0034> <0621>
        <004E> <0640>
        <0059> <064B>
        <005D> <064F>
        endbfchar
        16 beginbfrange
        <0064> <0064> [<0032>]
        <0065> <006D> <0031>
        <007B> <007C> [<0623> <0623>]
        <007E> <007F> <0624>
        <0083> <0083> [<0626>]
        <0084> <0097> <0626>
        <009A> <009C> [<062D> <062D> <062D>]
        <009F> <009F> [<062E>]
        <00A0> <00AC> <062E>
        <00AF> <00AF> [<0634>]
        <00B0> <00B1> <0634>
        <00B3> <00B4> [<0635> <0635>]
        <00B6> <00B7> [<0636> <0636>]
        <00B8> <00BC> <0636>
        <00BE> <00BF> [<0638> <0638>]
        <00C0> <00C4> <0638>
        endbfrange
        1 beginbfchar
        <00C7> <063A>
        endbfchar
        2 beginbfrange
        <00C9> <00CB> [<0641> <0641> <0641>]
        <00CC> <00ED> <0641>
        endbfrange
        2 beginbfchar
        <00EF> <06440623>
        <00F1> <06440625>
        endbfchar
        1 beginbfrange
        <00F3> <00F4> [<06440627> <06440627>]
        endbfrange
        1 beginbfchar
        <00F7> <0641064A>
        endbfchar
        1 beginbfrange
        <00FC> <00FD> <06440649>
        endbfrange
        2 beginbfchar
        <0105> <06280631>
        <0108> <062A0631>
        endbfchar
        3 beginbfrange
        <010C> <010D> [<064A0631> <064A0646>]
        <0111> <0111> [<06280645>]
        <0112> <0113> <062A062C>
        endbfrange
        2 beginbfchar
        <0115> <062A0645>
        <0119> <062E0645>
        endbfchar
        1 beginbfrange
        <011B> <011F> <0644062C>
        endbfrange
        3 beginbfchar
        <0121> <0645062D>
        <0127> <064A062C>
        <012A> <064A0645>
        endbfchar
        endcmap
    """.trimIndent()

    @Test fun parsesTrustedAndIncrementedEntries() {
        val p = ToUnicodeRepair.parseToUnicode(ILOVEPDF_PT_BOLD_HEADING)
        assertEquals("ئ", p.trusted[0x84])                     // first code of a range: right
        assertEquals("ب", p.ranged[0x86])                      // incremented: ALEF FINAL read as ب
        assertEquals(0x84, p.rangeStart[0x86])
        assertEquals("ٌ", p.ranged[0xD7])                      // LAM INITIAL read as dammatan
        assertEquals("لد", p.ranged[0x11E])               // ligature lam-meem read as «لد»
        assertEquals(")", p.trusted[0x0B]); assertEquals("(", p.trusted[0x0C])
    }

    @Test fun infersTheGlyphLayoutOfACmaplessFontAndRepairsIt() {
        val p = ToUnicodeRepair.parseToUnicode(ILOVEPDF_PT_BOLD_HEADING)
        val k = ToUnicodeRepair.inferOffset(p)
        assertEquals(0xFE08, k)                                      // = the cmap of the same font in the other PDF
        val fix = ToUnicodeRepair.inferredCorrections(p, k)
        assertEquals("ا", fix[0x86]?.value)                     // ALEF FINAL
        assertEquals("ل", fix[0xD7]?.value)                     // LAM INITIAL
        assertEquals("م", fix[0xDB]?.value)                     // MEEM INITIAL
        assertEquals("م", fix[0xDC]?.value)                     // MEEM MEDIAL
        assertEquals("ة", fix[0x8C]?.value)                     // TEH MARBUTA FINAL
        assertEquals("لم", fix[0x11E]?.value?.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC) })
        assertTrue(fix[0x11C]?.letter == false)                      // لح right, re-encoded only
        assertEquals("ﻷ", fix[0xEF]?.value)                     // trusted لأ -> ligature code point
        assertNull(fix[0x31]); assertNull(fix[0x65]); assertNull(fix[0x84])
    }

    @Test fun aCorrectCmapIsNotTouchedAndRandomOnesAreRejected() {
        // a correct CMap for the same layout: every code maps to the letter of code + K
        val correct = buildString {
            append("100 beginbfchar\n")
            for (c in 0x78..0xDC) {
                val cp = c + 0xFE08
                if (Character.getName(cp) == null) continue
                append("<%04X> <%04X>\n".format(c, ToUnicodeRepair.baseOf(cp)[0].code))
            }
            append("endbfchar\n")
        }
        assertNull(ToUnicodeRepair.inferOffset(ToUnicodeRepair.parseToUnicode(correct)))
        // letters that follow no layout (another producer's arbitrary codes)
        val random = "20 beginbfchar\n" + (0 until 20).joinToString("\n") { "<%04X> <%04X>".format(0x40 + it * 7, 0x0628 + (it * 5) % 20) } + "\nendbfchar\n"
        assertNull(ToUnicodeRepair.inferOffset(ToUnicodeRepair.parseToUnicode(random)))
    }

    @Test fun writesAValidBfcharCmap() {
        val t = ToUnicodeRepair.cmapText(sortedMapOf(0x86 to "ا", 0x11C to "ﳊ", 0x3 to " "))
        assertTrue(t.contains("3 beginbfchar"))
        assertTrue(t.contains("<0086> <0627>")); assertTrue(t.contains("<011C> <FCCA>")); assertTrue(t.contains("<0003> <0020>"))
        val back = ToUnicodeRepair.parseToUnicode(t)
        assertEquals("ا", back.trusted[0x86]); assertFalse(back.ranged.isNotEmpty())
    }
}
