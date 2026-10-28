package com.smartdocs.editor.core.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import com.google.zxing.oned.CodaBarWriter
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.Code39Writer
import com.google.zxing.oned.Code93Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.EAN8Writer
import com.google.zxing.oned.ITFWriter
import com.google.zxing.oned.OneDimensionalCodeWriter
import com.google.zxing.oned.UPCEWriter
import com.google.zxing.pdf417.encoder.PDF417
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The one barcode encoder of the app (zxing), shared by the form-designer barcode fields (core/formsx/FxBarcodes)
 * and the drawing of XFA barcode fields (core/forms/NpfBarcode). 2D symbols give a module matrix; linear ones
 * give the module row (true = bar). Returns null when the value cannot be encoded in that symbology.
 */
object BarcodeEncoder {

    enum class Kind(val twoD: Boolean) {
        QR(true), DATAMATRIX(true), PDF417(true),
        CODE128(false), CODE39(false), CODE93(false), EAN13(false), EAN8(false), UPCA(false), UPCE(false), ITF(false), CODABAR(false),
    }

    /** [modules] rows (row 0 at the top); [rowHeight] is the height of a row in module widths (PDF417 rows are taller). */
    data class Matrix(val modules: Array<BooleanArray>, val rowHeight: Float = 1f)

    /** [ecc]: QR 0..3 = L/M/Q/H; PDF417 0..8 = error-correction level. [yx]: PDF417 row height / module width. */
    fun matrix(kind: Kind, text: String, ecc: Int = 1, yx: Float = 3f): Matrix? = runCatching {
        val data = text.ifEmpty { " " }
        when (kind) {
            Kind.QR -> {
                val ecl = when (ecc.coerceIn(0, 3)) { 0 -> ErrorCorrectionLevel.L; 1 -> ErrorCorrectionLevel.M; 2 -> ErrorCorrectionLevel.Q; else -> ErrorCorrectionLevel.H }
                val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ecl, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"))
                Matrix(Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } })
            }
            Kind.DATAMATRIX -> {
                val m = DataMatrixWriter().encode(data, BarcodeFormat.DATA_MATRIX, 0, 0, mapOf(EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE))
                Matrix(Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } })
            }
            Kind.PDF417 -> {
                val enc = PDF417()
                enc.setEncoding(Charsets.UTF_8)
                enc.generateBarcodeLogic(data, ecc.coerceIn(0, 8), true)
                val rows = enc.barcodeMatrix.matrix
                Matrix(Array(rows.size) { y -> BooleanArray(rows[y].size) { x -> rows[y][x].toInt() == 1 } }, yx.coerceIn(1f, 10f))
            }
            else -> linear(kind, text)?.let { Matrix(arrayOf(it)) }
        }
    }.getOrNull()

    /** Module row of a linear symbology (without quiet zones), or null when [text] is not valid for it. */
    fun linear(kind: Kind, text: String): BooleanArray? = runCatching {
        if (text.isEmpty()) return null
        val w: OneDimensionalCodeWriter = when (kind) {
            Kind.CODE128 -> Code128Writer(); Kind.CODE39 -> Code39Writer(); Kind.CODE93 -> Code93Writer()
            Kind.EAN13 -> EAN13Writer(); Kind.EAN8 -> EAN8Writer(); Kind.UPCA -> return upcA(text); Kind.UPCE -> UPCEWriter()
            Kind.ITF -> ITFWriter(); Kind.CODABAR -> CodaBarWriter()
            else -> return null
        }
        w.encode(if (kind == Kind.CODE39) text.uppercase() else text)
    }.getOrNull()

    // UPCAWriter has no public encode(String): a UPC-A symbol is the EAN-13 symbol with a leading 0
    private fun upcA(text: String): BooleanArray? = EAN13Writer().encode("0$text")

    /** XFA `<barcode type="…">` names (case-insensitive) → kind; null for symbologies zxing cannot write. */
    fun fromXfa(type: String): Kind? = when (type.lowercase().replace("_", "").replace("-", "")) {
        "qrcode", "qr" -> Kind.QR
        "datamatrix" -> Kind.DATAMATRIX
        "pdf417", "pdf417macro" -> Kind.PDF417
        "code128", "code128a", "code128b", "code128c", "code128sscc" -> Kind.CODE128
        "code3of9", "code3of9extended", "code39", "logmars" -> Kind.CODE39
        "code93" -> Kind.CODE93
        "ean13", "ean13add2", "ean13add5", "ean13pwcd" -> Kind.EAN13
        "ean8", "ean8add2", "ean8add5" -> Kind.EAN8
        "upca", "upcaadd2", "upcaadd5", "upcapwcd" -> Kind.UPCA
        "upce", "upceadd2", "upceadd5" -> Kind.UPCE
        "interleaved2of5", "itf", "itf14" -> Kind.ITF
        "codabar" -> Kind.CODABAR
        else -> null
    }
}
