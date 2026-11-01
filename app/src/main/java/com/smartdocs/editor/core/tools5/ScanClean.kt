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
    /** Skin tone (YCbCr box rule, also for darker skin). */
    internal fun skin(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return y > 40 && cb in 77.0..127.0 && cr in 135.0..173.0 && r > b && r > g
    }

    /**
     * Scanner essentials: fingers holding a book open — skin-coloured areas that touch the left, right or bottom edge
     * of the page, inside the outer [band] of it, are painted with the paper colour. Returns the pixels changed. Pure.
     */
    fun removeFingers(px: IntArray, w: Int, h: Int, band: Float = 0.16f): Int {
        if (w < 16 || h < 16) return 0
        val bw = (w * band).toInt().coerceAtLeast(2); val bh = (h * band).toInt().coerceAtLeast(2)
        fun inBand(x: Int, y: Int) = x < bw || x >= w - bw || y >= h - bh
        val hit = BooleanArray(w * h); val stack = IntArray(w * h); var sp = 0
        fun push(i: Int) { if (!hit[i] && skin(px[i])) { val x = i % w; val y = i / w; if (inBand(x, y)) { hit[i] = true; stack[sp++] = i } } }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        for (x in 0 until w) push((h - 1) * w + x)
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        var n = 0; for (v in hit) if (v) n++
        // too small to be a finger (a skin-toned print), or more than the band (a photo of a person): leave it
        if (n < w * h / 2000 || n > w * h / 6) return 0
        // paper colour: the light, non-skin pixels of the band
        val samples = ArrayList<Int>()
        var i = 0
        while (i < px.size) { val x = i % w; val y = i / w; if (inBand(x, y) && !hit[i]) samples += px[i]; i += 37 }
        val paper = samples.sortedBy { c -> ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) }.let { if (it.isEmpty()) -0x1 else it[it.size * 4 / 5] }
        // a little around the finger too (its shadow and soft edge)
        val grow = (minOf(w, h) / 150).coerceAtLeast(1)
        val out = hit.copyOf()
        for (y in 0 until h) for (x in 0 until w) if (hit[y * w + x]) for (dy in -grow..grow) for (dx in -grow..grow) {
            val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) out[yy * w + xx] = true
        }
        var changed = 0
        for (k in out.indices) if (out[k]) { px[k] = paper; changed++ }
        return changed
    }

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
                val crop = e.crop?.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }.orEmpty()
                append("$g|$f|${e.filter.name}|${e.brightness}|${e.contrast}|${e.rotation}|${e.despeckle}|$erase|${e.flatten}|$crop\n")
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
                p.getOrNull(6)?.toBooleanStrictOrNull() ?: false, erase, p.getOrNull(8)?.toBooleanStrictOrNull() ?: false,
                p.getOrNull(9)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 8 }?.toFloatArray())
            byGroup.getOrPut(g) { ArrayList() } += p[1] to e
        }
        return name to byGroup.values.toList()
    }

    /** Keep [groups] (pictures + edits) as a project; the oldest projects beyond [MAX] are removed. */
    fun save(ctx: Context, name: String, groups: List<List<Pair<File, PageEdit>>>, replacing: Project? = null): File {
        val dir = File(root(ctx), System.currentTimeMillis().toString()).apply { mkdirs() }
        try {
            val named = groups.mapIndexed { g, pages -> pages.mapIndexed { i, (f, e) -> val n = "p${g}_$i.${f.extension.ifBlank { "jpg" }}"; f.copyTo(File(dir, n), true); n to e } }
            File(dir, "manifest.txt").writeText(encode(name, named))
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
        // the reopened project is replaced only once the new copy is complete, and before pruning (so no other project goes)
        replacing?.takeIf { it.dir != dir }?.let { delete(it) }
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
