package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.PageSet
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Word count (Foxit "Word Count"): words, characters with and without spaces, lines, Asian (CJK) characters and
 * pages, for a whole PDF, a page range or a text selection. Asian characters are counted one each and also count
 * as words, as word processors do.
 */
data class WordCountResult(val words: Int, val chars: Int, val charsNoSpaces: Int, val lines: Int, val asian: Int, val pages: Int) {
    operator fun plus(o: WordCountResult) = WordCountResult(words + o.words, chars + o.chars, charsNoSpaces + o.charsNoSpaces, lines + o.lines, asian + o.asian, pages + o.pages)
}

object WordCount {
    fun isAsian(c: Int): Boolean = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0xF900..0xFAFF ||
        c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F

    /** Counts of [text] ([pages] is passed through: 0 for a selection). */
    fun count(text: String, pages: Int = 0): WordCountResult {
        var words = 0; var chars = 0; var noSpace = 0; var asian = 0
        var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            i += n
            if (cp == '\n'.code || cp == '\r'.code) { inWord = false; continue }
            chars++
            val space = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
            if (!space) noSpace++
            when {
                isAsian(cp) -> { asian++; words++; inWord = false }
                space -> inWord = false
                Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> { if (!inWord) { words++; inWord = true } }
                else -> {}   // punctuation joins the word it touches without starting one
            }
        }
        val lines = text.split('\n').count { it.isNotBlank() }
        return WordCountResult(words, chars, noSpace, lines, asian, pages)
    }

    /** Counts of the pages in [range] ("" = all) of [file]. */
    suspend fun ofPdf(file: File, password: String?, range: String = ""): WordCountResult = withContext(Dispatchers.IO) {
        (if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)).use { doc ->
            val pages = PageSet.parse(range, doc.numberOfPages)
            val st = PDFTextStripper().apply { sortByPosition = true }
            pages.fold(WordCountResult(0, 0, 0, 0, 0, 0)) { acc, p ->
                st.startPage = p + 1; st.endPage = p + 1
                acc + count(runCatching { st.getText(doc) }.getOrDefault(""), 1)
            }
        }
    }
}
