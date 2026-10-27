package com.smartdocs.editor.core.nve.shell

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.security.MessageDigest

/** Favourite tools (Nitro "Favorite Tools"): an ordered list of tool ids, at most [MAX]. Pure list operations. */
object Favorites {
    const val MAX = 8

    fun parse(raw: String?): List<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)
    fun format(list: List<String>): String = list.joinToString(",")

    /** Add [id] at the end (when there is room) or remove it. */
    fun toggle(list: List<String>, id: String): List<String> =
        if (id in list) list - id else if (list.size >= MAX) list else list + id

    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList(); m.add(to, m.removeAt(from)); return m
    }

    fun removeAll(): List<String> = emptyList()
}

/**
 * Reader toolbar customisation (SHL-3): order and visibility of the reader tabs and of the View-tab chips.
 * Ids are stable strings ("VIEW", "COMMENT"… for tabs; free ids for chips).
 */
object ToolbarModel {
    val DEFAULT_TABS = listOf("VIEW", "COMMENT", "CUSTOM", "EDIT", "FILL_SIGN", "SIGN", "TOOLS", "PAGES")

    /** The saved order completed with tabs it does not know (new tabs go to the end), unknown ids dropped. */
    fun order(saved: List<String>, all: List<String> = DEFAULT_TABS): List<String> =
        saved.filter { it in all }.distinct() + all.filter { it !in saved }

    /** Visible tabs in order; never empty (VIEW is kept when everything is hidden). */
    fun visible(order: List<String>, hidden: Set<String>): List<String> =
        order.filter { it !in hidden }.ifEmpty { listOf(order.firstOrNull() ?: "VIEW") }

    fun parse(raw: String?): List<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Auto-recovery copies (SHL-10): naming and retention, independent of Android. */
object RecoveryNames {
    /** Stable key of a document path (the recovery file name inside the recovery folder). */
    fun key(path: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
        return d.take(10).joinToString("") { "%02x".format(it) }
    }

    fun fileName(path: String): String = key(path) + ".pdf"

    /** Name shown / used when the recovered copy is opened: "<name> (Recovered).pdf". */
    fun recoveredName(original: String, suffix: String = "Recovered"): String {
        val base = original.substringBeforeLast('.', original)
        return "$base ($suffix).pdf"
    }

    /** Files of [dir] to delete: older than [maxAgeMs], or beyond the newest [keep]. */
    fun expired(files: List<Pair<String, Long>>, now: Long, maxAgeMs: Long = 7L * 24 * 3600 * 1000, keep: Int = 20): List<String> {
        val sorted = files.sortedByDescending { it.second }
        return sorted.filterIndexed { i, (_, t) -> i >= keep || now - t > maxAgeMs }.map { it.first }
    }

    /** Recovery intervals offered in settings, in minutes (0 = off). */
    val INTERVALS = listOf(0, 1, 5, 10, 15)
}

/** Automatic repair of damaged PDFs: PDFBox's lenient parser rebuilds a broken xref / trailer; the result is re-saved. */
object PdfRepair {
    class Result(val pages: Int, val out: File)

    fun repair(src: File, out: File, password: String? = null): Result {
        val doc = try {
            PDDocument.load(src, password ?: "", MemoryUsageSetting.setupTempFileOnly())
        } catch (e: Exception) {
            // last resort: cut everything after the last "%%EOF"-less garbage is not recoverable here; rethrow
            throw e
        }
        doc.use { d ->
            if (d.isEncrypted) d.isAllSecurityToBeRemoved = password == null
            val n = d.numberOfPages
            if (n == 0) throw IllegalStateException("no pages")
            val tmp = File(out.absoluteFile.parentFile, ".${out.name}.tmp")
            d.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return Result(n, out)
        }
    }

    /** "<name> (repaired).pdf" */
    fun repairedName(original: String): String = original.substringBeforeLast('.', original) + " (repaired).pdf"
}
