package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai5.Flashcard
import java.io.File

/**
 * ai6 exports: answers / whole chats to Word (AI-15) and flashcards to Anki (AI-19).
 * Pure text builders are unit tested; [writeDocx] uses the app's minimal DOCX writer.
 */
object ChatExport {

    /** One exported chat turn. */
    data class Turn(val user: Boolean, val text: String)

    /** Readable transcript: "س: …" / "ج: …" blocks (labels passed in so the UI language decides). */
    fun transcript(turns: List<Turn>, userLabel: String, aiLabel: String): String =
        turns.filter { it.text.isNotBlank() }.joinToString("\n\n") { t -> (if (t.user) userLabel else aiLabel) + ": " + t.text.trim() }

    /** Text split into DOCX "pages": one page (the writer adds a page break only between pages). */
    suspend fun writeDocx(text: String, out: File, title: String) {
        com.smartdocs.editor.core.pdf.DocxWriter.write(listOf(stripMarkdown(text)), out, title)
    }

    /** Light Markdown cleanup for Word: drops **bold** markers, heading hashes and code fences, keeps the content. */
    fun stripMarkdown(s: String): String = s.lines().joinToString("\n") { line ->
        var l = line
        if (l.trimStart().startsWith("```")) return@joinToString ""
        l = l.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
        l = l.replace("**", "").replace("__", "")
        l
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    // ---------------- Anki ----------------

    /** One field for Anki's text import: tabs/newlines become spaces / <br> (Anki's "Allow HTML" import). */
    fun ankiField(s: String): String = s.trim().replace("\t", " ").replace("\r\n", "\n").replace("\n", "<br>")

    /**
     * Anki text-import file (File → Import): header lines tell Anki the separator, that fields hold HTML and
     * which column carries the tags. Columns: front, back, tags.
     */
    fun anki(cards: List<Flashcard>, deck: String, tag: String = "SmartDocs"): String = buildString {
        append("#separator:tab\n")
        append("#html:true\n")
        append("#deck:").append(deck.replace('\n', ' ').trim().ifEmpty { "SmartDocs" }).append('\n')
        append("#tags column:3\n")
        val t = tag.trim().replace(Regex("\\s+"), "_")
        cards.forEach { c ->
            if (c.front.isBlank()) return@forEach
            val back = c.back + (c.page?.let { p -> "<br><small>p. $p</small>" } ?: "")
            append(ankiField(c.front)).append('\t').append(ankiField(back)).append('\t').append(t).append('\n')
        }
    }

    /** CSV for spreadsheets / other flashcard apps (RFC 4180 quoting). */
    fun csv(cards: List<Flashcard>): String = buildString {
        append("front,back,page\n")
        cards.forEach { c -> append(q(c.front)).append(',').append(q(c.back)).append(',').append(c.page?.toString() ?: "").append('\n') }
    }

    private fun q(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
