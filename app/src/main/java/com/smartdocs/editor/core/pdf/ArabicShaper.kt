package com.smartdocs.editor.core.pdf

/**
 * Minimal Arabic shaper for drawing text with PdfBox, which has no bidi/shaping support.
 * Converts letters to Unicode presentation forms (isolated/initial/medial/final), joins lam-alef,
 * and reorders the string into visual (left-to-right) order so a plain showText() renders correctly.
 */
object ArabicShaper {

    // base char -> [isolated, final, initial, medial]; 0 means the form does not exist
    private val forms: Map<Char, IntArray> = mapOf(
        'ء' to intArrayOf(0xFE80, 0, 0, 0), 'آ' to intArrayOf(0xFE81, 0xFE82, 0, 0), 'أ' to intArrayOf(0xFE83, 0xFE84, 0, 0),
        'ؤ' to intArrayOf(0xFE85, 0xFE86, 0, 0), 'إ' to intArrayOf(0xFE87, 0xFE88, 0, 0), 'ئ' to intArrayOf(0xFE89, 0xFE8A, 0xFE8B, 0xFE8C),
        'ا' to intArrayOf(0xFE8D, 0xFE8E, 0, 0), 'ب' to intArrayOf(0xFE8F, 0xFE90, 0xFE91, 0xFE92), 'ة' to intArrayOf(0xFE93, 0xFE94, 0, 0),
        'ت' to intArrayOf(0xFE95, 0xFE96, 0xFE97, 0xFE98), 'ث' to intArrayOf(0xFE99, 0xFE9A, 0xFE9B, 0xFE9C), 'ج' to intArrayOf(0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0),
        'ح' to intArrayOf(0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4), 'خ' to intArrayOf(0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8), 'د' to intArrayOf(0xFEA9, 0xFEAA, 0, 0),
        'ذ' to intArrayOf(0xFEAB, 0xFEAC, 0, 0), 'ر' to intArrayOf(0xFEAD, 0xFEAE, 0, 0), 'ز' to intArrayOf(0xFEAF, 0xFEB0, 0, 0),
        'س' to intArrayOf(0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4), 'ش' to intArrayOf(0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8), 'ص' to intArrayOf(0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC),
        'ض' to intArrayOf(0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0), 'ط' to intArrayOf(0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4), 'ظ' to intArrayOf(0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8),
        'ع' to intArrayOf(0xFEC9, 0xFECA, 0xFECB, 0xFECC), 'غ' to intArrayOf(0xFECD, 0xFECE, 0xFECF, 0xFED0), 'ف' to intArrayOf(0xFED1, 0xFED2, 0xFED3, 0xFED4),
        'ق' to intArrayOf(0xFED5, 0xFED6, 0xFED7, 0xFED8), 'ك' to intArrayOf(0xFED9, 0xFEDA, 0xFEDB, 0xFEDC), 'ل' to intArrayOf(0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0),
        'م' to intArrayOf(0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4), 'ن' to intArrayOf(0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8), 'ه' to intArrayOf(0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC),
        'و' to intArrayOf(0xFEED, 0xFEEE, 0, 0), 'ى' to intArrayOf(0xFEEF, 0xFEF0, 0, 0), 'ي' to intArrayOf(0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4),
        'ک' to intArrayOf(0xFED9, 0xFEDA, 0xFEDB, 0xFEDC), 'ی' to intArrayOf(0xFEEF, 0xFEF0, 0xFEF3, 0xFEF4),
    )
    private val lamAlef = mapOf('آ' to intArrayOf(0xFEF5, 0xFEF6), 'أ' to intArrayOf(0xFEF7, 0xFEF8), 'إ' to intArrayOf(0xFEF9, 0xFEFA), 'ا' to intArrayOf(0xFEFB, 0xFEFC))
    private val transparent = setOf('ً', 'ٌ', 'ٍ', 'َ', 'ُ', 'ِ', 'ّ', 'ْ', 'ٰ', 'ـ')

    private fun isArabicLetter(c: Char) = forms.containsKey(c)
    private fun joinsLeft(c: Char) = forms[c]?.let { it[2] != 0 } ?: false   // can connect to the following letter
    private fun joinsRight(c: Char) = forms.containsKey(c)                    // every letter can receive a connection from the previous one

    fun isArabic(text: String) = text.any { it in '؀'..'ۿ' }

    /** Shape + reorder for visual rendering. Non-Arabic text is returned unchanged. */
    fun shape(text: String): String {
        if (!isArabic(text)) return text
        val chars = text.toCharArray()
        val out = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (!isArabicLetter(c)) { out.append(c); i++; continue }
            // previous joinable letter (skipping diacritics)
            var p = i - 1; while (p >= 0 && chars[p] in transparent) p--
            val prevJoins = p >= 0 && isArabicLetter(chars[p]) && joinsLeft(chars[p])
            // lam-alef ligature
            if (c == 'ل' && i + 1 < chars.size) {
                var n = i + 1; while (n < chars.size && chars[n] in transparent) n++
                if (n < chars.size && lamAlef.containsKey(chars[n])) {
                    val la = lamAlef.getValue(chars[n])
                    out.append((if (prevJoins) la[1] else la[0]).toChar())
                    for (k in i + 1 until n) out.append(chars[k])
                    i = n + 1; continue
                }
            }
            var n = i + 1; while (n < chars.size && chars[n] in transparent) n++
            val nextJoins = n < chars.size && isArabicLetter(chars[n]) && joinsRight(chars[n]) && joinsLeft(c)
            val f = forms.getValue(c)
            val code = when {
                prevJoins && nextJoins && f[3] != 0 -> f[3]
                prevJoins && f[1] != 0 -> f[1]
                nextJoins && f[2] != 0 -> f[2]
                else -> f[0]
            }
            out.append(code.toChar()); i++
        }
        return visualOrder(out.toString())
    }

    /** Reverse the string for RTL display while keeping LTR runs (Latin letters, digits) in their natural order. */
    private fun visualOrder(s: String): String {
        val runs = ArrayList<Pair<Boolean, StringBuilder>>()   // (isLtr, text)
        for (c in s) {
            val ltr = c.isLetterOrDigit() && !(c in '؀'..'ۿ' || c in 'ﹰ'..'﻿' || c in 'ݐ'..'ݿ')
            if (runs.isEmpty() || runs.last().first != ltr) runs += ltr to StringBuilder()
            runs.last().second.append(c)
        }
        val mirrored = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<')
        return runs.asReversed().joinToString("") { (ltr, sb) ->
            if (ltr) sb.toString() else sb.reversed().map { mirrored[it] ?: it }.joinToString("")
        }
    }
}

/**
 * Draw [shaped] (visual-order presentation forms) but tag it with the original logical [original] text as
 * /ActualText, so search, copy and screen readers get normal Arabic instead of reversed presentation forms.
 */
fun com.tom_roush.pdfbox.pdmodel.PDPageContentStream.showArabic(original: String, shaped: String) {
    if (!ArabicShaper.isArabic(original)) { showText(shaped); return }
    val props = com.tom_roush.pdfbox.cos.COSDictionary()
    props.setItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("ActualText"), com.tom_roush.pdfbox.cos.COSString(original))
    beginMarkedContent(com.tom_roush.pdfbox.cos.COSName.getPDFName("Span"),
        com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(props))
    showText(shaped)
    endMarkedContent()
}
