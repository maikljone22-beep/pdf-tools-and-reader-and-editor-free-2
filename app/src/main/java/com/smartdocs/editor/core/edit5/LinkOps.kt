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

/**
 * How a go-to destination shows the target page. XYZ keeps [LinkSpec.zoom] (0 = keep the reader's zoom); FITV = fit
 * height, FITB / FITBH = fit the visible content (whole / width), FITR = fit a rectangle.
 */
enum class DestFit { XYZ, FIT, FITH, FITV, FITB, FITBH, FITR }

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
    val left: Float? = null,
    /** FitR rectangle (left, bottom, right, top). */
    val fitR: List<Float>? = null,
    /** For [LinkKind.OTHER]: the action list (several actions run in order; kept types are copied). */
    val actions: List<NavAction> = emptyList(),
    /** Cross-reference group the link belongs to ("" = none; see [AutoLinks.groupLinks]). */
    val group: String = "",
) {
    val view: DestView get() = DestView(fit, left, top, zoom, fitR)
    fun withView(v: DestView) = copy(fit = v.fit, left = v.left, top = v.top, zoom = v.zoom, fitR = v.rect)
}

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
        var spec = LinkSpec(rect = rect, border = border, borderWidth = bw.coerceAtLeast(0.5f), color = color, highlight = d.getNameAsString(COSName.H) ?: "I", quads = q, contents = a.contents ?: "",
            group = (d.getDictionaryObject(AutoLinks.XREF_KEY) as? COSString)?.string ?: "")
        val action = d.getCOSDictionary(COSName.A)
        val dest = d.getDictionaryObject(COSName.DEST)
        var editable = true
        spec = when {
            action != null && action.getDictionaryObject(COSName.NEXT) != null ->
                spec.copy(kind = LinkKind.OTHER, uri = action.getNameAsString(COSName.S) ?: "", actions = NavActions.read(doc, action))
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
                    spec.withView((dd as? COSArray)?.let { Dests.view(it) } ?: DestView(DestFit.FIT)).copy(kind = LinkKind.REMOTE, file = f, page = pg,
                        named = (dd as? COSString)?.string ?: (dd as? COSName)?.name ?: "", newWindow = action.getBoolean(COSName.getPDFName("NewWindow"), true))
                }
                "Launch" -> spec.copy(kind = LinkKind.LAUNCH, file = fileName(action.getDictionaryObject(COSName.F)), newWindow = action.getBoolean(COSName.getPDFName("NewWindow"), true))
                // named, JavaScript, form, media … actions: edited as an action list (unknown types are kept)
                else -> spec.copy(kind = LinkKind.OTHER, uri = action.getNameAsString(COSName.S) ?: "", actions = NavActions.read(doc, action))
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
                base.withView(Dests.view(v)).copy(kind = LinkKind.PAGE, page = pg.coerceAtLeast(0))
            }
            else -> base.copy(kind = LinkKind.OTHER)
        }
    }

    // ---------------- writing ----------------

    /** Add links to pages: map page → specs. */
    suspend fun add(src: File, out: File, password: String?, links: Map<Int, List<LinkSpec>>) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed ->
            links.forEach { (p, specs) ->
                if (p !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(p)
                val annots = page.annotations
                specs.forEach { annots.add(build(doc, page, it, src)) }
                page.annotations = annots
                changed += page
            }
        }
    }

    /** Replace link [index] of [page] by [spec] (keeps its place in the tab order). */
    suspend fun update(src: File, out: File, password: String?, page: Int, index: Int, spec: LinkSpec) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed ->
            val pg = doc.getPage(page)
            val annots = pg.annotations
            if (index in annots.indices && annots[index] is PDAnnotationLink) {
                annots[index] = build(doc, pg, spec, src, annots[index].cosObject.getDictionaryObject(COSName.A)); pg.annotations = annots; changed += pg
            }
        }
    }

    /** Delete links: page → annotation indexes. */
    suspend fun delete(src: File, out: File, password: String?, targets: Map<Int, Set<Int>>) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed ->
            targets.forEach { (p, idx) ->
                if (p !in 0 until doc.numberOfPages) return@forEach
                val pg = doc.getPage(p)
                val annots = pg.annotations
                val keep = annots.filterIndexed { i, a -> !(i in idx && a is PDAnnotationLink) }
                pg.annotations = keep.toMutableList(); changed += pg
            }
        }
    }

    /** Remove every link ([webOnly] = only URI / mailto links). Returns how many were removed. */
    suspend fun removeAll(src: File, out: File, password: String?, webOnly: Boolean): Int = withContext(Dispatchers.IO) {
        var n = 0
        NavIo.edit(src, out, password) { doc, changed ->
            for (pg in doc.pages) {
                val annots = runCatching { pg.annotations }.getOrNull() ?: continue
                val keep = annots.filter { a ->
                    val drop = a is PDAnnotationLink && (!webOnly || a.cosObject.getCOSDictionary(COSName.A)?.getNameAsString(COSName.S) == "URI")
                    if (drop) n++
                    !drop
                }
                if (keep.size != annots.size) { pg.annotations = keep.toMutableList(); changed += pg }
            }
        }
        n
    }

    private fun relativeName(target: String, doc: File): String {
        val t = File(target)
        return if (t.parentFile?.absolutePath == doc.absoluteFile.parentFile?.absolutePath) t.name else target
    }

    private fun build(doc: PDDocument, page: PDPage, s: LinkSpec, src: File, originalAction: COSBase? = null): PDAnnotation {
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
        if (s.group.isNotBlank()) d.setItem(AutoLinks.XREF_KEY, COSString(s.group))
        val n = doc.numberOfPages
        when (s.kind) {
            LinkKind.OTHER -> {
                val chain = if (s.actions.isNotEmpty()) NavActions.write(doc, s.actions, originalAction, src) else null
                when {
                    chain != null -> d.setItem(COSName.A, chain)
                    originalAction != null -> d.setItem(COSName.A, originalAction)
                    else -> Unit
                }
            }
            LinkKind.URI, LinkKind.MAIL -> {
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

    private fun destArray(pageRef: COSBase, s: LinkSpec): COSArray = Dests.array(pageRef, s.view)

    /**
     * Move / resize links of [page]: annotation index → new rect. QuadPoints follow the rect (same offset and
     * scale) and the border appearance is rebuilt.
     */
    suspend fun setRects(src: File, out: File, password: String?, page: Int, rects: Map<Int, RectF>) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed ->
            if (page in 0 until doc.numberOfPages) {
                val pg = doc.getPage(page)
                pg.annotations.forEachIndexed { i, a ->
                    val r = rects[i]
                    if (r != null && a is PDAnnotationLink) {
                        val old = a.rectangle?.let { E5Geom.rectF(it) }
                        a.rectangle = E5Geom.rect(r)
                        val q = a.quadPoints
                        if (old != null && q != null && q.size >= 8)
                            a.quadPoints = LinkGeom.mapQuads(q, LinkGeom.Box(old.left, old.bottom, old.right, old.top), LinkGeom.Box(r.left, r.bottom, r.right, r.top))
                        a.cosObject.removeItem(COSName.AP)
                        val bs = a.cosObject.getCOSDictionary(COSName.BS)
                        if (bs == null || bs.getFloat(COSName.W, 1f) > 0f) runCatching { a.constructAppearances(doc) }
                        changed += pg
                    }
                }
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
