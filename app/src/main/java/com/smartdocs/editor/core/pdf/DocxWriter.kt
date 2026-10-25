package com.smartdocs.editor.core.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a minimal but valid Word (.docx) package: one paragraph per text line, a page break
 * between source pages, and `<w:bidi/>` on paragraphs that contain Arabic so they lay out RTL.
 */
object DocxWriter {

    suspend fun write(pages: List<String>, out: File, title: String = "") = withContext(Dispatchers.IO) {
        ZipOutputStream(BufferedOutputStream(out.outputStream())).use { zip ->
            put(zip, "[Content_Types].xml", CONTENT_TYPES)
            put(zip, "_rels/.rels", RELS)
            put(zip, "word/_rels/document.xml.rels", DOC_RELS)
            put(zip, "word/styles.xml", STYLES)
            put(zip, "docProps/core.xml", coreXml(title))
            put(zip, "word/document.xml", documentXml(pages))
        }
    }

    /** Simple HTML export with one `<p>` per line; the page is `dir="rtl"` when the text is mostly Arabic. */
    fun html(pages: List<String>, title: String = ""): String {
        val all = pages.joinToString("\n")
        val rtl = ArabicShaper.isArabic(all)
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"").append(if (rtl) "ar" else "en").append("\" dir=\"").append(if (rtl) "rtl" else "ltr").append("\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<title>").append(escape(title)).append("</title>\n")
        sb.append("<style>body{font-family:'Noto Naskh Arabic','Segoe UI',Tahoma,sans-serif;line-height:1.8;max-width:800px;margin:24px auto;padding:0 16px;color:#222}p{margin:0 0 8px}hr{margin:24px 0;border:0;border-top:1px dashed #bbb}.pg{color:#888;font-size:12px;text-align:center}</style>\n</head>\n<body>\n")
        pages.forEachIndexed { i, page ->
            if (i > 0) sb.append("<hr>\n")
            if (pages.size > 1) sb.append("<div class=\"pg\">").append(i + 1).append("</div>\n")
            page.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val dir = if (ArabicShaper.isArabic(line)) "rtl" else "ltr"
                sb.append("<p dir=\"").append(dir).append("\">").append(escape(line)).append("</p>\n")
            }
        }
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun documentXml(pages: List<String>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
        pages.forEachIndexed { i, page ->
            if (i > 0) sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            val lines = page.lines()
            if (lines.all { it.isBlank() }) sb.append("<w:p/>")
            lines.forEach { line -> sb.append(paragraph(line)) }
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun paragraph(line: String): String {
        val text = line.trimEnd()
        if (text.isBlank()) return "<w:p/>"
        val rtl = ArabicShaper.isArabic(text)
        val pPr = if (rtl) "<w:pPr><w:bidi/></w:pPr>" else ""
        val rPr = if (rtl) "<w:rPr><w:rtl/></w:rPr>" else ""
        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r></w:p>"
    }

    private fun coreXml(title: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${escape(title)}</dc:title><dc:creator>SmartDocs</dc:creator></cp:coreProperties>"

    /** XML-escape and strip characters that are illegal in XML 1.0. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c.code < 0x20 || c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "</Types>"

    private const val RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "</Relationships>"

    private const val DOC_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private const val STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Arial\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        "</w:styles>"
}
