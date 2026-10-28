package com.smartdocs.editor.core.files

import java.io.File

/**
 * Output file names (pure, JVM-tested). Android file systems allow 255 bytes per name; an Arabic letter takes 2 bytes
 * in UTF-8 (some symbols 3-4), so a 140-letter Telegram title plus "_accessible (2).txt" failed with ENAMETOOLONG
 * (convert audit A-L3 / B32 / C44). Names are cut at a code-point boundary and never end in a broken surrogate pair.
 */
object FileNames {

    /** Bytes kept for the base name (room for a suffix such as "_images (12)" and the extension). */
    const val MAX_BASE_BYTES = 150

    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** [base] without characters that are illegal in file names, trimmed, cut to [maxBytes] UTF-8 bytes. */
    fun clean(base: String, maxBytes: Int = MAX_BASE_BYTES, fallback: String = "document"): String {
        val s = base.replace(illegal, "_").trim().trimEnd('.').trim()
        return cut(s, maxBytes).trim().ifBlank { fallback }
    }

    /** The longest prefix of [s] that fits [maxBytes] UTF-8 bytes, cut between code points. */
    fun cut(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    /**
     * A file in [dir] named after [base] that does not exist yet: "name.ext", then "name (2).ext", "name (3).ext" …
     * [ext] empty = a folder name.
     */
    fun unique(dir: File, base: String, ext: String): File {
        val clean = clean(base)
        val dot = if (ext.isEmpty()) "" else ".$ext"
        var f = File(dir, clean + dot); var n = 2
        while (f.exists()) { f = File(dir, "$clean ($n)$dot"); n++ }
        return f
    }
}
