package com.smartdocs.editor.core.view5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stored-comment helpers for the comment manager: text-markup quads (to extract the highlighted text),
 * private checkmarks (/StateModel /Marked replies) and a Word (.docx) table export.
 */
object CommentOps5 {
    private val IRT = COSName.getPDFName("IRT")
    private val STATE = COSName.getPDFName("State")
    private val STATE_MODEL = COSName.getPDFName("StateModel")
    private val QUAD = COSName.getPDFName("QuadPoints")

    data class Extra(val quads: List<FloatArray>, val checked: Boolean)

    private fun open(src: File, pw: String?): PDDocument = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)

    /** (page, index) → quads (each [x0, y0, x1, y1], y up) and the latest private checkmark. */
    suspend fun extras(src: File, pw: String?): Map<Pair<Int, Int>, Extra> = withContext(Dispatchers.IO) {
        open(src, pw).use { doc ->
            val out = HashMap<Pair<Int, Int>, Extra>()
            doc.pages.forEachIndexed { pi, page ->
                val list = runCatching { page.annotations }.getOrNull() ?: return@forEachIndexed
                val idx = java.util.IdentityHashMap<COSDictionary, Int>()
                list.forEachIndexed { i, an -> idx[an.cosObject] = i }
                val marks = HashMap<Int, Pair<Boolean, String>>()
                list.forEach { an ->
                    val c = an.cosObject
                    val parent = (c.getDictionaryObject(IRT) as? COSDictionary)?.let { idx[it] } ?: return@forEach
                    val model = c.getString(STATE_MODEL) ?: c.getNameAsString(STATE_MODEL) ?: return@forEach
                    if (model != "Marked") return@forEach
                    val st = c.getString(STATE) ?: c.getNameAsString(STATE) ?: return@forEach
                    val date = c.getString(COSName.M) ?: ""
                    val prev = marks[parent]
                    if (prev == null || date >= prev.second) marks[parent] = (st == "Marked") to date
                }
                list.forEachIndexed { i, an ->
                    val arr = an.cosObject.getDictionaryObject(QUAD) as? COSArray
                    val quads = ArrayList<FloatArray>()
                    if (arr != null) {
                        val v = FloatArray(arr.size()) { k -> (arr.getObject(k) as? COSNumber)?.floatValue() ?: 0f }
                        var k = 0
                        while (k + 7 < v.size) {
                            val xs = floatArrayOf(v[k], v[k + 2], v[k + 4], v[k + 6]); val ys = floatArrayOf(v[k + 1], v[k + 3], v[k + 5], v[k + 7])
                            quads += floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()); k += 8
                        }
                    }
                    val m = marks[i]?.first ?: false
                    if (quads.isNotEmpty() || m) out[pi to i] = Extra(quads, m)
                }
            }
            out
        }
    }

    /** Add a private checkmark reply (hidden Text annotation, /StateModel /Marked) to a stored comment. */
    suspend fun setChecked(src: File, out: File, pw: String?, page: Int, index: Int, checked: Boolean, author: String) = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { doc ->
            val p = doc.getPage(page)
            val parent = p.annotations[index]
            val r = parent.rectangle ?: PDRectangle(0f, 0f, 10f, 10f)
            val reply = PDAnnotationText()
            reply.rectangle = PDRectangle(r.lowerLeftX, r.lowerLeftY, 1f, 1f)
            reply.titlePopup = author
            reply.contents = if (checked) "Marked" else "Unmarked"
            reply.setCreationDate(Calendar.getInstance()); reply.setModifiedDate(Calendar.getInstance())
            reply.isHidden = true
            reply.cosObject.setItem(IRT, parent.cosObject)
            reply.cosObject.setString(STATE_MODEL, "Marked")
            reply.cosObject.setString(STATE, if (checked) "Marked" else "Unmarked")
            p.annotations.add(reply)
            doc.save(out)
        }
    }

    // ---------------- Word export ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it.code >= 0x20 }

    private fun para(text: String, bold: Boolean = false, size: Int = 20): String {
        val rtl = ReflowBlocks.isRtl(text)
        val runs = text.split('\n').joinToString("<w:r><w:br/></w:r>") { line ->
            "<w:r><w:rPr>${if (bold) "<w:b/>" else ""}${if (rtl) "<w:rtl/>" else ""}<w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">${esc(line)}</w:t></w:r>"
        }
        return "<w:p><w:pPr>${if (rtl) "<w:bidi/>" else ""}</w:pPr>$runs</w:p>"
    }

    /** One table: header row + one row per comment. */
    suspend fun writeDocx(rows: List<List<String>>, header: List<String>, title: String, out: File, rtlTable: Boolean = true) = withContext(Dispatchers.IO) {
        val body = StringBuilder()
        body.append(para(title, bold = true, size = 32))
        body.append("<w:tbl><w:tblPr>${if (rtlTable) "<w:bidiVisual/>" else ""}<w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/>")
        body.append("<w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { body.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>") }
        body.append("</w:tblBorders></w:tblPr>")
        fun row(cells: List<String>, head: Boolean) {
            body.append("<w:tr>")
            cells.forEach { c -> body.append("<w:tc><w:tcPr>${if (head) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8EAF6\"/>" else ""}</w:tcPr>").append(para(c, bold = head, size = 18)).append("</w:tc>") }
            body.append("</w:tr>")
        }
        row(header, true)
        rows.forEach { row(it, false) }
        body.append("</w:tbl>").append(para(""))
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1000\" w:right=\"900\" w:bottom=\"1000\" w:left=\"900\" w:header=\"500\" w:footer=\"500\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        ZipOutputStream(BufferedOutputStream(out.outputStream())).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            put("word/document.xml", doc)
        }
    }
}
