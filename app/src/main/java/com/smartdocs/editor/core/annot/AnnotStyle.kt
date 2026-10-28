package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.view5.DashStyle
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.util.Locale

/**
 * Small, testable (plain floats, no android.*) PDF helpers for the comment features of section 7: border
 * style / dash / no border (/BS), interior colour (/IC), cloudy border (/BE), line endings (/LE), dimension
 * leader lines (/LL /LLE /LLO), lock flags (/F), author / subject / name, pop-up annotations (/Popup), rich
 * text (/RC /DS), flattening chosen annotations into the page content and numbered tickmarks.
 */
object AnnotStyle {

    /** /Subj of numbered / lettered / symbol tickmarks. */
    const val TICKMARK = "Tickmark"

    const val F_HIDDEN = 2
    const val F_PRINT = 4
    const val F_LOCKED = 128
    const val F_LOCKED_CONTENTS = 512

    private val BS = COSName.getPDFName("BS")
    private val BE = COSName.getPDFName("BE")
    private val IC = COSName.getPDFName("IC")
    private val LE = COSName.getPDFName("LE")
    private val LL = COSName.getPDFName("LL")
    private val LLE = COSName.getPDFName("LLE")
    private val LLO = COSName.getPDFName("LLO")
    private val SUBJ = COSName.getPDFName("Subj")
    private val NM = COSName.getPDFName("NM")
    private val T = COSName.getPDFName("T")
    private val POPUP = COSName.getPDFName("Popup")
    private val OPEN = COSName.getPDFName("Open")
    private val RC = COSName.getPDFName("RC")
    private val DS = COSName.getPDFName("DS")
    private val NAME = COSName.getPDFName("Name")
    private val D = COSName.getPDFName("D")
    private val W = COSName.getPDFName("W")
    private val I = COSName.getPDFName("I")

    /** Note icon names of PDF text annotations (ISO 32000 12.5.6.4 + the Acrobat / PDFium set) offered by the picker. */
    val NOTE_ICONS = listOf("Comment", "Key", "Note", "Help", "NewParagraph", "Paragraph", "Insert", "Check", "Circle", "Cross",
        "CrossHairs", "RightArrow", "RightPointer", "Star", "UpArrow", "UpLeftArrow")

    /** File attachment icon names (ISO 32000 12.5.6.15). */
    val ATTACH_ICONS = listOf("PushPin", "Paperclip", "Graph", "Tag")

    /**
     * Glyph of a note icon drawn in a 20x20 box with the current stroke colour (the box itself is drawn by the
     * caller). Simple vector shapes so every viewer shows the chosen icon the same way.
     */
    fun noteGlyph(cs: PDPageContentStream, icon: String) {
        cs.setLineWidth(1.4f); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
        fun l(x0: Float, y0: Float, x1: Float, y1: Float) { cs.moveTo(x0, y0); cs.lineTo(x1, y1) }
        fun circle(cx: Float, cy: Float, r: Float) {
            val k = 0.5523f * r
            cs.moveTo(cx + r, cy); cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r); cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r); cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy); cs.closePath()
        }
        when (icon) {
            "Key" -> { circle(7f, 12f, 3.5f); l(10f, 10f, 16f, 4f); l(13.5f, 6.5f, 15.5f, 8.5f) }
            "Help" -> { circle(10f, 10f, 7f); cs.moveTo(7.5f, 12.5f); cs.curveTo(7.5f, 16f, 12.5f, 16f, 12.5f, 12.5f); cs.curveTo(12.5f, 10.5f, 10f, 10.5f, 10f, 8f); l(10f, 5.5f, 10f, 5f) }
            "NewParagraph" -> { l(4f, 4f, 4f, 16f); l(4f, 16f, 10f, 4f); l(10f, 4f, 10f, 16f); l(13f, 10f, 17f, 10f); l(15f, 8f, 15f, 12f) }
            "Paragraph" -> { l(11f, 4f, 11f, 16f); l(14f, 4f, 14f, 16f); l(8f, 16f, 15f, 16f); circle(8.5f, 12.5f, 3.2f) }
            "Insert" -> { l(4f, 5f, 10f, 15f); l(10f, 15f, 16f, 5f) }
            "Check" -> { l(4f, 10f, 8f, 5f); l(8f, 5f, 16f, 15f) }
            "Circle" -> circle(10f, 10f, 6f)
            "Cross" -> { l(5f, 5f, 15f, 15f); l(5f, 15f, 15f, 5f) }
            "CrossHairs" -> { circle(10f, 10f, 5.5f); l(10f, 3f, 10f, 17f); l(3f, 10f, 17f, 10f) }
            "RightArrow" -> { l(4f, 10f, 16f, 10f); l(11f, 15f, 16f, 10f); l(11f, 5f, 16f, 10f) }
            "RightPointer" -> { cs.moveTo(5f, 15f); cs.lineTo(16f, 10f); cs.lineTo(5f, 5f); cs.lineTo(8f, 10f); cs.closePath() }
            "Star" -> {
                for (i in 0..5) {
                    val a = Math.toRadians(90.0 + i * 144.0); val x = 10f + 6.5f * kotlin.math.cos(a).toFloat(); val y = 10f + 6.5f * kotlin.math.sin(a).toFloat()
                    if (i == 0) cs.moveTo(x, y) else cs.lineTo(x, y)
                }
                cs.closePath()
            }
            "UpArrow" -> { l(10f, 4f, 10f, 16f); l(5f, 11f, 10f, 16f); l(15f, 11f, 10f, 16f) }
            "UpLeftArrow" -> { l(15f, 5f, 5f, 15f); l(5f, 15f, 5f, 9f); l(5f, 15f, 11f, 15f) }
            "Note" -> { l(5f, 15f, 15f, 15f); l(5f, 12f, 15f, 12f); l(5f, 9f, 15f, 9f); l(5f, 6f, 11f, 6f) }
            else -> { l(5f, 13f, 15f, 13f); l(5f, 9f, 15f, 9f); l(5f, 5f, 11f, 5f) }
        }
        cs.stroke()
    }

    // ================= flags =================

    fun flags(cos: COSDictionary): Int = cos.getInt(COSName.F, 0)

    /** Set or clear Locked (128) and LockedContents (512); the other flags are kept. */
    fun setLocked(cos: COSDictionary, locked: Boolean, contents: Boolean = locked) {
        var f = flags(cos)
        f = if (locked) f or F_LOCKED else f and F_LOCKED.inv()
        f = if (contents) f or F_LOCKED_CONTENTS else f and F_LOCKED_CONTENTS.inv()
        cos.setInt(COSName.F, f)
    }

    fun isLocked(cos: COSDictionary): Boolean = flags(cos) and F_LOCKED != 0
    fun isLockedContents(cos: COSDictionary): Boolean = flags(cos) and F_LOCKED_CONTENTS != 0

    // ================= border / fill / cloud =================

    /**
     * /BS of a shape: [width] points, [dash] = [DashStyle] ordinal (0 solid), [noBorder] writes /W 0 (the
     * shape is drawn by its fill only).
     */
    fun applyBorder(cos: COSDictionary, width: Float, dash: Int, noBorder: Boolean = false) {
        val bs = COSDictionary()
        bs.setName(COSName.TYPE, "Border")
        if (noBorder) { bs.setItem(W, COSFloat(0f)); bs.setName(COSName.S, "S"); cos.setItem(BS, bs); return }
        bs.setItem(W, COSFloat(width))
        val style = DashStyle.entries.getOrElse(dash) { DashStyle.SOLID }
        val p = style.scaled(width)
        if (p != null) {
            bs.setName(COSName.S, "D")
            bs.setItem(D, COSArray().apply { p.forEach { add(COSFloat(it)) } })
        } else bs.setName(COSName.S, "S")
        cos.setItem(BS, bs)
    }

    fun borderWidth(cos: COSDictionary): Float? = (cos.getDictionaryObject(BS) as? COSDictionary)?.let { (it.getDictionaryObject(W) as? COSNumber)?.floatValue() }

    fun isNoBorder(cos: COSDictionary): Boolean = borderWidth(cos)?.let { it <= 0f } ?: false

    /** [DashStyle] ordinal matching the /BS /D array of [cos] (0 when solid or unknown). */
    fun dashIndexOf(cos: COSDictionary): Int {
        val bs = cos.getDictionaryObject(BS) as? COSDictionary ?: return 0
        if (bs.getNameAsString(COSName.S) != "D") return 0
        val w = (bs.getDictionaryObject(W) as? COSNumber)?.floatValue()?.coerceAtLeast(0.5f) ?: 1f
        val arr = bs.getDictionaryObject(D) as? COSArray ?: return 1
        val v = FloatArray(arr.size()) { (arr.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
        DashStyle.entries.forEach { d ->
            val p = d.scaled(w) ?: return@forEach
            if (p.size == v.size && p.indices.all { kotlin.math.abs(p[it] - v[it]) < 0.05f }) return d.ordinal
        }
        return 1
    }

    fun setInterior(cos: COSDictionary, rgb: Int?) {
        if (rgb == null) { cos.removeItem(IC); return }
        cos.setItem(IC, COSArray().apply { comps(rgb).forEach { add(COSFloat(it)) } })
    }

    fun interiorOf(cos: COSDictionary): Int? {
        val arr = cos.getDictionaryObject(IC) as? COSArray ?: return null
        if (arr.size() != 3) return null
        fun b(i: Int) = (((arr.getObject(i) as? COSNumber)?.floatValue() ?: 0f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (b(0) shl 16) or (b(1) shl 8) or b(2)
    }

    /** Cloudy border effect (/BE /S /C /I intensity 0..2; Nitro's "cloudy 1-3" = intensities 0, 1, 2). */
    fun setCloudy(cos: COSDictionary, intensity: Int, inward: Boolean = false) {
        setCloudInward(cos, intensity > 0 && inward)
        if (intensity <= 0) { cos.removeItem(BE); return }
        cos.setItem(BE, COSDictionary().apply { setName(COSName.S, "C"); setItem(I, COSFloat((intensity - 1).coerceIn(0, 2).toFloat())) })
    }

    /** Foxit 24.33: scallops pointing into the shape (our key; other viewers draw their own outward cloud from /BE). */
    val CLOUD_INWARD: COSName = COSName.getPDFName("SDCloudIn")
    fun setCloudInward(cos: COSDictionary, inward: Boolean) { if (inward) cos.setBoolean(CLOUD_INWARD, true) else cos.removeItem(CLOUD_INWARD) }
    fun cloudInwardOf(cos: COSDictionary): Boolean = cos.getBoolean(CLOUD_INWARD, false)

    /** Cloud level 1..3 written by [setCloudy] (0 = not cloudy). */
    fun cloudyOf(cos: COSDictionary): Int {
        val be = cos.getDictionaryObject(BE) as? COSDictionary ?: return 0
        if (be.getNameAsString(COSName.S) != "C") return 0
        return (be.getFloat(I, 0f).toInt() + 1).coerceIn(1, 3)
    }

    // ================= lines =================

    fun setLineEnds(cos: COSDictionary, start: String, end: String) {
        cos.setItem(LE, COSArray().apply { add(COSName.getPDFName(start.ifBlank { "None" })); add(COSName.getPDFName(end.ifBlank { "None" })) })
    }

    fun lineEndsOf(cos: COSDictionary): Pair<String, String> {
        val a = cos.getDictionaryObject(LE) as? COSArray ?: return "None" to "None"
        fun at(i: Int) = (runCatching { a.getObject(i) }.getOrNull() as? COSName)?.name ?: "None"
        return at(0) to at(1)
    }

    /** Leader lines of a dimension line (ISO 32000 12.5.6.7): length, extension and offset in points. */
    fun setLeader(cos: COSDictionary, length: Float, extension: Float, offset: Float) {
        if (length == 0f) cos.removeItem(LL) else cos.setItem(LL, COSFloat(length))
        if (extension <= 0f) cos.removeItem(LLE) else cos.setItem(LLE, COSFloat(extension))
        if (offset <= 0f) cos.removeItem(LLO) else cos.setItem(LLO, COSFloat(offset))
    }

    fun leaderOf(cos: COSDictionary): Triple<Float, Float, Float> =
        Triple(cos.getFloat(LL, 0f), cos.getFloat(LLE, 0f), cos.getFloat(LLO, 0f))

    /**
     * End points (a', b') of the dimension line drawn with leader lines: the line [a]-[b] moved perpendicular by
     * [ll] + sign(ll) * [llo]... the leader lines run from (a + llo*n) to (a' + lle*n). Returned as
     * [ax', ay', bx', by', leaderAx0, leaderAy0, leaderAx1, leaderAy1, leaderBx0, leaderBy0, leaderBx1, leaderBy1].
     */
    fun leaderGeometry(ax: Float, ay: Float, bx: Float, by: Float, ll: Float, lle: Float, llo: Float): FloatArray {
        val dx = bx - ax; val dy = by - ay
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
        // perpendicular to the left of a→b (PDF: positive LL goes "up" for a left-to-right line)
        val nx = -dy / len; val ny = dx / len
        val sgn = if (ll < 0) -1f else 1f
        val off = ll
        val a1x = ax + nx * off; val a1y = ay + ny * off
        val b1x = bx + nx * off; val b1y = by + ny * off
        val s0 = llo * sgn; val s1 = off + lle * sgn
        return floatArrayOf(a1x, a1y, b1x, b1y,
            ax + nx * s0, ay + ny * s0, ax + nx * s1, ay + ny * s1,
            bx + nx * s0, by + ny * s0, bx + nx * s1, by + ny * s1)
    }

    // ================= author / subject / name =================

    /** Author (/T), subject (/Subj) and unique name (/NM); null leaves a value unchanged, blank removes it. */
    fun setMeta(cos: COSDictionary, author: String? = null, subject: String? = null, name: String? = null) {
        fun put(k: COSName, v: String?) { if (v == null) return; if (v.isBlank()) cos.removeItem(k) else cos.setString(k, v) }
        put(T, author); put(SUBJ, subject); put(NM, name)
        cos.setString(COSName.M, com.tom_roush.pdfbox.util.DateConverter.toString(java.util.Calendar.getInstance()))
    }

    fun authorOf(cos: COSDictionary): String = cos.getString(T) ?: ""
    fun subjectOf(cos: COSDictionary): String = cos.getString(SUBJ) ?: ""
    fun nameOf(cos: COSDictionary): String = cos.getString(NM) ?: ""

    fun setNoteIcon(cos: COSDictionary, icon: String) { if (icon.isNotBlank()) cos.setName(NAME, icon) }
    fun noteIconOf(cos: COSDictionary): String = cos.getNameAsString(NAME) ?: "Comment"

    // ================= pop-up annotations =================

    /** Default pop-up box for a parent at [rect] (llx, lly, urx, ury) on a page [pageW] wide: to its right, or left. */
    fun popupRect(rect: FloatArray, pageW: Float, pageH: Float): FloatArray {
        val w = 180f; val h = 120f
        val top = rect[3].coerceIn(h, pageH.coerceAtLeast(h))
        val left = if (rect[2] + 8f + w <= pageW) rect[2] + 8f else (rect[0] - 8f - w).coerceAtLeast(0f)
        return floatArrayOf(left, top - h, left + w, top)
    }

    private fun rectOf(cos: COSDictionary): FloatArray? {
        val a = cos.getDictionaryObject(COSName.RECT) as? COSArray ?: return null
        if (a.size() < 4) return null
        val v = FloatArray(4) { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    private fun rectArray(r: FloatArray) = COSArray().apply { r.forEach { add(COSFloat(it)) } }

    /**
     * Add a /Popup annotation for the markup annotation [parent] on [page] (closed unless [open]), linked both
     * ways (/Parent, /Popup). Returns the pop-up dictionary; an existing pop-up is reused.
     */
    fun addPopup(page: PDPage, parent: COSDictionary, open: Boolean = false): COSDictionary {
        (parent.getDictionaryObject(POPUP) as? COSDictionary)?.let { return it }
        val r = rectOf(parent) ?: floatArrayOf(0f, 0f, 20f, 20f)
        val box = page.mediaBox
        val p = COSDictionary()
        p.setName(COSName.TYPE, "Annot"); p.setName(COSName.SUBTYPE, "Popup")
        p.setItem(COSName.RECT, rectArray(popupRect(r, box.width, box.height)))
        p.setItem(COSName.PARENT, parent)
        p.setBoolean(OPEN, open)
        p.setInt(COSName.F, F_PRINT or 16 /* NoRotate */ or 8 /* NoZoom */)
        parent.setItem(POPUP, p)
        val list = page.annotations
        list.add(PDAnnotation.createAnnotation(p))
        page.annotations = list
        return p
    }

    /** Put the pop-up of [parent] back next to it, closed. Returns false when it has none. */
    fun resetPopup(page: PDPage, parent: COSDictionary): Boolean {
        val p = parent.getDictionaryObject(POPUP) as? COSDictionary ?: return false
        val r = rectOf(parent) ?: return false
        val box = page.mediaBox
        p.setItem(COSName.RECT, rectArray(popupRect(r, box.width, box.height)))
        p.setBoolean(OPEN, false)
        return true
    }

    fun popupOpen(parent: COSDictionary): Boolean? = (parent.getDictionaryObject(POPUP) as? COSDictionary)?.getBoolean(OPEN, false)

    /** Remove annotations [indices] of [page] together with their pop-ups. */
    fun removeWithPopups(page: PDPage, indices: Collection<Int>) {
        val list = page.annotations
        val drop = HashSet<COSDictionary>()
        indices.forEach { i -> list.getOrNull(i)?.cosObject?.let { c -> drop += c; (c.getDictionaryObject(POPUP) as? COSDictionary)?.let { drop += it } } }
        page.annotations = list.filter { an -> an.cosObject !in drop && (an.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.let { it in drop } != true }.toMutableList()
    }

    // ================= rich text =================

    data class Rich(val fontSize: Float, val color: Int, val bold: Boolean, val italic: Boolean, val underline: Boolean = false,
                    val strike: Boolean = false, val family: Int = 0, val align: Int = -1, val lineHeight: Float = 1.3f,
                    val letterSpacing: Float = 0f, val wordSpacing: Float = 0f, val bullets: Boolean = false, val script: Int = 0,
                    /** 24.105 numbered list and indent, 24.107 paragraph spacing (points). */
                    val numbering: Boolean = false, val indent: Float = 0f, val paraSpacing: Float = 0f)

    /** Foxit 24.102: glyph size of super- / subscript text ([script] 1 / 2) set in [size]. */
    fun scriptSize(size: Float, script: Int): Float = if (script == 1 || script == 2) size * 0.65f else size

    /** Baseline shift (PDF units, up positive) of super- / subscript text set in [size]. */
    fun scriptRise(size: Float, script: Int): Float = when (script) { 1 -> size * 0.35f; 2 -> -size * 0.15f; else -> 0f }

    private fun scriptCss(script: Int) = when (script) { 1 -> "super"; 2 -> "sub"; else -> null }

    private val FAMILIES = listOf("Helvetica,sans-serif", "Times New Roman,serif", "Courier New,monospace")

    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    private fun esc(s: String) = buildString { s.forEach { ch ->
        when (ch) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); else -> append(ch) }
    } }

    /** Default style string (/DS) for a FreeText annotation. */
    fun defaultStyle(r: Rich): String = buildString {
        append("font: ")
        if (r.italic) append("italic ")
        if (r.bold) append("bold ")
        append(String.format(Locale.US, "%.1fpt %s; color:%s", r.fontSize, FAMILIES.getOrElse(r.family) { FAMILIES[0] }, hex(r.color)))
        val deco = listOfNotNull("underline".takeIf { r.underline }, "line-through".takeIf { r.strike })
        if (deco.isNotEmpty()) append("; text-decoration:").append(deco.joinToString(" "))
        if (r.align in 0..3) append("; text-align:").append(listOf("start", "center", "end", "justify")[r.align])
        if (r.lineHeight != 1.3f) append(String.format(Locale.US, "; line-height:%.2f", r.lineHeight))
        if (r.letterSpacing != 0f) append(String.format(Locale.US, "; letter-spacing:%.1fpt", r.letterSpacing))
        if (r.wordSpacing != 0f) append(String.format(Locale.US, "; word-spacing:%.1fpt", r.wordSpacing))
        scriptCss(r.script)?.let { append("; vertical-align:").append(it) }
    }

    /** XHTML rich-text body (/RC) of [text] in style [r]; [rtl] sets the direction. */
    fun richBody(text: String, r: Rich, rtl: Boolean): String {
        val style = buildString {
            append(String.format(Locale.US, "font-size:%.1fpt;color:%s", r.fontSize, hex(r.color)))
            if (r.bold) append(";font-weight:bold")
            if (r.italic) append(";font-style:italic")
            val deco = listOfNotNull("underline".takeIf { r.underline }, "line-through".takeIf { r.strike })
            if (deco.isNotEmpty()) append(";text-decoration:").append(deco.joinToString(" "))
            append(";font-family:").append(FAMILIES.getOrElse(r.family) { FAMILIES[0] })
            append(";text-align:").append(when (r.align) { 1 -> "center"; 2 -> if (rtl) "left" else "right"; 3 -> "justify"; else -> if (rtl) "right" else "left" })
            if (r.lineHeight != 1.3f) append(String.format(Locale.US, ";line-height:%.2f", r.lineHeight))
            if (r.letterSpacing != 0f) append(String.format(Locale.US, ";letter-spacing:%.1fpt", r.letterSpacing))
            if (r.wordSpacing != 0f) append(String.format(Locale.US, ";word-spacing:%.1fpt", r.wordSpacing))
            scriptCss(r.script)?.let { append(";vertical-align:").append(it) }
            if (r.indent > 0f) append(String.format(Locale.US, ";margin-%s:%.1fpt", if (rtl) "right" else "left", r.indent))
        }
        // 24.107: paragraph spacing on every paragraph / list item
        val para = if (r.paraSpacing > 0f) String.format(Locale.US, " style=\"margin-bottom:%.1fpt\"", r.paraSpacing) else ""
        return buildString {
            append("<?xml version=\"1.0\"?><body xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\" xfa:APIVersion=\"Acrobat:11.0.0\" xfa:spec=\"2.0.2\"")
            if (rtl) append(" dir=\"rtl\"")
            append(" style=\"").append(style).append("\">")
            if (r.numbering) {
                append("<ol>"); text.split('\n').forEach { line -> append("<li").append(para).append(">").append(esc(line)).append("</li>") }; append("</ol>")
            } else if (r.bullets) {
                append("<ul>"); text.split('\n').forEach { line -> append("<li").append(para).append(">").append(esc(line)).append("</li>") }; append("</ul>")
            } else text.split('\n').forEach { line -> append("<p").append(para).append(">").append(esc(line)).append("</p>") }
            append("</body>")
        }
    }

    fun applyRich(cos: COSDictionary, text: String, r: Rich, rtl: Boolean) {
        cos.setString(RC, richBody(text, r, rtl))
        cos.setString(DS, defaultStyle(r))
    }

    /** Style read back from /DS (or /RC). Null when the annotation has no rich text. */
    fun readRich(cos: COSDictionary): Rich? {
        val s = (cos.getString(DS) ?: "") + ";" + (cos.getString(RC) ?: "")
        if (s.length <= 1) return null
        val size = Regex("""([0-9.]+)pt""").find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: 12f
        val color = Regex("""color:\s*#([0-9A-Fa-f]{6})""").find(s)?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
        val bold = Regex("""\bbold\b""").containsMatchIn(s)
        val italic = Regex("""\bitalic\b""").containsMatchIn(s)
        val underline = s.contains("underline")
        val strike = s.contains("line-through")
        val ds = cos.getString(DS) ?: ""
        val family = when { s.contains("monospace") || s.contains("Courier") -> 2; s.contains("serif") && !s.contains("sans-serif") || s.contains("Times") -> 1; else -> 0 }
        val align = Regex("""text-align:\s*(start|center|end|justify)""").find(ds)?.groupValues?.get(1)?.let { listOf("start", "center", "end", "justify").indexOf(it) } ?: -1
        val lh = Regex("""line-height:\s*([0-9.]+)""").find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.3f
        val ls = Regex("""letter-spacing:\s*(-?[0-9.]+)pt""").find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val ws = Regex("""word-spacing:\s*(-?[0-9.]+)pt""").find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val rc = cos.getString(RC) ?: ""
        val numbering = rc.contains("<ol")
        val bullets = !numbering && (rc.contains("<li>") || rc.contains("<li "))
        val script = when (Regex("vertical-align:\\s*(super|sub)\\b").find(s)?.groupValues?.get(1)) { "super" -> 1; "sub" -> 2; else -> 0 }
        val indent = Regex("""margin-(?:left|right):\s*([0-9.]+)pt""").find(rc)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val paraSpacing = Regex("""margin-bottom:\s*([0-9.]+)pt""").find(rc)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        return Rich(size, color, bold, italic, underline, strike, family, align, lh, ls, ws, bullets, script, numbering, indent, paraSpacing)
    }

    /** Plain text of an /RC body (paragraphs become lines). */
    fun richPlain(rc: String): String =
        rc.replace(Regex("""</(p|li)>\s*<(p|li)[^>]*>"""), "\n").replace(Regex("""<[^>]+>"""), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").trim()

    // ================= shape appearance =================

    fun comps(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    /**
     * Appearance stream of a Square / Circle annotation of size [w]×[h] placed at ([x], [y]): optional [fill],
     * border [width] with [dash] (or no border), opacity.
     */
    fun shapeStream(doc: PDDocument, x: Float, y: Float, w: Float, h: Float, oval: Boolean, stroke: Int, fill: Int?, width: Float,
                    dash: Int, noBorder: Boolean, opacity: Float, cloudy: Int = 0, inward: Boolean = false): PDAppearanceStream {
        val form = PDAppearanceStream(doc)
        form.bBox = PDRectangle(0f, 0f, w, h)
        form.resources = PDResources()
        form.setMatrix(com.tom_roush.harmony.awt.geom.AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
        PDPageContentStream(doc, form).use { cs ->
            if (opacity < 0.999f) cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingAlphaConstant = opacity; nonStrokingAlphaConstant = opacity })
            val sc = comps(stroke); cs.setStrokingColor(sc[0], sc[1], sc[2])
            fill?.let { val fc = comps(it); cs.setNonStrokingColor(fc[0], fc[1], fc[2]) }
            val lw = if (noBorder) 0f else width
            cs.setLineWidth(lw.coerceAtLeast(0.01f))
            DashStyle.entries.getOrElse(dash) { DashStyle.SOLID }.scaled(width)?.let { if (!noBorder) cs.setLineDashPattern(it, 0f) }
            // inward scallops stay inside the outline, so the shape keeps the whole box
            val i = lw / 2 + if (cloudy > 0 && !inward) cloudPad(cloudy, width) else 0f
            val iw = (w - 2 * i).coerceAtLeast(1f); val ih = (h - 2 * i).coerceAtLeast(1f)
            if (cloudy > 0) {
                val lv = cloudy.coerceIn(1, 3)
                val pts = if (oval) CloudPath.ellipse(i, i, i + iw, i + ih, lv, width) else listOf(i to i, i + iw to i, i + iw to i + ih, i to i + ih)
                val segs = CloudPath.build(pts, lv, width, inward)
                segs.forEachIndexed { k, c -> if (k == 0) cs.moveTo(c.x0, c.y0); cs.curveTo(c.x1, c.y1, c.x2, c.y2, c.x3, c.y3) }
                cs.closePath()
            } else if (!oval) cs.addRect(i, i, iw, ih)
            else {
                val k = 0.5523f; val rx = iw / 2; val ry = ih / 2; val cx = w / 2; val cy = h / 2
                cs.moveTo(cx + rx, cy)
                cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
                cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
                cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
                cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
                cs.closePath()
            }
            when {
                fill != null && !noBorder -> cs.fillAndStroke()
                fill != null -> cs.fill()
                noBorder -> {}
                else -> cs.stroke()
            }
        }
        return form
    }

    /** Extra margin a cloudy border needs outside the shape (level 1..3). */
    fun cloudPad(level: Int, width: Float): Float = if (level <= 0) 0f else CloudPath.margin(level.coerceIn(1, 3), width)

    // ================= flatten chosen annotations =================

    private fun normalAppearance(cos: COSDictionary): COSStream? {
        val ap = cos.getDictionaryObject(COSName.AP) as? COSDictionary ?: return null
        return when (val n = ap.getDictionaryObject(COSName.N)) {
            is COSStream -> n
            is COSDictionary -> cos.getCOSName(COSName.AS)?.let { n.getDictionaryObject(it) as? COSStream }
            else -> null
        }
    }

    private fun floatsOf(b: COSBase?): FloatArray? = (b as? COSArray)?.let { a -> FloatArray(a.size()) { (a.getObject(it) as? COSNumber)?.floatValue() ?: 0f } }

    /**
     * Matrix (a b c d e f) that maps the appearance [bbox] transformed by its form [matrix] onto [rect]
     * (ISO 32000 12.5.5 algorithm), to be concatenated before drawing the form XObject.
     */
    fun flattenMatrix(bbox: FloatArray, matrix: FloatArray?, rect: FloatArray): FloatArray {
        val m = matrix?.takeIf { it.size >= 6 } ?: floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val xs = FloatArray(4); val ys = FloatArray(4)
        val corners = listOf(bbox[0] to bbox[1], bbox[2] to bbox[1], bbox[2] to bbox[3], bbox[0] to bbox[3])
        corners.forEachIndexed { i, (x, y) -> xs[i] = m[0] * x + m[2] * y + m[4]; ys[i] = m[1] * x + m[3] * y + m[5] }
        val bx0 = xs.min(); val by0 = ys.min(); val bw = (xs.max() - bx0).coerceAtLeast(1e-3f); val bh = (ys.max() - by0).coerceAtLeast(1e-3f)
        val rx0 = minOf(rect[0], rect[2]); val ry0 = minOf(rect[1], rect[3])
        val sx = (maxOf(rect[0], rect[2]) - rx0) / bw; val sy = (maxOf(rect[1], rect[3]) - ry0) / bh
        return floatArrayOf(sx, 0f, 0f, sy, rx0 - bx0 * sx, ry0 - by0 * sy)
    }

    /**
     * Draw the normal appearance of annotations [indices] of [page] into the page content and remove them
     * (with their pop-ups and replies). Hidden annotations and ones without an appearance are only removed when
     * [dropWithoutAppearance]. Returns how many were flattened.
     */
    fun flatten(doc: PDDocument, page: PDPage, indices: Collection<Int>, dropWithoutAppearance: Boolean = false): Int {
        val list = page.annotations
        val chosen = indices.distinct().mapNotNull { list.getOrNull(it) }
        if (chosen.isEmpty()) return 0
        var n = 0
        val remove = ArrayList<Int>()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            chosen.forEach { an ->
                val cos = an.cosObject
                val idx = list.indexOf(an)
                val hidden = flags(cos) and F_HIDDEN != 0
                val ap = normalAppearance(cos)
                val rect = rectOf(cos)
                if (ap == null || rect == null || hidden) { if (dropWithoutAppearance) remove += idx; return@forEach }
                val form = PDFormXObject(ap)
                val bbox = floatsOf(ap.getDictionaryObject(COSName.BBOX))?.takeIf { it.size >= 4 } ?: floatArrayOf(0f, 0f, rect[2] - rect[0], rect[3] - rect[1])
                val m = flattenMatrix(bbox, floatsOf(ap.getDictionaryObject(COSName.MATRIX)), rect)
                cs.saveGraphicsState()
                cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                cs.drawForm(form)
                cs.restoreGraphicsState()
                remove += idx; n++
            }
        }
        // replies (/IRT) of flattened annotations go too: they would point at nothing
        val gone = remove.mapNotNull { list.getOrNull(it)?.cosObject }.toSet()
        val replies = list.indices.filter { i -> (list[i].cosObject.getDictionaryObject(COSName.getPDFName("IRT")) as? COSDictionary)?.let { it in gone && list[i].cosObject.getNameAsString(COSName.getPDFName("RT")) != "Group" } == true }
        removeWithPopups(page, remove + replies)
        return n
    }

    // ================= numbered tickmarks =================

    /**
     * A numbered tickmark (Nitro / Bluebeam "count" mark): a filled circle with the number [n] in white, as a
     * /Circle annotation with /Subj "Tickmark" and /Contents n. Text is drawn with a raw Helvetica resource
     * (no font program needed). Returns the annotation dictionary (already added to [page]).
     */
    fun writeTickmark(doc: PDDocument, page: PDPage, cx: Float, cy: Float, label: String, color: Int, size: Float = 16f, author: String = ""): COSDictionary {
        // a ZapfDingbats symbol (palette) is passed as "zd:" + its code character
        val symbol = label.startsWith("zd:")
        val shown = (if (symbol) label.removePrefix("zd:") else label).filter { it.code in 0x20..0x7E }.ifEmpty { "?" }
        val w = maxOf(size, size * 0.42f * label.removePrefix("zd:").length + 6f); val h = size
        val x = cx - w / 2; val y = cy - h / 2
        val form = PDAppearanceStream(doc)
        form.bBox = PDRectangle(0f, 0f, w, h)
        val res = PDResources()
        val helv = COSDictionary().apply {
            setName(COSName.TYPE, "Font"); setName(COSName.SUBTYPE, "Type1")
            setName(COSName.BASE_FONT, if (symbol) "ZapfDingbats" else "Helvetica-Bold")
            if (!symbol) setName(COSName.ENCODING, "WinAnsiEncoding")
        }
        res.cosObject.setItem(COSName.FONT, COSDictionary().apply { setItem(COSName.getPDFName("TkH"), helv) })
        form.resources = res
        form.setMatrix(com.tom_roush.harmony.awt.geom.AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
        val c = comps(color)
        val fs = size * 0.62f
        val tw = shown.length * (if (symbol) 0.8f else 0.556f) * fs
        val k = 0.5523f; val rx = w / 2 - 0.5f; val ry = h / 2 - 0.5f; val ox = w / 2; val oy = h / 2
        val content = buildString {
            append(String.format(Locale.US, "%.3f %.3f %.3f rg %.3f %.3f %.3f RG 1 w\n", c[0], c[1], c[2], c[0], c[1], c[2]))
            append(String.format(Locale.US, "%.2f %.2f m ", ox + rx, oy))
            append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c ", ox + rx, oy + ry * k, ox + rx * k, oy + ry, ox, oy + ry))
            append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c ", ox - rx * k, oy + ry, ox - rx, oy + ry * k, ox - rx, oy))
            append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c ", ox - rx, oy - ry * k, ox - rx * k, oy - ry, ox, oy - ry))
            append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c h B\n", ox + rx * k, oy - ry, ox + rx, oy - ry * k, ox + rx, oy))
            append(String.format(Locale.US, "BT 1 1 1 rg /TkH %.2f Tf %.2f %.2f Td (%s) Tj ET\n", fs, (w - tw) / 2, (h - fs * 0.7f) / 2, shown.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")))
        }
        form.cosObject.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
        val an = COSDictionary()
        an.setName(COSName.TYPE, "Annot"); an.setName(COSName.SUBTYPE, "Circle")
        an.setItem(COSName.RECT, rectArray(floatArrayOf(x, y, x + w, y + h)))
        an.setItem(COSName.C, COSArray().apply { c.forEach { add(COSFloat(it)) } })
        an.setItem(IC, COSArray().apply { c.forEach { add(COSFloat(it)) } })
        an.setString(COSName.CONTENTS, label.removePrefix("zd:"))
        an.setString(SUBJ, TICKMARK)
        an.setString(COSName.getPDFName("SDTick"), label)
        an.setString(NM, "sd-tick-" + System.nanoTime().toString(36))
        if (author.isNotBlank()) an.setString(T, author)
        an.setInt(COSName.F, F_PRINT)
        an.setString(COSName.M, com.tom_roush.pdfbox.util.DateConverter.toString(java.util.Calendar.getInstance()))
        an.setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, form.cosObject) })
        val arr = (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray) ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }
        arr.add(an)
        return an
    }

    /** Next tickmark number on [page]: one more than the largest stored "Tickmark" number. */
    fun nextTickmark(page: PDPage): Int =
        (runCatching { page.annotations }.getOrDefault(emptyList()).filter { it.cosObject.getString(SUBJ) == "Tickmark" && it.cosObject.getString(COSName.getPDFName("SDTick"))?.startsWith("zd:") != true }
            .mapNotNull { it.contents?.trim()?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    @Suppress("unused") private fun str(s: String) = COSString(s)
}
