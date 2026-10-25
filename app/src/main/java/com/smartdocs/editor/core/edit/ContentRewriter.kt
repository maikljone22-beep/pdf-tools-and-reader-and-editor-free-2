package com.smartdocs.editor.core.edit

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.abs

/** Loading / saving helpers shared by the edit operations. */
internal object EditIo {
    /** Load for editing (encrypted documents are re-protected with the same password, see [PdfOps.load]). */
    fun load(file: File, password: String?): PDDocument = PdfOps.load(file, password)

    fun save(doc: PDDocument, out: File) {
        val tmp = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
        try {
            doc.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        } finally { if (tmp.exists()) tmp.delete() }
    }
}

/**
 * The tokens of a page's content stream (all /Contents streams concatenated, exactly what the content
 * stream engine executes) with per-operator replacements. [commit] writes one new content stream.
 */
internal class ContentRewriter(private val doc: PDDocument, private val page: PDPage) {
    private val tokens: List<Any> = PDFStreamParser(page).let { it.parse(); it.tokens }
    /** token index of the n-th operator */
    private val opPos: IntArray = tokens.indices.filter { tokens[it] is Operator }.toIntArray()
    private val replacements = HashMap<Int, List<Any>>()

    val operatorCount get() = opPos.size

    fun operatorName(op: Int): String? = (tokens.getOrNull(opPos.getOrElse(op) { -1 }) as? Operator)?.name

    /** Operands + operator of operator [op], as parsed. */
    fun original(op: Int): List<Any> {
        val start = if (op == 0) 0 else opPos[op - 1] + 1
        return tokens.subList(start, opPos[op] + 1).toList()
    }

    fun replace(op: Int, with: List<Any>) { if (op in opPos.indices) replacements[op] = with }

    fun remove(op: Int) = replace(op, emptyList())

    /**
     * Remove a text-showing operator without moving the text that follows it in the same text object:
     * the glyphs are replaced by an equivalent invisible advance (`[n] TJ`).
     */
    fun removeText(op: Int, info: TextOpInfo?) {
        val name = operatorName(op) ?: return
        if (name != "Tj" && name != "TJ" && name != "'" && name != "\"") return
        val adv = ArrayList<Any>()
        if (info != null && info.fontSize != 0f) {
            val n = if (info.vertical) -info.ty * 1000f / info.fontSize
                    else if (info.hScale != 0f) -info.tx * 1000f / (info.fontSize * info.hScale) else 0f
            if (abs(n) > 0.01f && !n.isNaN() && !n.isInfinite()) {
                adv.add(COSArray().apply { add(COSFloat(n)) })
                adv.add(Operator.getOperator("TJ"))
            }
        }
        val out = ArrayList<Any>()
        when (name) {
            "'" -> out.add(Operator.getOperator("T*"))
            "\"" -> {
                val ops = info?.operands
                if (ops != null && ops.size >= 2) { out.add(ops[0]); out.add(Operator.getOperator("Tw")); out.add(ops[1]); out.add(Operator.getOperator("Tc")) }
                out.add(Operator.getOperator("T*"))
            }
        }
        out.addAll(adv)
        replace(op, out)
    }

    /** Wrap operator [op] (e.g. `/Im1 Do`) as `q <m> cm … Q`, optionally swapping the XObject name. */
    fun wrapWithMatrix(op: Int, m: Matrix, newName: COSName? = null) {
        val orig = original(op)
        val body: List<Any> = if (newName != null) listOf(newName, Operator.getOperator("Do")) else orig
        val v = Affine.values(m)
        val list = ArrayList<Any>()
        list.add(Operator.getOperator("q"))
        v.forEach { list.add(COSFloat(it)) }
        list.add(Operator.getOperator("cm"))
        list.addAll(body)
        list.add(Operator.getOperator("Q"))
        replace(op, list)
    }

    /** Write the edited token list as the page's single content stream, wrapped in q … Q. */
    fun commit() {
        val out = ArrayList<Any>(tokens.size + 16)
        out.add(Operator.getOperator("q"))
        var t = 0
        for (op in opPos.indices) {
            val end = opPos[op]
            val rep = replacements[op]
            if (rep == null) { while (t <= end) { out.add(tokens[t]); t++ } }
            else { out.addAll(rep); t = end + 1 }
        }
        // dangling operands after the last operator are dropped (they would be a syntax error anyway)
        out.add(Operator.getOperator("Q"))
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(out) }
        page.setContents(stream)
    }

    companion object {
        fun cos(list: List<Any>): List<COSBase> = list.filterIsInstance<COSBase>()
    }
}
