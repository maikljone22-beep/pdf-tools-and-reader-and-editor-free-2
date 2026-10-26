package com.smartdocs.editor.core.redact

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.ContentRewriter
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import java.io.ByteArrayOutputStream

/** What to change in a page's content stream (operator indexes as in [PageInspector]). */
internal class RewritePlan {
    /** op → glyph keys ([PageInspector.key] of str, idx) to remove, keeping the position of what follows. */
    val glyphs = HashMap<Int, HashSet<Long>>()
    val removeOps = HashSet<Int>()
    /** painting operators turned into `n` (the path is built but not painted). */
    val noPaint = HashSet<Int>()
    /** image `Do` operators that now paint another (blanked) image. */
    val swapImage = HashMap<Int, COSName>()
    val isEmpty get() = glyphs.isEmpty() && removeOps.isEmpty() && noPaint.isEmpty() && swapImage.isEmpty()
    fun removeGlyph(g: GlyphProbe) { glyphs.getOrPut(g.op) { HashSet() }.add(PageInspector.key(g.str, g.idx)) }
}

/**
 * True content removal: glyphs under a mark are deleted from the content stream (the text after them keeps its
 * position), paths and images entirely under a mark are deleted, images partly under a mark get their pixels
 * painted in the fill colour, form XObjects entirely under a mark are dropped, and annotations / widgets touching a
 * mark are removed (with the field value). When something under a mark cannot be rewritten (text inside a form
 * XObject, vertical fonts, inline images partly covered), [Result.needsRaster] tells the caller to rasterise the page.
 */
internal object TrueRedactor {

    class Result(
        val glyphsRemoved: Int, val objectsRemoved: Int, val imagesBlanked: Int, val annotsRemoved: Int,
        val needsRaster: Boolean,
        /** MCIDs of marked content that lost glyphs/objects: their structure elements must lose /ActualText too. */
        val touchedMcids: Set<Int> = emptySet(),
    )

    fun redactPage(doc: PDDocument, pageIndex: Int, marks: List<Box>, fillRgb: Int, oc: PDOptionalContentProperties? = doc.documentCatalog.ocProperties): Result {
        val page = doc.getPage(pageIndex)
        if (marks.isEmpty()) return Result(0, 0, 0, 0, false)
        val insp = PageInspector(page, oc).run(page)
        var raster = insp.failed
        val plan = RewritePlan()
        val removedForms = HashSet<Int>()
        var objects = 0; var blanked = 0
        for (p in insp.paints) {
            if (p.nested || !RedactMath.touches(p.box, marks)) continue
            val inside = RedactMath.enclosed(p.box, marks)
            when (p.kind) {
                PaintKind.FILL, PaintKind.STROKE -> if (inside) { plan.noPaint += p.op; objects++ }
                PaintKind.FORM -> if (inside) { plan.removeOps += p.op; removedForms += p.op; objects++ }
                PaintKind.INLINE_IMAGE -> if (inside) { plan.removeOps += p.op; objects++ } else raster = true
                PaintKind.IMAGE -> if (inside) { plan.removeOps += p.op; objects++ } else {
                    val name = runCatching { blankImage(doc, page, p, marks, fillRgb) }.getOrNull()
                    if (name != null) { plan.swapImage[p.op] = name; blanked++ } else raster = true
                }
                PaintKind.SHADING -> {}
            }
        }
        // nested content (inside form XObjects that stay) under a mark cannot be rewritten in place
        for (p in insp.paints) if (p.nested && p.op !in removedForms && p.kind != PaintKind.FILL && p.kind != PaintKind.STROKE && p.kind != PaintKind.SHADING && RedactMath.touches(p.box, marks)) raster = true
        var glyphs = 0
        for (g in insp.glyphs) {
            if (!RedactMath.glyphHit(g.box, marks)) continue
            if (g.nested) { if (g.op !in removedForms) raster = true; continue }
            if (g.vertical || g.badSize) { raster = true; continue }
            plan.removeGlyph(g); glyphs++
        }
        val mcids = HashSet<Int>()
        if (!plan.isEmpty && !apply(doc, page, insp, plan, mcids)) raster = true
        val annots = removeAnnotations(doc, page, marks)
        return Result(glyphs, objects, blanked, annots, raster, mcids)
    }

    /**
     * Rewrites the page's content with [plan]. False when the operator numbering does not match (nothing is changed).
     * /ActualText /Alt /E around rewritten operators are dropped (audit AN-09); their MCIDs go into [touchedMcids].
     */
    fun apply(doc: PDDocument, page: PDPage, insp: PageInspector, plan: RewritePlan, touchedMcids: MutableSet<Int>? = null): Boolean {
        val rw = runCatching { ContentRewriter(doc, page) }.getOrNull() ?: return false
        if (rw.operatorCount != insp.opCount) return false
        val adv = HashMap<Int, HashMap<Long, Float>>()
        insp.glyphs.forEach { g -> if (!g.nested && g.op in plan.glyphs) adv.getOrPut(g.op) { HashMap() }[PageInspector.key(g.str, g.idx)] = g.adv }
        for ((op, set) in plan.glyphs) {
            val list = rewriteText(rw, op, set, insp) { s, i -> adv[op]?.get(PageInspector.key(s, i)) ?: 0f } ?: return false
            rw.replace(op, list)
        }
        plan.removeOps.forEach { rw.remove(it) }
        plan.noPaint.forEach { rw.replace(it, listOf(Operator.getOperator("n"))) }
        plan.swapImage.forEach { (op, name) -> rw.replace(op, listOf(name, Operator.getOperator("Do"))) }
        val touched = plan.glyphs.keys + plan.removeOps + plan.noPaint + plan.swapImage.keys
        val mcids = RedactCleanup.stripActualText(rw, page, touched)
        touchedMcids?.addAll(mcids)
        rw.commit()
        return true
    }

    private fun rewriteText(rw: ContentRewriter, op: Int, remove: Set<Long>, insp: PageInspector, advOf: (Int, Int) -> Float): List<Any>? {
        val orig = rw.original(op)
        val operator = orig.lastOrNull() as? Operator ?: return null
        val operands = orig.dropLast(1).filterIsInstance<COSBase>()
        val out = COSArray()
        fun addString(bytes: ByteArray, s: Int): Boolean {
            val codes = insp.codes[PageInspector.key(op, s)] ?: return false
            if (codes.sumOf { it.second } != bytes.size) return false
            val buf = ByteArrayOutputStream()
            var pending = 0f
            var pos = 0
            codes.forEachIndexed { gi, (_, len) ->
                if (PageInspector.key(s, gi) in remove) {
                    if (buf.size() > 0) { out.add(COSString(buf.toByteArray())); buf.reset() }
                    pending += advOf(s, gi)
                } else {
                    if (pending != 0f) { out.add(COSFloat(pending)); pending = 0f }
                    buf.write(bytes, pos, len)
                }
                pos += len
            }
            if (buf.size() > 0) out.add(COSString(buf.toByteArray()))
            if (pending != 0f) out.add(COSFloat(pending))
            return true
        }
        val ok = when (operator.name) {
            "Tj", "'" -> (operands.getOrNull(0) as? COSString)?.let { addString(it.bytes, 0) } ?: false
            "\"" -> (operands.getOrNull(2) as? COSString)?.let { addString(it.bytes, 0) } ?: false
            "TJ" -> {
                val arr = operands.getOrNull(0) as? COSArray
                if (arr == null) false else {
                    var s = 0
                    var good = true
                    for (i in 0 until arr.size()) {
                        when (val el = arr.getObject(i)) {
                            is COSString -> { if (!addString(el.bytes, s)) good = false; s++ }
                            null -> {}
                            else -> out.add(el)
                        }
                    }
                    good
                }
            }
            else -> false
        }
        if (!ok) return null
        val list = ArrayList<Any>()
        when (operator.name) {
            "'" -> list.add(Operator.getOperator("T*"))
            "\"" -> { list.add(operands[0]); list.add(Operator.getOperator("Tw")); list.add(operands[1]); list.add(Operator.getOperator("Tc")); list.add(Operator.getOperator("T*")) }
        }
        list.add(out); list.add(Operator.getOperator("TJ"))
        return list
    }

    /** A copy of the image with the parts under [marks] painted in [rgb], added to the page resources. */
    private fun blankImage(doc: PDDocument, page: PDPage, p: PaintProbe, marks: List<Box>, rgb: Int): COSName? {
        val img = p.image ?: return null
        val ctm = p.ctm ?: return null
        val src = img.image ?: return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val inv = Affine.values(Affine.inverse(ctm))
        val paint = Paint().apply { color = 0xFF000000.toInt() or (rgb and 0xFFFFFF); style = Paint.Style.FILL }
        val canvas = Canvas(bmp)
        for (m in marks) {
            if (!m.intersects(p.box)) continue
            val u = RedactMath.map(inv, m)   // unit square of the image, y up
            canvas.drawRect(u.l * w - 1f, (1f - u.t) * h - 1f, u.r * w + 1f, (1f - u.b) * h + 1f, paint)
        }
        val x = LosslessFactory.createFromImage(doc, bmp)
        bmp.recycle()
        val res = page.resources ?: PDResources().also { page.resources = it }
        return res.add(x, "Rd")
    }

    /** Removes annotations and widgets touching a mark (not the /Redact marks themselves, handled by the caller). */
    fun removeAnnotations(doc: PDDocument, page: PDPage, marks: List<Box>): Int {
        val list = runCatching { page.annotations }.getOrNull() ?: return 0
        val gone = HashSet<COSDictionary>()
        for (a in list) {
            if (RedactMarks.isRedact(a) || a.subtype == "Popup") continue
            val r = a.rectangle ?: continue
            if (RedactMath.touches(Box(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY), marks)) gone += a.cosObject
        }
        if (gone.isEmpty()) return 0
        val keep = list.filter { a ->
            if (a.cosObject in gone) { clearField(a); false }
            else !(a.subtype == "Popup" && (a.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.let { it in gone } == true)
        }
        page.annotations = keep
        return gone.size
    }

    /** A removed widget takes its field's value with it. */
    private fun clearField(a: PDAnnotation) {
        if (a.subtype != "Widget") return
        val d = a.cosObject
        val field = if (d.containsKey(COSName.T)) d else d.getDictionaryObject(COSName.PARENT) as? COSDictionary ?: return
        field.removeItem(COSName.V); field.removeItem(COSName.DV)
    }
}
