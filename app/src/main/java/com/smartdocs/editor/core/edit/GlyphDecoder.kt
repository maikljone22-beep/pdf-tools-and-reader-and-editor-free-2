package com.smartdocs.editor.core.edit

import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType0
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1CFont
import java.util.IdentityHashMap

/**
 * Recovers the Unicode text of a glyph when PdfBox's `PDFont.toUnicode` gives nothing useful (no /ToUnicode map,
 * Identity-H CID fonts, private-use or control codes, a non-Unicode cmap). Tried in order:
 *  1. the PdfBox answer, when it is a real character;
 *  2. the embedded TrueType font's own Unicode cmap ((3,1), (3,10), (0,x)) looked up backwards: glyph id → char;
 *  3. the glyph name (TrueType `post` table, CFF charset, simple-font encoding): `uni0627`, `u0627`, `afii57415`,
 *     `alefarabic`, `alef`, `alef-ar`, `beh.init`, `lam_alef`, `uni0644_uni0627`, …
 * Returns "" when nothing works (the caller marks the block as lossy / unreadable).
 * One instance per page analysis (caches per font object).
 */
internal class GlyphDecoder {
    private val fonts = IdentityHashMap<PDFont, FontLookup>()

    /** Characters that make a decoded glyph useless for editing. */
    private fun bad(c: Char) = c.code in 0xE000..0xF8FF || c.code == 0xFFFD || (c.code < 0x20 && c != '\t') || c.code in 0xFFF0..0xFFFF

    private fun usable(u: String?): Boolean = u != null && u.isNotEmpty() && u.none { bad(it) }

    fun text(font: PDFont?, code: Int, unicode: String?): String {
        if (font == null) return if (usable(unicode)) unicode!! else ""
        val f = fonts.getOrPut(font) { FontLookup(font) }
        // no /ToUnicode: PdfBox took the FIRST cmap code of the glyph (Arial: ی U+06CC for ي, ھ for ه); our ranked
        // reverse lookup prefers the basic Arabic letter
        if (!f.hasToUnicode) f.recover(code)?.let { return it }
        if (usable(unicode) && !f.distrustPdfBox) return unicode!!
        val r = runCatching { f.recover(code) }.getOrNull()
        if (r != null) return r
        return if (usable(unicode)) unicode!! else ""
    }

    private class FontLookup(val font: PDFont) {
        private val cache = HashMap<Int, String?>()
        val hasToUnicode = runCatching { font.cosObject.containsKey(COSName.TO_UNICODE) }.getOrDefault(false)
        private val ttf: TrueTypeFont? = runCatching {
            when (font) {
                is PDType0Font -> (font.descendantFont as? PDCIDFontType2)?.takeIf { it.isEmbedded }?.trueTypeFont
                is PDTrueTypeFont -> font.takeIf { it.isEmbedded }?.trueTypeFont
                else -> null
            }
        }.getOrNull()
        /** glyph id → best Unicode char of the font's Unicode cmaps (built once). */
        private val reverse: Map<Int, Char>? by lazy { buildReverse() }

        /**
         * PdfBox's own fallback for fonts without /ToUnicode takes ANY cmap (Mac Roman, symbol) and returns its codes as
         * if they were Unicode: Latin letters for an Arabic font. When the font has no ToUnicode and no Unicode cmap,
         * the glyph names are preferred.
         */
        val distrustPdfBox: Boolean by lazy { !hasToUnicode && ttf != null && reverse.isNullOrEmpty() && postNamesUseful() }

        private fun postNamesUseful(): Boolean = runCatching {
            val names = ttf?.postScript?.glyphNames ?: return@runCatching false
            names.take(400).count { nameToUnicode(it) != null } >= 3
        }.getOrDefault(false)

        private fun buildReverse(): Map<Int, Char>? = runCatching {
            val ttf = this.ttf ?: return@runCatching null
            val cmaps = ttf.cmap?.cmaps ?: return@runCatching null
            val uni = cmaps.filter { (it.platformId == 3 && (it.platformEncodingId == 1 || it.platformEncodingId == 10)) || it.platformId == 0 }
            if (uni.isEmpty()) return@runCatching null
            val out = HashMap<Int, Char>()
            val numGlyphs = runCatching { ttf.numberOfGlyphs }.getOrDefault(0).coerceIn(0, 65535)
            for (gid in 1 until numGlyphs) {
                var best: Char? = null
                for (st in uni) {
                    val codes = runCatching { st.getCharCodes(gid) }.getOrNull() ?: continue
                    for (cp in codes) {
                        if (cp !in 0x20..0xFFFF) continue
                        val c = cp.toChar()
                        if (c.code in 0xE000..0xF8FF) continue
                        if (best == null || rank(c) < rank(best)) best = c
                    }
                }
                if (best != null) out[gid] = best
            }
            // contextual forms made by the font's GSUB (init/medi/fina/isol single substitutions) have no cmap entry
            // of their own: map them back to the letter of the glyph they were substituted from
            runCatching {
                val gsub = ttf.gsub ?: return@runCatching
                val scripts = arrayOf("arab")
                val letters = out.entries.filter { it.value.code in 0x0620..0x06FF }.map { it.key to it.value }
                for (feature in listOf("init", "medi", "fina", "isol")) for ((gid, ch) in letters) {
                    val s = runCatching { gsub.getSubstitution(gid, scripts, listOf(feature)) }.getOrNull() ?: continue
                    if (s != gid && s > 0 && !out.containsKey(s)) out[s] = ch
                }
            }
            out
        }.getOrNull()

        /**
         * Lower is better. A glyph shared by several characters (Arabic yeh / Farsi yeh, heh / heh doachashmee, their
         * presentation forms) gets the basic Arabic letter: candidates are compared by their base letter (NFKC), basic
         * block U+0621..U+065F first, then the rest of U+06xx, then others; ties by code point.
         */
        private fun rank(c: Char): Int {
            val b = if (c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFE)
                java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFKC).trim().firstOrNull() ?: c else c
            val group = when {
                b.code in 0x0621..0x065F -> 0
                b.code in 0x0600..0x06FF || b.code in 0x0750..0x077F -> 1
                else -> 2
            }
            return group * 0x20000 + b.code * 2 + (if (b == c) 0 else 1)
        }

        private fun gidOf(code: Int): Int? = runCatching {
            when (font) {
                is PDType0Font -> font.codeToGID(code)
                is PDTrueTypeFont -> font.codeToGID(code)
                else -> null
            }
        }.getOrNull()

        fun recover(code: Int): String? = cache.getOrPut(code) { compute(code) }

        private fun compute(code: Int): String? {
            val gid = gidOf(code)
            if (gid != null && gid > 0) {
                reverse?.get(gid)?.let { return it.toString() }
                ttf?.let { t -> runCatching { t.postScript?.getName(gid) }.getOrNull()?.let { nameToUnicode(it) }?.let { return it } }
            }
            // CFF glyph names
            runCatching {
                when (font) {
                    is PDType0Font -> (font.descendantFont as? PDCIDFontType0)?.cffFont?.let { cff ->
                        cff.charset?.getNameForGID((font.descendantFont as PDCIDFontType0).codeToGID(code))
                    }
                    is PDType1CFont -> font.encoding?.getName(code)
                    else -> null
                }
            }.getOrNull()?.let { nameToUnicode(it) }?.let { return it }
            // simple fonts: encoding (+ /Differences) names
            val simple = font as? PDSimpleFont
            if (simple != null) {
                val name: String? = runCatching { simple.encoding?.getName(code) }.getOrNull()
                nameToUnicode(name)?.let { return it }
            }
            return null
        }
    }

    companion object {
        /** Arabic letter names used by font tools (Glyphs, FontForge, many Arabic fonts) without the "arabic" suffix. */
        private val NAMES: Map<String, Int> = mapOf(
            "hamza" to 0x0621, "alefmadda" to 0x0622, "alefmaddaabove" to 0x0622, "alefhamza" to 0x0623, "alefhamzaabove" to 0x0623,
            "wawhamza" to 0x0624, "wawhamzaabove" to 0x0624, "alefhamzabelow" to 0x0625, "yehhamza" to 0x0626, "yehhamzaabove" to 0x0626,
            "alef" to 0x0627, "alif" to 0x0627, "beh" to 0x0628, "tehmarbuta" to 0x0629, "tehmarbutah" to 0x0629, "teh" to 0x062A,
            "theh" to 0x062B, "tha" to 0x062B, "jeem" to 0x062C, "jim" to 0x062C, "hah" to 0x062D, "khah" to 0x062E, "kha" to 0x062E, "dal" to 0x062F,
            "thal" to 0x0630, "dhal" to 0x0630, "reh" to 0x0631, "ra" to 0x0631, "zain" to 0x0632, "zay" to 0x0632, "seen" to 0x0633, "sin" to 0x0633,
            "sheen" to 0x0634, "shin" to 0x0634, "sad" to 0x0635, "dad" to 0x0636, "tah" to 0x0637, "zah" to 0x0638, "ain" to 0x0639, "ghain" to 0x063A,
            "tatweel" to 0x0640, "kashida" to 0x0640, "feh" to 0x0641, "fa" to 0x0641, "qaf" to 0x0642, "kaf" to 0x0643, "lam" to 0x0644,
            "meem" to 0x0645, "mim" to 0x0645, "noon" to 0x0646, "nun" to 0x0646, "heh" to 0x0647, "waw" to 0x0648,
            "alefmaksura" to 0x0649, "yeh" to 0x064A, "ya" to 0x064A, "fathatan" to 0x064B, "dammatan" to 0x064C, "kasratan" to 0x064D,
            "fatha" to 0x064E, "damma" to 0x064F, "kasra" to 0x0650, "shadda" to 0x0651, "sukun" to 0x0652, "alefwasla" to 0x0671,
            "peh" to 0x067E, "tcheh" to 0x0686, "jeh" to 0x0698, "veh" to 0x06A4, "keheh" to 0x06A9, "gaf" to 0x06AF, "farsiyeh" to 0x06CC,
        )
        /** A few standard Latin names, used only when the Adobe glyph list resource cannot be loaded. */
        private val LATIN: Map<String, Char> = mapOf(
            "zero" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4', "five" to '5', "six" to '6', "seven" to '7', "eight" to '8',
            "nine" to '9', "period" to '.', "comma" to ',', "colon" to ':', "semicolon" to ';', "hyphen" to '-', "parenleft" to '(', "parenright" to ')',
            "slash" to '/', "percent" to '%', "question" to '?', "exclam" to '!',
        )

        /** Adobe glyph list entries for Arabic, in case the AGL resource is unavailable. afii57xxx. */
        private fun afii(n: Int): Int? = when (n) {
            57381 -> 0x066A; 57388 -> 0x060C; 57403 -> 0x061B; 57407 -> 0x061F
            in 57392..57401 -> 0x0660 + (n - 57392)
            in 57409..57434 -> 0x0621 + (n - 57409)
            in 57440..57446 -> 0x0640 + (n - 57440)
            in 57448..57458 -> 0x0648 + (n - 57448)
            57470 -> 0x0647
            57506 -> 0x067E; 57507 -> 0x0686; 57508 -> 0x0698; 57509 -> 0x06AF; 57511 -> 0x0679; 57512 -> 0x0688; 57513 -> 0x0691
            57514 -> 0x06BA; 57519 -> 0x06D2; 57534 -> 0x06D5
            else -> null
        }

        private val agl: com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList? by lazy {
            try { com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList.getAdobeGlyphList() } catch (t: Throwable) { null }
        }

        /** Unicode text of a glyph name, or null. Ligature names ("lam_alef", "uni06440627") give several letters. */
        fun nameToUnicode(name0: String?): String? {
            val name = name0?.trim() ?: return null
            if (name.isEmpty() || name == ".notdef" || name.startsWith(".null") || name == "nonmarkingreturn") return null
            if (name == "space" || name == "nbspace" || name == "uni00A0") return " "
            val base = name.substringBefore('.').ifEmpty { return null }
            val parts = base.split('_').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val sb = StringBuilder()
            for (p in parts) sb.append(component(p) ?: return null)
            val s = sb.toString()
            return s.takeIf { it.isNotEmpty() && it.none { c -> c.code in 0xE000..0xF8FF || c.code < 0x20 } }
        }

        private fun hex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

        private fun component(p0: String): String? {
            // "uni0627", "uni06440627" (groups of 4), "u1EE00"
            if (p0.startsWith("uni") && p0.length >= 7 && (p0.length - 3) % 4 == 0 && hex(p0.substring(3))) {
                val sb = StringBuilder()
                var i = 3
                while (i < p0.length) { sb.append(p0.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                return sb.toString()
            }
            if (p0.startsWith("u") && p0.length in 5..7 && hex(p0.substring(1))) {
                val cp = p0.substring(1).toInt(16)
                return if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
            }
            if (p0.startsWith("afii")) p0.substring(4).toIntOrNull()?.let { afii(it) }?.let { return it.toChar().toString() }
            // Arabic font-tool names first: the AGL maps bare "alef" to the HEBREW letter (U+05D0)
            arabicName(p0)?.let { return it }
            agl?.let { g -> runCatching { g.toUnicode(p0) }.getOrNull()?.let { return it } }
            if (agl == null) {
                LATIN[p0]?.let { return it.toString() }
                if (p0.length == 1 && (p0[0] in 'a'..'z' || p0[0] in 'A'..'Z')) return p0
            }
            return null
        }

        private fun arabicName(p0: String): String? {
            // "alef-ar", "beh-arab", "lam.init" (already stripped), "Alef", "alefHamzaabove", "behinitial", "yeh_fina"
            var k = p0.lowercase().removeSuffix("-ar").removeSuffix("-arab").removeSuffix("arabic")
            for (suf in listOf("isolated", "initial", "medial", "final", "isol", "init", "medi", "fina", "fin", "ini", "med", "iso")) {
                if (k.length > suf.length + 1 && k.endsWith(suf)) { k = k.removeSuffix(suf); break }
            }
            NAMES[k]?.let { return it.toChar().toString() }
            // "lamalef" (common ligature name without separator)
            if (k == "lamalef" || k == "lamalif" || k == "lamaleffinal" || k == "la") return "لا"
            return null
        }
    }
}
