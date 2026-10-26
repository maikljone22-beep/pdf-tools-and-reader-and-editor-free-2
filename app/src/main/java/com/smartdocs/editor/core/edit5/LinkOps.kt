package com.smartdocs.editor.core.edit5

import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a link does. */
enum class LinkKind { URI, PAGE, NAMED, REMOTE, LAUNCH, MAIL, OTHER }

/** How a go-to destination shows the target page. XYZ keeps [LinkSpec.zoom] (0 = keep the reader's zoom). */
enum class DestFit { XYZ, FIT, FITH }

enum class LinkBorder { NONE, SOLID, DASHED, UNDERLINE }

/**
 * A real /Link annotation. [rect] and [quads] are in PDF user space (top > bottom). For [LinkKind.PAGE] / remote,
 * [page] is zero-based. [file] is the target file name for REMOTE (another PDF) and LAUNCH (any other file);
 * stored relative to the document when it sits in the same folder, so the link still works after copying both.
 */
data class LinkSpec(
    val rect: RectF,
    val kind: LinkKind = LinkKind.URI,
    val uri: String = "",
    val page: Int = 0,
    val fit: DestFit = DestFit.XYZ,
    val zoom: Float = 0f,
    val top: Float? = null,
    val named: String = "",
    val file: String = "",
    val newWindow: Boolean = true,
    val border: LinkBorder = LinkBorder.NONE,
    val borderWidth: Float = 1f,
    val color: Int = 0x1565C0,
    val highlight: String = "I",
    val quads: List<RectF> = emptyList(),
    val contents: String = "",
)

/** A link found on a page: its position in the page's /Annots ([index]) and its decoded [spec]. */
data class PageLinkInfo(val page: Int, val index: Int, val spec: LinkSpec, val editable: Boolean)

/**
 * Real link annotations (ISO 32000 §12.5.6.5) that open in every PDF app: URI, go-to page (XYZ / Fit / FitH),
 * named destination, other PDF (GoToR), other file (Launch) and e-mail (URI mailto:). Replaces the reader's old
 * "link" tool that only stored a note.
 */
object LinkOps {
    // ---------------- reading ----------------

    suspend fun list(src: File, password: String? = null, pageFilter: Int? = null): List<PageLinkInfo> = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val out = ArrayList<PageLinkInfo>()
            val pages = if (pageFilter != null) listOf(pageFilter) else (0 until doc.numberOfPages).toList()
            for (p in pages) {
                if (p !in 0 until doc.numberOfPages) continue
                val annots = runCatching { doc.getPage(p).annotations }.getOrNull() ?: continue
                annots.forEachIndexed { i, a -> if (a is PDAnnotationLink) decode(doc, a)?.let { (s, ok) -> out += PageLinkInfo(p, i, s, ok) } }
            }
            out
        }
    }

    /** Link under the user-space point ([x], [y]) on [page], or null. */
    suspend fun at(src: File, password: String?, page: Int, x: Float, y: Float): PageLinkInfo? =
        list(src, password, page).lastOrNull { val r = it.spec.rect; x in minOf(r.left, r.right)..maxOf(r.left, r.right) && y in minOf(r.top, r.bottom)..maxOf(r.top, r.bottom) }

    private fun decode(doc: PDDocument, a: PDAnnotationLink): Pair<LinkSpec, Boolean>? {
        val d = a.cosObject
        val r = a.rectangle ?: return null
        val rect = E5Geom.rectF(r)
        val bs = d.getCOSDictionary(COSName.BS)
        val style = bs?.getNameAsString(COSName.S)
        val bw = bs?.getFloat(COSName.W, 1f) ?: ((d.getDictionaryObject(COSName.BORDER) as? COSArray)?.let { (it.getObject(2) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() } ?: 1f)
        val border = when {
            bw <= 0f -> LinkBorder.NONE
            style == "D" -> LinkBorder.DASHED
            style == "U" -> LinkBorder.UNDERLINE
            bs == null && d.getDictionaryObject(COSName.BORDER) == null -> LinkBorder.SOLID   // spec default [0 0 1]
            else -> LinkBorder.SOLID
        }
        val c = (d.getDictionaryObject(COSName.C) as? COSArray)?.toFloatArray()
        val color = if (c != null && c.size >= 3) (((c[0] * 255).toInt() and 0xFF) shl 16) or (((c[1] * 255).toInt() and 0xFF) shl 8) or ((c[2] * 255).toInt() and 0xFF) else 0x1565C0
        val q = a.quadPoints?.let { qp -> (0 until qp.size / 8).map { k -> val o = k * 8; RectF(minOf(qp[o], qp[o + 4]), maxOf(qp[o + 1], qp[o + 3]), maxOf(qp[o + 2], qp[o + 6]), minOf(qp[o + 5], qp[o + 7])) } } ?: emptyList()
        var spec = LinkSpec(rect = rect, border = border, borderWidth = bw.coerceAtLeast(0.5f), color = color, highlight = d.getNameAsString(COSName.H) ?: "I", quads = q, contents = a.contents ?: "")
        val action = d.getCOSDictionary(COSName.A)
        val dest = d.getDictionaryObject(COSName.DEST)
        var editable = true
        spec = when {
            action != null -> when (action.getNameAsString(COSName.S)) {
                "URI" -> {
                    val u = action.getString(COSName.URI) ?: ""
                    if (u.startsWith("mailto:", true)) spec.copy(kind = LinkKind.MAIL, uri = u) else spec.copy(kind = LinkKind.URI, uri = u)
                }
                "GoTo" -> destSpec(doc, spec, action.getDictionaryObject(COSName.D))
                "GoToR" -> {
                    val f = fileName(action.getDictionaryObject(COSName.F))
                    val dd = action.getDictionaryObject(COSName.D)
                    val pg = ((dd as? COSArray)?.getObject(0) as? COSInteger)?.intValue() ?: 0
                    val fit = (dd as? COSArray)?.getName(1)
                    spec.copy(kind = LinkKind.REMOTE, file = f, page = pg, fit = if (fit == "FitH") DestFit.FITH else if (fit == "XYZ") DestFit.XYZ else DestFit.FIT,
                        named = (dd as? COSString)?.string ?: (dd as? COSName)?.name ?: "", newWindow = action.getBoolean(COSName.getPDFName("NewWindow"), true))
                }
                "Launch" -> spec.copy(kind = LinkKind.LAUNCH, file = fileName(action.getDictionaryObject(COSName.F)), newWindow = action.getBoolean(COSName.getPDFName("NewWindow"), true))
                else -> { editable = false; spec.copy(kind = LinkKind.OTHER, uri = action.getNameAsString(COSName.S) ?: "") }
            }
            dest != null -> destSpec(doc, spec, dest)
            else -> { editable = false; spec.copy(kind = LinkKind.OTHER) }
        }
        return spec to editable
    }

    private fun fileName(f: COSBase?): String = when (f) {
        is COSString -> f.string
        is COSDictionary -> f.getString(COSName.UF) ?: f.getString(COSName.F) ?: ""
        is COSObject -> fileName(f.getObject())
        else -> ""
    }

    private fun destSpec(doc: PDDocument, base: LinkSpec, d: COSBase?): LinkSpec {
        val v = if (d is COSObject) d.getObject() else d
        return when (v) {
            is COSString, is COSName -> {
                val name = (v as? COSString)?.string ?: (v as COSName).name
                val pg = runCatching { doc.documentCatalog.findNamedDestinationPage(PDNamedDestination(name))?.retrievePageNumber() }.getOrNull() ?: -1
                base.copy(kind = LinkKind.NAMED, named = name, page = pg.coerceAtLeast(0))
            }
            is COSArray -> {
                val pd = runCatching { com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination.create(v) as? PDPageDestination }.getOrNull()
                val pg = pd?.retrievePageNumber() ?: ((v.getObject(0) as? COSInteger)?.intValue() ?: 0)
                val fitName = v.getName(1)
                val fit = when (fitName) { "XYZ" -> DestFit.XYZ; "FitH", "FitBH" -> DestFit.FITH; else -> DestFit.FIT }
                val zoom = if (fit == DestFit.XYZ) ((v.getObject(4) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue() ?: 0f) else 0f
                val top = when (fit) {
                    DestFit.XYZ -> (v.getObject(3) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue()
                    DestFit.FITH -> (v.getObject(2) as? com.tom_roush.pdfbox.cos.COSNumber)?.floatValue()
                    else -> null
                }
                base.copy(kind = LinkKind.PAGE, page = pg.coerceAtLeast(0), fit = fit, zoom = zoom, top = top)
            }
            else -> base.copy(kind = LinkKind.OTHER)
        }
    }

    // ---------------- writing ----------------

    /** Add links to pages: map page → specs. */
    suspend fun add(src: File, out: File, password: String?, links: Map<Int, List<LinkSpec>>) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            links.forEach { (p, specs) ->
                if (p !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(p)
                val annots = page.annotations
                specs.forEach { annots.add(build(doc, page, it, src)) }
                page.annotations = annots
            }
        }
    }

    /** Replace link [index] of [page] by [spec] (keeps its place in the tab order). */
    suspend fun update(src: File, out: File, password: String?, page: Int, index: Int, spec: LinkSpec) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val pg = doc.getPage(page)
            val annots = pg.annotations
            if (index in annots.indices && annots[index] is PDAnnotationLink) { annots[index] = build(doc, pg, spec, src); pg.annotations = annots }
        }
    }

    /** Delete links: page → annotation indexes. */
    suspend fun delete(src: File, out: File, password: String?, targets: Map<Int, Set<Int>>) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            targets.forEach { (p, idx) ->
                if (p !in 0 until doc.numberOfPages) return@forEach
                val pg = doc.getPage(p)
                val annots = pg.annotations
                val keep = annots.filterIndexed { i, a -> !(i in idx && a is PDAnnotationLink) }
                pg.annotations = keep.toMutableList()
            }
        }
    }

    /** Remove every link ([webOnly] = only URI / mailto links). Returns how many were removed. */
    suspend fun removeAll(src: File, out: File, password: String?, webOnly: Boolean): Int = withContext(Dispatchers.IO) {
        var n = 0
        E5Io.edit(src, out, password) { doc ->
            for (pg in doc.pages) {
                val annots = runCatching { pg.annotations }.getOrNull() ?: continue
                val keep = annots.filter { a ->
                    val drop = a is PDAnnotationLink && (!webOnly || a.cosObject.getCOSDictionary(COSName.A)?.getNameAsString(COSName.S) == "URI")
                    if (drop) n++
                    !drop
                }
                if (keep.size != annots.size) pg.annotations = keep.toMutableList()
            }
        }
        n
    }

    private fun relativeName(target: String, doc: File): String {
        val t = File(target)
        return if (t.parentFile?.absolutePath == doc.absoluteFile.parentFile?.absolutePath) t.name else target
    }

    private fun build(doc: PDDocument, page: PDPage, s: LinkSpec, src: File): PDAnnotation {
        val link = PDAnnotationLink()
        val d = link.cosObject
        link.rectangle = E5Geom.rect(s.rect)
        link.setPage(page)
        link.isPrinted = true
        if (s.contents.isNotBlank()) link.contents = s.contents
        d.setName(COSName.H, s.highlight.ifBlank { "I" })
        val rgb = E5Io.rgb(s.color)
        d.setItem(COSName.C, COSArray().apply { rgb.forEach { add(COSFloat(it)) } })
        val bs = COSDictionary()
        bs.setItem(COSName.TYPE, COSName.getPDFName("Border"))
        when (s.border) {
            LinkBorder.NONE -> { bs.setFloat(COSName.W, 0f); d.setItem(COSName.BORDER, COSArray().apply { add(COSInteger.ZERO); add(COSInteger.ZERO); add(COSInteger.ZERO) }) }
            LinkBorder.SOLID -> { bs.setFloat(COSName.W, s.borderWidth); bs.setName(COSName.S, "S") }
            LinkBorder.DASHED -> { bs.setFloat(COSName.W, s.borderWidth); bs.setName(COSName.S, "D"); bs.setItem(COSName.D, COSArray().apply { add(COSInteger.get(3)); add(COSInteger.get(2)) }) }
            LinkBorder.UNDERLINE -> { bs.setFloat(COSName.W, s.borderWidth); bs.setName(COSName.S, "U") }
        }
        d.setItem(COSName.BS, bs)
        if (s.quads.isNotEmpty()) link.quadPoints = E5Geom.quads(s.quads)
        val n = doc.numberOfPages
        when (s.kind) {
            LinkKind.URI, LinkKind.MAIL, LinkKind.OTHER -> {
                val a = COSDictionary(); a.setItem(COSName.TYPE, COSName.getPDFName("Action")); a.setName(COSName.S, "URI")
                a.setString(COSName.URI, if (s.kind == LinkKind.MAIL && !s.uri.startsWith("mailto:", true)) "mailto:" + s.uri.trim() else LinkDetect.normalizeUrl(s.uri))
                d.setItem(COSName.A, a)
            }
            LinkKind.PAGE -> d.setItem(COSName.DEST, destArray(doc.getPage(s.page.coerceIn(0, n - 1)).cosObject, s))
            LinkKind.NAMED -> d.setItem(COSName.DEST, COSString(s.named))
            LinkKind.REMOTE -> {
                val a = COSDictionary(); a.setItem(COSName.TYPE, COSName.getPDFName("Action")); a.setName(COSName.S, "GoToR")
                a.setItem(COSName.F, fileSpec(relativeName(s.file, src)))
                a.setItem(COSName.D, if (s.named.isNotBlank()) COSString(s.named) else destArray(COSInteger.get(s.page.coerceAtLeast(0).toLong()), s))
                a.setBoolean(COSName.getPDFName("NewWindow"), s.newWindow)
                d.setItem(COSName.A, a)
            }
            LinkKind.LAUNCH -> {
                val a = COSDictionary(); a.setItem(COSName.TYPE, COSName.getPDFName("Action")); a.setName(COSName.S, "Launch")
                a.setItem(COSName.F, fileSpec(relativeName(s.file, src)))
                a.setBoolean(COSName.getPDFName("NewWindow"), s.newWindow)
                d.setItem(COSName.A, a)
            }
        }
        if (s.border != LinkBorder.NONE) runCatching { link.constructAppearances(doc) }
        return link
    }

    private fun fileSpec(name: String): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FILESPEC)
        setString(COSName.F, name); setString(COSName.UF, name)
    }

    private fun destArray(pageRef: COSBase, s: LinkSpec): COSArray = COSArray().apply {
        add(pageRef)
        when (s.fit) {
            DestFit.FIT -> add(COSName.getPDFName("Fit"))
            DestFit.FITH -> { add(COSName.getPDFName("FitH")); add(s.top?.let { COSFloat(it) } ?: COSNull.NULL) }
            DestFit.XYZ -> {
                add(COSName.getPDFName("XYZ")); add(COSNull.NULL); add(s.top?.let { COSFloat(it) } ?: COSNull.NULL)
                add(if (s.zoom > 0f) COSFloat(s.zoom) else COSNull.NULL)
            }
        }
    }

    // ---------------- text links ----------------

    /**
     * Turn plain-text URLs / e-mails into real links. [boxes] supplies, per page, the page text and one user-space
     * box per character (PDFium charBoxes order). Returns how many links were added.
     */
    suspend fun linkifyText(src: File, out: File, password: String?, pages: Map<Int, Pair<String, List<RectF>>>, phones: Boolean = false): Int {
        val add = HashMap<Int, List<LinkSpec>>()
        var n = 0
        pages.forEach { (p, tb) ->
            val (text, boxes) = tb
            val specs = LinkDetect.find(text, phones).mapNotNull { tl ->
                val rs = tl.range.mapNotNull { boxes.getOrNull(it) }.filter { it.width() != 0f }
                val lines = linesOf(rs)
                val u = E5Geom.union(lines) ?: return@mapNotNull null
                LinkSpec(u, if (tl.target.startsWith("mailto:")) LinkKind.MAIL else LinkKind.URI, uri = tl.target, quads = lines)
            }
            if (specs.isNotEmpty()) { add[p] = specs; n += specs.size }
        }
        if (n > 0) add(src, out, password, add) else if (out.absolutePath != src.absolutePath) src.copyTo(out, overwrite = true)
        return n
    }

    /** Merge character boxes into one rect per text line (PDF convention). */
    fun linesOf(rs: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        for (r in rs) {
            val t = maxOf(r.top, r.bottom); val b = minOf(r.top, r.bottom)
            val last = out.lastOrNull()
            if (last != null && kotlin.math.abs((last.top + last.bottom) / 2 - (t + b) / 2) < (last.top - last.bottom) * 0.6f) {
                last.left = minOf(last.left, minOf(r.left, r.right)); last.right = maxOf(last.right, maxOf(r.left, r.right))
                last.top = maxOf(last.top, t); last.bottom = minOf(last.bottom, b)
            } else out += RectF(minOf(r.left, r.right), t, maxOf(r.left, r.right), b)
        }
        return out
    }

}
