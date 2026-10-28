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
            moveInto(tmp, out)
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /**
     * Moves the finished [tmp] (same folder) to [out]. A rename where possible; otherwise the bytes are copied to a
     * second temp file next to [out] first, so [out] is never a half-written copy.
     */
    fun moveInto(tmp: File, out: File) {
        if (tmp.renameTo(out)) return
        // renameTo does not replace an existing file on every platform
        if (out.exists() && out.delete() && tmp.renameTo(out)) return
        val copy = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.cp")
        try {
            tmp.copyTo(copy, overwrite = true)
            if (!copy.renameTo(out)) { out.delete(); if (!copy.renameTo(out)) throw java.io.IOException("cannot write ${out.name}") }
        } finally { if (copy.exists()) copy.delete() }
    }
}
