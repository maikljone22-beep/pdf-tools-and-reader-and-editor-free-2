package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.pdf.OcrLayerCleanup
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Redo OCR / replace the text layer" (FINAL_CHECK K2, PRO_OCR_r5): before the chosen pages are recognised again, the
 * old **invisible** text on them is removed — the app's own OCR layer ([OcrLayerCleanup]) and any other invisible text
 * (rendering mode 3: a scanner's or another app's OCR). Everything visible stays: drawn glyphs, pictures, paths. A text
 * object is removed only when *all* of its text is invisible, and its text-state settings (font, spacing, rendering
 * mode, colours, marked content) stay in place, so later visible text is drawn exactly as before.
 */
object OcrRedo {

    /** Operators that only position or show text inside BT … ET; dropped (with their operands) in an invisible block. */
    private val POSITION_SHOW = setOf("BT", "ET", "Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"")
    private val SHOW = setOf("Tj", "TJ", "'", "\"")

    /** Rendering-mode state carried through a page's content (q/Q save and restore it). */
    class State { var tr = 0; val stack = ArrayList<Int>() }

    /**
     * [tokens] (operands + [Operator]s of one content stream, PDFStreamParser order) without the text objects whose
     * shown text is all invisible (Tr 3 at every show). Returns the new tokens and the number of objects removed.
     * [st] carries the rendering mode from the page's earlier streams. Plain Kotlin (unit-tested with real operators).
     */
    fun stripInvisible(tokens: List<Any?>, st: State = State()): Pair<List<Any?>, Int> {
        // pass 1: decide per BT…ET block (by token index of its BT) whether every show in it is invisible
        val drop = HashSet<Int>()
        run {
            var tr = st.tr; val stack = ArrayList(st.stack)
            var bt = -1; var shows = 0; var visible = false
            val operands = ArrayList<Any?>()
            tokens.forEachIndexed { i, t ->
                if (t !is Operator) { operands += t; return@forEachIndexed }
                when (t.name) {
                    "q" -> stack += tr
                    "Q" -> if (stack.isNotEmpty()) tr = stack.removeAt(stack.size - 1)
                    "Tr" -> (operands.lastOrNull() as? COSNumber)?.let { tr = it.intValue() }
                    "BT" -> { bt = i; shows = 0; visible = false }
                    "ET" -> { if (bt >= 0 && shows > 0 && !visible) drop += bt; bt = -1 }
                    in SHOW -> { shows++; if (tr != 3) visible = true }
                }
                operands.clear()
            }
        }
        // pass 2: copy, leaving out positioning / showing operators (and their operands) of the dropped blocks
        val out = ArrayList<Any?>(tokens.size)
        val pending = ArrayList<Any?>()
        var inDrop = false
        tokens.forEachIndexed { i, t ->
            if (t !is Operator) { pending += t; return@forEachIndexed }
            val name = t.name
            if (name == "BT" && i in drop) inDrop = true
            if (inDrop && name in POSITION_SHOW) pending.clear() else { out.addAll(pending); out += t; pending.clear() }
            if (name == "ET" && inDrop) inDrop = false
            when (name) {
                "q" -> st.stack += st.tr
                "Q" -> if (st.stack.isNotEmpty()) st.tr = st.stack.removeAt(st.stack.size - 1)
            }
            if (name == "Tr") (out.getOrNull(out.size - 2) as? COSNumber)?.let { st.tr = it.intValue() }
        }
        out.addAll(pending)
        return out to drop.size
    }

    /** Invisible text removed from every content stream of [page]; returns the number of text objects removed. */
    fun removeInvisibleText(page: PDPage): Int {
        val st = State()
        var n = 0
        for (s in page.contentStreams.asSequence().toList()) {
            val tokens = runCatching { PDFStreamParser(s).also { it.parse() }.tokens }.getOrNull() ?: continue
            val (kept, removed) = stripInvisible(tokens, st)
            if (removed == 0) continue
            runCatching { s.cosObject.createOutputStream().use { o -> ContentStreamWriter(o).writeTokens(kept) } }.onSuccess { n += removed }
        }
        return n
    }

    /**
     * A copy of [src] in [dir] whose [pages] (zero-based; null = all) have no old invisible text left. Returns the copy
     * and the number of pages that had some; the caller deletes the copy.
     */
    suspend fun strippedCopy(src: File, password: String?, pages: List<Int>?, dir: File): Pair<File, Int> = withContext(Dispatchers.IO) {
        val out = File(dir, "ocr_redo_${System.nanoTime()}.pdf")
        PdfOps.load(src, password).use { doc ->
            var changed = 0
            val range = pages ?: (0 until doc.numberOfPages).toList()
            for (pi in range) {
                if (pi !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(pi)
                val ours = runCatching { OcrLayerCleanup.removeOurs(page, latinOnly = true) }.getOrDefault(0)
                val other = runCatching { removeInvisibleText(page) }.getOrDefault(0)
                if (ours + other > 0) changed++
            }
            doc.save(out)
            out to changed
        }
    }
}
