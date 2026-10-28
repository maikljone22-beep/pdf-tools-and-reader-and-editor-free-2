package com.smartdocs.editor.core.convert6

/**
 * FINAL_CHECK_2 #3: creation presets of the to-PDF cards (Nitro / Acrobat "conversion settings"): Standard keeps the
 * card's quality; High quality 95; Smallest size 60 plus the optimizer; Archival makes PDF/A-2b. Pure.
 */
object CreatePreset {
    const val STANDARD = 0
    const val HIGH = 1
    const val SMALLEST = 2
    const val ARCHIVAL = 3

    /** Picture quality a preset uses ([current] = the card's own setting). */
    fun quality(preset: Int, current: Int): Int = when (preset) { HIGH -> 95; SMALLEST -> 60; else -> current }
}
