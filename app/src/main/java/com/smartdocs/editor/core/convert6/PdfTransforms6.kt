package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.tools5.OptimizeOptions
import com.smartdocs.editor.core.tools5.Optimizer
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
import kotlin.coroutines.coroutineContext

/** PDF → grayscale (vector colours rewritten, images converted) and N-up handouts. */
object PdfTransforms6 {

    private fun gray(r: Float, g: Float, b: Float) = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)

    /** Components of a colour space name when it is RGB/CMYK-like and safe to turn into DeviceGray, else 0. */
    private fun convertible(name: COSName, res: PDResources?): Int {
        if (name == COSName.DEVICERGB || name.name == "RGB") return 3
        if (name == COSName.DEVICECMYK || name.name == "CMYK") return 4
        val cs = runCatching { res?.getColorSpace(name) }.getOrNull() ?: return 0
        return if (cs.name in setOf("ICCBased", "CalRGB") && cs.numberOfComponents in 3..4) cs.numberOfComponents else 0
    }

    private fun nums(ops: List<Any>): FloatArray? = if (ops.all { it is COSNumber }) FloatArray(ops.size) { (ops[it] as COSNumber).floatValue() } else null

    private fun toGray(v: FloatArray): Float = when (v.size) {
        3 -> gray(v[0], v[1], v[2])
        4 -> gray((1 - v[0]) * (1 - v[3]), (1 - v[1]) * (1 - v[3]), (1 - v[2]) * (1 - v[3]))
        else -> v.firstOrNull() ?: 0f
    }

    /** Rewrites colour operators of one token list. */
    fun rewrite(tokens: List<Any>, res: PDResources?): List<Any> {
        val out = ArrayList<Any>(tokens.size)
        val ops = ArrayList<Any>()
        var fillN = 0; var strokeN = 0
        for (t in tokens) {
            if (t !is Operator) { ops += t; continue }
            val v = nums(ops)
            val stroke = t.name.firstOrNull()?.isUpperCase() == true
            when (t.name) {
                "rg", "RG", "k", "K" -> if (v != null && v.size == (if (t.name.equals("rg", true)) 3 else 4)) {
                    out += COSFloat(toGray(v)); out += Operator.getOperator(if (stroke) "G" else "g"); ops.clear(); continue
                }
                "cs", "CS" -> {
                    val n = (ops.lastOrNull() as? COSName)?.let { convertible(it, res) } ?: 0
                    if (stroke) strokeN = n else fillN = n
                    if (n > 0) { out += COSName.DEVICEGRAY; out += t; ops.clear(); continue }
                }
                "sc", "scn", "SC", "SCN" -> {
                    val n = if (stroke) strokeN else fillN
                    if (n > 0 && v != null && v.size == n) { out += COSFloat(toGray(v)); out += Operator.getOperator(if (stroke) "SC" else "sc"); ops.clear(); continue }
                }
            }
            out.addAll(ops); ops.clear(); out += t
        }
        return out
    }

    private fun writeTokens(stream: PDStream, tokens: List<Any>) {
        stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(tokens) }
    }

    private fun forms(doc: PDDocument, res: PDResources?, seen: IdentityHashMap<Any, Boolean>, depth: Int) {
        if (res == null || depth > 12) return
        for (n in res.xObjectNames) {
            val x = runCatching { res.getXObject(n) }.getOrNull() as? PDFormXObject ?: continue
            if (seen.put(x.cosObject, true) != null) continue
            runCatching {
                val toks = PDFStreamParser(x).let { it.parse(); it.tokens }
                val r = x.resources ?: res
                writeTokens(x.contentStream, rewrite(toks, r))
                forms(doc, r, seen, depth + 1)
            }
        }
    }

    suspend fun grayscale(src: File, password: String?, out: File, tempDir: File, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(tempDir, "cv6_gray_${System.nanoTime()}.pdf")
        try {
            PdfFlow6.load(src, password).use { doc ->
                doc.isAllSecurityToBeRemoved = true
                val seen = IdentityHashMap<Any, Boolean>()
                val n = doc.numberOfPages
                for (i in 0 until n) {
                    coroutineContext.ensureActive()
                    onProgress(i, n + 1)
                    val page = doc.getPage(i)
                    runCatching {
                        val toks = PDFStreamParser(page).let { it.parse(); it.tokens }
                        val stream = PDStream(doc)
                        writeTokens(stream, rewrite(toks, page.resources))
                        page.setContents(stream)
                    }
                    forms(doc, page.resources, seen, 0)
                }
                doc.save(tmp)
            }
            // pictures: the optimizer converts every image to grey (kept at print resolution)
            Optimizer.optimize(tmp, null, out, OptimizeOptions(imageDpi = 300, jpegQuality = 85, grayImages = true, onlyIfSmaller = false, stripMetadata = false))
        } finally { tmp.delete() }
        out
    }

    /**
     * N pages / slides per sheet ([n] = 2, 3, 4, 6, 8, 9, 16) on A4, a thin frame around each, reading order
     * right-to-left for Arabic documents ([rtl]).
     */
    suspend fun nUp(src: File, password: String?, n: Int, out: File, rtl: Boolean, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        PdfFlow6.load(src, password).use { s ->
            PDDocument().use { d ->
                val lu = LayerUtility(d)
                val first = s.getPage(0)
                val landscapePages = first.cropBox.width > first.cropBox.height != ((first.rotation / 90) % 2 == 1)
                val landscapeSheet = landscapePages && n in setOf(4, 9, 16)
                val a4 = PDRectangle.A4
                val sheetW = if (landscapeSheet) a4.height else a4.width; val sheetH = if (landscapeSheet) a4.width else a4.height
                val cells = Images6.nUpCells(n, sheetW, sheetH, 28f, 14f, rtl)
                val total = s.numberOfPages
                var i = 0
                while (i < total) {
                    coroutineContext.ensureActive()
                    onProgress(i, total)
                    val sheet = PDPage(PDRectangle(sheetW, sheetH)); d.addPage(sheet)
                    PDPageContentStream(d, sheet).use { cs ->
                        for (cell in cells) {
                            if (i >= total) break
                            val form = lu.importPageAsForm(s, i)
                            val bb = form.bBox
                            val m = form.matrix
                            val xs = floatArrayOf(bb.lowerLeftX, bb.upperRightX); val ys = floatArrayOf(bb.lowerLeftY, bb.upperRightY)
                            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                            for (x in xs) for (y in ys) {
                                val tx = m.scaleX * x + m.shearX * y + m.translateX; val ty = m.shearY * x + m.scaleY * y + m.translateY
                                minX = minOf(minX, tx); minY = minOf(minY, ty); maxX = maxOf(maxX, tx); maxY = maxOf(maxY, ty)
                            }
                            val fw = (maxX - minX).coerceAtLeast(1f); val fh = (maxY - minY).coerceAtLeast(1f)
                            val r = Images6.fitInCell(fw, fh, cell)
                            val k = r[2] / fw
                            cs.saveGraphicsState()
                            cs.transform(Matrix(k, 0f, 0f, k, r[0] - minX * k, r[1] - minY * k))
                            cs.drawForm(form)
                            cs.restoreGraphicsState()
                            cs.setStrokingColor(0.6f, 0.6f, 0.6f)
                            cs.setLineWidth(0.5f)
                            cs.addRect(r[0], r[1], r[2], r[3]); cs.stroke()
                            i++
                        }
                    }
                }
                onProgress(total, total)
                d.documentInformation.creator = "SmartDocs"
                d.save(out)
            }
        }
        out
    }
}
