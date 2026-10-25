package com.smartdocs.editor.core.convert

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streaming writer of an OPC (Office Open XML) zip package. */
class OoxmlZip(out: OutputStream) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(out, 64 * 1024))
    private val names = HashSet<String>()

    fun text(name: String, xml: String) = bytes(name, xml.toByteArray(Charsets.UTF_8))

    fun text(name: String, xml: StringBuilder) = text(name, xml.toString())

    fun bytes(name: String, data: ByteArray) {
        require(names.add(name)) { "duplicate part $name" }
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    override fun close() = zip.close()

    companion object {
        const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
        const val REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        /** Points → EMU. */
        fun emu(pt: Float): Long = Math.round(pt.toDouble() * 12700.0)

        /** Points → twentieths of a point (Word twips). */
        fun twips(pt: Float): Int = Math.round(pt * 20f)

        fun rels(vararg items: Triple<String, String, String>): String {
            val sb = StringBuilder(XML_HEAD).append("<Relationships xmlns=\"").append(NS_REL).append("\">")
            items.forEach { (id, type, target) -> sb.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(type).append("\" Target=\"").append(BidiText.xml(target)).append("\"/>") }
            return sb.append("</Relationships>").toString()
        }

        fun coreProps(title: String): String = XML_HEAD +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${BidiText.xml(title)}</dc:title><dc:creator>SmartDocs</dc:creator></cp:coreProperties>"

        const val CT_CORE = "application/vnd.openxmlformats-package.core-properties+xml"
        const val REL_CORE = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"

        fun imageContentType(ext: String) = if (ext == "png") "image/png" else "image/jpeg"
    }
}
