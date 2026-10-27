package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * Repairs the wrong ToUnicode maps of embedded Arabic fonts, so copy, search, AI, TTS and reflow get the letters the
 * page shows («الدرس … الكيمياء» instead of «اٌذسط … اٌىُُّبء»).
 *
 * The fault (Word documents exported to PDF by iText 5 and similar tools, fonts PT Bold Heading, Times New Roman,
 * Arial, Yaser, Bader, AL-Hor…): the glyphs are positional forms (ﺑ ﺒ ﺐ ﺏ) and the ToUnicode CMap describes runs of
 * consecutive glyph ids with an *incrementing* `bfrange` — `<00CC> <00E1> <0641>` gives the 22 forms of ف ق ك ل م ن
 * the 22 code points ف ق ك ل م ن ه و ى ي ً ٌ ٍ َ ُ ِ ّ ْ ٓ ٔ ٕ ٖ — so most forms extract as a later letter or a mark.
 * The mapping depends on the glyph (form), not on the letter: the same wrong code point comes from several letters
 * (extracted «د» is teh-isolated, hah-initial or dal), so it cannot be undone reliably on the extracted text.
 * The embedded TrueType font still carries its own cmap, which maps every glyph to its presentation-form code point
 * (U+FE8F BEH ISOLATED…). That is the authoritative identity of the glyph; this repair rewrites the ToUnicode entries
 * that disagree with it. Rendering is untouched (only /ToUnicode changes).
 *
 * Ligature glyphs (لا لأ لم مح في …) are written as their single presentation-form code point: PDFium reverses a
 * multi-letter ToUnicode value in right-to-left text («مح» extracts as «حم», «في» as «يف»), a ligature code point it
 * decomposes in the right order.
 */
object ToUnicodeRepair {

    /** [fonts] fonts rewritten, [letters] glyph entries whose letter was wrong, [pages] pages using a repaired font. */
    /** [ocrLayersKept]: repaired pages that keep the app's old OCR layer (they show pictures): OCR them again. */
    data class Result(val fonts: Int, val letters: Int, val ligatures: Int, val pages: Set<Int>, val ocrLayersRemoved: Int = 0, val ocrLayersKept: Set<Int> = emptySet()) {
        val changed: Boolean get() = fonts > 0 || ocrLayersRemoved > 0
    }

    /** One glyph's new value and whether it fixes a wrong letter (true) or only re-encodes a correct ligature (false). */
    data class Fix(val value: String, val letter: Boolean)

    fun isArabicCp(c: Int) = c in 0x0600..0x06FF || c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFC
    private fun isDigitCp(c: Int) = c in 0x0660..0x0669 || c in 0x06F0..0x06F9
    private fun isMark(c: Char) = c.code in 0x064B..0x065F || c.code == 0x0670
    private fun letterCount(s: String) = s.count { it.code in 0x0621..0x064A || it.code in 0x0671..0x06D3 }

    /** The letters a font code point stands for: presentation forms folded (NFKC), no spacing, no tatweel inside ligatures. */
    fun baseOf(cp: Int): String {
        val s = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC).replace(" ", "")
        return if (s.length > 1) s.replace("ـ", "") else s
    }

    private fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).replace(" ", "")

    /**
     * New ToUnicode value for one glyph, or null to keep [current]. [cmapCodes]: code points the embedded font's own cmap
     * maps to the glyph. Only Arabic glyphs are judged; digits (Word maps Arabic-Indic digit glyphs to the ASCII digits
     * that were typed), brackets (mirrored on purpose), Latin and symbols keep the producer's choice. Plain Kotlin.
     */
    fun correction(current: String?, cmapCodes: Collection<Int>): Fix? {
        // Word's math font (Cambria Math): the bold digits of a fraction like «5/2» (U+1D7D3, U+1D7D0, outside the BMP) are
        // mapped to a space, so «C2H2 + 5/2 O2» copied as «C2H2 + ⁄ O2» (OCR_QUALITY_r5). The cmap names the real
        // mathematical letter or digit; it is written folded (NFKC: 𝟓 -> 5).
        if (current.isNullOrBlank()) {
            val m = cmapCodes.firstOrNull { it in 0x1D400..0x1D7FF }
            if (m != null) {
                val v = normalized(String(Character.toChars(m)))
                if (v.length == 1 && v[0].isLetterOrDigit()) return Fix(v, true)
            }
        }
        val ar = cmapCodes.filter { isArabicCp(it) && !isDigitCp(it) }
            .sortedWith(compareBy({ if (it in 0xFE70..0xFEFC || it in 0x0621..0x0652) 0 else 1 }, { it }))
        if (ar.isEmpty()) return null
        val cur = current.orEmpty()
        val curBase = normalized(cur)
        if (ar.any { baseOf(it) == curBase }) {
            // right letters; a ligature spelled as several letters is reversed by PDFium -> its presentation code point
            if (letterCount(curBase) < 2) return null
            val lig = ar.firstOrNull { it >= 0xFB50 && baseOf(it) == curBase } ?: return null
            val l = String(Character.toChars(lig))
            return if (cur == l) null else Fix(l, false)
        }
        if (cur.isNotEmpty() && cur.any { !isArabicCp(it.code) }) return null
        val best = ar.first()
        val b = baseOf(best)
        if (b.isEmpty()) return null
        // the font's «ﷲ» glyph drawn after a separate alef glyph: the producer's «لله» is right, «الله» would double the alef
        if (letterCount(curBase) >= 2 && b.length > curBase.length && b.endsWith(curBase)) return null
        // a mark glyph drawn as shadda + kasra whose cmap names only the kasra: the producer's marks are more complete
        if (cur.isNotEmpty() && cur.all { isMark(it) } && b.all { isMark(it) } && b.all { it in cur }) return null
        return Fix(if (letterCount(b) >= 2) String(Character.toChars(best)) else b, true)
    }

    /** A font's ToUnicode CMap split into [trusted] entries (bfchar, array bfrange, first code of a range) and [ranged] ones. */
    data class ParsedCMap(val trusted: Map<Int, String>, val ranged: Map<Int, String>, val rangeStart: Map<Int, Int> = emptyMap())

    /** Parses the bfchar / bfrange sections of a ToUnicode CMap (2-byte or 1-byte codes). Plain Kotlin (unit-tested). */
    fun parseToUnicode(text: String): ParsedCMap {
        val trusted = TreeMap<Int, String>(); val ranged = TreeMap<Int, String>(); val start = HashMap<Int, Int>()
        fun hex(h: String): String { val sb = StringBuilder(); var i = 0; while (i + 4 <= h.length) { sb.append(h.substring(i, i + 4).toInt(16).toChar()); i += 4 }; return sb.toString() }
        Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { b ->
            Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]*)>""").findAll(b.groupValues[1]).forEach { m ->
                trusted[m.groupValues[1].toInt(16)] = hex(m.groupValues[2])
            }
        }
        Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { b ->
            Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(\[[^\]]*\]|<[0-9A-Fa-f]*>)""").findAll(b.groupValues[1]).forEach { m ->
                val lo = m.groupValues[1].toInt(16); val hi = m.groupValues[2].toInt(16); val dst = m.groupValues[3]
                if (hi < lo || hi - lo > 0xFFFF) return@forEach
                if (dst.startsWith("[")) {
                    Regex("<([0-9A-Fa-f]*)>").findAll(dst).forEachIndexed { i, h -> if (lo + i <= hi) trusted[lo + i] = hex(h.groupValues[1]) }
                } else {
                    val v = hex(dst.trim('<', '>'))
                    if (v.isEmpty()) return@forEach
                    trusted[lo] = v
                    for (i in 1..(hi - lo)) { ranged[lo + i] = v.dropLast(1) + (v.last() + i); start[lo + i] = lo }
                }
            }
        }
        return ParsedCMap(trusted, ranged, start)
    }

    private val presentation: List<Int> by lazy {
        (0xFE70..0xFEFC).filter { runCatching { Character.getName(it) }.getOrNull() != null && baseOf(it).isNotEmpty() }
    }

    /** Multi-letter value -> a presentation-form ligature code point with exactly these letters (لا -> U+FEFB…). */
    private val ligatureOf: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (cp in (0xFEF5..0xFEFC) + (0xFB50..0xFDFF)) {
            if (runCatching { Character.getName(cp) }.getOrNull() == null) continue
            val b = baseOf(cp)
            if (letterCount(b) >= 2 && b.all { it.code in 0x0621..0x0652 }) m.putIfAbsent(b, cp)
        }
        m
    }

    /** Letters -> every presentation-form ligature code point with exactly these letters (لا -> U+FEFB, U+FEFC). */
    private val ligaturesByBase: Map<String, List<Int>> by lazy {
        val m = HashMap<String, MutableList<Int>>()
        for (cp in (0xFB50..0xFDFF) + (0xFEF5..0xFEFC)) {
            if (runCatching { Character.getName(cp) }.getOrNull() == null) continue
            val b = baseOf(cp)
            if (letterCount(b) >= 2 && b.all { it.code in 0x0621..0x0652 }) m.getOrPut(b) { ArrayList() } += cp
        }
        m
    }

    private fun isLetterValue(v: String) = v.length == 1 && v[0].code in 0x0621..0x064A

    /**
     * For a font whose embedded program has no cmap (stripped by a later subsetter such as iLovePDF): infers the offset K
     * with glyph code + K = presentation-form code point (Windows Arabic fonts lay their positional forms out in
     * U+FE80.. order: K = 0xFE08 for PT Bold Heading, 0xFB00 for Times New Roman / Arial) from the [ParsedCMap.trusted]
     * entries. The K that agrees with the most trusted letters in the block wins; it is accepted only when at least 12
     * agree and at most 10 % disagree (a re-subsetter may have baked a few range errors into single entries), and only
     * when it changes something (a correct CMap gives no corrections). Null when not confident.
     * Checked against the real cmap of every sample font that has one: 29 fonts, 2887/2887 glyphs agree. Plain Kotlin.
     */
    fun inferOffset(p: ParsedCMap): Int? {
        val votes = HashMap<Int, Int>()
        for ((c, v) in p.trusted) if (isLetterValue(v)) for (cp in presentation) if (baseOf(cp) == v) votes.merge(cp - c, 1, Int::plus)
        var best: Int? = null; var bestAgree = 0; var bestChanges = 0
        for ((k, _) in votes.entries.sortedByDescending { it.value }.take(6)) {
            var agree = 0; var bad = 0
            for ((c, v) in p.trusted) {
                if (!isLetterValue(v) || c + k !in 0xFE70..0xFEFC) continue
                if (c + k in presentation && baseOf(c + k) == v) agree++ else bad++
            }
            if (agree < 12 || bad * 10 > agree + bad || agree <= bestAgree) continue
            val wrong = p.ranged.count { (c, v) -> c + k in presentation && v.all { isArabicCp(it.code) } && normalized(v) != baseOf(c + k) }
            best = k; bestAgree = agree; bestChanges = wrong + bad
        }
        return if (best != null && bestChanges > 0) best else null
    }

    /**
     * Corrections from an inferred offset [k] (see [inferOffset]): range entries and the few trusted single letters that
     * disagree with code + k; plus ligature re-encodings of trusted multi-letter values (any font, k may be null).
     */
    fun inferredCorrections(p: ParsedCMap, k: Int?): Map<Int, Fix> {
        val out = TreeMap<Int, Fix>()
        if (k != null) {
            for ((c, v) in p.ranged + p.trusted.filterValues { isLetterValue(it) }) {
                val cp = c + k
                if (cp !in presentation || !v.all { isArabicCp(it.code) } || letterCount(v) > 1) continue
                val b = baseOf(cp)
                if (normalized(v) != b) out[c] = Fix(b, true)
            }
        }
        if (k != null) {
            // ligature glyphs inside a faulty range (لج لح لخ «لد» «له»…): the range covers consecutive ligature code points
            // starting at a form of its first value; fixed only when every such form gives the same letters
            for ((c, v) in p.ranged) {
                if (letterCount(v) < 2 || !v.all { isArabicCp(it.code) }) continue
                val lo = p.rangeStart[c] ?: continue
                val starts = ligaturesByBase[normalized(p.trusted[lo] ?: continue)] ?: continue
                val cands = starts.map { it + (c - lo) }
                if (cands.any { runCatching { Character.getName(it) }.getOrNull() == null }) continue
                val letters = cands.map { baseOf(it) }.distinct()
                if (letters.size != 1 || letterCount(letters[0]) < 2) continue
                // right letters already (لح): still re-encoded, PDFium reverses the multi-letter value
                out[c] = Fix(String(Character.toChars(cands[0])), letters[0] != normalized(v))
            }
        }
        for ((c, v) in p.trusted) {
            if (out.containsKey(c)) continue
            val lig = ligatureOf[normalized(v)] ?: continue
            if (letterCount(v) >= 2 && v.all { isArabicCp(it.code) } && v != String(Character.toChars(lig))) out[c] = Fix(String(Character.toChars(lig)), false)
        }
        return out
    }

    /** Corrections (code -> fix) for one Type0 font with an embedded TrueType program; empty when nothing is wrong. */
    fun corrections(font: PDType0Font): Map<Int, Fix> {
        val cid = font.descendantFont as? PDCIDFontType2 ?: return emptyMap()
        if (!cid.isEmbedded || font.cosObject.getDictionaryObject(COSName.TO_UNICODE) !is COSStream) return emptyMap()
        val enc = font.cosObject.getDictionaryObject(COSName.ENCODING) as? COSName ?: return emptyMap()
        if (enc.name != "Identity-H" && enc.name != "Identity-V") return emptyMap()
        val ttf = runCatching { cid.trueTypeFont }.getOrNull() ?: return emptyMap()
        val subs = runCatching { ttf.cmap?.cmaps?.toList() }.getOrNull().orEmpty()
            .filter { it.platformId == 3 && (it.platformEncodingId == 1 || it.platformEncodingId == 10) }
        if (subs.isEmpty()) {
            // subset without a cmap: infer the glyph layout from the faulty CMap itself (identity CID -> GID only)
            val tu = font.cosObject.getDictionaryObject(COSName.TO_UNICODE) as COSStream
            val text = runCatching { tu.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) } }.getOrNull() ?: return emptyMap()
            val parsed = parseToUnicode(text)
            return inferredCorrections(parsed, inferOffset(parsed))
        }
        val glyphs = runCatching { ttf.numberOfGlyphs }.getOrDefault(0)
        if (glyphs <= 0) return emptyMap()
        val identity = cid.cosObject.getDictionaryObject(COSName.CID_TO_GID_MAP).let { it == null || it is COSName }
        val out = TreeMap<Int, Fix>()
        for (code in 0..(if (identity) minOf(glyphs - 1, 0xFFFF) else 0xFFFF)) {
            val gid = if (identity) code else runCatching { cid.codeToGID(code) }.getOrDefault(0)
            if (gid <= 0 || gid >= glyphs) continue
            val cps = LinkedHashSet<Int>()
            subs.forEach { s -> runCatching { s.getCharCodes(gid) }.getOrNull()?.let { cps.addAll(it) } }
            if (cps.isEmpty()) continue
            val fix = correction(runCatching { font.toUnicode(code) }.getOrNull(), cps) ?: continue
            out[code] = fix
        }
        // only ligature re-encodings and no wrong letter: still worth it (PDFium reverses multi-letter ligature values)
        return out
    }

    /**
     * Rewrites the ToUnicode maps of the fonts used by [pages] (all pages when null) of [doc] in memory. A font shared by
     * several pages is handled once. [Result.pages]: pages that use a font with wrong letters (their text changes).
     */
    fun repair(doc: PDDocument, pages: Collection<Int>? = null, removeOldOcr: Boolean = false): Result {
        val done = IdentityHashMap<COSDictionary, Map<Int, Fix>>()
        val touched = HashSet<Int>()
        var fonts = 0; var letters = 0; var ligatures = 0
        for (pi in pages ?: (0 until doc.numberOfPages).toList()) {
            if (pi !in 0 until doc.numberOfPages) continue
            var wrongLetters = false
            forEachFont(doc.getPage(pi).resources, 0, HashSet()) { font ->
                val fix = done.getOrPut(font.cosObject) {
                    val f = runCatching { corrections(font) }.getOrDefault(emptyMap())
                    if (f.isNotEmpty() && runCatching { write(doc, font, f) }.isSuccess) {
                        fonts++; letters += f.values.count { it.letter }; ligatures += f.values.count { !it.letter }
                        f
                    } else emptyMap()
                }
                if (fix.values.any { it.letter }) wrongLetters = true
            }
            if (wrongLetters) touched += pi
        }
        // our earlier invisible OCR words only duplicate a text layer that now reads correctly ([OcrLayerCleanup])
        var removed = 0
        val kept = HashSet<Int>()
        if (removeOldOcr) for (pi in touched) {
            val page = doc.getPage(pi)
            if (scannedShare(page) >= 0.10f) {
                if (runCatching { OcrLayerCleanup.removeOurs(page, dryRun = true) }.getOrDefault(0) > 0) kept += pi
                continue
            }
            removed += runCatching { OcrLayerCleanup.removeOurs(page) }.getOrDefault(0)
        }
        return Result(fonts, letters, ligatures, touched, removed, kept)
    }

    /** Share of the page covered by pictures of at least 64x64 px (where OCR words may be the only text). */
    private fun scannedShare(page: com.tom_roush.pdfbox.pdmodel.PDPage): Float = runCatching {
        val b = page.cropBox ?: page.mediaBox
        val area = b.width * b.height
        if (area <= 0f) 0f else com.smartdocs.editor.core.ocr.MixedPageOcr.imageBoxes(page).sumOf { it.area.toDouble() }.toFloat() / area
    }.getOrDefault(1f)

    /**
     * Loads [src], repairs it and saves it to [out] when something changed (nothing is written otherwise). With
     * [removeOldOcr] the app's own earlier OCR layer is removed from repaired pages. Returns what was done.
     */
    suspend fun repairFile(src: File, password: String?, out: File, removeOldOcr: Boolean = false): Result = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc ->
            val r = repair(doc, removeOldOcr = removeOldOcr)
            if (r.changed) doc.save(out)
            r
        }
    }

    private fun forEachFont(res: PDResources?, depth: Int, seen: HashSet<COSDictionary>, f: (PDType0Font) -> Unit) {
        if (res == null || depth > 4 || !seen.add(res.cosObject)) return
        for (name in res.fontNames) {
            val font = runCatching { res.getFont(name) }.getOrNull()
            if (font is PDType0Font) f(font)
        }
        for (name in res.xObjectNames) {
            val x = runCatching { res.getXObject(name) }.getOrNull()
            if (x is PDFormXObject) forEachFont(x.resources, depth + 1, seen, f)
        }
    }

    /** Replaces the font's /ToUnicode by a CMap with its current entries (as PDFium reads them) and [fix] applied. */
    private fun write(doc: PDDocument, font: PDType0Font, fix: Map<Int, Fix>) {
        val cid = font.descendantFont as PDCIDFontType2
        val glyphs = cid.trueTypeFont.numberOfGlyphs
        val identity = cid.cosObject.getDictionaryObject(COSName.CID_TO_GID_MAP).let { it == null || it is COSName }
        val map = TreeMap<Int, String>()
        for (code in 0..(if (identity) minOf(glyphs - 1, 0xFFFF) else 0xFFFF)) {
            val v = fix[code]?.value ?: runCatching { font.toUnicode(code) }.getOrNull()
            if (!v.isNullOrEmpty()) map[code] = v
        }
        val stream = doc.document.createCOSStream()
        stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(cmapText(map).toByteArray(Charsets.US_ASCII)) }
        font.cosObject.setItem(COSName.TO_UNICODE, stream)
    }

    /** A ToUnicode CMap (2-byte codes) with one bfchar entry per code. Plain Kotlin (unit-tested). */
    fun cmapText(map: Map<Int, String>): String {
        val sb = StringBuilder()
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        map.entries.chunked(100).forEach { chunk ->
            sb.append(chunk.size).append(" beginbfchar\n")
            chunk.forEach { (code, v) ->
                sb.append('<').append("%04X".format(code)).append("> <")
                v.forEach { c -> sb.append("%04X".format(c.code)) }
                sb.append(">\n")
            }
            sb.append("endbfchar\n")
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return sb.toString()
    }
}
