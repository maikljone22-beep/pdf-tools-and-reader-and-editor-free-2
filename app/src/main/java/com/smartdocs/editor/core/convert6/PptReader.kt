package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.ImageData
import com.smartdocs.editor.core.office.Inline
import com.smartdocs.editor.core.office.PresentationModel
import com.smartdocs.editor.core.office.RunStyle
import com.smartdocs.editor.core.office.SlideModel
import com.smartdocs.editor.core.office.SlideParagraph
import com.smartdocs.editor.core.office.SlideShape

/**
 * PowerPoint 97-2003 (.ppt, [MS-PPT]) → the same [PresentationModel] the PPTX path prints: slides in presentation order
 * (persist directory), each shape of a slide's drawing (OfficeArt) placed by its client anchor, with its text (own text
 * atoms or the slide's SlideListWithText entry) and pictures (BLIPs of the BStore / "Pictures" stream: JPEG, PNG, DIB,
 * TIFF). Metafile pictures (EMF / WMF / PICT), masters, themes and text styling are not read. Pure.
 */
object PptReader {
    private fun u16(b: ByteArray, o: Int) = CfbReader.le16(b, o)
    private fun u32(b: ByteArray, o: Int) = CfbReader.le32(b, o)

    /** 576 master units per inch → EMU (914 400 per inch). */
    private const val EMU_PER_MASTER = 1587.5

    private class Rec(val ver: Int, val inst: Int, val type: Int, val body: Int, val len: Int)

    private fun recs(s: ByteArray, start: Int, end: Int): List<Rec> {
        val out = ArrayList<Rec>(); var o = start
        while (o + 8 <= end) {
            val vi = u16(s, o); val type = u16(s, o + 2); val len = u32(s, o + 4)
            if (len < 0 || o + 8 + len > end) break
            out += Rec(vi and 0x0F, vi ushr 4, type, o + 8, len)
            o += 8 + len
        }
        return out
    }

    private fun kids(s: ByteArray, r: Rec) = if (r.ver == 0x0F) recs(s, r.body, r.body + r.len) else emptyList()

    private fun find(s: ByteArray, r: Rec, type: Int, depth: Int = 0): Rec? {
        if (depth > 12) return null
        for (k in kids(s, r)) { if (k.type == type) return k; find(s, k, type, depth + 1)?.let { return it } }
        return null
    }

    private fun findAll(s: ByteArray, r: Rec, type: Int, out: MutableList<Rec>, depth: Int = 0) {
        if (depth > 12) return
        for (k in kids(s, r)) { if (k.type == type) out += k; findAll(s, k, type, out, depth + 1) }
    }

    private fun text(s: ByteArray, r: Rec): String? = when (r.type) {
        0x0FA0 -> String(s, r.body, r.len and 1.inv(), Charsets.UTF_16LE)            // TextCharsAtom
        0x0FA8 -> String(s, r.body, r.len, TextDecodeCp1252)                          // TextBytesAtom
        else -> null
    }

    private val TextDecodeCp1252 = charset("windows-1252")

    /** Persist id → offset in the "PowerPoint Document" stream, newest edit first ([MS-PPT] 2.3.4 / 2.3.6). */
    fun persistMap(s: ByteArray, currentUser: ByteArray?): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        // CurrentUserAtom: header (8) + size (4) + headerToken (4) + offsetToCurrentEdit (4)
        var edit = currentUser?.takeIf { it.size >= 20 }?.let { u32(it, 16) } ?: return out
        var guard = 0
        while (edit in 0..(s.size - 8) && guard++ < 64) {
            if (u16(s, edit + 2) != 0x0FF5) break                                       // UserEditAtom
            val body = edit + 8
            val lastEdit = u32(s, body + 8); val dirOff = u32(s, body + 12)
            if (dirOff in 0..(s.size - 8) && u16(s, dirOff + 2) == 0x1772) {          // PersistDirectoryAtom
                val len = u32(s, dirOff + 4); var o = dirOff + 8; val end = minOf(s.size, dirOff + 8 + len)
                while (o + 4 <= end) {
                    val e = u32(s, o); val id0 = e and 0xFFFFF; val n = (e ushr 20) and 0xFFF; o += 4
                    for (k in 0 until n) { if (o + 4 > end) break; out.putIfAbsent(id0 + k, u32(s, o)); o += 4 }
                }
            }
            if (lastEdit <= 0 || lastEdit == edit) break
            edit = lastEdit
        }
        return out
    }

    /** One picture of the BStore (1-based index → bytes / mime), from the Pictures stream or embedded. */
    private fun pictures(s: ByteArray, pics: ByteArray?, top: List<Rec>): List<ImageData?> {
        val doc = top.firstOrNull { it.type == 0x03E8 } ?: return emptyList()                  // DocumentContainer
        val bstore = find(s, doc, 0xF001) ?: return emptyList()                                 // OfficeArtBStoreContainer
        return kids(s, bstore).map { fbse ->
            if (fbse.type != 0xF007 || fbse.len < 36) return@map null
            val foDelay = u32(s, fbse.body + 28)
            val cbName = s[fbse.body + 33].toInt() and 0xFF
            when {
                pics != null && foDelay in 0..(pics.size - 8) -> blip(pics, foDelay)
                fbse.len > 36 + cbName + 8 -> blip(s, fbse.body + 36 + cbName)
                else -> null
            }
        }
    }

    /** A BLIP record at [o]: bitmap types only (JPEG, PNG, DIB, TIFF), raw data after the UID(s) and tag byte. */
    fun blip(b: ByteArray, o: Int): ImageData? {
        if (o + 8 > b.size) return null
        val vi = u16(b, o); val type = u16(b, o + 2); val len = u32(b, o + 4)
        val inst = vi ushr 4
        if (len <= 0 || o + 8 + len > b.size) return null
        val (twoUids, mime) = when (type) {
            0xF01D, 0xF02A -> (inst == 0x46B || inst == 0x6E3) to "image/jpeg"
            0xF01E -> (inst == 0x6E1) to "image/png"
            0xF01F -> (inst == 0x7A9) to "image/bmp"
            0xF029 -> (inst == 0x6E5) to "image/tiff"
            else -> return null                                                                   // EMF / WMF / PICT
        }
        val head = 16 + (if (twoUids) 16 else 0) + 1
        if (len <= head) return null
        var data = b.copyOfRange(o + 8 + head, o + 8 + len)
        if (type == 0xF01F) data = dibToBmp(data) ?: return null
        return ImageData(data, mime)
    }

    /** A DIB (BITMAPINFOHEADER + pixels) as a .bmp file (14-byte file header added). */
    fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val hdr = u32(dib, 0); val bits = u16(dib, 14); val clr = u32(dib, 32)
        val palette = if (bits <= 8) (if (clr > 0) clr else 1 shl bits) * 4 else 0
        val off = 14 + hdr + palette
        val total = 14 + dib.size
        val f = ByteArray(14)
        f[0] = 'B'.code.toByte(); f[1] = 'M'.code.toByte()
        for (k in 0 until 4) { f[2 + k] = (total ushr (8 * k)).toByte(); f[10 + k] = (off ushr (8 * k)).toByte() }
        return f + dib
    }

    /** OfficeArtFOPT property [id] (14 bits), or null. */
    private fun prop(s: ByteArray, fopt: Rec, id: Int): Int? {
        val n = fopt.inst
        for (k in 0 until n) {
            val o = fopt.body + k * 6
            if (o + 6 > fopt.body + fopt.len) break
            if ((u16(s, o) and 0x3FFF) == id) return u32(s, o + 2)
        }
        return null
    }

    /** Client anchor: [top, left, right, bottom] in master units (SmallRectStruct INT16 or RectStruct INT32 form). */
    fun anchor(b: ByteArray, body: Int, len: Int): IntArray? = when (len) {
        8 -> intArrayOf(s16(b, body), s16(b, body + 2), s16(b, body + 4), s16(b, body + 6))
        16 -> intArrayOf(u32(b, body), u32(b, body + 4), u32(b, body + 8), u32(b, body + 12))
        else -> null
    }
    private fun anchor(s: ByteArray, r: Rec): IntArray? = anchor(s, r.body, r.len)

    private fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()

    /** A text of a slide with its text type (title, body …) and its StyleTextPropAtom styles. */
    private class PText(val type: Int, val text: String, var styles: PptStyle.Styles? = null)

    private fun paragraphs(t: PText): List<SlideParagraph> = PptStyle.paragraphs(t.text, t.styles)

    fun read(bytes: ByteArray): PresentationModel {
        val cfb = CfbReader(bytes)
        if (cfb.stream("EncryptedSummary") != null) throw LegacyEncrypted()
        val s = cfb.stream("PowerPoint Document") ?: throw IllegalArgumentException("no PowerPoint Document stream")
        val top = recs(s, 0, s.size)
        val persist = persistMap(s, cfb.stream("Current User"))
        val pics = pictures(s, cfb.stream("Pictures"), top)
        val doc = top.firstOrNull { it.type == 0x03E8 }
        // slide size (DocumentAtom: slideSize x, y in master units)
        val docAtom = doc?.let { d -> kids(s, d).firstOrNull { it.type == 0x03E9 } }
        val sw = ((docAtom?.let { u32(s, it.body) } ?: 5760) * EMU_PER_MASTER).toLong()
        val sh = ((docAtom?.let { u32(s, it.body + 4) } ?: 4320) * EMU_PER_MASTER).toLong()
        // slides in order: SlideListWithText (instance 0) → SlidePersistAtom → persist id → Slide container
        val slwt = doc?.let { d -> kids(s, d).firstOrNull { it.type == 0x0FF0 && it.inst == 0 } }
        class Entry(val persistId: Int, val texts: MutableList<PText>)
        val entries = ArrayList<Entry>()
        slwt?.let { l ->
            var cur: Entry? = null; var type = 1
            for (r in kids(s, l)) when (r.type) {
                0x03F3 -> cur = Entry(u32(s, r.body), ArrayList()).also { entries += it }
                0x0F9F -> type = u32(s, r.body)                                                  // TextHeaderAtom
                0x0FA0, 0x0FA8 -> text(s, r)?.let { t -> cur?.texts?.add(PText(type, t)) }
                0x0FA1 -> cur?.texts?.lastOrNull()?.let { pt -> pt.styles = PptStyle.parse(s, r.body, r.body + r.len, pt.text.length) }   // StyleTextPropAtom
            }
        }
        val slideRecs: List<Pair<Rec, Entry?>> = if (entries.isNotEmpty() && persist.isNotEmpty())
            entries.mapNotNull { e -> persist[e.persistId]?.let { off -> recs(s, off, s.size).firstOrNull()?.takeIf { it.type == 0x03EE }?.let { it to e } } }
        else emptyList()
        val slides = slideRecs.ifEmpty { top.flatMap { r -> if (r.type == 0x03EE) listOf(r to null) else emptyList() } }
        // masters: their own shapes (logos, decorations; not placeholders) go behind the slides that follow master objects
        val masterShapes = HashMap<Int, List<SlideShape>>()
        fun masterOf(slide: Rec): List<SlideShape> {
            val atom = kids(s, slide).firstOrNull { it.type == 0x03EF && it.len >= 22 } ?: return emptyList()     // SlideAtom
            val masterId = u32(s, atom.body + 12)
            val follow = (u16(s, atom.body + 20) and 1) != 0                                                       // fMasterObjects
            if (!follow || masterId == 0) return emptyList()
            return masterShapes.getOrPut(masterId) {
                val m = persist[masterId]?.let { off -> recs(s, off, s.size).firstOrNull() }?.takeIf { it.type == 0x03F8 || it.type == 0x03EE } ?: return@getOrPut emptyList()
                shapesOf(s, m, emptyList(), pics, skipPlaceholders = true, used = HashSet())
            }
        }
        return PresentationModel(slides.map { (slide, entry) -> slideModel(s, slide, entry?.texts ?: emptyList(), pics, sw, sh, masterOf(slide)) }
            .ifEmpty { listOf(SlideModel(sw, sh, emptyList())) })
    }

    private fun slideModel(s: ByteArray, slide: Rec, texts: List<PText>, pics: List<ImageData?>, sw: Long, sh: Long, master: List<SlideShape>): SlideModel {
        val usedTexts = HashSet<Int>()
        val shapes = ArrayList<SlideShape>(master)
        shapes += shapesOf(s, slide, texts, pics, skipPlaceholders = false, used = usedTexts)
        // texts of the slide that no shape referenced (placeholders inherited from the master): stacked in the slide
        val rest = texts.filterIndexed { i, _ -> i !in usedTexts }
        if (shapes.none { it is SlideShape.TextBox && it !in master } && rest.isNotEmpty()) {
            var y = sh / 12
            rest.forEach { t ->
                val title = t.type == 0 || t.type == 6
                val hh = if (title) sh / 6 else sh / 2
                shapes += SlideShape.TextBox(sw / 16, y, sw * 7 / 8, hh, paragraphs(t), defaultSizePt = if (title) 32f else 20f)
                y += hh
            }
        }
        return SlideModel(sw, sh, shapes)
    }

    /** The anchored shapes of a slide / master drawing; [skipPlaceholders]: leave out placeholder shapes (masters). */
    private fun shapesOf(s: ByteArray, slide: Rec, texts: List<PText>, pics: List<ImageData?>, skipPlaceholders: Boolean, used: MutableSet<Int>): List<SlideShape> {
        val shapes = ArrayList<SlideShape>()
        val spContainers = ArrayList<Rec>()
        find(s, slide, 0x040C)?.let { drawing -> findAll(s, drawing, 0xF004, spContainers) }   // PPDrawing → OfficeArtSpContainer
        for (sp in spContainers) {
            val ks = kids(s, sp)
            val a = ks.firstOrNull { it.type == 0xF010 }?.let { anchor(s, it) } ?: continue          // shapes without a client anchor: group / background
            val x = (a[1] * EMU_PER_MASTER).toLong(); val y = (a[0] * EMU_PER_MASTER).toLong()
            val w = ((a[2] - a[1]) * EMU_PER_MASTER).toLong(); val h = ((a[3] - a[0]) * EMU_PER_MASTER).toLong()
            if (w <= 0 || h <= 0) continue
            if (skipPlaceholders && ks.firstOrNull { it.type == 0xF011 }?.let { cd -> find(s, cd, 0x0BC3) != null } == true) continue   // OEPlaceholderAtom
            val fopt = ks.firstOrNull { it.type == 0xF00B }
            val pib = fopt?.let { prop(s, it, 0x0104) }
            if (pib != null && pib in 1..pics.size) { shapes += SlideShape.Picture(x, y, w, h, pics[pib - 1]); continue }
            val box = ks.firstOrNull { it.type == 0xF00D }                                          // ClientTextbox
            var t: PText? = null; var tType = 1
            box?.let { b ->
                for (r in kids(s, b)) when (r.type) {
                    0x0F9F -> tType = u32(s, r.body)
                    0x0FA0, 0x0FA8 -> text(s, r)?.let { t = PText(tType, it) }
                    0x0FA1 -> t?.let { pt -> pt.styles = PptStyle.parse(s, r.body, r.body + r.len, pt.text.length) }
                    0x0F9E -> { val idx = u32(s, r.body); texts.getOrNull(idx)?.let { pt -> t = pt; tType = pt.type; used += idx } }   // OutlineTextRefAtom
                }
            }
            val tx = t?.takeIf { it.text.isNotBlank() } ?: continue
            val size = when (tType) { 0, 6 -> 32f; 1, 5, 7, 8 -> 20f; else -> 18f }                // title / center title / body / other
            shapes += SlideShape.TextBox(x, y, w, h, paragraphs(tx), defaultSizePt = size)
        }
        return shapes
    }
}
