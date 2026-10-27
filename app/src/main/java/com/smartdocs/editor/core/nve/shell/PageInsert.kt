package com.smartdocs.editor.core.nve.shell

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Organize "Insert from several files / a folder" and "Insert into another file" (page copying with PDFBox). */
object PageInsert {
    /** All pages of [files], in order, into [out] (one temp file to insert in a single step). */
    fun merge(files: List<File>, out: File): Int {
        require(files.isNotEmpty())
        val m = PDFMergerUtility()
        files.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        return PDDocument.load(out).use { it.numberOfPages }
    }

    /**
     * Copy every page of [pages] into a copy of [target] before page index [at] (clamped; -1 or beyond = at the end),
     * written to [out]. Returns the page count of the result.
     */
    fun into(target: File, pages: File, at: Int, out: File, targetPassword: String? = null): Int {
        PDDocument.load(target, targetPassword ?: "").use { dst ->
            if (dst.isEncrypted) dst.isAllSecurityToBeRemoved = false
            PDDocument.load(pages).use { src ->
                val n0 = dst.numberOfPages
                val pos = if (at < 0 || at > n0) n0 else at
                // import at the end, then move the imported pages to [pos]
                val imported = src.pages.map { dst.importPage(it) }
                if (pos < n0) {
                    val tree = dst.pages
                    val anchor = tree.get(pos)
                    imported.forEach { p -> tree.remove(p) }
                    imported.forEach { p -> tree.insertBefore(p, anchor) }
                }
                // save while the source is still open: imported pages share its streams
                dst.save(out)
                return dst.numberOfPages
            }
        }
    }
}
