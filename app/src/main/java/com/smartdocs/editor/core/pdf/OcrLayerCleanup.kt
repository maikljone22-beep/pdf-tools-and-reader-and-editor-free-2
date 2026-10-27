package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font

/**
 * Removes the invisible OCR text this app added to a page ([com.smartdocs.editor.core.ocr.SearchablePdfWriter]) once
 * the page's own text layer was repaired by [ToUnicodeRepair]: the old OCR words then only duplicate the original text
 * on copy / search (and older builds wrote them reversed).
 *
 * Our layer is recognised by its shape, not guessed: an appended content stream that contains nothing but invisible
 * text (rendering mode 3) set in the app's Noto Naskh Arabic font (plus Helvetica for Latin words) — no painting at
 * all. Such a stream is emptied (a leading «Q» that closes the page's saved graphics state is kept). Pages showing a
 * scanned picture are left alone: there the OCR words may be the only text of the picture.
 */
object OcrLayerCleanup {

    private val TEXT_ONLY = setOf("q", "Q", "BT", "ET", "Tr", "Tf", "Tz", "Tm", "Td", "TD", "T*", "Tj", "TJ", "'", "\"", "Tc", "Tw", "TL", "Ts", "BDC", "BMC", "EMC")

    /**
     * Number of content streams of [page] emptied ([dryRun]: only counted). [latinOnly] also accepts a layer set only in
     * Helvetica (a Latin-only OCR page) — for callers that know the page carries our layer (its stored OCR words).
     */
    fun removeOurs(page: PDPage, dryRun: Boolean = false, latinOnly: Boolean = false): Int {
        val res = page.resources ?: return 0
        val streams = page.contentStreams.asSequence().toList()
        if (streams.size < 2) return 0                       // our layer is always an appended stream
        var removed = 0
        for (st in streams) {
            val tokens = runCatching { PDFStreamParser(st).also { it.parse() }.tokens }.getOrNull() ?: continue
            if (!isOurLayer(tokens, { name -> fontKind(runCatching { res.getFont(name) }.getOrNull()) }, latinOnly)) continue
            if (dryRun) { removed++; continue }
            val keepQ = (tokens.firstOrNull() as? Operator)?.name == "Q"
            runCatching { st.cosObject.createOutputStream().use { if (keepQ) it.write("Q\n".toByteArray(Charsets.US_ASCII)) } }
                .onSuccess { removed++ }
        }
        return removed
    }

    /** 1 = the app's Noto Naskh Arabic (embedded Type0), 0 = standard Helvetica (Latin OCR words), -1 = anything else. */
    private fun fontKind(f: com.tom_roush.pdfbox.pdmodel.font.PDFont?): Int = when {
        f is PDType0Font && f.name.orEmpty().contains("NotoNaskhArabic") -> 1
        f is com.tom_roush.pdfbox.pdmodel.font.PDType1Font && f.name == "Helvetica" && !f.isEmbedded -> 0
        else -> -1
    }

    /**
     * True for a token list made only of text operators, with rendering mode 3 set before any text is shown, at least
     * one font of kind 1 and only fonts of kind 0 / 1 ([fontKind]: 1 ours, 0 Helvetica, -1 other). Plain Kotlin (unit-tested).
     */
    fun isOurLayer(tokens: List<Any?>, fontKind: (COSName) -> Int, latinOnly: Boolean = false): Boolean {
        var invisible = false; var ours = false; var shown = false
        val operands = ArrayList<Any?>()
        for (t in tokens) {
            if (t !is Operator) { operands += t; continue }
            val op = t.name
            if (op !in TEXT_ONLY) return false
            when (op) {
                "Tr" -> invisible = (operands.lastOrNull() as? COSNumber)?.intValue() == 3
                "Tf" -> {
                    val name = operands.firstOrNull { it is COSName } as? COSName ?: return false
                    when (fontKind(name)) { 1 -> ours = true; 0 -> Unit; else -> return false }
                }
                "Tj", "TJ", "'", "\"" -> { if (!invisible) return false; shown = true }
            }
            operands.clear()
        }
        return shown && (ours || latinOnly)
    }
}
