package com.smartdocs.editor.core.forms

/**
 * nav-prefs (Nitro §24 / Foxit §37 "barcode form fields"): draws the barcode of an XFA barcode field as filled boxes.
 * The app has no barcode library (no zxing): QR codes use the app's own encoder (ui/cloud/QrEncoder, byte mode,
 * level M, up to version 10) and Code 128 (set B) / Code 3 of 9 are encoded here. Other symbologies (PDF417,
 * Data Matrix, EAN, …) return null and the caller shows the value as text.
 */
object NpfBarcode {

    /** Bar / space widths in modules, starting with a bar; null when [text] cannot be encoded. */
    fun code128(text: String): IntArray? {
        if (text.isEmpty() || text.any { it.code !in 32..126 }) return null
        val codes = ArrayList<Int>()
        codes += START_B
        text.forEach { codes += it.code - 32 }
        var sum = START_B
        text.forEachIndexed { i, c -> sum += (c.code - 32) * (i + 1) }
        codes += sum % 103
        codes += STOP
        return codes.flatMap { c -> C128[c].map { it - '0' } }.toIntArray()
    }

    /** Code 3 of 9 with start / stop "*", narrow = 1 and wide = 3 modules, one narrow gap between characters. */
    fun code39(text: String): IntArray? {
        val up = text.uppercase()
        if (up.isEmpty() || up.any { it == '*' || C39.indexOf(it) < 0 }) return null
        val out = ArrayList<Int>()
        ("*$up*").forEachIndexed { k, ch ->
            if (k > 0) out += 1          // inter-character gap (a space)
            C39_PAT[C39.indexOf(ch)].forEach { out += if (it == 'w') 3 else 1 }
        }
        return out.toIntArray()
    }

    /** Filled boxes for [type] / [value] inside (x, y, w, h); null = not drawable (the caller shows the value). */
    fun boxes(type: String, value: String, x: Float, y: Float, w: Float, h: Float): List<XItem>? {
        val t = type.lowercase()
        return when {
            t.startsWith("qr") -> qr(value, x, y, w, h)
            t.startsWith("code128") -> code128(value)?.let { linear(it, x, y, w, h) }
            t == "code3of9" || t == "code39" || t == "code3of9extended" -> code39(value)?.let { linear(it, x, y, w, h) }
            else -> null
        }
    }

    /** Pure: x offsets and widths of the bars of [widths] scaled into [w] (quiet zone of 10 modules on each side). */
    fun bars(widths: IntArray, x: Float, w: Float): List<Pair<Float, Float>> {
        val modules = widths.sum() + 20
        val m = w / modules
        val out = ArrayList<Pair<Float, Float>>()
        var cx = x + 10 * m
        widths.forEachIndexed { i, n -> if (i % 2 == 0) out += cx to n * m; cx += n * m }
        return out
    }

    private fun linear(widths: IntArray, x: Float, y: Float, w: Float, h: Float): List<XItem> =
        bars(widths, x, w).map { (bx, bw) -> XItem.Box(bx, y, bw, h, List(4) { null }, BLACK) }

    private fun qr(value: String, x: Float, y: Float, w: Float, h: Float): List<XItem>? {
        val m = com.smartdocs.editor.ui.cloud.QrEncoder.encode(value) ?: return null
        val side = minOf(w, h); val cell = side / (m.size + 8)
        val ox = x + (w - side) / 2 + 4 * cell; val oy = y + (h - side) / 2 + 4 * cell
        val out = ArrayList<XItem>()
        for (r in 0 until m.size) {
            var c = 0
            while (c < m.size) {
                if (!m[c, r]) { c++; continue }
                val start = c
                while (c < m.size && m[c, r]) c++
                out += XItem.Box(ox + start * cell, oy + r * cell, (c - start) * cell, cell, List(4) { null }, BLACK)
            }
        }
        return out
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val START_B = 104
    private const val STOP = 106

    /** Code 128 symbol patterns (bar, space, bar, space, bar, space[, bar]) for values 0..106. */
    internal val C128 = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
    )

    internal const val C39 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. *\$/+%"
    internal val C39_PAT = arrayOf(
        "nnnwwnwnn", "wnnwnnnnw", "nnwwnnnnw", "wnwwnnnnn", "nnnwwnnnw", "wnnwwnnnn", "nnwwwnnnn", "nnnwnnwnw", "wnnwnnwnn", "nnwwnnwnn",
        "wnnnnwnnw", "nnwnnwnnw", "wnwnnwnnn", "nnnnwwnnw", "wnnnwwnnn", "nnwnwwnnn", "nnnnnwwnw", "wnnnnwwnn", "nnwnnwwnn", "nnnnwwwnn",
        "wnnnnnnww", "nnwnnnnww", "wnwnnnnwn", "nnnnwnnww", "wnnnwnnwn", "nnwnwnnwn", "nnnnnnwww", "wnnnnnwwn", "nnwnnnwwn", "nnnnwnwwn",
        "wwnnnnnnw", "nwwnnnnnw", "wwwnnnnnn", "nwnnwnnnw", "wwnnwnnnn", "nwwnwnnnn", "nwnnnnwnw", "wwnnnnwnn", "nwwnnnwnn", "nwnnwnwnn",
        "nwnwnwnnn", "nwnwnnnwn", "nwnnnwnwn", "nnnwnwnwn",
    )
}
