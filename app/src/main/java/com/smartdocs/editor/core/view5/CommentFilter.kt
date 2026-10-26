package com.smartdocs.editor.core.view5

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A comment as the comment manager sees it (pure data; built from core/annot CommentItem or pending
 * annotations). [status] is the review state name ("None", "Accepted"…), [checked] the private
 * /StateModel Marked checkmark.
 */
data class CRow(
    val page: Int,
    val index: Int,
    val type: String,          // PDF subtype: Highlight, Text, FreeText, Line…
    val author: String,
    val contents: String,
    val date: Long,
    val status: String = "None",
    val replies: List<String> = emptyList(),
    val color: Int? = null,
    val checked: Boolean = false,
    val markedText: String = "",
    val pending: Boolean = false,
)

data class CFilter(
    val query: String = "",
    val authors: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val pages: Set<Int> = emptySet(),
    val checked: Boolean? = null,
) {
    val active: Boolean get() = query.isNotBlank() || authors.isNotEmpty() || types.isNotEmpty() || statuses.isNotEmpty() || pages.isNotEmpty() || checked != null
}

enum class CSort { PAGE, DATE_NEW, DATE_OLD, AUTHOR, TYPE, STATUS }

object CommentFilter {

    /** Arabic-aware folding: lower case, no tashkeel / tatweel, alef / yaa / taa marbuta forms unified. */
    fun fold(s: String): String = buildString(s.length) {
        s.lowercase(Locale.ROOT).forEach { c ->
            when (c.code) {
                in 0x064B..0x065F, 0x0670, 0x0640 -> {}
                0x0622, 0x0623, 0x0625, 0x0671 -> append('ا')
                0x0649 -> append('ي')
                0x0629 -> append('ه')
                0x0624 -> append('و')
                0x0626 -> append('ي')
                else -> append(c)
            }
        }
    }

    fun matches(r: CRow, f: CFilter): Boolean {
        if (f.authors.isNotEmpty() && r.author !in f.authors) return false
        if (f.types.isNotEmpty() && r.type !in f.types) return false
        if (f.statuses.isNotEmpty() && r.status !in f.statuses) return false
        if (f.pages.isNotEmpty() && r.page !in f.pages) return false
        if (f.checked != null && r.checked != f.checked) return false
        if (f.query.isNotBlank()) {
            val q = fold(f.query.trim())
            val hay = fold(r.contents + "\n" + r.author + "\n" + r.markedText + "\n" + r.replies.joinToString("\n"))
            if (q.split(Regex("\\s+")).any { it.isNotEmpty() && !hay.contains(it) }) return false
        }
        return true
    }

    fun apply(rows: List<CRow>, f: CFilter, sort: CSort = CSort.PAGE): List<CRow> = sorted(rows.filter { matches(it, f) }, sort)

    fun sorted(rows: List<CRow>, sort: CSort): List<CRow> = when (sort) {
        CSort.PAGE -> rows.sortedWith(compareBy({ it.page }, { it.index }))
        CSort.DATE_NEW -> rows.sortedByDescending { it.date }
        CSort.DATE_OLD -> rows.sortedBy { it.date }
        CSort.AUTHOR -> rows.sortedWith(compareBy({ it.author.lowercase(Locale.ROOT) }, { it.page }))
        CSort.TYPE -> rows.sortedWith(compareBy({ it.type }, { it.page }))
        CSort.STATUS -> rows.sortedWith(compareBy({ it.status }, { it.page }))
    }

    /** Distinct values for the filter chips. */
    fun authors(rows: List<CRow>) = rows.map { it.author }.filter { it.isNotBlank() }.distinct().sorted()
    fun types(rows: List<CRow>) = rows.map { it.type }.distinct().sorted()
    fun statuses(rows: List<CRow>) = rows.map { it.status }.distinct().sorted()
    fun pages(rows: List<CRow>) = rows.map { it.page }.distinct().sorted()

    private fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun date(ms: Long) = if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    /** UTF-8 BOM CSV: page, type, author, date, status, checked, contents, marked text, replies. */
    fun csv(rows: List<CRow>, header: List<String>): String {
        val sb = StringBuilder("﻿").append(header.joinToString(",") { cell(it) }).append("\r\n")
        rows.forEach { r ->
            sb.append(listOf((r.page + 1).toString(), r.type, r.author, date(r.date), r.status, if (r.checked) "1" else "0", r.contents, r.markedText,
                r.replies.joinToString(" | ")).joinToString(",") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Plain-text summary grouped by page (used for "summary" export and share). */
    fun summary(rows: List<CRow>, title: String, pageLabel: (Int) -> String, typeLabel: (String) -> String): String {
        val sb = StringBuilder(title).append('\n').append("=".repeat(title.length.coerceIn(3, 60))).append("\n\n")
        rows.groupBy { it.page }.toSortedMap().forEach { (p, list) ->
            sb.append(pageLabel(p)).append('\n')
            list.forEach { r ->
                sb.append(" • [").append(typeLabel(r.type)).append("] ")
                if (r.author.isNotBlank()) sb.append(r.author).append(" — ")
                if (r.date > 0) sb.append(date(r.date)).append(" ")
                if (r.status != "None") sb.append("(").append(r.status).append(")")
                sb.append('\n')
                if (r.markedText.isNotBlank()) sb.append("   «").append(r.markedText.trim()).append("»\n")
                if (r.contents.isNotBlank()) sb.append("   ").append(r.contents.trim().replace("\n", "\n   ")).append('\n')
                r.replies.forEach { sb.append("   ↳ ").append(it).append('\n') }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Highlighted text only, grouped by page. */
    fun highlightedText(rows: List<CRow>, pageLabel: (Int) -> String): String {
        val sb = StringBuilder()
        rows.filter { it.markedText.isNotBlank() }.groupBy { it.page }.toSortedMap().forEach { (p, list) ->
            sb.append(pageLabel(p)).append('\n')
            list.forEach { sb.append("• ").append(it.markedText.trim()).append('\n') }
            sb.append('\n')
        }
        return sb.toString()
    }
}
