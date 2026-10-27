package com.smartdocs.editor.core.annot

import android.content.Context
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.fdf.FDFAnnotation
import com.tom_roush.pdfbox.pdmodel.fdf.FDFDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.roundToInt

/** Export comments to XFDF / FDF, import them back, and merge comments from another copy of the PDF. */
object CommentExchange {

    private val IRT = COSName.getPDFName("IRT")
    private val RT = COSName.getPDFName("RT")
    private val POPUP = COSName.getPDFName("Popup")
    private val SKIP = setOf("Link", "Popup", "Widget")

    private fun open(src: File, pw: String?): PDDocument = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)

    // ======================= XFDF export =======================

    private fun esc(s: String) = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
                else -> if (c.code < 0x20 && c != '\n' && c != '\t' && c != '\r') append(' ') else append(c)
            }
        }
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun floats(cos: COSDictionary, key: String): FloatArray? =
        (cos.getDictionaryObject(COSName.getPDFName(key)) as? COSArray)?.let { a -> FloatArray(a.size()) { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f } }

    private fun hex(cos: COSDictionary, key: String): String? {
        val v = floats(cos, key) ?: return null
        fun b(x: Float) = (x.coerceIn(0f, 1f) * 255f).roundToInt()
        val rgb = when (v.size) { 1 -> Triple(b(v[0]), b(v[0]), b(v[0])); 3 -> Triple(b(v[0]), b(v[1]), b(v[2])); else -> return null }
        return String.format(Locale.US, "#%02X%02X%02X", rgb.first, rgb.second, rgb.third)
    }

    /** Write every stored comment of [src] as XFDF (Adobe XML Forms Data Format) into [out]. Returns the count. */
    suspend fun exportXfdf(src: File, pw: String?, out: File): Int = withContext(Dispatchers.IO) {
        open(src, pw).use { doc ->
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n")
            sb.append("<f href=\"").append(esc(src.name)).append("\"/>\n<annots>\n")
            var count = 0
            doc.pages.forEachIndexed { pi, page ->
                val list = runCatching { page.annotations }.getOrNull() ?: return@forEachIndexed
                // names for in-reply-to links (annotations without /NM get one for this export only)
                val names = IdentityHashMap<COSDictionary, String>()
                list.forEachIndexed { i, an -> names[an.cosObject] = an.annotationName?.takeIf { it.isNotBlank() } ?: "sd-x-$pi-$i" }
                list.forEach { an -> if (runCatching { element(sb, an, pi, names) }.getOrDefault(false)) count++ }
            }
            sb.append("</annots>\n</xfdf>\n")
            out.writeText(sb.toString(), Charsets.UTF_8)
            count
        }
    }

    private fun element(sb: StringBuilder, an: PDAnnotation, page: Int, names: IdentityHashMap<COSDictionary, String>): Boolean {
        val sub = an.subtype ?: return false
        if (sub in SKIP) return false
        val tag = when (sub) {
            "Text" -> "text"; "FreeText" -> "freetext"; "Line" -> "line"; "Square" -> "square"; "Circle" -> "circle"
            "Polygon" -> "polygon"; "PolyLine" -> "polyline"; "Highlight" -> "highlight"; "Underline" -> "underline"
            "Squiggly" -> "squiggly"; "StrikeOut" -> "strikeout"; "Stamp" -> "stamp"; "Caret" -> "caret"; "Ink" -> "ink"
            "FileAttachment" -> "fileattachment"; "Sound" -> "sound"; else -> return false
        }
        val cos = an.cosObject
        val r = an.rectangle ?: return false
        sb.append('<').append(tag)
        fun attr(k: String, v: String?) { if (!v.isNullOrEmpty()) sb.append(' ').append(k).append("=\"").append(esc(v)).append('"') }
        attr("page", page.toString())
        attr("rect", "${f(r.lowerLeftX)},${f(r.lowerLeftY)},${f(r.upperRightX)},${f(r.upperRightY)}")
        attr("color", hex(cos, "C"))
        attr("interior-color", hex(cos, "IC"))
        cos.getDictionaryObject(COSName.getPDFName("CA"))?.let { attr("opacity", f((it as? COSNumber)?.floatValue() ?: 1f)) }
        attr("title", cos.getString(COSName.T))
        attr("subject", cos.getString(COSName.getPDFName("Subj")))
        attr("date", cos.getString(COSName.M))
        attr("creationdate", cos.getString(COSName.getPDFName("CreationDate")))
        attr("name", names[cos])
        attr("flags", buildList { if (an.isHidden) add("hidden"); if (an.isPrinted) add("print"); if (an.isNoZoom) add("nozoom"); if (an.isNoRotate) add("norotate") }.joinToString(","))
        (cos.getDictionaryObject(IRT) as? COSDictionary)?.let { p ->
            attr("inreplyto", names[p])
            attr("replyType", if (cos.getNameAsString(RT) == "Group") "group" else "reply")
        }
        attr("intent", cos.getNameAsString(COSName.getPDFName("IT")))
        attr("state", cos.getString(COSName.getPDFName("State")))
        attr("statemodel", cos.getString(COSName.getPDFName("StateModel")))
        ((cos.getDictionaryObject(COSName.getPDFName("BS")) as? COSDictionary)?.getFloat(COSName.getPDFName("W"), -1f))?.takeIf { it >= 0f }?.let { attr("width", f(it)) }
        when (sub) {
            "Text" -> attr("icon", cos.getNameAsString(COSName.NAME) ?: "Comment")
            "Highlight", "Underline", "Squiggly", "StrikeOut" -> floats(cos, "QuadPoints")?.let { q -> attr("coords", q.joinToString(",") { f(it) }) }
            "Line" -> floats(cos, "L")?.takeIf { it.size >= 4 }?.let { l ->
                attr("start", "${f(l[0])},${f(l[1])}"); attr("end", "${f(l[2])},${f(l[3])}")
                (cos.getDictionaryObject(COSName.getPDFName("LE")) as? COSArray)?.let { le ->
                    attr("head", (le.getObject(0) as? COSName)?.name); attr("tail", (le.getObject(1) as? COSName)?.name)
                }
            }
            "Stamp" -> attr("icon", cos.getNameAsString(COSName.NAME))
            "FileAttachment" -> attr("icon", cos.getNameAsString(COSName.NAME))
        }
        sb.append(">\n")
        an.contents?.takeIf { it.isNotEmpty() }?.let { sb.append("<contents>").append(esc(it)).append("</contents>\n") }
        when (sub) {
            "Ink" -> (cos.getDictionaryObject(COSName.INKLIST) as? COSArray)?.let { ink ->
                sb.append("<inklist>")
                for (i in 0 until ink.size()) {
                    val g = ink.getObject(i) as? COSArray ?: continue
                    val v = FloatArray(g.size()) { (g.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
                    sb.append("<gesture>").append((0 until v.size / 2).joinToString(";") { "${f(v[it * 2])},${f(v[it * 2 + 1])}" }).append("</gesture>")
                }
                sb.append("</inklist>\n")
            }
            "Polygon", "PolyLine" -> floats(cos, "Vertices")?.let { v ->
                sb.append("<vertices>").append((0 until v.size / 2).joinToString(";") { "${f(v[it * 2])},${f(v[it * 2 + 1])}" }).append("</vertices>\n")
            }
            "FreeText" -> cos.getString(COSName.DA)?.let { sb.append("<defaultappearance>").append(esc(it)).append("</defaultappearance>\n") }
        }
        sb.append("</").append(tag).append(">\n")
        return true
    }

    // ======================= FDF export =======================

    private fun clean(src: COSDictionary): COSDictionary = COSDictionary().apply {
        addAll(src)
        removeItem(COSName.P); removeItem(POPUP); removeItem(COSName.PARENT); removeItem(COSName.PAGE)
    }

    /** Write every stored comment of [src] into an FDF file [out] (PdfBox FDFDocument). Returns the count. */
    suspend fun exportFdf(src: File, pw: String?, out: File): Int = withContext(Dispatchers.IO) {
        open(src, pw).use { doc ->
            FDFDocument().use { fdf ->
                val annots = ArrayList<FDFAnnotation>()
                val clones = IdentityHashMap<COSDictionary, COSDictionary>()
                val pending = ArrayList<Pair<COSDictionary, COSDictionary>>()
                doc.pages.forEachIndexed { pi, page ->
                    val list = runCatching { page.annotations }.getOrNull() ?: return@forEachIndexed
                    list.forEach { an ->
                        if (an.subtype in SKIP) return@forEach
                        val c = clean(an.cosObject)
                        c.removeItem(COSName.AP)                // importers rebuild appearances; keeps the FDF small
                        c.setItem(COSName.PAGE, COSInteger.get(pi.toLong()))
                        clones[an.cosObject] = c; pending += an.cosObject to c
                    }
                }
                // in-reply-to links point at the clones, never back into the source document
                pending.forEach { (orig, c) ->
                    (orig.getDictionaryObject(IRT) as? COSDictionary)?.let { p -> clones[p]?.let { c.setItem(IRT, it) } ?: c.removeItem(IRT) }
                    runCatching { FDFAnnotation.create(c) }.getOrNull()?.let { annots += it }
                }
                fdf.catalog.getFDF().setAnnotations(annots)
                fdf.catalog.getFDF().setFile(com.tom_roush.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification().apply { file = src.name })
                fdf.save(out)
                annots.size
            }
        }
    }

    // ======================= import / merge =======================

    private fun signature(page: Int, cos: COSDictionary): String {
        val r = (cos.getDictionaryObject(COSName.RECT) as? COSArray)?.let { a -> (0 until a.size()).joinToString(",") { ((a.getObject(it) as? COSNumber)?.floatValue() ?: 0f).roundToInt().toString() } } ?: ""
        return "$page|${cos.getNameAsString(COSName.SUBTYPE)}|$r|${cos.getString(COSName.CONTENTS) ?: ""}"
    }

    /**
     * Append annotation dictionaries (page index → dictionary) to [doc]. Skips duplicates (same /NM, or same
     * subtype + rect + contents on the page), rebuilds appearance streams for kinds the editor can write when
     * the source has none, and re-links /IRT replies and groups to the copies. Returns the number added.
     */
    private fun append(context: Context, doc: PDDocument, entries: List<Pair<Int, COSDictionary>>): Int {
        val nms = HashMap<Int, MutableSet<String>>(); val sigs = HashMap<Int, MutableSet<String>>()
        val byNm = HashMap<String, COSDictionary>()
        doc.pages.forEachIndexed { pi, page ->
            runCatching { page.annotations }.getOrNull()?.forEach { an ->
                an.annotationName?.let { nms.getOrPut(pi) { HashSet() } += it; byNm[it] = an.cosObject }
                sigs.getOrPut(pi) { HashSet() } += signature(pi, an.cosObject)
            }
        }
        val map = IdentityHashMap<COSDictionary, COSDictionary>()
        var added = 0
        fun addOne(pi: Int, orig: COSDictionary, parent: COSDictionary?): Boolean {
            if (pi !in 0 until doc.numberOfPages) return false
            val sub = orig.getNameAsString(COSName.SUBTYPE) ?: return false
            if (sub in SKIP) return false
            val nm = orig.getString(COSName.NM)
            if (nm != null && nm in nms[pi].orEmpty()) { byNm[nm]?.let { map[orig] = it }; return false }
            val sig = signature(pi, orig)
            if (sig in sigs[pi].orEmpty()) return false
            val page = doc.getPage(pi)
            val c = clean(orig)
            if (parent != null) c.setItem(IRT, parent) else c.removeItem(IRT)
            val an = runCatching { PDAnnotation.createAnnotation(c) }.getOrNull() ?: return false
            var result: COSDictionary = c
            val rebuilt = if (!c.containsKey(COSName.AP) && !an.isHidden) runCatching { AnnotationWriter.toAnnot(an, pi) }.getOrNull() else null
            if (rebuilt != null) {
                val list = page.annotations
                val before = list.size
                AnnotationWriter.write(context, doc, page, rebuilt)
                val now = page.annotations
                if (now.size > before) {
                    result = now[before].cosObject
                    listOf(COSName.NM, COSName.T, COSName.M, COSName.getPDFName("CreationDate"), COSName.getPDFName("Subj"), IRT, RT, COSName.getPDFName("IT"))
                        .forEach { k -> c.getItem(k)?.let { result.setItem(k, it) } }
                } else { an.page = page; page.annotations.add(an) }
            } else {
                an.page = page
                page.annotations.add(an)
            }
            map[orig] = result
            nm?.let { nms.getOrPut(pi) { HashSet() } += it; byNm[it] = result }
            sigs.getOrPut(pi) { HashSet() } += sig
            added++
            return true
        }
        // parents first, then replies / states / group members (their parents must exist to link to)
        entries.filter { !it.second.containsKey(IRT) }.forEach { (pi, d) -> runCatching { addOne(pi, d, null) } }
        entries.filter { it.second.containsKey(IRT) }.forEach { (pi, d) ->
            runCatching {
                val p = d.getDictionaryObject(IRT) as? COSDictionary ?: return@runCatching
                val target = map[p] ?: p.getString(COSName.NM)?.let { byNm[it] } ?: return@runCatching
                addOne(pi, d, target)
            }
        }
        return added
    }

    /** Import an FDF or XFDF file into [src], writing the result to [out]. Returns the number of comments added. */
    suspend fun importFile(context: Context, src: File, out: File, pw: String?, data: File): Int = withContext(Dispatchers.IO) {
        val head = runCatching { data.inputStream().use { s -> ByteArray(256).let { b -> String(b, 0, s.read(b).coerceAtLeast(0), Charsets.ISO_8859_1) } } }.getOrDefault("")
        val xml = head.trimStart().startsWith("<") || data.extension.equals("xfdf", true)
        val fdf = if (xml) FDFDocument.loadXFDF(data) else FDFDocument.load(data)
        fdf.use {
            PdfOps.loadRaw(src, pw).use { doc ->
                val entries = fdf.catalog.getFDF().annotations.orEmpty().mapNotNull { fa -> (fa.page ?: 0) to fa.cosObject }
                val n = append(context, doc, entries)
                SignedSave.saveEdited(doc, out, pw, entries.map { it.first })
                n
            }
        }
    }

    /** Merge comments of [other] (another copy of the same document) into [src] → [out]. Returns the count added. */
    suspend fun merge(context: Context, src: File, out: File, pw: String?, other: File, otherPw: String? = null): Int = withContext(Dispatchers.IO) {
        open(other, otherPw).use { o ->
            PdfOps.loadRaw(src, pw).use { doc ->
                val entries = ArrayList<Pair<Int, COSDictionary>>()
                o.pages.forEachIndexed { pi, page ->
                    runCatching { page.annotations }.getOrNull()?.forEach { an -> if (an.subtype !in SKIP) entries += pi to an.cosObject }
                }
                val n = append(context, doc, entries)
                SignedSave.saveEdited(doc, out, pw, entries.map { it.first })  // the other document stays open until its objects are written
                n
            }
        }
    }
}
