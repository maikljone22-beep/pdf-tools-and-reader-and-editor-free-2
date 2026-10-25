package com.smartdocs.editor.core.office

import java.io.File

/** Office-ish document families handled by the office module. */
enum class OfficeKind { DOCX, XLSX, PPTX, TXT, LEGACY_DOC, LEGACY_XLS, LEGACY_PPT, UNKNOWN;

    val isLegacy: Boolean get() = this == LEGACY_DOC || this == LEGACY_XLS || this == LEGACY_PPT
    val isEditable: Boolean get() = this == DOCX || this == XLSX || this == TXT

    val mime: String get() = when (this) {
        DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        TXT -> "text/plain"
        LEGACY_DOC -> "application/msword"
        LEGACY_XLS -> "application/vnd.ms-excel"
        LEGACY_PPT -> "application/vnd.ms-powerpoint"
        UNKNOWN -> "*/*"
    }
}

private val DOCX_EXT = setOf("docx", "docm", "dotx", "dotm")
private val XLSX_EXT = setOf("xlsx", "xlsm", "xltx", "xltm")
private val PPTX_EXT = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm")
private val TXT_EXT = setOf("txt", "text", "log", "csv", "md")
private val DOC_EXT = setOf("doc", "dot", "rtf")
private val XLS_EXT = setOf("xls", "xlt")
private val PPT_EXT = setOf("ppt", "pot", "pps")

/** Kind of a document by its file name extension only. */
fun officeKindOf(name: String): OfficeKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in DOCX_EXT -> OfficeKind.DOCX
        in XLSX_EXT -> OfficeKind.XLSX
        in PPTX_EXT -> OfficeKind.PPTX
        in TXT_EXT -> OfficeKind.TXT
        in DOC_EXT -> OfficeKind.LEGACY_DOC
        in XLS_EXT -> OfficeKind.LEGACY_XLS
        in PPT_EXT -> OfficeKind.LEGACY_PPT
        else -> OfficeKind.UNKNOWN
    }
}

/** True for any file the office module should open (OOXML, plain text and legacy binaries that get a message). */
fun isOfficeFile(name: String): Boolean = officeKindOf(name) != OfficeKind.UNKNOWN

/** True for legacy binary Office formats (.doc/.xls/.ppt) that can only be handed to another app. */
fun isLegacyOfficeFile(name: String): Boolean = officeKindOf(name).isLegacy

/**
 * Kind of a file looking at its content when the extension lies: a ZIP container is inspected for
 * its main part, an OLE2 compound file (D0 CF 11 E0) is a legacy binary document.
 */
fun detectOfficeKind(file: File): OfficeKind {
    val byName = officeKindOf(file.name)
    val head = ByteArray(8)
    val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
    if (n < 4) return byName
    val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    val isOle = (head[0].toInt() and 0xFF) == 0xD0 && (head[1].toInt() and 0xFF) == 0xCF &&
        (head[2].toInt() and 0xFF) == 0x11 && (head[3].toInt() and 0xFF) == 0xE0
    if (isOle) return when (byName) {
        OfficeKind.DOCX, OfficeKind.LEGACY_DOC -> OfficeKind.LEGACY_DOC
        OfficeKind.XLSX, OfficeKind.LEGACY_XLS -> OfficeKind.LEGACY_XLS
        OfficeKind.PPTX, OfficeKind.LEGACY_PPT -> OfficeKind.LEGACY_PPT
        else -> byName
    }
    if (isZip && byName !in setOf(OfficeKind.DOCX, OfficeKind.XLSX, OfficeKind.PPTX)) {
        val names = runCatching { java.util.zip.ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toSet() } }.getOrDefault(emptySet())
        return when {
            "word/document.xml" in names -> OfficeKind.DOCX
            "xl/workbook.xml" in names -> OfficeKind.XLSX
            "ppt/presentation.xml" in names -> OfficeKind.PPTX
            else -> byName
        }
    }
    return byName
}
