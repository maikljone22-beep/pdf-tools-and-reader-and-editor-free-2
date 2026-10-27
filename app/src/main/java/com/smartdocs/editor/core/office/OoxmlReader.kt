package com.smartdocs.editor.core.office

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Entry point of the on-device Office parsers (no Apache POI). Pure JVM: only java.util.zip and
 * org.xmlpull (Android's bundled kXML) are used, so it can be unit-tested off-device.
 */
object OoxmlReader {

    fun readDocx(file: File): DocModel = OoxmlPackage(file).use { DocxReader(it).read() }

    fun readXlsx(file: File, maxRows: Int = 5000, maxCols: Int = 200): WorkbookModel =
        OoxmlPackage(file).use { XlsxReader(it, maxRows, maxCols).read() }

    fun readPptx(file: File): PresentationModel = OoxmlPackage(file).use { PptxReader(it).read() }

    /** Sheet names and worksheet part paths without parsing cells. */
    fun sheetNames(file: File): List<String> = OoxmlPackage(file).use { p -> XlsxReader(p).sheetRefs().map { it.name } }

    /**
     * Reads a text file detecting BOMs (UTF-8 / UTF-16 LE/BE); without a BOM it tries strict UTF-8 and
     * falls back to Windows-1256 (common for Arabic files from older Windows tools).
     */
    fun readText(file: File): TextContent {
        val b = file.readBytes()
        fun dec(cs: Charset, off: Int) = String(b, off, b.size - off, cs)
        return when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> TextContent(dec(Charsets.UTF_8, 3), "UTF-8", true)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> TextContent(dec(Charsets.UTF_16LE, 2), "UTF-16LE", true)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> TextContent(dec(Charsets.UTF_16BE, 2), "UTF-16BE", true)
            else -> {
                val strict = runCatching {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
                }.getOrNull()
                // open-all: UTF-16 without BOM, then Windows-1256 (Arabic) vs Windows-1252 by content
                val u16 = com.smartdocs.editor.core.openall.TextDecode.utf16Guess(b)
                if (strict != null && u16 == null) TextContent(strict, "UTF-8", false)
                else if (u16 != null) TextContent(String(b, u16), u16.name(), false)
                else {
                    val cs = com.smartdocs.editor.core.openall.TextDecode.singleByteGuess(b)
                    TextContent(String(b, cs), cs.name(), false)
                }
            }
        }
    }

    /** Writes [text] back with the encoding (and BOM) it was read with. */
    fun writeText(file: File, text: String, like: TextContent?) {
        val enc = like?.encoding ?: "UTF-8"
        val cs = runCatching { Charset.forName(enc) }.getOrDefault(Charsets.UTF_8)
        val body = text.toByteArray(cs)
        val bom = if (like?.bom != true) ByteArray(0) else when (enc) {
            "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        file.outputStream().use { it.write(bom); it.write(body) }
    }
}

data class TextContent(val text: String, val encoding: String, val bom: Boolean)

/**
 * Replaces [target] with the output of [write] atomically-ish: writes to a temp file next to it, then
 * renames over the original (falls back to copy when rename is refused).
 */
fun replaceFileSafely(target: File, write: (File) -> Unit) {
    val tmp = File(target.parentFile, ".${target.name}.${System.currentTimeMillis()}.tmp")
    try {
        write(tmp)
        if (tmp.length() == 0L) throw java.io.IOException("empty output")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
