package com.smartdocs.editor.core.convert6

/**
 * fix/files-index: Word 97-2003 (.doc) with basic formatting — headings (built-in styles Heading 1-9), bold and
 * italic runs (character FKPs), page breaks, inline pictures (PICF in the Data stream) and floating pictures
 * (FSPA → OfficeArt shape → BStore BLIP). Bitmap pictures only (JPEG / PNG / DIB / TIFF); metafiles are skipped.
 * Falls back to [DocReader.text] when the formatting tables cannot be read. Pure.
 */
object LegacyDocFlow {
    private fun le16(b: ByteArray, o: Int) = if (o + 2 <= b.size && o >= 0) CfbReader.le16(b, o) else 0
    private fun le32(b: ByteArray, o: Int) = if (o + 4 <= b.size && o >= 0) CfbReader.le32(b, o) else 0

    /** Character properties of a run of file positions [fcStart, fcEnd). */
    class Chp(val fcStart: Int, val fcEnd: Int, val bold: Boolean, val italic: Boolean, val special: Boolean, val picLocation: Int)

    /** Paragraph style of a run of file positions. */
    class Pap(val fcStart: Int, val fcEnd: Int, val istd: Int)

    /** Size in bytes of a sprm (code + operand) starting at [o] of [g]. */
    fun sprmSize(g: ByteArray, o: Int): Int {
        val sprm = le16(g, o)
        return 2 + when (sprm ushr 13) {
            0, 1 -> 1
            2, 4, 5 -> 2
            3 -> 4
            7 -> 3
            else -> if (sprm == 0xD608 || sprm == 0xC615) le16(g, o + 2) + 1 else (if (o + 2 < g.size) (g[o + 2].toInt() and 0xFF) + 1 else 1)
        }
    }

    /** Bold / italic / special / picture location of a character grpprl. Toggle operands (0x81) count as on. */
    fun chp(fcStart: Int, fcEnd: Int, g: ByteArray, from: Int, to: Int): Chp {
        var bold = false; var italic = false; var special = false; var pic = -1
        var o = from
        while (o + 2 <= to) {
            val sprm = le16(g, o)
            val op = o + 2
            fun b() = if (op < g.size) g[op].toInt() and 0xFF else 0
            when (sprm) {
                0x0835 -> bold = b() == 1 || b() == 0x81
                0x0836 -> italic = b() == 1 || b() == 0x81
                0x0855 -> special = b() != 0
                0x6A03 -> pic = le32(g, op)
            }
            o += sprmSize(g, o).coerceAtLeast(2)
        }
        return Chp(fcStart, fcEnd, bold, italic, special, pic)
    }

    /** Runs of a PlcBte (CHPX or PAPX) table: (n+1) FCs, then n page numbers; each page is a 512-byte FKP. */
    private fun fkpPages(table: ByteArray, fc: Int, lcb: Int): List<Int> {
        if (lcb < 8 || fc < 0 || fc + lcb > table.size) return emptyList()
        val n = (lcb - 4) / 8
        return (0 until n).map { i -> le32(table, fc + (n + 1) * 4 + i * 4) and 0x3FFFFF }
    }

    fun chpRuns(wd: ByteArray, table: ByteArray): List<Chp> {
        val out = ArrayList<Chp>()
        for (pn in fkpPages(table, le32(wd, 0xFA), le32(wd, 0xFE))) {
            val base = pn * 512
            if (base + 512 > wd.size) continue
            val crun = wd[base + 511].toInt() and 0xFF
            for (i in 0 until crun) {
                val s = le32(wd, base + i * 4); val e = le32(wd, base + (i + 1) * 4)
                val off = (wd[base + (crun + 1) * 4 + i].toInt() and 0xFF) * 2
                if (off == 0) { out += Chp(s, e, false, false, false, -1); continue }
                val cb = wd[base + off].toInt() and 0xFF
                out += chp(s, e, wd, base + off + 1, minOf(base + off + 1 + cb, base + 511))
            }
        }
        return out.sortedBy { it.fcStart }
    }

    fun papRuns(wd: ByteArray, table: ByteArray): List<Pap> {
        val out = ArrayList<Pap>()
        for (pn in fkpPages(table, le32(wd, 0x102), le32(wd, 0x106))) {
            val base = pn * 512
            if (base + 512 > wd.size) continue
            val crun = wd[base + 511].toInt() and 0xFF
            for (i in 0 until crun) {
                val s = le32(wd, base + i * 4); val e = le32(wd, base + (i + 1) * 4)
                val off = (wd[base + (crun + 1) * 4 + i * 13].toInt() and 0xFF) * 2
                if (off == 0) { out += Pap(s, e, 0); continue }
                val cb = wd[base + off].toInt() and 0xFF
                val start = if (cb == 0) base + off + 2 else base + off + 1
                out += Pap(s, e, le16(wd, start))
            }
        }
        return out.sortedBy { it.fcStart }
    }

    private fun <T> find(runs: List<T>, fc: Int, start: (T) -> Int, end: (T) -> Int): T? {
        var lo = 0; var hi = runs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = runs[mid]
            when { fc < start(r) -> hi = mid - 1; fc >= end(r) -> lo = mid + 1; else -> return r }
        }
        return null
    }

    // ---- OfficeArt pictures

    private class Rec(val type: Int, val inst: Int, val container: Boolean, val start: Int, val body: Int, val len: Int)

    private fun recs(b: ByteArray, from: Int, to: Int): List<Rec> {
        val out = ArrayList<Rec>(); var o = from
        while (o + 8 <= to) {
            val vi = le16(b, o); val type = le16(b, o + 2); val len = le32(b, o + 4)
            if (len < 0 || o + 8 + len > to) break
            out += Rec(type, vi ushr 4, (vi and 0x0F) == 0x0F, o, o + 8, len)
            o += 8 + len
        }
        return out
    }

    private fun walk(b: ByteArray, from: Int, to: Int, depth: Int, f: (Rec) -> Unit) {
        if (depth > 10) return
        for (r in recs(b, from, to)) { f(r); if (r.container) walk(b, r.body, r.body + r.len, depth + 1, f) }
    }

    /** Picture of an FBSE: embedded BLIP, else the BLIP at foDelay in [delay] (the WordDocument stream). */
    private fun fbse(b: ByteArray, r: Rec, delay: ByteArray?): ByteArray? {
        if (r.len < 36) return null
        val cbName = b[r.body + 33].toInt() and 0xFF
        val foDelay = le32(b, r.body + 28)
        val embedded = r.body + 36 + cbName
        return if (r.len > 36 + cbName + 8) PptReader.blip(b, embedded)?.bytes
        else if (delay != null && foDelay in 0..(delay.size - 8)) PptReader.blip(delay, foDelay)?.bytes else null
    }

    /** The first bitmap of the PICFAndOfficeArtData at [off] of the Data stream (inline picture). */
    fun inlinePicture(data: ByteArray, off: Int): ByteArray? {
        if (off < 0 || off + 0x44 > data.size) return null
        val lcb = le32(data, off); val cbHeader = le16(data, off + 4)
        if (lcb <= cbHeader || off + lcb > data.size || cbHeader < 0x44) return null
        var p = off + cbHeader
        if (le16(data, off + 6) == 0x66) p += 1 + (data[p].toInt() and 0xFF)        // MM_SHAPEFILE: picture name
        var found: ByteArray? = null
        walk(data, p, off + lcb, 0) { r ->
            if (found != null) return@walk
            found = when {
                r.type == 0xF007 -> fbse(data, r, null)
                r.type in 0xF018..0xF117 && !r.container -> PptReader.blip(data, r.start)?.bytes
                else -> null
            }
        }
        return found
    }

    /** Floating pictures: main-document CP of the anchor (0x08 character) → picture bytes. */
    fun floatingPictures(wd: ByteArray, table: ByteArray): Map<Int, ByteArray> {
        val spaFc = le32(wd, 0x1DA); val spaLcb = le32(wd, 0x1DE)
        val dggFc = le32(wd, 0x22A); val dggLcb = le32(wd, 0x22E)
        if (spaLcb < 30 || dggLcb <= 0 || spaFc + spaLcb > table.size || dggFc + dggLcb > table.size) return emptyMap()
        val n = (spaLcb - 4) / 30
        val spidAt = HashMap<Int, Int>()
        for (i in 0 until n) spidAt[le32(table, spaFc + (n + 1) * 4 + i * 26)] = le32(table, spaFc + i * 4)
        // BStore pictures (1-based), then each shape's spid → pib
        val pics = ArrayList<ByteArray?>()
        val pibOf = HashMap<Int, Int>()
        walk(table, dggFc, dggFc + dggLcb, 0) { r ->
            when (r.type) {
                0xF007 -> pics += fbse(table, r, wd)
                0xF004 -> {
                    var spid = -1; var pib = -1
                    for (k in recs(table, r.body, r.body + r.len)) {
                        if (k.type == 0xF00A && k.len >= 4) spid = le32(table, k.body)
                        if (k.type == 0xF00B) for (j in 0 until k.inst) {
                            val o = k.body + j * 6
                            if (o + 6 > k.body + k.len) break
                            if ((le16(table, o) and 0x3FFF) == 0x0104) pib = le32(table, o + 2)
                        }
                    }
                    if (spid >= 0 && pib > 0) pibOf[spid] = pib
                }
            }
        }
        val out = HashMap<Int, ByteArray>()
        spidAt.forEach { (spid, cp) -> pibOf[spid]?.let { pics.getOrNull(it - 1) }?.let { out[cp] = it } }
        return out
    }

    // ---- text with formatting

    fun read(bytes: ByteArray, title: String): FlowDoc = runCatching { formatted(bytes, title) }.getOrNull()
        ?: FlowDoc.fromText(DocReader.text(bytes), title)

    private fun formatted(bytes: ByteArray, title: String): FlowDoc? {
        val cfb = CfbReader(bytes)
        val wd = cfb.stream("WordDocument") ?: return null
        val flags = le16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw LegacyEncrypted()
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: return null
        val data = cfb.stream("Data")
        val ccpText = le32(wd, 0x4C)
        val fcClx = le32(wd, 0x1A2); val lcbClx = le32(wd, 0x1A6)
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.size || ccpText <= 0) return null
        // characters and their file positions (piece table)
        val sb = StringBuilder(); val fcs = ArrayList<Int>()
        var p = fcClx
        while (p < fcClx + lcbClx && table[p].toInt() == 1) p += 3 + le16(table, p + 1)
        if (p >= table.size || table[p].toInt() != 2) return null
        val lcb = le32(table, p + 1); val base = p + 5; val n = (lcb - 4) / 12
        for (i in 0 until n) {
            val cpStart = le32(table, base + i * 4); val cpEnd = le32(table, base + (i + 1) * 4)
            if (cpStart >= ccpText) break
            val fcRaw = le32(table, base + (n + 1) * 4 + i * 8 + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val len = minOf(cpEnd, ccpText) - cpStart
            if (len <= 0 || sb.length > 5_000_000) continue
            if (compressed) {
                val off = (fcRaw and 0x3FFFFFFF) / 2
                if (off + len > wd.size) continue
                sb.append(String(wd, off, len, charset("windows-1252"))); for (k in 0 until len) fcs += off + k
            } else {
                if (fcRaw + len * 2 > wd.size) continue
                sb.append(String(wd, fcRaw, len * 2, Charsets.UTF_16LE)); for (k in 0 until len) fcs += fcRaw + k * 2
            }
        }
        val chps = chpRuns(wd, table); val paps = papRuns(wd, table)
        if (chps.isEmpty()) return null
        val floating = runCatching { floatingPictures(wd, table) }.getOrDefault(emptyMap())
        val blocks = ArrayList<FBlock>()
        val runs = ArrayList<FRun>(); val cur = StringBuilder(); var bold = false; var italic = false
        fun flushRun() { if (cur.isNotEmpty()) { runs += FRun(cur.toString(), bold, italic); cur.setLength(0) } }
        fun endPara(istd: Int) {
            flushRun()
            val merged = runs.toList().let { rs -> if (rs.isNotEmpty() && rs.last().text.endsWith(" | ")) rs.dropLast(1) + rs.last().copy(text = rs.last().text.removeSuffix(" | ")) else rs }
            val plain = Text6.plain(merged)
            if (plain.isNotBlank()) blocks += if (istd in 1..9) FBlock.Heading(minOf(istd, 6), merged, Text6.rtlDominant(plain)) else FBlock.Para(merged, Text6.rtlDominant(plain))
            else if (blocks.lastOrNull() !is FBlock.Para || (blocks.last() as FBlock.Para).runs.isNotEmpty()) blocks += FBlock.Para(emptyList(), false)
            runs.clear()
        }
        fun picture(b: ByteArray?) {
            if (b == null || b.isEmpty()) return
            flushRun()
            if (runs.isNotEmpty()) endPara(0)           // the text before the picture stays its own paragraph
            blocks += FBlock.Image(b, Images6.sniffExt(b) ?: "png")
        }
        var fieldDepth = 0; val inCode = ArrayList<Boolean>()
        for (i in sb.indices) {
            val ch = sb[i]; val fc = fcs[i]
            when (ch.code) {
                0x13 -> { fieldDepth++; inCode += true; continue }
                0x14 -> { if (inCode.isNotEmpty()) inCode[inCode.size - 1] = false; continue }
                0x15 -> { if (fieldDepth > 0) { fieldDepth--; inCode.removeAt(inCode.size - 1) }; continue }
            }
            if (inCode.any { it }) continue
            val c = find(chps, fc, { it.fcStart }, { it.fcEnd })
            when (ch.code) {
                0x0D -> endPara(find(paps, fc, { it.fcStart }, { it.fcEnd })?.istd ?: 0)
                0x0B -> endPara(find(paps, fc, { it.fcStart }, { it.fcEnd })?.istd ?: 0)
                0x0C -> { endPara(0); blocks += FBlock.PageBreak }
                0x01 -> if (c != null && c.picLocation >= 0 && data != null) picture(inlinePicture(data, c.picLocation))
                0x08 -> picture(floating[i])
                else -> {
                    val t = when (ch.code) {
                        0x07 -> " | "; 0x09 -> "\t"; 0x1E -> "-"; 0xA0 -> " "
                        0x1F, 0x02, 0x03, 0x04, 0x05 -> null
                        else -> if (ch.code >= 0x20) ch.toString() else null
                    } ?: continue
                    val b = c?.bold ?: false; val ital = c?.italic ?: false
                    if (b != bold || ital != italic) { flushRun(); bold = b; italic = ital }
                    cur.append(t)
                }
            }
        }
        endPara(0)
        while (blocks.lastOrNull().let { it is FBlock.Para && it.runs.isEmpty() }) blocks.removeAt(blocks.size - 1)
        return FlowDoc(title, blocks)
    }
}
