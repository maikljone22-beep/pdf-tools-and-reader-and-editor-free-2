package com.smartdocs.editor.core.convert6

/**
 * Minimal reader of OLE2 / Compound File Binary containers (Word 97-2003 .doc, Excel .xls, PowerPoint .ppt).
 * Read-only, whole file in memory; enough to fetch named streams. Pure JVM.
 */
class CfbReader(private val b: ByteArray) {

    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private val secShift: Int
    private val secSize: Int
    private val miniSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    val entries: List<Entry>
    private val miniStream: ByteArray

    init {
        require(b.size >= 512 && u32(0) == 0xE011CFD0.toInt() && u32(4) == 0xE11AB1A1.toInt()) { "not a compound file" }
        secShift = u16(0x1E)
        require(secShift in 7..16) { "bad sector size" }
        secSize = 1 shl secShift
        miniSize = 1 shl u16(0x20)
        miniCutoff = u32(0x38).toLong() and 0xFFFFFFFFL
        val numFat = u32(0x2C)
        val dirStart = u32(0x30)
        val miniFatStart = u32(0x3C)
        var difatSec = u32(0x44)
        // DIFAT: 109 entries in the header, then chained DIFAT sectors
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) { val s = u32(0x4C + i * 4); if (s >= 0) fatSectors += s }
        var guard = 0
        while (difatSec >= 0 && guard++ < 10_000) {
            val off = offset(difatSec); if (off + secSize > b.size) break
            for (i in 0 until secSize / 4 - 1) { val s = u32(off + i * 4); if (s >= 0) fatSectors += s }
            difatSec = u32(off + secSize - 4)
        }
        val per = secSize / 4
        val nFat = if (numFat in 1..fatSectors.size) numFat else fatSectors.size
        val fatList = IntArray(nFat * per)
        fatSectors.take(nFat).forEachIndexed { k, s ->
            val off = offset(s)
            for (i in 0 until per) fatList[k * per + i] = if (off + i * 4 + 4 <= b.size) u32(off + i * 4) else -2
        }
        fat = fatList
        // directory
        val dirBytes = chain(dirStart, Long.MAX_VALUE)
        val list = ArrayList<Entry>()
        var o = 0
        while (o + 128 <= dirBytes.size) {
            val nameLen = (dirBytes[o + 64].toInt() and 0xFF) or ((dirBytes[o + 65].toInt() and 0xFF) shl 8)
            val type = dirBytes[o + 66].toInt() and 0xFF
            val name = if (nameLen in 2..64) String(dirBytes, o, nameLen - 2, Charsets.UTF_16LE) else ""
            val start = le32(dirBytes, o + 116)
            val size = le32(dirBytes, o + 120).toLong() and 0xFFFFFFFFL
            list += Entry(name, type, start, size)
            o += 128
        }
        entries = list
        val root = list.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) chain(root.start, root.size) else ByteArray(0)
        miniFat = if (miniFatStart >= 0) chain(miniFatStart, Long.MAX_VALUE).let { mb -> IntArray(mb.size / 4) { le32(mb, it * 4) } } else IntArray(0)
    }

    private fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(o: Int) = le32(b, o)
    private fun offset(sec: Int) = (sec + 1) shl secShift

    private fun chain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start; var guard = 0
        val limit = if (size == Long.MAX_VALUE) Long.MAX_VALUE else size
        while (s >= 0 && s < fat.size && guard++ < 1_000_000 && out.size() < limit) {
            val off = offset(s)
            if (off >= b.size) break
            out.write(b, off, minOf(secSize, b.size - off))
            s = fat[s]
        }
        val r = out.toByteArray()
        return if (limit != Long.MAX_VALUE && r.size > limit) r.copyOf(limit.toInt()) else r
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start; var guard = 0
        while (s >= 0 && s < miniFat.size && guard++ < 1_000_000 && out.size() < size) {
            val off = s * miniSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSize, miniStream.size - off))
            s = miniFat[s]
        }
        val r = out.toByteArray()
        return if (r.size > size) r.copyOf(size.toInt()) else r
    }

    fun streamNames(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    /** Content of the first stream called [name] (case-insensitive), or null. */
    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, true) } ?: return null
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }

    companion object {
        fun le32(a: ByteArray, o: Int): Int =
            (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8) or ((a[o + 2].toInt() and 0xFF) shl 16) or ((a[o + 3].toInt() and 0xFF) shl 24)
        fun le16(a: ByteArray, o: Int): Int = (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
    }
}
