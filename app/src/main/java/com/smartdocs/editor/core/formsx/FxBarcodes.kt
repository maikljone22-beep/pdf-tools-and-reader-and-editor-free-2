package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Barcode form fields (Nitro / Acrobat / Foxit "Barcode field"): a read-only text field whose value is the
 * payload of other fields (tab-delimited or XML) and whose appearance is a PDF417, QR or Data Matrix symbol.
 * Settings live in /SDBarcode (ours) and Acrobat's /PMD; the value is recomputed with [refreshAll] after filling.
 */
object FxBarcodes {

    private fun n(s: String) = COSName.getPDFName(s)
    private val SD_BARCODE = n("SDBarcode")

    // ---------------- settings ----------------

    fun writeSpec(b: FxBarcode) = COSDictionary().apply {
        setName(COSName.TYPE, "SDBarcode")
        setName(n("Kind"), b.kind.name)
        setItem(n("XDim"), COSFloat(b.xDim)); setItem(n("YX"), COSFloat(b.yxRatio)); setInt(n("ECC"), b.ecc)
        setItem(n("Fields"), COSArray().apply { b.fields.forEach { add(COSString(it)) } })
        setBoolean(n("XML"), b.xml); setBoolean(n("Names"), b.includeNames); setBoolean(n("Compress"), b.compress)
    }

    fun readSpec(d: COSDictionary): FxBarcode {
        val fields = (d.getDictionaryObject(n("Fields")) as? COSArray)?.let { a -> (0 until a.size()).mapNotNull { (a.getObject(it) as? COSString)?.string } } ?: emptyList()
        return FxBarcode(
            kind = runCatching { FxBarcodeKind.valueOf(d.getNameAsString(n("Kind")) ?: "QR") }.getOrDefault(FxBarcodeKind.QR),
            xDim = d.getFloat(n("XDim"), 2f), yxRatio = d.getFloat(n("YX"), 1f), ecc = d.getInt(n("ECC"), 1), fields = fields,
            xml = (d.getDictionaryObject(n("XML")) as? COSBoolean)?.value ?: false,
            includeNames = (d.getDictionaryObject(n("Names")) as? COSBoolean)?.value ?: true,
            compress = (d.getDictionaryObject(n("Compress")) as? COSBoolean)?.value ?: false,
        )
    }

    /** Acrobat's paper metadata dictionary (/PMD) so Acrobat recognises the field as a barcode. */
    fun pmd(b: FxBarcode) = COSDictionary().apply {
        setName(n("Symbology"), when (b.kind) { FxBarcodeKind.QR -> "QRCode"; FxBarcodeKind.PDF417 -> "PDF417"; FxBarcodeKind.DATAMATRIX -> "DataMatrix" })
        setItem(n("XSymWidth"), COSFloat(b.xDim)); setItem(n("YSymHeight"), COSFloat(b.xDim * b.yxRatio))
        setInt(n("ECC"), b.ecc); setInt(n("DataPrep"), if (b.compress) 1 else 0)
    }

    fun readPmd(d: COSDictionary): FxBarcode = FxBarcode(
        kind = when (d.getNameAsString(n("Symbology"))) { "PDF417" -> FxBarcodeKind.PDF417; "DataMatrix" -> FxBarcodeKind.DATAMATRIX; else -> FxBarcodeKind.QR },
        xDim = d.getFloat(n("XSymWidth"), 2f), yxRatio = (d.getFloat(n("YSymHeight"), 2f) / d.getFloat(n("XSymWidth"), 2f).coerceAtLeast(0.1f)),
        ecc = d.getInt(n("ECC"), 1), compress = d.getInt(n("DataPrep"), 0) != 0,
    )

    // ---------------- payload ----------------

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Tab-delimited (names line + values line) or XML payload of [values] (in the given order). */
    fun payload(values: List<Pair<String, String>>, b: FxBarcode): String {
        val text = if (b.xml) buildString {
            append("<xfdf><fields>")
            values.forEach { (k, v) -> append("<field name=\"").append(xml(k)).append("\"><value>").append(xml(v)).append("</value></field>") }
            append("</fields></xfdf>")
        } else buildString {
            if (b.includeNames) append(values.joinToString("\t") { it.first }).append('\n')
            append(values.joinToString("\t") { it.second.replace('\t', ' ').replace('\n', ' ') })
        }
        if (!b.compress) return text
        val d = Deflater(9); d.setInput(text.toByteArray(Charsets.UTF_8)); d.finish()
        val bos = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val k = d.deflate(buf); bos.write(buf, 0, k) }
        d.end()
        return java.util.Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    /** Calculate script that keeps the value up to date in Acrobat-like viewers (same payload as [payload] without compression). */
    fun calcJs(b: FxBarcode): String {
        val list = b.fields.joinToString(", ") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        val names = if (b.fields.isEmpty()) "var n = []; for (var i = 0; i < this.numFields; i++) { var f = this.getField(this.getNthFieldName(i)); if (f && f.type != \"button\" && f.type != \"signature\" && f.name != event.target.name) n.push(f.name); }"
            else "var n = [$list];"
        val body = if (b.xml) "var s = \"<xfdf><fields>\"; for (var i = 0; i < n.length; i++) { var f = this.getField(n[i]); s += \"<field name=\\\"\" + n[i] + \"\\\"><value>\" + (f ? f.valueAsString : \"\") + \"</value></field>\"; } event.value = s + \"</fields></xfdf>\";"
            else "var v = []; for (var i = 0; i < n.length; i++) { var f = this.getField(n[i]); v.push(f ? f.valueAsString : \"\"); } event.value = ${if (b.includeNames) "n.join(\"\\t\") + \"\\n\" + " else ""}v.join(\"\\t\");"
        return "/* SmartDocs barcode */ $names $body"
    }

    // ---------------- symbols ----------------

    data class Symbol(val modules: Array<BooleanArray>, val rowHeight: Float)

    /** The symbol of [text] with the field's settings, through the app's shared zxing encoder. */
    fun encode(text: String, b: FxBarcode): Symbol? {
        val kind = when (b.kind) {
            FxBarcodeKind.QR -> com.smartdocs.editor.core.util.BarcodeEncoder.Kind.QR
            FxBarcodeKind.DATAMATRIX -> com.smartdocs.editor.core.util.BarcodeEncoder.Kind.DATAMATRIX
            FxBarcodeKind.PDF417 -> com.smartdocs.editor.core.util.BarcodeEncoder.Kind.PDF417
        }
        val m = com.smartdocs.editor.core.util.BarcodeEncoder.matrix(kind, text, b.ecc, b.yxRatio) ?: return null
        return Symbol(m.modules, m.rowHeight)
    }

    // ---------------- document ----------------

    /** (name, value) of the fields a barcode encodes: its list, or every data field except barcodes. */
    fun sourceValues(doc: PDDocument, b: FxBarcode, self: String): List<Pair<String, String>> {
        val form = doc.documentCatalog.getAcroForm(null) ?: return emptyList()
        val all = FxEditor.terminals(form)
        val chosen = if (b.fields.isNotEmpty()) b.fields.mapNotNull { nm -> all.firstOrNull { it.fullyQualifiedName == nm } }
            else all.filter { it !is PDPushButton && it !is PDSignatureField && it.fullyQualifiedName != self && it.cosObject.getDictionaryObject(SD_BARCODE) == null }
        return chosen.map { f ->
            f.fullyQualifiedName to when (f) {
                is PDChoice -> runCatching { f.value.joinToString(",") }.getOrDefault("")
                is PDButton -> runCatching { f.value }.getOrDefault("")
                else -> runCatching { f.valueAsString }.getOrDefault("")
            }
        }
    }

    /** Recompute the payload of barcode field [f] and redraw its symbol. */
    fun render(doc: PDDocument, f: PDTextField, spec: FieldSpec) {
        val b = spec.barcode ?: return
        val text = payload(sourceValues(doc, b, f.fullyQualifiedName), b)
        f.cosObject.setString(COSName.V, text)
        val sym = encode(text, b)
        f.widgets.forEach { w ->
            val r = w.rectangle ?: return@forEach
            val rect = FxRect(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
            w.cosObject.setItem(COSName.AP, FxAppearance.barcodeAp(doc, spec, rect, sym?.modules ?: emptyArray(), sym?.rowHeight ?: 1f))
        }
    }

    /** Refresh every barcode field of [doc] (call after form values changed). Returns how many were redrawn. */
    fun refreshAll(doc: PDDocument): Int {
        val form = doc.documentCatalog.getAcroForm(null) ?: return 0
        var count = 0
        FxEditor.terminals(form).filterIsInstance<PDTextField>().forEach { f ->
            if (f.cosObject.getDictionaryObject(SD_BARCODE) == null && f.cosObject.getDictionaryObject(n("PMD")) == null) return@forEach
            runCatching { render(doc, f, FxEditor.read(doc, f)); count++ }
        }
        return count
    }
}
