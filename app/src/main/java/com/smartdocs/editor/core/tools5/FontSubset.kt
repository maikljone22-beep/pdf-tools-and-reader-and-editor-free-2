package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Foxit "Subset all embedded fonts" for embedded TrueType programs (/FontFile2 of simple TrueType and CIDFontType2
 * fonts). The outlines of glyphs that no page uses are emptied; glyph ids stay the same, so the font's encoding,
 * /W widths and CIDToGIDMap keep working (safe for Arabic and CJK). Fonts that annotations, form fields, patterns
 * or Type 3 glyphs use are left whole, because their text can change later.
 */
object FontSubset {

    /** Subsets every safe embedded TrueType font of [doc]. Returns (fonts subset, bytes saved). */
    fun run(doc: PDDocument): Pair<Int, Long> {
        val unsafe = HashSet<COSDictionary>()
        val used = HashMap<COSDictionary, MutableSet<Int>>()          // font dict → glyph ids
        val fonts = HashMap<COSDictionary, PDFont>()
        var failed = false
        fun markUnsafe(res: COSDictionary?, depth: Int = 0) {
            if (res == null || depth > 10) return
            (res.getDictionaryObject(COSName.FONT) as? COSDictionary)?.let { fd -> fd.keySet().forEach { k -> (fd.getDictionaryObject(k) as? COSDictionary)?.let { unsafe += it } } }
            (res.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let { xo -> xo.keySet().forEach { k ->
                (xo.getDictionaryObject(k) as? COSStream)?.let { s -> markUnsafe(s.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, depth + 1) } } }
        }
        doc.pages.forEach { p ->
            runCatching { p.annotations }.getOrNull()?.forEach { a ->
                (a.cosObject.getDictionaryObject(COSName.AP) as? COSDictionary)?.values?.forEach { v ->
                    when (val x = deref(v)) {
                        is COSStream -> markUnsafe(x.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)
                        is COSDictionary -> x.values.forEach { s -> (deref(s) as? COSStream)?.let { markUnsafe(it.getDictionaryObject(COSName.RESOURCES) as? COSDictionary) } }
                        else -> {}
                    }
                }
            }
            (p.resources?.cosObject?.getDictionaryObject(COSName.PATTERN) as? COSDictionary)?.let { pd -> pd.keySet().forEach { k ->
                (pd.getDictionaryObject(k) as? COSStream)?.let { markUnsafe(it.getDictionaryObject(COSName.RESOURCES) as? COSDictionary) } } }
        }
        (doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary)?.let { markUnsafe(it.getDictionaryObject(COSName.DR) as? COSDictionary) }

        fun scan(tokens: List<Any>, res: PDResources, depth: Int) {
            val stack = ArrayDeque<PDFont?>()
            var font: PDFont? = null
            val operands = ArrayList<Any>()
            for (t in tokens) {
                if (t !is Operator) { operands += t; continue }
                when (t.name) {
                    "q" -> stack.addLast(font)
                    "Q" -> font = stack.removeLastOrNull() ?: font
                    "Tf" -> (operands.getOrNull(0) as? COSName)?.let { nm ->
                        font = runCatching { res.getFont(nm) }.getOrNull()
                        if (font is com.tom_roush.pdfbox.pdmodel.font.PDType3Font) font?.let { f -> (f.cosObject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { markUnsafe(it) } }
                    }
                    "Tj", "'", "\"", "TJ" -> {
                        val f = font
                        if (f != null) {
                            val strings = when (t.name) {
                                "TJ" -> (operands.getOrNull(0) as? COSArray)?.let { a -> (0 until a.size()).mapNotNull { a.getObject(it) as? COSString } }.orEmpty()
                                else -> listOfNotNull(operands.lastOrNull() as? COSString)
                            }
                            val gids = used.getOrPut(f.cosObject) { HashSet() }
                            fonts[f.cosObject] = f
                            strings.forEach { s -> gids += glyphs(f, s.bytes) }
                        }
                    }
                    "Do" -> (operands.getOrNull(0) as? COSName)?.let { nm ->
                        val x = runCatching { res.getXObject(nm) }.getOrNull()
                        if (x is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject && depth < 10) {
                            val p = PDFStreamParser(x); p.parse()
                            scan(p.tokens, x.resources ?: res, depth + 1)
                        }
                    }
                }
                operands.clear()
            }
        }
        doc.pages.forEach { p ->
            runCatching { val parser = PDFStreamParser(p); parser.parse(); scan(parser.tokens, p.resources ?: PDResources(), 0) }.onFailure { failed = true }
        }
        if (failed) return 0 to 0L   // a page we could not read may use any glyph: leave the fonts whole
        var count = 0; var saved = 0L
        for ((dict, gids) in used) {
            if (dict in unsafe) continue
            val f = fonts[dict] ?: continue
            val desc = when (f) {
                is PDTrueTypeFont -> f.fontDescriptor?.cosObject
                is PDType0Font -> (f.descendantFont.cosObject.getDictionaryObject(COSName.FONT_DESC) as? COSDictionary)
                else -> null
            } ?: continue
            val ff = desc.getDictionaryObject(COSName.FONT_FILE2) as? COSStream ?: continue
            val data = runCatching { ff.createInputStream().use { it.readBytes() } }.getOrNull() ?: continue
            val stripped = runCatching { TtfStrip.keep(data, gids + 0) }.getOrNull() ?: continue
            if (stripped.size >= data.size) continue
            val oldLen = runCatching { ff.length }.getOrDefault(data.size.toLong())
            ff.removeItem(COSName.getPDFName("Length1"))
            ff.createOutputStream(COSName.FLATE_DECODE).use { it.write(stripped) }
            ff.setInt(COSName.getPDFName("Length1"), stripped.size)
            saved += (oldLen - ff.length).coerceAtLeast(0); count++
        }
        return count to saved
    }

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.`object` else b

    /** Glyph ids that [bytes] shows in [font]. */
    private fun glyphs(font: PDFont, bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        val inp = ByteArrayInputStream(bytes)
        var guard = 0
        while (inp.available() > 0 && guard++ < 100_000) {
            val code = runCatching { font.readCode(inp) }.getOrElse { return out }
            val gid = runCatching {
                when (font) {
                    is PDTrueTypeFont -> font.codeToGID(code)
                    is PDType0Font -> font.descendantFont.codeToGID(code)
                    else -> -1
                }
            }.getOrDefault(-1)
            if (gid >= 0) out += gid
        }
        return out
    }
}

/** TrueType glyph stripping: empties the glyf entries not in a set (plus the components of kept composites). */
object TtfStrip {
    private class Table(val tag: String, val data: ByteArray)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    private fun tables(font: ByteArray): List<Table> {
        val n = u16(font, 4)
        return (0 until n).map { i ->
            val r = 12 + i * 16
            val tag = String(font, r, 4, Charsets.ISO_8859_1)
            val off = u32(font, r + 8).toInt(); val len = u32(font, r + 12).toInt()
            Table(tag, font.copyOfRange(off, off + len))
        }
    }

    /** The font with only [keep] glyph outlines (and their composite parts). Throws on fonts it cannot parse. */
    fun keep(font: ByteArray, keep: Set<Int>): ByteArray {
        val ts = tables(font)
        val head = ts.first { it.tag == "head" }.data
        val maxp = ts.first { it.tag == "maxp" }.data
        val loca = ts.first { it.tag == "loca" }.data
        val glyf = ts.first { it.tag == "glyf" }.data
        val longLoca = u16(head, 50) == 1
        val count = u16(maxp, 4)
        val offs = IntArray(count + 1) { i -> if (longLoca) u32(loca, i * 4).toInt() else u16(loca, i * 2) * 2 }
        // composites: keep their components too
        val wanted = keep.filter { it in 0 until count }.toMutableSet()
        val queue = ArrayDeque(wanted)
        while (queue.isNotEmpty()) {
            val g = queue.removeFirst()
            val s = offs[g]; val e = offs[g + 1]
            if (e - s < 10) continue
            if ((glyf[s].toInt() shl 8 or (glyf[s + 1].toInt() and 0xFF)).toShort() >= 0) continue
            var p = s + 10
            while (true) {
                val flags = u16(glyf, p); val comp = u16(glyf, p + 2)
                if (wanted.add(comp)) queue += comp
                p += 4 + (if (flags and 0x0001 != 0) 4 else 2)
                p += when { flags and 0x0008 != 0 -> 2; flags and 0x0040 != 0 -> 4; flags and 0x0080 != 0 -> 8; else -> 0 }
                if (flags and 0x0020 == 0) break
            }
        }
        val ng = ByteArrayOutputStream()
        val newOffs = IntArray(count + 1)
        for (g in 0 until count) {
            newOffs[g] = ng.size()
            if (g in wanted) {
                ng.write(glyf, offs[g], offs[g + 1] - offs[g])
                while (ng.size() % (if (longLoca) 4 else 2) != 0) ng.write(0)
            }
        }
        newOffs[count] = ng.size()
        val nl = ByteBuffer.allocate((count + 1) * (if (longLoca) 4 else 2))
        newOffs.forEach { if (longLoca) nl.putInt(it) else nl.putShort((it / 2).toShort()) }
        val replaced = ts.map { t -> when (t.tag) { "glyf" -> Table("glyf", ng.toByteArray()); "loca" -> Table("loca", nl.array()); else -> t } }
        return assemble(font, replaced)
    }

    private fun checksum(b: ByteArray): Long {
        var sum = 0L; var i = 0
        while (i < b.size) {
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (if (i + k < b.size) (b[i + k].toLong() and 0xFF) else 0L)
            sum = (sum + v) and 0xFFFFFFFFL; i += 4
        }
        return sum
    }

    private fun assemble(orig: ByteArray, ts: List<Table>): ByteArray {
        val n = ts.size
        val headerLen = 12 + 16 * n
        val out = ByteArrayOutputStream()
        out.write(orig, 0, 12)                                   // sfnt version, numTables, search fields
        var off = headerLen
        val dir = ByteBuffer.allocate(16 * n)
        val sorted = ts.sortedBy { it.tag }
        val head = sorted.indexOfFirst { it.tag == "head" }
        val datas = sorted.map { t -> if (t.tag == "head") t.data.copyOf().also { h -> for (k in 8 until 12) h[k] = 0 } else t.data }
        sorted.forEachIndexed { i, t ->
            dir.put(t.tag.toByteArray(Charsets.ISO_8859_1)); dir.putInt(checksum(datas[i]).toInt()); dir.putInt(off); dir.putInt(datas[i].size)
            off += (datas[i].size + 3) / 4 * 4
        }
        out.write(dir.array())
        datas.forEach { d -> out.write(d); repeat((4 - d.size % 4) % 4) { out.write(0) } }
        val bytes = out.toByteArray()
        if (head >= 0) {
            val adj = (0xB1B0AFBAL - checksum(bytes)) and 0xFFFFFFFFL
            val ho = ByteBuffer.wrap(bytes, 12 + head * 16 + 8, 4).int
            ByteBuffer.wrap(bytes, ho + 8, 4).putInt(adj.toInt())
        }
        return bytes
    }
}
