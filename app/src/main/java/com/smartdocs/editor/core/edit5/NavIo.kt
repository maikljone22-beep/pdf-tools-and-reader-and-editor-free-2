package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File

/**
 * Load / save for the navigation tools (bookmarks, links, destinations, document scripts). A signed file gets an
 * incremental update through [SignedSave] so its signatures stay valid: the block adds the pages it changed to the
 * set it gets; the catalog is always rewritten, and every new dictionary set into it (outline, /Names, /AA) is
 * written as a new object. Other files are saved in full, re-protected like the source ([E5Io.save]).
 */
object NavIo {
    fun <T> edit(src: File, out: File, password: String?, block: (PDDocument, MutableSet<PDPage>) -> T): T {
        val tmp = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.nav")
        try {
            val r = PdfOps.loadRaw(src, password).use { doc ->
                val signed = SignedSave.isSigned(doc)
                if (signed) SignedSave.requireAnnotationsAllowed(doc) else PdfOps.reprotect(doc, password)
                val pages = LinkedHashSet<PDPage>()
                val r = block(doc, pages)
                if (signed) SignedSave.saveIncremental(doc, pages, tmp) else doc.save(tmp)
                r
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return r
        } finally { if (tmp.exists()) tmp.delete() }
    }
}
