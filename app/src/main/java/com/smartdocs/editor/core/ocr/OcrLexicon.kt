package com.smartdocs.editor.core.ocr

/**
 * Known Arabic words for choosing between two OCR readings ([OcrEnsemble]). The list is the app's own chemistry /
 * school glossary (assets/ocr/glossary_ar.txt, written for SmartDocs: units, reactions, elements, question words, common
 * function words) plus words the caller adds (for example the document's own repaired text layer, [addText]).
 * A word is known with or without the article «ال» or one clitic (و ب ل ف ك). Harakat and tatweel are ignored.
 */
class OcrLexicon(words: Collection<String> = emptyList()) {
    private val set = HashSet<String>()

    init { words.forEach { add(it) } }

    fun add(word: String) { val w = norm(word); if (w.length >= 1) set += w }

    /** Every Arabic word of [text] (for example a page's text layer). */
    fun addText(text: String) { arabicWords(text).forEach { set += it } }

    val size: Int get() = set.size

    fun known(word: String): Boolean {
        val w = norm(word)
        if (w.isEmpty()) return false
        if (w in set) return true
        if (w.length > 3 && w.startsWith("ال") && w.substring(2) in set) return true
        return w.length > 2 && w[0] in "وبلفك" && w.substring(1) in set
    }

    companion object {
        const val GLOSSARY_ASSET = "ocr/glossary_ar.txt"
        private val WORD = Regex("[ء-ي]+")

        fun norm(s: String): String = s.filter { it.code != 0x0640 && it.code !in 0x064B..0x0652 && it.code != 0x0670 }

        /** The Arabic words of [s] (harakat and tatweel removed first). */
        fun arabicWords(s: String): List<String> = WORD.findAll(norm(s)).map { it.value }.toList()

        /** A lexicon of the whitespace-separated words of [text] (the glossary asset). */
        fun fromText(text: String): OcrLexicon = OcrLexicon(text.split(Regex("\\s+")).filter { it.isNotBlank() })
    }
}
