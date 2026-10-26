package com.smartdocs.editor.core.convert6

/** How extracted images are named. */
enum class NameMode { PAGE_INDEX, RESOURCE, CAPTION }

/** A text line near an image, top-left page coordinates (points). */
data class TextLine(val text: String, val x0: Float, val top: Float, val x1: Float, val bottom: Float)

/** File naming of «extract all images»: sanitising (Arabic kept), caption lookup and de-duplication. Pure. */
object ImageNaming {

    private val illegal = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** Bidi controls, zero-width marks and other format characters that make names unreadable / unsafe. */
    private fun isFormat(c: Char): Boolean = Character.getType(c) == Character.FORMAT.toInt() || c.code < 0x20 || c.code == 0x7F

    /**
     * Safe file base name: illegal / control / bidi-format characters removed, Arabic diacritics kept,
     * whitespace collapsed, trimmed of dots and spaces, at most [max] characters (cut at a word boundary when possible).
     */
    fun sanitize(raw: String, max: Int = 80): String {
        val sb = StringBuilder()
        var space = false
        raw.forEach { c ->
            when {
                c in illegal -> { if (!space && sb.isNotEmpty()) { sb.append(' '); space = true } }
                isFormat(c) -> {}
                c.isWhitespace() -> { if (!space && sb.isNotEmpty()) { sb.append(' '); space = true } }
                else -> { sb.append(c); space = false }
            }
        }
        var s = sb.toString().trim().trim('.', ' ', '-', '_')
        if (s.length > max) {
            val cut = s.lastIndexOf(' ', max)
            s = (if (cut >= max / 2) s.substring(0, cut) else s.substring(0, max)).trim().trim('.', ' ', '-', '_')
        }
        // reserved device names on Windows / FAT targets
        if (s.uppercase() in setOf("CON", "PRN", "AUX", "NUL") || Regex("""^(COM|LPT)\d$""", RegexOption.IGNORE_CASE).matches(s)) s = "_$s"
        return s
    }

    private val captionStart = Regex("""^\s*(الشكل|شكل|صورة|الصورة|رسم|الرسم|جدول|الجدول|مخطط|المخطط|Figure|Fig\.?|Image|Picture|Table|Chart|Photo|Plate|Illustration)\b""", RegexOption.IGNORE_CASE)

    fun looksLikeCaption(s: String) = captionStart.containsMatchIn(s)

    /**
     * The caption of an image at ([x0],[top])–([x1],[bottom]): a line that starts like a caption («الشكل 3: …», "Figure 2")
     * within [far] points below/above wins; otherwise the nearest line just below (within [near]), then just above.
     * Lines must overlap the image horizontally.
     */
    fun caption(lines: List<TextLine>, x0: Float, top: Float, x1: Float, bottom: Float, near: Float = 36f, far: Float = 90f): String? {
        val w = (x1 - x0).coerceAtLeast(1f)
        fun overlaps(l: TextLine): Boolean {
            val ov = minOf(x1, l.x1) - maxOf(x0, l.x0)
            val lw = (l.x1 - l.x0).coerceAtLeast(1f)
            return ov >= 0.3f * minOf(w, lw)
        }
        fun gapBelow(l: TextLine) = l.top - bottom
        fun gapAbove(l: TextLine) = top - l.bottom
        val cands = lines.filter { it.text.isNotBlank() && overlaps(it) }
        val captions = cands.filter { looksLikeCaption(it.text) && (gapBelow(it) in -2f..far || gapAbove(it) in -2f..far) }
            .minByOrNull { minOf(gapBelow(it).let { g -> if (g >= -2f) g else Float.MAX_VALUE }, gapAbove(it).let { g -> if (g >= -2f) g else Float.MAX_VALUE }) }
        if (captions != null) return captions.text.trim()
        cands.filter { gapBelow(it) in -2f..near }.minByOrNull { gapBelow(it) }?.let { return it.text.trim() }
        cands.filter { gapAbove(it) in -2f..near }.minByOrNull { gapAbove(it) }?.let { return it.text.trim() }
        return null
    }

    /** Default name: `<doc>_p<page>_<n>` (page 1-based, n = index on that page, 1-based). */
    fun pageIndexName(doc: String, page: Int, n: Int) = sanitize(doc, 60).ifEmpty { "image" } + "_p" + page + "_" + n

    /** Base name (no extension) for [mode]; falls back to the page/index name when the source has nothing usable. */
    fun baseName(mode: NameMode, doc: String, page: Int, n: Int, resource: String?, caption: String?): String {
        val fallback = pageIndexName(doc, page, n)
        return when (mode) {
            NameMode.PAGE_INDEX -> fallback
            NameMode.RESOURCE -> resource?.let { sanitize(it) }?.ifEmpty { null } ?: fallback
            NameMode.CAPTION -> caption?.let { sanitize(it) }?.ifEmpty { null } ?: fallback
        }
    }

    /** "name.ext", "name (2).ext", "name (3).ext"… — unique case-insensitively, in order. */
    fun dedupe(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { n ->
            val base = n.substringBeforeLast('.', n); val ext = if (n.contains('.')) "." + n.substringAfterLast('.') else ""
            var cand = n; var k = 2
            while (!used.add(cand.lowercase())) { cand = "$base ($k)$ext"; k++ }
            cand
        }
    }
}
