package com.smartdocs.editor.core.data

import com.smartdocs.editor.core.edit5.Digits

/** nav-prefs: pure helpers behind the preferences (unit-tested). */
object NpfText {
    /** PREF-2: digits typed into comments / edit text — "arabic" → ٠١٢, "latin" → 012, anything else unchanged. */
    fun digits(text: String, mode: String): String = when (mode) {
        "arabic" -> Digits.toArabic(text)
        "latin" -> Digits.toWestern(text)
        else -> text
    }

    /**
     * Accessibility "replace document colours": a 4x5 colour matrix (Android / Compose layout) that maps the page by
     * luminance: white → [bgRgb], black → [fgRgb], greys in between. Alpha is kept.
     */
    fun a11yMatrix(bgRgb: Int, fgRgb: Int): FloatArray {
        val lum = floatArrayOf(0.299f, 0.587f, 0.114f)
        val m = FloatArray(20)
        for (ch in 0..2) {
            val shift = 16 - ch * 8
            val bg = ((bgRgb shr shift) and 0xFF).toFloat(); val fg = ((fgRgb shr shift) and 0xFF).toFloat()
            // out = fg + (bg - fg) * lum   (lum of the source in 0..1; the matrix works on 0..255 values)
            for (k in 0..2) m[ch * 5 + k] = (bg - fg) / 255f * lum[k]
            m[ch * 5 + 4] = fg
        }
        m[18] = 1f
        return m
    }

    /** Font mapping lines "Missing Font = Replacement" (one per line, # comments); keys compared without case and spaces. */
    fun parseFontMap(text: String): Map<String, String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { '=' in it }
        .mapNotNull { line ->
            val k = line.substringBefore('=').trim(); val v = line.substringAfter('=').trim()
            if (k.isEmpty() || v.isEmpty()) null else fontKey(k) to v
        }.toMap()

    fun fontKey(name: String): String = name.substringAfter('+').lowercase().filter { it.isLetterOrDigit() }

    /** Replacement for [fontName] from the user's mapping, or null. Subset prefixes ("ABCDEF+") are ignored. */
    fun mappedFont(map: Map<String, String>, fontName: String?): String? = fontName?.let { map[fontKey(it)] }

    @Volatile private var fontMapSrc: String? = null
    @Volatile private var fontMapCache: Map<String, String> = emptyMap()

    /** [parseFontMap] of the saved mapping, cached until it changes. */
    fun userFontMap(): Map<String, String> {
        val src = NpfPrefs.fontMap
        if (src != fontMapSrc) { fontMapCache = parseFontMap(src); fontMapSrc = src }
        return fontMapCache
    }

    /** Snap [v] (points) to the grid of [stepPt] shifted by [offsetPt]. */
    fun snap(v: Float, stepPt: Float, offsetPt: Float): Float {
        if (stepPt <= 0f) return v
        val k = Math.round((v - offsetPt) / stepPt)
        return offsetPt + k * stepPt
    }

    /** Points per unit of the default units setting. */
    fun ptPerUnit(units: String): Float = when (units) { "mm" -> 72f / 25.4f; "in" -> 72f; "pt" -> 1f; else -> 72f / 2.54f }
}
