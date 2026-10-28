package com.smartdocs.editor.core.openall

import com.smartdocs.editor.core.convert6.CfbReader
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** WOFF 1.0 → plain sfnt (TrueType / OpenType) so the platform can load it. Pure. */
object Woff {
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    fun toSfnt(w: ByteArray, maxBytes: Int = 64 shl 20): ByteArray {
        require(w.size >= 44 && String(w, 0, 4, Charsets.ISO_8859_1) == "wOFF") { "not WOFF" }
        val flavor = u32(w, 4)
        val numTables = u16(w, 12)
        require(numTables in 1..200 && 44 + numTables * 20 <= w.size) { "bad table count" }
        class T(val tag: Long, val off: Int, val comp: Int, val orig: Int, val sum: Long)
        val tables = (0 until numTables).map { i -> val o = 44 + i * 20; T(u32(w, o), u32(w, o + 4).toInt(), u32(w, o + 8).toInt(), u32(w, o + 12).toInt(), u32(w, o + 16)) }
        require(tables.sumOf { it.orig.toLong() } <= maxBytes) { "too big" }
        val out = ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        fun w32(v: Long) { w16((v shr 16).toInt() and 0xFFFF); w16(v.toInt() and 0xFFFF) }
        var entrySel = 0; while ((1 shl (entrySel + 1)) <= numTables) entrySel++
        val searchRange = (1 shl entrySel) * 16
        w32(flavor); w16(numTables); w16(searchRange); w16(entrySel); w16(numTables * 16 - searchRange)
        var offset = 12 + numTables * 16
        val data = tables.map { t ->
            require(t.off >= 0 && t.comp >= 0 && t.off + t.comp <= w.size) { "bad table" }
            if (t.comp < t.orig) {
                val inf = Inflater(); inf.setInput(w, t.off, t.comp)
                val buf = ByteArray(t.orig); var n = 0
                while (n < t.orig && !inf.finished()) { val r = inf.inflate(buf, n, t.orig - n); if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += r }
                inf.end(); buf
            } else w.copyOfRange(t.off, t.off + t.orig)
        }
        tables.forEachIndexed { i, t -> w32(t.tag); w32(t.sum); w32(offset.toLong()); w32(t.orig.toLong()); offset += (data[i].size + 3) and 3.inv() }
        data.forEach { d -> out.write(d); repeat(((d.size + 3) and 3.inv()) - d.size) { out.write(0) } }
        return out.toByteArray()
    }
}

/** sfnt `name` table: family / full name for the font preview. Pure. */
object SfntNames {
    private fun u16(b: ByteArray, o: Int) = if (o + 2 <= b.size) ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF) else 0
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    /** nameId → value (1 family, 2 subfamily, 4 full name, 5 version, 0 copyright). Windows Unicode entries preferred. */
    fun read(f: ByteArray): Map<Int, String> = runCatching {
        var base = 0
        if (String(f, 0, 4, Charsets.ISO_8859_1) == "ttcf") base = u32(f, 12).toInt()
        val n = u16(f, base + 4)
        var nameOff = -1
        for (i in 0 until n) { val o = base + 12 + i * 16; if (String(f, o, 4, Charsets.ISO_8859_1) == "name") { nameOff = u32(f, o + 8).toInt(); break } }
        if (nameOff < 0) return emptyMap()
        val count = u16(f, nameOff + 2); val strOff = nameOff + u16(f, nameOff + 4)
        val out = HashMap<Int, String>()
        for (i in 0 until count) {
            val r = nameOff + 6 + i * 12
            val platform = u16(f, r); val id = u16(f, r + 6); val len = u16(f, r + 8); val off = u16(f, r + 10)
            if (strOff + off + len > f.size) continue
            val s = if (platform == 3 || platform == 0) String(f, strOff + off, len, Charsets.UTF_16BE) else String(f, strOff + off, len, Charsets.ISO_8859_1)
            if (platform == 3 || !out.containsKey(id)) out[id] = s
        }
        out
    }.getOrDefault(emptyMap())
}

/**
 * PowerPoint 97-2003 (.ppt) slide texts: TextCharsAtom / TextBytesAtom records of the "PowerPoint Document" stream
 * grouped by SlideListWithText. Read-only, no layout. Pure.
 */
object PptText {
    private fun u16(b: ByteArray, o: Int) = CfbReader.le16(b, o)
    private fun u32(b: ByteArray, o: Int) = CfbReader.le32(b, o)

    fun slides(bytes: ByteArray): List<Pair<String, List<String>>> {
        val cfb = CfbReader(bytes)
        if (cfb.stream("EncryptedSummary") != null) throw com.smartdocs.editor.core.convert6.LegacyEncrypted()
        val s = cfb.stream("PowerPoint Document") ?: throw IllegalArgumentException("no PowerPoint Document stream")
        // pass 1: SlideListWithText (classic writers); pass 2: text boxes inside each Slide container (newer writers)
        for (pass in 1..2) {
            val slides = ArrayList<MutableList<String>>()
            var cur: MutableList<String>? = null
            fun walk(start: Int, end: Int, depth: Int, inList: Boolean) {
                var o = start
                while (o + 8 <= end) {
                    val verInst = u16(s, o); val type = u16(s, o + 2); val len = u32(s, o + 4)
                    val body = o + 8
                    if (len < 0 || body + len > end) break
                    val container = (verInst and 0x0F) == 0x0F
                    val listHere = inList || type == 0x0FF0
                    if (pass == 1 && type == 0x03F3 && inList) cur = ArrayList<String>().also { slides += it }   // SlidePersistAtom
                    if (pass == 2 && type == 0x03EE) cur = ArrayList<String>().also { slides += it }               // Slide container
                    val take = if (pass == 1) inList else !inList
                    if (take && type == 0x0FA0) cur?.add(String(s, body, len and 1.inv(), Charsets.UTF_16LE))   // TextCharsAtom
                    if (take && type == 0x0FA8) cur?.add(String(s, body, len, TextDecode.WIN1252))              // TextBytesAtom
                    if (container && depth < 16) walk(body, body + len, depth + 1, listHere)
                    o = body + len
                }
            }
            walk(0, s.size, 0, false)
            val out = slides.filter { it.isNotEmpty() }.map { ts ->
                val lines = ts.flatMap { it.replace('\u000B', '\n').split('\r', '\n') }.map { it.trim() }.filter { it.isNotEmpty() }
                (lines.firstOrNull() ?: "") to lines.drop(1)
            }.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }
}
