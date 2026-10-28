package com.smartdocs.editor.core.convert6

/** Thrown for password-protected legacy files (no decryption on device). */
class LegacyEncrypted : Exception("encrypted")

/**
 * Word 97-2003 (.doc) text through the piece table: paragraphs, line / page breaks, field results kept,
 * table cells joined with " | ". No formatting, pictures or tables layout (read-only text). Pure.
 */
object DocReader {

    fun text(bytes: ByteArray): String {
        val cfb = CfbReader(bytes)
        val wd = cfb.stream("WordDocument") ?: throw IllegalArgumentException("no WordDocument stream")
        val flags = CfbReader.le16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw LegacyEncrypted()
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw IllegalArgumentException("no table stream")
        val ccpText = CfbReader.le32(wd, 0x4C)
        val fcClx = CfbReader.le32(wd, 0x1A2)
        val lcbClx = CfbReader.le32(wd, 0x1A6)
        val sb = StringBuilder()
        if (lcbClx > 0 && fcClx >= 0 && fcClx + lcbClx <= table.size) {
            var p = fcClx
            while (p < fcClx + lcbClx && table[p].toInt() == 1) { p += 3 + CfbReader.le16(table, p + 1) }
            require(p < table.size && table[p].toInt() == 2) { "no piece table" }
            val lcb = CfbReader.le32(table, p + 1)
            val base = p + 5
            val n = (lcb - 4) / 12
            for (i in 0 until n) {
                val cpStart = CfbReader.le32(table, base + i * 4)
                val cpEnd = CfbReader.le32(table, base + (i + 1) * 4)
                if (cpStart >= ccpText) break
                val pcd = base + (n + 1) * 4 + i * 8
                val fcRaw = CfbReader.le32(table, pcd + 2)
                val compressed = fcRaw and 0x40000000 != 0
                val len = minOf(cpEnd, ccpText) - cpStart
                if (len <= 0) continue
                if (compressed) {
                    val off = (fcRaw and 0x3FFFFFFF) / 2
                    if (off + len <= wd.size) sb.append(String(wd, off, len, charset("windows-1252")))
                } else {
                    val off = fcRaw
                    if (off + len * 2 <= wd.size) sb.append(String(wd, off, len * 2, Charsets.UTF_16LE))
                }
            }
        } else {
            // very old / fast-saved files without a piece table: text starts at fcMin
            val fcMin = CfbReader.le32(wd, 0x18)
            val fcMac = CfbReader.le32(wd, 0x1C)
            if (fcMin in 0 until fcMac && fcMac <= wd.size) sb.append(String(wd, fcMin, fcMac - fcMin, charset("windows-1252")))
        }
        return clean(sb.toString())
    }

    /** Word control characters → plain text (fields keep their result, objects dropped). */
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        var fieldDepth = 0; val inCode = ArrayList<Boolean>()
        for (c in raw) {
            when (c.code) {
                0x13 -> { fieldDepth++; inCode += true }
                0x14 -> { if (inCode.isNotEmpty()) inCode[inCode.size - 1] = false }
                0x15 -> { if (fieldDepth > 0) { fieldDepth--; inCode.removeAt(inCode.size - 1) } }
                else -> {
                    if (inCode.any { it }) continue
                    when (c.code) {
                        0x0D -> out.append('\n')
                        0x0B -> out.append('\n')
                        0x0C -> out.append('\u000C')
                        0x07 -> out.append(" | ")
                        0x09 -> out.append('\t')
                        0x1E -> out.append('-')
                        0x1F, 0x01, 0x02, 0x08, 0x05, 0x04, 0x03 -> {}
                        0xA0 -> out.append(' ')
                        else -> if (c.code >= 0x20) out.append(c)
                    }
                }
            }
        }
        return out.toString().replace(Regex("""( \| )+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}

/**
 * Excel 97-2003 (.xls, BIFF8) cell values: shared strings, numbers (incl. RK / MULRK), inline strings,
 * formula cached results, booleans; fix/files-index: number formats (FORMAT / XF records, dates in the workbook's
 * date system) applied through [XlsNumFmt]. Pure.
 */
object XlsReader {

    private class Rec(val type: Int, val data: ByteArray, val offset: Int)

    private fun records(s: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var p = 0
        while (p + 4 <= s.size) {
            val t = CfbReader.le16(s, p); val len = CfbReader.le16(s, p + 2)
            if (p + 4 + len > s.size) break
            out += Rec(t, s.copyOfRange(p + 4, p + 4 + len), p)
            p += 4 + len
        }
        return out
    }

    private fun num(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString().let { if (it.contains('E')) java.math.BigDecimal(d).stripTrailingZeros().toPlainString() else it }

    private fun rk(v: Int): Double {
        val d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) d / 100.0 else d
    }

    private fun double(b: ByteArray, o: Int): Double {
        var bits = 0L
        for (i in 7 downTo 0) bits = (bits shl 8) or (b[o + i].toLong() and 0xFF)
        return java.lang.Double.longBitsToDouble(bits)
    }

    /** SST across CONTINUE records: a string split at a record boundary restarts with a flags byte. */
    private class Seg(val parts: List<ByteArray>) {
        var part = 0; var pos = 0
        fun cur() = parts[part]
        fun left() = cur().size - pos
        fun next() { part++; pos = 0 }
        fun u8(): Int { if (left() < 1) next(); return cur()[pos++].toInt() and 0xFF }
        fun u16(): Int { if (left() < 2) next(); val v = CfbReader.le16(cur(), pos); pos += 2; return v }
        fun u32(): Int { if (left() < 4) next(); val v = CfbReader.le32(cur(), pos); pos += 4; return v }
        fun skip(n0: Int) { var n = n0; while (n > 0) { if (left() == 0) next(); val k = minOf(n, left()); pos += k; n -= k } }
        fun chars(cch: Int, high0: Boolean): String {
            val sb = StringBuilder(cch); var high = high0; var left = cch
            while (left > 0) {
                if (left() == 0) { next(); high = (u8() and 1) != 0 }
                val avail = if (high) left() / 2 else left()
                val k = minOf(left, avail)
                if (k <= 0) { next(); high = (u8() and 1) != 0; continue }
                if (high) { sb.append(String(cur(), pos, k * 2, Charsets.UTF_16LE)); pos += k * 2 } else { for (i in 0 until k) sb.append((cur()[pos + i].toInt() and 0xFF).toChar()); pos += k }
                left -= k
            }
            return sb.toString()
        }
    }

    private fun sst(parts: List<ByteArray>): List<String> {
        val s = Seg(parts)
        s.u32(); val unique = s.u32()
        val out = ArrayList<String>(unique.coerceIn(0, 1_000_000))
        try {
            repeat(unique) {
                val cch = s.u16(); val fl = s.u8()
                val runs = if (fl and 8 != 0) s.u16() else 0
                val ext = if (fl and 4 != 0) s.u32() else 0
                out += s.chars(cch, fl and 1 != 0)
                s.skip(runs * 4 + ext)
            }
        } catch (_: IndexOutOfBoundsException) { }
        return out
    }

    private fun xlString(b: ByteArray, o: Int, cchBytes: Int): String {
        val cch = if (cchBytes == 1) b[o].toInt() and 0xFF else CfbReader.le16(b, o)
        val fl = b[o + cchBytes].toInt()
        val start = o + cchBytes + 1
        return if (fl and 1 != 0) String(b, start, minOf(cch * 2, b.size - start), Charsets.UTF_16LE) else String(b, start, minOf(cch, b.size - start), Charsets.ISO_8859_1)
    }

    /** Sheets: name → rows (dense, trailing empties trimmed). */
    fun read(bytes: ByteArray, maxRows: Int = 65536, maxCols: Int = 256): List<Pair<String, List<List<String>>>> {   // the whole BIFF8 grid (audit C5)
        val cfb = CfbReader(bytes)
        val wb = cfb.stream("Workbook") ?: cfb.stream("Book") ?: throw IllegalArgumentException("no Workbook stream")
        val recs = records(wb)
        if (recs.any { it.type == 0x002F }) throw LegacyEncrypted()
        val sheetNames = HashMap<Int, String>()
        // fix/files-index: number formats — FORMAT (id → code), XF (index → format id), DATEMODE (1904 system)
        val formats = HashMap<Int, String>(); val xfFormat = ArrayList<Int>(); var date1904 = false
        val order = ArrayList<Int>()
        var sstParts: ArrayList<ByteArray>? = null
        val sstAll = ArrayList<ByteArray>()
        recs.forEach { r ->
            when (r.type) {
                0x0085 -> { val pos = CfbReader.le32(r.data, 0); if ((r.data[5].toInt() and 0xFF) == 0) { sheetNames[pos] = xlString(r.data, 6, 1); order += pos } }
                0x041E -> runCatching { formats[CfbReader.le16(r.data, 0)] = xlString(r.data, 2, 2) }
                0x00E0 -> if (r.data.size >= 4) xfFormat += CfbReader.le16(r.data, 2)
                0x0022 -> if (r.data.size >= 2) date1904 = CfbReader.le16(r.data, 0) == 1
                0x00FC -> { sstParts = ArrayList<ByteArray>().also { it += r.data }; }
                0x003C -> sstParts?.add(r.data)
                else -> sstParts?.let { sstAll.addAll(it); sstParts = null }
            }
        }
        sstParts?.let { sstAll.addAll(it) }
        val strings = if (sstAll.isEmpty()) emptyList() else sst(sstAll)
        fun fmt(d: Double, ixfe: Int): String {
            val id = xfFormat.getOrNull(ixfe) ?: return num(d)
            val code = formats[id] ?: XlsNumFmt.BUILT_IN[id] ?: return num(d)
            return runCatching { XlsNumFmt.format(d, code, date1904) }.getOrElse { num(d) }
        }
        val result = ArrayList<Pair<String, List<List<String>>>>()
        var cur: HashMap<Long, String>? = null; var name = ""
        var lastFormula: Long = -1
        fun finish() {
            val cells = cur ?: return
            val maxR = cells.keys.maxOfOrNull { (it shr 16).toInt() } ?: -1
            val maxC = cells.keys.maxOfOrNull { (it and 0xFFFF).toInt() } ?: -1
            val rows = (0..maxR).map { r -> (0..maxC).map { c -> cells[(r.toLong() shl 16) or c.toLong()] ?: "" } }
            result += name to rows
            cur = null
        }
        recs.forEach { r ->
            val d = r.data
            fun put(row: Int, col: Int, v: String) { if (row < maxRows && col < maxCols) cur?.put((row.toLong() shl 16) or col.toLong(), v) }
            when (r.type) {
                0x0809 -> { val dt = CfbReader.le16(d, 2); if (dt == 0x0010 && sheetNames.containsKey(r.offset)) { finish(); cur = HashMap(); name = sheetNames[r.offset]!! } }
                0x000A -> finish()
                0x00FD -> put(CfbReader.le16(d, 0), CfbReader.le16(d, 2), strings.getOrElse(CfbReader.le32(d, 6)) { "" })
                0x0203 -> put(CfbReader.le16(d, 0), CfbReader.le16(d, 2), fmt(double(d, 6), CfbReader.le16(d, 4)))
                0x027E -> put(CfbReader.le16(d, 0), CfbReader.le16(d, 2), fmt(rk(CfbReader.le32(d, 6)), CfbReader.le16(d, 4)))
                0x00BD -> {
                    val row = CfbReader.le16(d, 0); val first = CfbReader.le16(d, 2)
                    val n = (d.size - 6) / 6
                    for (k in 0 until n) put(row, first + k, fmt(rk(CfbReader.le32(d, 4 + k * 6 + 2)), CfbReader.le16(d, 4 + k * 6)))
                }
                0x0204 -> put(CfbReader.le16(d, 0), CfbReader.le16(d, 2), xlString(d, 6, 2))
                0x00D6 -> put(CfbReader.le16(d, 0), CfbReader.le16(d, 2), xlString(d, 6, 2))
                0x0205 -> { val v = d[6].toInt() and 0xFF; val err = d[7].toInt() != 0; put(CfbReader.le16(d, 0), CfbReader.le16(d, 2), if (err) "#ERR" else if (v != 0) "TRUE" else "FALSE") }
                0x0006 -> {
                    val row = CfbReader.le16(d, 0); val col = CfbReader.le16(d, 2)
                    if ((d[12].toInt() and 0xFF) == 0xFF && (d[13].toInt() and 0xFF) == 0xFF) {
                        when (d[6].toInt()) {
                            0 -> lastFormula = (row.toLong() shl 16) or col.toLong()
                            1 -> put(row, col, if (d[8].toInt() != 0) "TRUE" else "FALSE")
                            2 -> put(row, col, "#ERR")
                        }
                    } else put(row, col, fmt(double(d, 6), CfbReader.le16(d, 4)))
                }
                0x0207 -> if (lastFormula >= 0) { cur?.put(lastFormula, xlString(d, 0, 2)); lastFormula = -1 }
            }
        }
        finish()
        // keep sheet order of the workbook
        return result.map { (n, rows) -> n to rows.dropLastWhile { r -> r.all { it.isEmpty() } } }
    }
}
