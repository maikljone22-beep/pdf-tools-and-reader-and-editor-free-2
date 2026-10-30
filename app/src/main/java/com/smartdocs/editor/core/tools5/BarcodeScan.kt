package com.smartdocs.editor.core.tools5

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * FINAL_CHECK_2 Low (OLD K11): QR / barcode reading in the scanner — ZXing on ARGB pixels (pure, JVM-tested).
 * Finds every code on a page picture (QR, Data Matrix, Aztec, PDF417, EAN / UPC, Code 39 / 93 / 128, ITF, Codabar).
 */
object BarcodeScan {

    data class Code(val format: String, val text: String)

    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.CODABAR,
        ),
    )

    /** Every code found in the picture (distinct by text); the picture is tried as it is and rotated 90° for codes that are sideways. */
    fun decode(px: IntArray, w: Int, h: Int, multiple: Boolean = true): List<Code> {
        val out = LinkedHashMap<String, Code>()
        fun run(p: IntArray, pw: Int, ph: Int) {
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(pw, ph, p)))
            val reader = MultiFormatReader().apply { setHints(hints) }
            if (multiple) runCatching { GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints) }.getOrNull()?.forEach { r -> out.putIfAbsent(r.text, Code(r.barcodeFormat.name, r.text)) }
            if (out.isEmpty()) runCatching { reader.decodeWithState(bitmap) }.getOrNull()?.let { r -> out.putIfAbsent(r.text, Code(r.barcodeFormat.name, r.text)) }
        }
        run(px, w, h)
        if (out.isEmpty()) run(IntArray(w * h) { k -> val x = k % h; val y = k / h; px[(h - 1 - x) * w + y] }, h, w)
        return out.values.toList()
    }

    /** True when [text] looks like something to open (a web address, mail, phone, geo, wifi…). */
    fun isLink(text: String): Boolean = Regex("^(https?://|mailto:|tel:|geo:|sms:|market://|WIFI:)", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
}
