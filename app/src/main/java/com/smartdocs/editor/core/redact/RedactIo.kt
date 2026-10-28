package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * audit 5 R-10: redact / sanitize / protect outputs are written to a temp file in the same folder and then renamed,
 * so a crash or a full disk mid-save never leaves a truncated PDF under the final name (same as PdfOps.saveTo,
 * which is private to PdfOps).
 */
internal object RedactIo {
    fun saveAtomic(doc: PDDocument, out: File) {
        val dir = out.absoluteFile.parentFile
        val tmp = File(dir, ".${out.name}.${System.nanoTime()}.tmp")
        try {
            doc.save(tmp)
            if (!tmp.renameTo(out)) {
                // renameTo does not replace an existing file on every platform
                if (out.exists() && !out.delete()) { tmp.copyTo(out, overwrite = true) }
                else if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
            }
        } finally { if (tmp.exists()) tmp.delete() }
    }
}
