package com.smartdocs.editor.core.tools5

import android.content.Context
import java.io.File

/** Old plan K8 / K12: eraser and despeckle on scanned pictures (ARGB pixel arrays). Pure, JVM-tested. */
object ScanClean {
    private fun dark(c: Int, t: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 < t
    }

    /**
     * Despeckle: groups of touching dark pixels (8-connected) of at most [maxSize] pixels are dust — they become
     * white; strokes and letters (larger groups) stay. Returns how many pixels were cleaned.
     */
    fun despeckle(px: IntArray, w: Int, h: Int, threshold: Int = 128, maxSize: Int = 4): Int {
        val isDark = BooleanArray(px.size) { dark(px[it], threshold) }
        val seen = BooleanArray(px.size)
        val queue = IntArray(px.size)
        var cleaned = 0
        for (start in px.indices) {
            if (!isDark[start] || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    if (isDark[j] && !seen[j]) { seen[j] = true; queue[tail++] = j }
                }
            }
            if (tail <= maxSize) { for (k in 0 until tail) px[queue[k]] = 0xFFFFFFFF.toInt(); cleaned += tail }
        }
        return cleaned
    }

    /** Eraser: paints the [rects] (fractions of the picture: left, top, right, bottom) white. */
    fun erase(px: IntArray, w: Int, h: Int, rects: List<FloatArray>) {
        for (r in rects) {
            if (r.size < 4) continue
            val x0 = (minOf(r[0], r[2]) * w).toInt().coerceIn(0, w); val x1 = (maxOf(r[0], r[2]) * w).toInt().coerceIn(0, w)
            val y0 = (minOf(r[1], r[3]) * h).toInt().coerceIn(0, h); val y1 = (maxOf(r[1], r[3]) * h).toInt().coerceIn(0, h)
            for (y in y0 until y1) java.util.Arrays.fill(px, y * w + x0, y * w + x1, 0xFFFFFFFF.toInt())
        }
    }
}

/**
 * Old plan K3: a scan kept for later editing — its original pictures and their edits (filter, rotation, eraser …)
 * in `files/scan_projects/<id>/`, with a text manifest. Reopening puts the pages back into the scan studio.
 */
object ScanProjects {
    data class Project(val dir: File, val name: String, val time: Long, val pages: Int)

    private const val MAX = 12

    fun root(ctx: Context) = File(ctx.filesDir, "scan_projects").apply { mkdirs() }

    /** One manifest line per page: group|file|filter|brightness|contrast|rotation|despeckle|erase. Pure. */
    fun encode(name: String, groups: List<List<Pair<String, PageEdit>>>): String = buildString {
        append("name=").append(name.replace("\n", " ")).append('\n')
        groups.forEachIndexed { g, pages ->
            pages.forEach { (f, e) ->
                val erase = e.erase.joinToString("/") { r -> r.joinToString(",") { "%.4f".format(java.util.Locale.US, it) } }
                append("$g|$f|${e.filter.name}|${e.brightness}|${e.contrast}|${e.rotation}|${e.despeckle}|$erase|${e.flatten}\n")
            }
        }
    }

    /** Pure inverse of [encode]: (name, groups of (file name, edit)). */
    fun decode(text: String): Pair<String, List<List<Pair<String, PageEdit>>>> {
        val lines = text.lines()
        val name = lines.firstOrNull { it.startsWith("name=") }?.removePrefix("name=").orEmpty()
        val byGroup = sortedMapOf<Int, MutableList<Pair<String, PageEdit>>>()
        lines.filter { it.isNotBlank() && !it.startsWith("name=") }.forEach { l ->
            val p = l.split('|')
            if (p.size < 6) return@forEach
            val g = p[0].toIntOrNull() ?: return@forEach
            val erase = p.getOrNull(7).orEmpty().split('/').filter { it.isNotBlank() }.mapNotNull { r -> r.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.toFloatArray() }
            val e = PageEdit(runCatching { ScanFilter.valueOf(p[2]) }.getOrDefault(ScanFilter.AUTO), p[3].toFloatOrNull() ?: 0f, p[4].toFloatOrNull() ?: 1f, p[5].toIntOrNull() ?: 0,
                p.getOrNull(6)?.toBooleanStrictOrNull() ?: false, erase, p.getOrNull(8)?.toBooleanStrictOrNull() ?: false)
            byGroup.getOrPut(g) { ArrayList() } += p[1] to e
        }
        return name to byGroup.values.toList()
    }

    /** Keep [groups] (pictures + edits) as a project; the oldest projects beyond [MAX] are removed. */
    fun save(ctx: Context, name: String, groups: List<List<Pair<File, PageEdit>>>): File {
        val dir = File(root(ctx), System.currentTimeMillis().toString()).apply { mkdirs() }
        val named = groups.mapIndexed { g, pages -> pages.mapIndexed { i, (f, e) -> val n = "p${g}_$i.${f.extension.ifBlank { "jpg" }}"; f.copyTo(File(dir, n), true); n to e } }
        File(dir, "manifest.txt").writeText(encode(name, named))
        list(ctx).drop(MAX).forEach { it.dir.deleteRecursively() }
        return dir
    }

    fun list(ctx: Context): List<Project> = (root(ctx).listFiles() ?: emptyArray()).filter { File(it, "manifest.txt").isFile }.mapNotNull { d ->
        runCatching { val (n, g) = decode(File(d, "manifest.txt").readText()); Project(d, n.ifBlank { d.name }, d.name.toLongOrNull() ?: d.lastModified(), g.sumOf { it.size }) }.getOrNull()
    }.sortedByDescending { it.time }

    fun load(p: Project): List<List<Pair<File, PageEdit>>> =
        decode(File(p.dir, "manifest.txt").readText()).second.map { g -> g.map { (f, e) -> File(p.dir, f) to e }.filter { it.first.isFile } }.filter { it.isNotEmpty() }

    fun delete(p: Project) { p.dir.deleteRecursively() }
}
