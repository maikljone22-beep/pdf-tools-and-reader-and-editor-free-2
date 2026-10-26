package com.smartdocs.editor.core.connected

import java.io.File
import java.io.RandomAccessFile

/**
 * Recognises files protected by an enterprise rights-management system (we cannot decrypt them on purpose: the
 * keys live with the organisation's identity service). Heuristic byte scan of the first and last 2 MB.
 *
 * Kinds:
 *  - WRAPPER: generic protected-file wrapper (.pfile / .ppdf, or a PDF that embeds one)
 *  - IRM_PDF: PDF encrypted with the enterprise IRM security handler
 *  - OTHER_DRM: PDF with another third-party DRM security handler
 *  - OFFICE_IRM: OLE compound file with rights-management DataSpaces (protected Word/Excel/PowerPoint)
 *  - LABEL_ONLY: sensitivity label without encryption (opens normally, banner only)
 */
object EnterpriseProtection {
    enum class Kind { WRAPPER, IRM_PDF, OTHER_DRM, OFFICE_IRM, LABEL_ONLY }

    data class Info(val kind: Kind, val labelName: String?, val blocking: Boolean)

    /** Official viewers to try, in order (explicit package intents; the UI never shows these names). */
    val viewerPackages = listOf(
        "com.microsoft.ipviewer",                // protection viewer
        "com.microsoft.office.officehubrow",     // office hub
        "com.microsoft.office.outlook",
        "com.microsoft.office.word", "com.microsoft.office.excel", "com.microsoft.office.powerpoint",
    )
    const val VIEWER_STORE_ID = "com.microsoft.ipviewer"

    private const val WINDOW = 2 * 1024 * 1024
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val IRM_FILTER = Regex("""/Filter\s*/MicrosoftIRMServices""")
    private val OTHER_DRM = Regex("""/Filter\s*/(FOPN_\w+|Adobe\.APS|Adobe\.PubSec\.DRM|FileOpen\w*|LockLizard\w*|Vitrium\w*)""")
    private val LABEL_NAME = Regex("""MSIP_Label_[0-9a-fA-F-]{36}_Name\s*(?:\(|>|=")\s*([^)<"]{1,80})""")
    private val LABEL_ENABLED = Regex("""MSIP_Label_[0-9a-fA-F-]{36}_Enabled""")

    private fun utf16(s: String): String = s.flatMap { listOf(it, '\u0000') }.joinToString("")

    /** Pure detection on the head/tail bytes of a file. */
    fun detect(head: ByteArray, tail: ByteArray, fileName: String): Info? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val h = String(head, Charsets.ISO_8859_1); val t = String(tail, Charsets.ISO_8859_1)
        val all = h + t
        val label = LABEL_NAME.find(all)?.groupValues?.get(1)?.trim()
        if (ext == "pfile" || ext == "ppdf" || fileName.lowercase().endsWith(".pdf.pfile")) return Info(Kind.WRAPPER, label, true)
        if (head.size >= 8 && head.copyOfRange(0, 8).contentEquals(OLE)) {
            if (all.contains(utf16("DataSpaces")) || all.contains(utf16("DRMEncrypted")) || all.contains("Microsoft.Metadata.DRMTransform") ||
                all.contains(utf16("Microsoft.Container.DataSpaces")))
                return Info(Kind.OFFICE_IRM, label, true)
            return null
        }
        if (h.startsWith("%PDF") || h.contains("%PDF-")) {
            if (IRM_FILTER.containsMatchIn(all)) return Info(Kind.IRM_PDF, label, true)
            if (OTHER_DRM.containsMatchIn(all)) return Info(Kind.OTHER_DRM, label, true)
            // wrapper PDFs: a cover page that embeds the protected original
            if (all.contains("/EmbeddedFile") && (Regex("""\.p?pdf\.pfile|\.pfile\)|\.ppdf\)""").containsMatchIn(all) || all.contains("MicrosoftIRMServices")))
                return Info(Kind.WRAPPER, label, true)
            if (label != null || LABEL_ENABLED.containsMatchIn(all)) return Info(Kind.LABEL_ONLY, label, false)
        }
        return null
    }

    /** Reads up to 2 MB from both ends of [file]. Never throws. */
    fun detect(file: File): Info? = runCatching {
        if (!file.isFile || file.length() == 0L) return null
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val head = ByteArray(minOf(len, WINDOW.toLong()).toInt()); raf.readFully(head)
            val tail = if (len <= WINDOW) ByteArray(0) else ByteArray(minOf(len - WINDOW, WINDOW.toLong()).toInt()).also { raf.seek(len - it.size); raf.readFully(it) }
            detect(head, tail, file.name)
        }
    }.getOrNull()
}
