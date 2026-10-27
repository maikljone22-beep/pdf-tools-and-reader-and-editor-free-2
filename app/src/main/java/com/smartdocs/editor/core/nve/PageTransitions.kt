package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * Page transitions written into the PDF (/Trans dictionary of each page, ISO 32000 §12.4.4) and read back by the
 * presentation mode. Styles: the 12 of the specification (Split, Blinds, Box, Wipe, Dissolve, Glitter, R(eplace),
 * Fly, Push, Cover, Uncover, Fade).
 */
enum class TransStyle(val pdf: String) { REPLACE("R"), SPLIT("Split"), BLINDS("Blinds"), BOX("Box"), WIPE("Wipe"), DISSOLVE("Dissolve"), GLITTER("Glitter"), FLY("Fly"), PUSH("Push"), COVER("Cover"), UNCOVER("Uncover"), FADE("Fade") }

/** One page transition: [direction] in degrees (0 = left to right, 90 = bottom to top, 270 = top to bottom, 180 = right to left; 315 Glitter only). */
data class PageTrans(
    val style: TransStyle = TransStyle.REPLACE,
    val duration: Float = 1f,
    /** Split / Blinds: horizontal (H) or vertical (V). */
    val horizontal: Boolean = true,
    /** Split / Box: inward (true, /M /I) or outward. */
    val inward: Boolean = true,
    val direction: Int = 0,
    /** Auto-advance after this many seconds (/Dur on the page), null = manual. */
    val autoAdvance: Float? = null,
)

object PageTransitions {
    private val TRANS = COSName.getPDFName("Trans")
    private val DUR = COSName.getPDFName("Dur")

    fun read(doc: PDDocument, page: Int): PageTrans? {
        val pd = doc.getPage(page).cosObject
        val t = pd.getDictionaryObject(TRANS) as? COSDictionary
        val dur = pd.getFloat(DUR, -1f).takeIf { it >= 0f }
        if (t == null) return dur?.let { PageTrans(autoAdvance = it) }
        val s = t.getNameAsString(COSName.S) ?: "R"
        val style = TransStyle.entries.firstOrNull { it.pdf == s } ?: TransStyle.REPLACE
        val di = t.getDictionaryObject(COSName.getPDFName("Di"))
        val dir = when (di) { is com.tom_roush.pdfbox.cos.COSNumber -> di.intValue(); else -> 0 }
        return PageTrans(
            style, t.getFloat(COSName.D, 1f),
            horizontal = t.getNameAsString(COSName.getPDFName("Dm")) != "V",
            inward = t.getNameAsString(COSName.getPDFName("M")) != "O",
            direction = dir, autoAdvance = dur,
        )
    }

    /** Write [t] on [pages] (null = every page); a null [t] removes transitions and auto-advance. */
    fun write(doc: PDDocument, pages: Collection<Int>?, t: PageTrans?) {
        val targets = pages ?: (0 until doc.numberOfPages).toList()
        for (i in targets) {
            if (i !in 0 until doc.numberOfPages) continue
            val pd = doc.getPage(i).cosObject
            if (t == null) { pd.removeItem(TRANS); pd.removeItem(DUR); continue }
            val d = COSDictionary()
            d.setItem(COSName.TYPE, TRANS)
            d.setName(COSName.S, t.style.pdf)
            d.setItem(COSName.D, COSFloat(t.duration))
            if (t.style == TransStyle.SPLIT || t.style == TransStyle.BLINDS) d.setName(COSName.getPDFName("Dm"), if (t.horizontal) "H" else "V")
            if (t.style == TransStyle.SPLIT || t.style == TransStyle.BOX || t.style == TransStyle.FLY) d.setName(COSName.getPDFName("M"), if (t.inward) "I" else "O")
            if (t.style in setOf(TransStyle.WIPE, TransStyle.GLITTER, TransStyle.FLY, TransStyle.COVER, TransStyle.UNCOVER, TransStyle.PUSH)) d.setInt(COSName.getPDFName("Di"), t.direction)
            pd.setItem(TRANS, d)
            if (t.autoAdvance != null) pd.setItem(DUR, COSFloat(t.autoAdvance)) else pd.removeItem(DUR)
        }
    }

    /** "1-3, 5, 8-" → zero-based pages (1-based input; open ranges go to the end). */
    fun parseRange(text: String, pageCount: Int): List<Int>? {
        if (text.isBlank()) return (0 until pageCount).toList()
        val out = sortedSetOf<Int>()
        for (part in text.split(',', ';', '،')) {
            val p = part.trim(); if (p.isEmpty()) continue
            val m = Regex("^(\\d*)\\s*-\\s*(\\d*)$").find(p)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: 1; val b = m.groupValues[2].toIntOrNull() ?: pageCount
                if (a < 1 || b < a) return null
                for (k in a..minOf(b, pageCount)) out.add(k - 1)
            } else { val k = p.toIntOrNull() ?: return null; if (k in 1..pageCount) out.add(k - 1) else return null }
        }
        return out.toList()
    }
}
