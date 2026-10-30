package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.E5Io
import com.smartdocs.editor.core.edit5.ImposeOrder
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/** Foxit 5 scan options beyond the scan types. Pure parts are JVM-tested. */
object ScanExtras {

    /** Page size in points for [paper]; FIT = the picture at 200 dpi. Landscape pictures get a landscape page. */
    fun pageRect(paper: ScanPaper, picW: Int, picH: Int): PDRectangle {
        val (w, h) = when (paper) {
            ScanPaper.FIT -> return PDRectangle(picW * 72f / 200f, picH * 72f / 200f)
            ScanPaper.A4 -> 595.28f to 841.89f
            ScanPaper.LETTER -> 612f to 792f
            ScanPaper.LEGAL -> 612f to 1008f
            ScanPaper.A3 -> 841.89f to 1190.55f
            ScanPaper.A5 -> 419.53f to 595.28f
            ScanPaper.B5 -> 498.9f to 708.66f
            ScanPaper.CARD -> 243.78f to 153.07f          // ISO 7810 ID-1 (85.6 × 54 mm), landscape
            ScanPaper.TABLOID -> 792f to 1224f
            ScanPaper.EXECUTIVE -> 522f to 756f
            else -> isoSize(paper) ?: (595.28f to 841.89f)
        }
        val wide = picW > picH
        return if (wide == (w > h)) PDRectangle(w, h) else PDRectangle(h, w)
    }

    private fun mm(w: Float, h: Float) = (w * 72f / 25.4f) to (h * 72f / 25.4f)

    /** ISO 216 A / B, ISO 269 C and JIS P 0138 B sizes in points (portrait), from the series' sheet 0 halved n times. */
    fun isoSize(paper: ScanPaper): Pair<Float, Float>? {
        val name = paper.name
        val (series, n) = when {
            name.startsWith("JIS_B") -> "JIS_B" to name.removePrefix("JIS_B").toIntOrNull()
            name.length in 2..3 && name[0] in "ABC" && name.substring(1).all { it.isDigit() } -> name.substring(0, 1) to name.substring(1).toIntOrNull()
            else -> return null
        }
        if (n == null || n !in 0..10) return null
        var (w, h) = when (series) { "A" -> 841f to 1189f; "B" -> 1000f to 1414f; "C" -> 917f to 1297f; else -> 1030f to 1456f }
        repeat(n) { val nh = w; val nw = kotlin.math.floor(h / 2f); w = nw; h = nh }
        return mm(w, h)
    }

    /** "Save multiple files": [n] pages per file (n <= 0 keeps one file). Pure. */
    fun <T> chunks(pages: List<T>, n: Int): List<List<T>> = if (n <= 0 || pages.size <= n) listOf(pages) else pages.chunked(n)

    /**
     * Manual duplex: [fronts] then [backs] (scanned as a second pass, usually last page first) into reading order.
     * Pure.
     */
    fun <T> duplex(fronts: List<T>, backs: List<T>, backsReversed: Boolean = true): List<T> =
        ImposeOrder.interleave(fronts.size, backs.size, backsReversed).map { (f, i) -> if (f == 0) fronts[i] else backs[i] }

    /** Title / author / subject written into the scan PDF ("Edit document information" after scanning). */
    fun setInfo(pdf: File, title: String, author: String, subject: String = "") {
        if (title.isBlank() && author.isBlank() && subject.isBlank()) return
        E5Io.edit(pdf, pdf, null) { d ->
            val info = d.documentInformation
            if (title.isNotBlank()) info.title = title.trim()
            if (author.isNotBlank()) info.author = author.trim()
            if (subject.isNotBlank()) info.subject = subject.trim()
            info.creator = "SmartDocs"
            d.documentInformation = info
        }
    }
}

/** A user-saved scan preset (Foxit "Save as preset / manage presets"). */
data class ScanPreset(
    val name: String,
    val kind: ScanKind = ScanKind.DOCUMENT,
    val paper: ScanPaper = ScanPaper.FIT,
    val ocr: Boolean = true,
    val langs: String = "auto",
    val removeBlank: Boolean = false,
    val perFile: Int = 0,
    val author: String = "",
) {
    /** One line, `key=value` fields separated by `;` (values escaped). Pure. */
    fun encode(): String = listOf("name" to name, "kind" to kind.name, "paper" to paper.name, "ocr" to ocr.toString(), "langs" to langs,
        "blank" to removeBlank.toString(), "per" to perFile.toString(), "author" to author).joinToString(";") { (k, v) -> "$k=" + esc(v) }

    companion object {
        private fun esc(v: String) = v.replace("%", "%25").replace(";", "%3B").replace("=", "%3D").replace("\n", " ")
        private fun unesc(v: String) = v.replace("%3D", "=").replace("%3B", ";").replace("%25", "%")

        fun decode(line: String): ScanPreset? {
            val m = line.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to unesc(p.substring(it + 1)) } }.toMap()
            val name = m["name"]?.takeIf { it.isNotBlank() } ?: return null
            return ScanPreset(name,
                runCatching { ScanKind.valueOf(m["kind"]!!) }.getOrDefault(ScanKind.DOCUMENT),
                runCatching { ScanPaper.valueOf(m["paper"]!!) }.getOrDefault(ScanPaper.FIT),
                m["ocr"]?.toBooleanStrictOrNull() ?: true, m["langs"] ?: "auto",
                m["blank"]?.toBooleanStrictOrNull() ?: false, m["per"]?.toIntOrNull() ?: 0, m["author"].orEmpty())
        }

        fun decodeAll(text: String): List<ScanPreset> = text.lines().mapNotNull { if (it.isBlank()) null else decode(it) }
        fun encodeAll(list: List<ScanPreset>): String = list.joinToString("\n") { it.encode() }
    }
}
