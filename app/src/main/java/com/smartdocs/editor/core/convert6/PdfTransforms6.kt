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

    /**
     * Content that the operator rewrite cannot make grey: shadings, patterns, inline images and indexed / separation /
     * DeviceN / Lab colour spaces (convert audit C27). Such pages are rasterised in grey instead of silently staying in colour.
     */
    fun needsRaster(tokens: List<Any>, res: PDResources?): Boolean {
        tokens.forEach { t ->
            if (t is Operator && (t.name == "sh" || t.name == "BI" || t.name == "d0" || t.name == "d1")) return true
            if (t is COSName && res != null) {
                val cs = runCatching { res.getColorSpace(t) }.getOrNull()
                if (cs != null && cs.name in setOf("Indexed", "Separation", "DeviceN", "Lab", "Pattern")) return true
                if (runCatching { res.patternNames.contains(t) }.getOrDefault(false)) return true
            }
        }
        return false
    }

    /** Annotation appearances (highlights, stamps, form fields) rewritten like page content (convert audit C27). */
    private fun annotations(doc: PDDocument, page: PDPage, seen: IdentityHashMap<Any, Boolean>) {
        runCatching { page.annotations }.getOrDefault(emptyList()).forEach { a ->
            val ap = runCatching { a.appearance }.getOrNull() ?: return@forEach
            listOfNotNull(runCatching { ap.normalAppearance }.getOrNull(), runCatching { ap.rolloverAppearance }.getOrNull(), runCatching { ap.downAppearance }.getOrNull()).forEach { entry ->
                val streams = if (entry.isStream) listOf(entry.appearanceStream) else runCatching { entry.subDictionary.values.toList() }.getOrDefault(emptyList())
                streams.filterNotNull().forEach { s ->
                    if (seen.put(s.cosObject, true) == null) runCatching {
                        val toks = PDFStreamParser(s).let { it.parse(); it.tokens }
                        val r = s.resources ?: page.resources
                        writeTokens(s.contentStream, rewrite(toks, r))
                        forms(doc, r, seen, 1)
                    }
                }
            }
            // the annotation's own colour (/C, /IC) for viewers that redraw it
            listOf(COSName.C, COSName.getPDFName("IC")).forEach { key ->
                (a.cosObject.getDictionaryObject(key) as? com.tom_roush.pdfbox.cos.COSArray)?.let { arr ->
                    val v = FloatArray(arr.size()) { (arr.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
                    if (v.size == 3 || v.size == 4) a.cosObject.setItem(key, com.tom_roush.pdfbox.cos.COSArray().apply { add(COSFloat(toGray(v))) })
                }
            }
        }
    }

    /**
     * PDF → grayscale. Vector colours (pages, forms, annotation appearances) are rewritten, pictures converted; pages
     * with shadings / patterns / inline images / special colour spaces, or whose rewrite failed, are rasterised in grey
     * when [ctx] is given (convert audit C27). The second pass converts only colour pictures and keeps an image when its
     * grey version is not smaller (bilevel / grey scans are not re-encoded — audit C28); it reports progress. The source
     * is loaded with temp-file memory (C10). Returns the number of rasterised pages.
     */
    suspend fun grayscale(src: File, password: String?, out: File, tempDir: File, ctx: android.content.Context? = null, onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val tmp = File(tempDir, "cv6_gray_${System.nanoTime()}.pdf")
        var rasterised = 0
        var pagesTotal = 1
        try {
            PdfFlow6.load(src, password).use { doc ->
                doc.isAllSecurityToBeRemoved = true   // the engine protects the result again like the source (keepProtection)
                val seen = IdentityHashMap<Any, Boolean>()
                val n = doc.numberOfPages
                pagesTotal = maxOf(1, n)
                val raster = HashSet<Int>()
                for (i in 0 until n) {
                    coroutineContext.ensureActive()
                    onProgress(i, pagesTotal * 2)
                    val page = doc.getPage(i)
                    val ok = runCatching {
                        val toks = PDFStreamParser(page).let { it.parse(); it.tokens }
                        if (needsRaster(toks, page.resources)) raster += i
                        val stream = PDStream(doc)
                        writeTokens(stream, rewrite(toks, page.resources))
                        page.setContents(stream)
                    }.isSuccess
                    if (!ok) raster += i
                    forms(doc, page.resources, seen, 0)
                    annotations(doc, page, seen)
                }
                if (ctx != null && raster.isNotEmpty()) rasterised = rasterGray(ctx, src, password, doc, raster)
                doc.save(tmp)
            }
            // pictures: colour images to grey at print resolution; grey / 1-bit pictures are kept as they are
            Optimizer.optimize(tmp, null, out, OptimizeOptions(imageDpi = 300, jpegQuality = 85, grayImages = true, onlyIfSmaller = false, stripMetadata = false),
                onProgress = { d, t -> onProgress(pagesTotal + (if (t > 0) d * pagesTotal / t else 0), pagesTotal * 2) })
        } finally { tmp.delete() }
        onProgress(1, 1)
        rasterised
    }

    /** Replaces [pages] of [doc] with grey renders of the source pages (PDFium, 200 dpi). */
    private suspend fun rasterGray(ctx: android.content.Context, src: File, password: String?, doc: PDDocument, pages: Set<Int>): Int {
        val s = com.smartdocs.editor.core.pdf.PdfSession.open(ctx, src, password)
        var n = 0
        try {
            pages.sorted().forEach { i ->
                coroutineContext.ensureActive()
                val (w, h) = s.pageSize(i)
                val k = Images6.renderScale(w, h, 200, ImageIo6.pixelBudget())
                val bmp = s.render(i, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = true)
                val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                com.smartdocs.editor.core.tools5.PixelOps.toGray(px)
                val grey = android.graphics.Bitmap.createBitmap(px, bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
                s.invalidateCache()
                val img = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, grey, 0.85f)
                grey.recycle()
                val page = doc.getPage(i)
                val box = page.cropBox
                val rot = page.rotation.mod(360)
                page.rotation = 0
                val dispW = if (rot == 90 || rot == 270) box.height else box.width
                val dispH = if (rot == 90 || rot == 270) box.width else box.height
                val nb = PDRectangle(box.lowerLeftX, box.lowerLeftY, dispW, dispH)
                page.mediaBox = nb; page.cropBox = nb
                page.resources = PDResources()
                page.annotations = emptyList()   // drawn into the picture
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs -> cs.drawImage(img, nb.lowerLeftX, nb.lowerLeftY, dispW, dispH) }
                n++
            }
        } finally { s.closeAsync() }
        return n
    }

    /**
     * N pages / slides per sheet ([n] = 2, 3, 4, 6, 8, 9, 16) on A4, a thin frame around each, reading order
     * right-to-left for Arabic documents ([rtl]).
     */
    suspend fun nUp(src: File, password: String?, n: Int, out: File, rtl: Boolean, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        PdfFlow6.load(src, password).use { s ->
            // an empty / damaged PDF gives a clear error instead of an IndexOutOfBounds (convert audit C46)
            if (s.numberOfPages == 0) throw IllegalStateException("empty PDF")
            PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { d ->
                val lu = LayerUtility(d)
                // the sheet orientation follows most pages, not only the first (audit C46)
                val sample = (0 until minOf(s.numberOfPages, 12)).map { s.getPage(it) }
                val first = sample.groupBy { p -> p.cropBox.width > p.cropBox.height != ((p.rotation / 90) % 2 == 1) }.maxByOrNull { it.value.size }!!.value.first()
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
