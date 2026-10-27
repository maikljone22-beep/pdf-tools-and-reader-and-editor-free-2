package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Locale

/**
 * Widget look (/DA, /MK, /BS, /F) and hand-written appearance streams for the field kinds PdfBox does not draw
 * (check boxes and radio buttons in a chosen style, push buttons with captions and highlight states, barcodes).
 * Fonts are plain standard-14 dictionaries (no font program is loaded), so this runs in JVM unit tests too.
 */
object FxAppearance {

    private fun n(s: String) = COSName.getPDFName(s)
    val MK: COSName = n("MK")
    private val BC = n("BC"); private val BG = n("BG"); private val CA = n("CA"); private val RC = n("RC"); private val AC = n("AC")
    private val TP = n("TP"); private val IF = n("IF"); private val SW = n("SW")
    private val R = n("R")

    fun f(v: Float): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    fun rgbArray(c: Int) = COSArray().apply { add(COSFloat(((c shr 16) and 0xFF) / 255f)); add(COSFloat(((c shr 8) and 0xFF) / 255f)); add(COSFloat((c and 0xFF) / 255f)) }

    fun colorOf(arr: COSArray?): Int? {
        if (arr == null || arr.size() == 0) return null
        fun v(i: Int) = ((arr.getObject(i) as? COSNumber)?.floatValue() ?: 0f).coerceIn(0f, 1f)
        fun b(x: Float) = (x * 255f + 0.5f).toInt()
        return when (arr.size()) {
            1 -> b(v(0)).let { (it shl 16) or (it shl 8) or it }
            3 -> (b(v(0)) shl 16) or (b(v(1)) shl 8) or b(v(2))
            4 -> { val k = 1f - v(3); (b((1f - v(0)) * k) shl 16) or (b((1f - v(1)) * k) shl 8) or b((1f - v(2)) * k) }
            else -> null
        }
    }

    private fun rg(c: Int, fill: Boolean) = "${f(((c shr 16) and 0xFF) / 255f)} ${f(((c shr 8) and 0xFF) / 255f)} ${f((c and 0xFF) / 255f)} ${if (fill) "rg" else "RG"}"

    // ---------------- /DA ----------------

    fun da(font: FxFont, size: Float, color: Int) = "/${font.res} ${f(size)} Tf ${rg(color, true)}"

    data class Da(val font: FxFont, val size: Float, val color: Int)

    fun parseDa(da: String?): Da {
        val s = da.orEmpty()
        val tf = Regex("""/([A-Za-z0-9_+\-]+)\s+([0-9.]+)\s+Tf""").find(s)
        val color = Regex("""([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+rg""").find(s)?.groupValues?.let { g ->
            fun b(x: String) = ((x.toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (b(g[1]) shl 16) or (b(g[2]) shl 8) or b(g[3])
        } ?: Regex("""(?:^|\s)([0-9.]+)\s+g(?:\s|$)""").find(s)?.groupValues?.get(1)?.toFloatOrNull()?.let { v -> val b = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt(); (b shl 16) or (b shl 8) or b } ?: 0
        return Da(FxFont.of(tf?.groupValues?.get(1)), tf?.groupValues?.get(2)?.toFloatOrNull() ?: 0f, color)
    }

    /** Standard-14 font dictionary (no font program needed: every viewer has these). */
    fun fontDict(font: FxFont) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, n("Type1")); setItem(COSName.BASE_FONT, n(font.base))
        if (font != FxFont.ZAPF) setItem(COSName.ENCODING, n("WinAnsiEncoding"))
    }

    /** Make sure the AcroForm /DR has [font] under its /DA resource name. */
    fun ensureDr(acro: COSDictionary, font: FxFont) {
        val dr = (acro.getDictionaryObject(COSName.DR) as? COSDictionary) ?: COSDictionary().also { acro.setItem(COSName.DR, it) }
        val fonts = (dr.getDictionaryObject(COSName.FONT) as? COSDictionary) ?: COSDictionary().also { dr.setItem(COSName.FONT, it) }
        if (fonts.getDictionaryObject(n(font.res)) == null) fonts.setItem(n(font.res), fontDict(font))
        if (acro.getString(COSName.DA) == null) acro.setString(COSName.DA, "/Helv 0 Tf 0 g")
        if (font != FxFont.HELVETICA && fonts.getDictionaryObject(n("Helv")) == null) fonts.setItem(n("Helv"), fontDict(FxFont.HELVETICA))
    }

    // ---------------- /MK /BS /F ----------------

    const val F_HIDDEN = 2
    const val F_PRINT = 4
    const val F_NOVIEW = 32
    const val F_LOCKED = 128

    fun annotFlags(old: Int, v: FxVisibility, locked: Boolean): Int {
        var x = old and (F_HIDDEN or F_PRINT or F_NOVIEW or F_LOCKED).inv()
        x = x or when (v) {
            FxVisibility.VISIBLE -> F_PRINT
            FxVisibility.HIDDEN -> F_HIDDEN
            FxVisibility.VISIBLE_NO_PRINT -> 0
            FxVisibility.HIDDEN_PRINTABLE -> F_NOVIEW or F_PRINT
        }
        if (locked) x = x or F_LOCKED
        return x
    }

    fun visibilityOf(flags: Int): FxVisibility = when {
        flags and F_HIDDEN != 0 -> FxVisibility.HIDDEN
        flags and F_NOVIEW != 0 -> FxVisibility.HIDDEN_PRINTABLE
        flags and F_PRINT == 0 -> FxVisibility.VISIBLE_NO_PRINT
        else -> FxVisibility.VISIBLE
    }

    /** Write the look of one widget: flags, /MK colours, rotation, captions, /BS, push-button /H. */
    fun writeLook(w: COSDictionary, s: FieldSpec) {
        w.setInt(COSName.F, annotFlags(w.getInt(COSName.F, 0), s.visibility, s.locked))
        val mk = (w.getDictionaryObject(MK) as? COSDictionary) ?: COSDictionary()
        if (s.borderColor != null && s.borderWidth > 0f) mk.setItem(BC, rgbArray(s.borderColor)) else mk.removeItem(BC)
        if (s.fillColor != null) mk.setItem(BG, rgbArray(s.fillColor)) else mk.removeItem(BG)
        if (s.rotation != 0) mk.setInt(R, ((s.rotation % 360) + 360) % 360) else mk.removeItem(R)
        when (s.type) {
            FxType.CHECKBOX, FxType.RADIO -> mk.setString(CA, s.checkStyle.zapf)
            FxType.BUTTON -> {
                if (s.layout != FxButtonLayout.ICON_ONLY) mk.setString(CA, s.caption) else mk.removeItem(CA)
                if (s.rolloverCaption.isNotEmpty()) mk.setString(RC, s.rolloverCaption) else mk.removeItem(RC)
                if (s.downCaption.isNotEmpty()) mk.setString(AC, s.downCaption) else mk.removeItem(AC)
                mk.setInt(TP, s.layout.tp)
                if (s.layout != FxButtonLayout.LABEL_ONLY) mk.setItem(IF, COSDictionary().apply {
                    setItem(SW, n(s.iconScale.pdf)); setItem(COSName.S, n(if (s.iconProportional) "P" else "A"))
                }) else mk.removeItem(IF)
                w.setItem(COSName.H, n(s.highlight.pdf))
            }
            else -> {}
        }
        if (mk.size() > 0) w.setItem(MK, mk) else w.removeItem(MK)
        val bs = (w.getDictionaryObject(COSName.BS) as? COSDictionary) ?: COSDictionary()
        bs.setItem(COSName.TYPE, n("Border"))
        bs.setItem(COSName.W, COSFloat(if (s.borderColor == null) 0f else s.borderWidth))
        bs.setItem(COSName.S, n(s.borderStyle.pdf))
        if (s.borderStyle == FxBorderStyle.DASHED) bs.setItem(COSName.D, COSArray().apply { add(COSInteger.get(3)) }) else bs.removeItem(COSName.D)
        w.setItem(COSName.BS, bs)
        w.removeItem(n("Border"))
    }

    // ---------------- appearance streams ----------------

    /** /Matrix of an appearance drawn upright in a box rotated by /MK /R inside a [w] x [h] widget. */
    private fun matrix(rot: Int, w: Float, h: Float): COSArray? {
        val v = when (((rot % 360) + 360) % 360) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, w, 0f)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, w, h)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, h)
            else -> return null
        }
        return COSArray().apply { v.forEach { add(COSFloat(it)) } }
    }

    /** A form XObject stream with [content], a [bw] x [bh] BBox and [fonts] in its resources. */
    fun stream(doc: PDDocument, bw: Float, bh: Float, content: String, fonts: List<FxFont> = emptyList(), rot: Int = 0, w: Float = bw, h: Float = bh): COSStream {
        val s = doc.document.createCOSStream()
        s.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
        s.setItem(COSName.TYPE, COSName.XOBJECT); s.setItem(COSName.SUBTYPE, COSName.FORM)
        s.setItem(COSName.BBOX, COSArray().apply { add(COSFloat(0f)); add(COSFloat(0f)); add(COSFloat(bw)); add(COSFloat(bh)) })
        matrix(rot, w, h)?.let { s.setItem(COSName.MATRIX, it) }
        val res = COSDictionary()
        if (fonts.isNotEmpty()) res.setItem(COSName.FONT, COSDictionary().apply { fonts.distinct().forEach { setItem(n(it.res), fontDict(it)) } })
        s.setItem(COSName.RESOURCES, res)
        return s
    }

    /** Background and border of a [w] x [h] box in the chosen style (Acrobat's look for beveled / inset). */
    fun frame(s: FieldSpec, w: Float, h: Float, down: Boolean = false, circle: Boolean = false): String {
        val sb = StringBuilder()
        val bw = if (s.borderColor == null) 0f else s.borderWidth
        val k = 0.5523f
        fun ellipse(x: Float, y: Float, rx: Float, ry: Float) {
            sb.append("${f(x + rx)} ${f(y)} m ")
            sb.append("${f(x + rx)} ${f(y + ry * k)} ${f(x + rx * k)} ${f(y + ry)} ${f(x)} ${f(y + ry)} c ")
            sb.append("${f(x - rx * k)} ${f(y + ry)} ${f(x - rx)} ${f(y + ry * k)} ${f(x - rx)} ${f(y)} c ")
            sb.append("${f(x - rx)} ${f(y - ry * k)} ${f(x - rx * k)} ${f(y - ry)} ${f(x)} ${f(y - ry)} c ")
            sb.append("${f(x + rx * k)} ${f(y - ry)} ${f(x + rx)} ${f(y - ry * k)} ${f(x + rx)} ${f(y)} c h ")
        }
        val fill = if (down && s.type == FxType.BUTTON && s.highlight == FxHighlight.PUSH) (s.fillColor ?: 0xC0C0C0).let { darker(it) } else s.fillColor
        if (fill != null) {
            sb.append("q ${rg(fill, true)} ")
            if (circle) ellipse(w / 2, h / 2, w / 2, h / 2) else sb.append("0 0 ${f(w)} ${f(h)} re ")
            sb.append("f Q\n")
        }
        if (bw > 0f && s.borderColor != null) {
            sb.append("q ${f(bw)} w ${rg(s.borderColor, false)} ")
            if (s.borderStyle == FxBorderStyle.DASHED) sb.append("[3] 0 d ")
            val i = bw / 2
            when {
                s.borderStyle == FxBorderStyle.UNDERLINE -> sb.append("0 ${f(i)} m ${f(w)} ${f(i)} l S ")
                circle -> { ellipse(w / 2, h / 2, w / 2 - i, h / 2 - i); sb.append("S ") }
                else -> sb.append("${f(i)} ${f(i)} ${f(w - bw)} ${f(h - bw)} re S ")
            }
            sb.append("Q\n")
            if (!circle && (s.borderStyle == FxBorderStyle.BEVELED || s.borderStyle == FxBorderStyle.INSET)) {
                // inner bevel: light top-left / dark bottom-right (inset: grey top-left)
                val tl = if (s.borderStyle == FxBorderStyle.BEVELED) "1 g" else "0.5 g"
                val br = if (s.borderStyle == FxBorderStyle.BEVELED) "0.5 g" else "0.75 g"
                val a = bw; val b = 2 * bw
                sb.append("q $tl ${f(a)} ${f(a)} m ${f(a)} ${f(h - a)} l ${f(w - a)} ${f(h - a)} l ${f(w - b)} ${f(h - b)} l ${f(b)} ${f(h - b)} l ${f(b)} ${f(b)} l f Q\n")
                sb.append("q $br ${f(w - a)} ${f(h - a)} m ${f(w - a)} ${f(a)} l ${f(a)} ${f(a)} l ${f(b)} ${f(b)} l ${f(w - b)} ${f(b)} l ${f(w - b)} ${f(h - b)} l f Q\n")
            }
        }
        return sb.toString()
    }

    private fun darker(c: Int): Int = (((c shr 16) and 0xFF) * 3 / 4 shl 16) or (((c shr 8) and 0xFF) * 3 / 4 shl 8) or ((c and 0xFF) * 3 / 4)

    /** PDF string literal for WinAnsi text (characters outside Latin-1 become '?'). */
    fun lit(s: String): String {
        val sb = StringBuilder("(")
        for (ch in s) {
            when {
                ch == '(' || ch == ')' || ch == '\\' -> sb.append('\\').append(ch)
                ch.code in 32..255 -> sb.append(ch)
                else -> sb.append('?')
            }
        }
        return sb.append(')').toString()
    }

    /** Approximate Helvetica width (no metrics loaded): good enough to centre captions. */
    fun approxWidth(text: String, size: Float): Float = text.sumOf { c ->
        when {
            c == ' ' || c == 'i' || c == 'l' || c == 'j' || c == '.' || c == ',' -> 0.28
            c.isUpperCase() || c == 'm' || c == 'w' -> 0.72
            c.isDigit() -> 0.556
            else -> 0.52
        }
    }.toFloat() * size

    /** Size of the box the content is drawn in (swapped for 90 / 270 rotation). */
    fun box(s: FieldSpec, r: FxRect): Pair<Float, Float> = if (s.rotation % 180 != 0) r.height to r.width else r.width to r.height

    /** Check box / radio /AP: /N and /D with the on-state [on] and /Off. */
    fun checkAp(doc: PDDocument, s: FieldSpec, r: FxRect, on: String): COSDictionary {
        val (w, h) = box(s, r)
        val circle = s.type == FxType.RADIO && s.checkStyle == FxCheckStyle.CIRCLE
        val size = if (s.fontSize > 0f) s.fontSize else minOf(w, h) * 0.8f
        val glyph = s.checkStyle.zapf
        val gw = when (s.checkStyle) { FxCheckStyle.CHECK -> 0.846f; FxCheckStyle.CIRCLE -> 0.791f; FxCheckStyle.CROSS -> 0.759f; FxCheckStyle.DIAMOND -> 0.776f; FxCheckStyle.SQUARE -> 0.761f; FxCheckStyle.STAR -> 0.816f } * size
        val mark = "q BT /ZaDb ${f(size)} Tf ${rg(s.textColor, true)} ${f((w - gw) / 2)} ${f((h - size * 0.7f) / 2)} Td ${lit(glyph)} Tj ET Q\n"
        fun dict(down: Boolean) = COSDictionary().apply {
            setItem(n(on), stream(doc, w, h, frame(s, w, h, down, circle) + mark, listOf(FxFont.ZAPF), s.rotation, r.width, r.height))
            setItem(COSName.Off, stream(doc, w, h, frame(s, w, h, down, circle), emptyList(), s.rotation, r.width, r.height))
        }
        return COSDictionary().apply { setItem(COSName.N, dict(false)); setItem(COSName.D, dict(true)) }
    }

    /** Push button /AP: normal, rollover and down captions; the down look follows the highlight mode. */
    /** Icon placement box (x, y, w, h) inside a [w] x [h] button for the layout, leaving room for the label. */
    fun iconBox(layout: FxButtonLayout, w: Float, h: Float, size: Float): FloatArray = when (layout) {
        FxButtonLayout.LABEL_BELOW_ICON -> floatArrayOf(2f, size + 4f, w - 4f, (h - size - 6f).coerceAtLeast(1f))
        FxButtonLayout.LABEL_ABOVE_ICON -> floatArrayOf(2f, 2f, w - 4f, (h - size - 6f).coerceAtLeast(1f))
        FxButtonLayout.LABEL_RIGHT_OF_ICON -> floatArrayOf(2f, 2f, minOf(h - 4f, w / 2), h - 4f)
        FxButtonLayout.LABEL_LEFT_OF_ICON -> floatArrayOf(w - 2f - minOf(h - 4f, w / 2), 2f, minOf(h - 4f, w / 2), h - 4f)
        else -> floatArrayOf(2f, 2f, w - 4f, h - 4f)
    }

    /** "cm" operands that fit an icon of [iw] x [ih] into box [b] by the /IF scaling rules (centred). */
    fun iconMatrix(iw: Float, ih: Float, b: FloatArray, scale: FxIconScale, proportional: Boolean): FloatArray {
        val sx0 = b[2] / iw.coerceAtLeast(0.01f); val sy0 = b[3] / ih.coerceAtLeast(0.01f)
        val doScale = when (scale) {
            FxIconScale.ALWAYS -> true
            FxIconScale.BIGGER -> iw > b[2] || ih > b[3]
            FxIconScale.SMALLER -> iw < b[2] && ih < b[3]
            FxIconScale.NEVER -> false
        }
        var sx = 1f; var sy = 1f
        if (doScale) { if (proportional) { val k = minOf(sx0, sy0); sx = k; sy = k } else { sx = sx0; sy = sy0 } }
        return floatArrayOf(sx, 0f, 0f, sy, b[0] + (b[2] - iw * sx) / 2, b[1] + (b[3] - ih * sy) / 2)
    }

    /** Push button /AP; [icon] is the /MK /I form XObject (drawn per layout and /IF). */
    fun buttonAp(doc: PDDocument, s: FieldSpec, r: FxRect, icon: COSStream? = null): COSDictionary {
        val (w, h) = box(s, r)
        val size = if (s.fontSize > 0f) s.fontSize else (h * 0.45f).coerceIn(6f, 14f)
        val ib = (icon?.getDictionaryObject(COSName.BBOX) as? COSArray)?.let { a -> FloatArray(4) { i -> (a.getObject(i) as? COSNumber)?.floatValue() ?: 0f } }
        fun face(caption: String, down: Boolean): COSStream {
            val sb = StringBuilder(frame(s, w, h, down))
            if (icon != null && ib != null && s.layout != FxButtonLayout.LABEL_ONLY) {
                val m = iconMatrix(ib[2] - ib[0], ib[3] - ib[1], iconBox(s.layout, w, h, size), s.iconScale, s.iconProportional)
                sb.append("q 0 0 ").append(f(w)).append(' ').append(f(h)).append(" re W n ").append(m.joinToString(" ") { f(it) })
                    .append(" cm 1 0 0 1 ").append(f(-ib[0])).append(' ').append(f(-ib[1])).append(" cm /FxIcon Do Q\n")
            }
            if (down && s.highlight == FxHighlight.INVERT) sb.append("q 1 1 1 rg 0 0 ${f(w)} ${f(h)} re f Q\n")
            if (s.layout != FxButtonLayout.ICON_ONLY && caption.isNotEmpty()) {
                val tw = approxWidth(caption, size)
                val dy = if (down && s.highlight == FxHighlight.PUSH) -1f else 0f
                val y = when (s.layout) {
                    FxButtonLayout.LABEL_BELOW_ICON -> 2f
                    FxButtonLayout.LABEL_ABOVE_ICON -> h - size - 2f
                    else -> (h - size * 0.7f) / 2
                } + dy
                val x = when (s.layout) {
                    FxButtonLayout.LABEL_RIGHT_OF_ICON -> (w - tw - 2f).coerceAtLeast(2f)
                    FxButtonLayout.LABEL_LEFT_OF_ICON -> 2f
                    else -> ((w - tw) / 2).coerceAtLeast(2f)
                }
                sb.append("q BT /${s.font.res} ${f(size)} Tf ${rg(s.textColor, true)} ${f(x)} ${f(y)} Td ${lit(caption)} Tj ET Q\n")
            }
            if (down && s.highlight == FxHighlight.OUTLINE) sb.append("q 2 w 0 0 0 RG 1 1 ${f(w - 2)} ${f(h - 2)} re S Q\n")
            val st = stream(doc, w, h, sb.toString(), listOf(s.font), s.rotation, r.width, r.height)
            if (icon != null) (st.getDictionaryObject(COSName.RESOURCES) as COSDictionary).setItem(COSName.XOBJECT, COSDictionary().apply { setItem(n("FxIcon"), icon) })
            return st
        }
        return COSDictionary().apply {
            setItem(COSName.N, face(s.caption, false))
            if (s.highlight == FxHighlight.PUSH || s.rolloverCaption.isNotEmpty()) setItem(COSName.R, face(s.rolloverCaption.ifEmpty { s.caption }, false))
            if (s.highlight != FxHighlight.NONE || s.downCaption.isNotEmpty()) setItem(COSName.D, face(s.downCaption.ifEmpty { s.caption }, true))
        }
    }

    /** Barcode /AP: the [modules] grid (rows of dark/light) scaled into the widget with a quiet zone. */
    fun barcodeAp(doc: PDDocument, s: FieldSpec, r: FxRect, modules: Array<BooleanArray>, rowHeight: Float = 1f): COSDictionary {
        val (w, h) = box(s, r)
        val sb = StringBuilder(frame(s.copy(fillColor = s.fillColor ?: 0xFFFFFF), w, h))
        if (modules.isNotEmpty() && modules[0].isNotEmpty()) {
            val cols = modules[0].size; val rows = modules.size
            val quiet = 2f
            val mw = (w - 2 * quiet) / cols
            val mh = minOf((h - 2 * quiet) / (rows * rowHeight), mw * 4f) * rowHeight
            val usedH = mh * rows
            val y0 = (h - usedH) / 2
            sb.append("q ${rg(s.textColor, true)}\n")
            for (y in 0 until rows) {
                var x = 0
                while (x < cols) {
                    if (!modules[y][x]) { x++; continue }
                    val start = x
                    while (x < cols && modules[y][x]) x++
                    val top = h - y0 - y * mh
                    sb.append("${f(quiet + start * mw)} ${f(top - mh)} ${f((x - start) * mw)} ${f(mh)} re\n")
                }
            }
            sb.append("f Q\n")
        }
        return COSDictionary().apply { setItem(COSName.N, stream(doc, w, h, sb.toString(), emptyList(), s.rotation, r.width, r.height)) }
    }

    /** Text appearance without font metrics (fallback when PdfBox cannot build one): value at the field's /DA. */
    fun textAp(doc: PDDocument, s: FieldSpec, r: FxRect, value: String): COSDictionary {
        val (w, h) = box(s, r)
        val size = if (s.fontSize > 0f) s.fontSize else (h * 0.6f).coerceIn(4f, 12f)
        val sb = StringBuilder(frame(s, w, h))
        sb.append("/Tx BMC q 1 1 ${f(w - 2)} ${f(h - 2)} re W n BT /${s.font.res} ${f(size)} Tf ${rg(s.textColor, true)}\n")
        val lines = if (s.multiline) value.split('\n') else listOf(value.replace('\n', ' '))
        var y = if (s.multiline) h - 2f - size else (h - size * 0.7f) / 2
        lines.forEach { line ->
            val tw = approxWidth(line, size)
            val x = when (s.align) { 1 -> (w - tw) / 2; 2 -> w - 2f - tw; else -> 2f }
            sb.append("1 0 0 1 ${f(x)} ${f(y)} Tm ${lit(line)} Tj\n")
            y -= size * 1.15f
        }
        sb.append("ET Q EMC\n")
        return COSDictionary().apply { setItem(COSName.N, stream(doc, w, h, sb.toString(), listOf(s.font), s.rotation, r.width, r.height)) }
    }

    /** Readable caption kept in /MK (used when reading a button back). */
    fun mkString(w: COSDictionary, key: String): String = ((w.getDictionaryObject(MK) as? COSDictionary)?.getDictionaryObject(n(key)) as? COSString)?.string ?: ""
}
