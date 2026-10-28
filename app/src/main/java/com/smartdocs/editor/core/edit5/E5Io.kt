package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.edit.PageFrame
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.File

/** Load / save shared by every edit5 operation. Saving goes through a sibling temp file, so [out] may equal the source. */
object E5Io {
    fun load(file: File, password: String? = null): PDDocument = PdfOps.load(file, password)

    fun save(doc: PDDocument, out: File) {
        com.smartdocs.editor.core.pdf.SignedGuard.checkFullSave(doc, out)   // tab-view-core E-01: never rewrite a signed file in full
        val tmp = File(out.absoluteFile.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
        try {
            doc.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /** Load [src], run [block], save into [out]. */
    inline fun <T> edit(src: File, out: File, password: String?, block: (PDDocument) -> T): T =
        load(src, password).use { doc -> val r = block(doc); save(doc, out); r }

    fun name(s: String): COSName = COSName.getPDFName(s)

    /** Visible page box (crop box clipped to the media box). */
    fun visibleBox(page: PDPage): PDRectangle = page.cropBox

    fun isLandscape(page: PDPage): Boolean {
        val f = PageFrame.of(page); return f.width > f.height
    }

    fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
}

/**
 * Tagged page content added by SmartDocs, so it can be updated or removed later:
 * `/Artifact <</Type /Pagination /Subtype /Header|/Footer|/Watermark|/Background /SDE5 (kind)>> BDC … EMC`.
 * Acrobat marks its own header/footer/watermark the same way (Type /Pagination), so [strip] can also remove those.
 * The settings used are stored per document in the catalog /PieceInfo /SmartDocsE5 /Private dictionary.
 */
object Marked {
    const val KEY = "SDE5"
    private val ARTIFACT = COSName.getPDFName("Artifact")

    /** Raw operator text that opens a tagged block (written with appendRawCommands, inline property dictionary). */
    fun open(kind: String, subtype: String): String =
        "/Artifact <</Type /Pagination /Subtype /$subtype /$KEY (${kind.filter { it.isLetterOrDigit() || it == '_' }})>> BDC\n"

    const val CLOSE = "EMC\n"

    /** Kinds of tagged blocks on [page] (ours by /SDE5, others' as "pdf:<Subtype>"). */
    fun kinds(page: PDPage): Set<String> {
        val out = LinkedHashSet<String>()
        scan(page) { props -> props?.let { tagOf(it) }?.let { out += it }; false }
        return out
    }

    private fun tagOf(props: COSDictionary): String? {
        props.getString(name(KEY))?.let { return it }
        if (props.getNameAsString(COSName.TYPE) == "Pagination") return "pdf:" + (props.getNameAsString(COSName.SUBTYPE) ?: "")
        return null
    }

    private fun name(s: String) = COSName.getPDFName(s)

    /**
     * Remove every tagged block whose tag matches [match] from the page content (all /Contents streams are joined
     * into one). Returns true when something was removed.
     */
    fun strip(doc: PDDocument, page: PDPage, match: (String) -> Boolean): Boolean {
        if (!page.hasContents()) return false
        val tokens = runCatching { PDFStreamParser(page).let { it.parse(); it.tokens } }.getOrNull() ?: return false
        val resProps = page.resources?.cosObject?.getCOSDictionary(COSName.PROPERTIES)
        val out = ArrayList<Any>(tokens.size)
        val operands = ArrayList<Any>()
        var depth = 0          // >0 while inside a removed block (counts nested marked content)
        var removed = false
        for (t in tokens) {
            if (t !is Operator) { if (depth == 0) operands += t; continue }
            val op = t.name
            if (depth > 0) {
                when (op) { "BMC", "BDC" -> depth++; "EMC" -> depth-- }
                operands.clear(); continue
            }
            if (op == "BDC" && operands.size >= 2 && (operands[0] as? COSName) == ARTIFACT) {
                val p = operands[1]
                val props = when (p) {
                    is COSDictionary -> p
                    is COSName -> resProps?.getDictionaryObject(p) as? COSDictionary
                    else -> null
                }
                val tag = props?.let { tagOf(it) }
                if (tag != null && match(tag)) { depth = 1; removed = true; operands.clear(); continue }
            }
            out.addAll(operands); operands.clear(); out += t
        }
        if (!removed) return false
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(out) }
        page.setContents(stream)
        return true
    }

    /** Walk BDC property lists of the page; [onProps] returning true stops. */
    private fun scan(page: PDPage, onProps: (COSDictionary?) -> Boolean) {
        if (!page.hasContents()) return
        val tokens = runCatching { PDFStreamParser(page).let { it.parse(); it.tokens } }.getOrNull() ?: return
        val resProps = page.resources?.cosObject?.getCOSDictionary(COSName.PROPERTIES)
        var prev: Any? = null; var prev2: Any? = null
        for (t in tokens) {
            if (t is Operator && t.name == "BDC" && prev2 == ARTIFACT) {
                val props = when (prev) { is COSDictionary -> prev; is COSName -> resProps?.getDictionaryObject(prev) as? COSDictionary; else -> null }
                if (onProps(props)) return
            }
            prev2 = prev; prev = t
        }
    }

    // ---------------- settings stored in the document ----------------

    private val PIECE = COSName.getPDFName("PieceInfo")
    private val APP = COSName.getPDFName("SmartDocsE5")
    private val PRIVATE = COSName.getPDFName("Private")

    /** Store [json] under [key] in the catalog's /PieceInfo (null removes it). */
    fun putSetting(doc: PDDocument, key: String, json: String?) {
        val cat = doc.documentCatalog.cosObject
        val piece = cat.getCOSDictionary(PIECE) ?: COSDictionary().also { cat.setItem(PIECE, it) }
        val app = piece.getCOSDictionary(APP) ?: COSDictionary().also { piece.setItem(APP, it) }
        val priv = app.getCOSDictionary(PRIVATE) ?: COSDictionary().also { app.setItem(PRIVATE, it) }
        if (json == null) priv.removeItem(name(key)) else priv.setItem(name(key), COSString(json))
        app.setDate(COSName.getPDFName("LastModified"), java.util.Calendar.getInstance())
    }

    fun getSetting(doc: PDDocument, key: String): String? =
        doc.documentCatalog.cosObject.getCOSDictionary(PIECE)?.getCOSDictionary(APP)?.getCOSDictionary(PRIVATE)?.getString(name(key))
}
