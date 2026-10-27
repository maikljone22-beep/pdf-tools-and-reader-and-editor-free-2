package com.smartdocs.editor.core.esign

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/*
 * "Send in bulk": one template document sent as a separate request to every row of a recipient list.
 * Pure Kotlin (no android.*) so the CSV rules, placeholders and the send loop run in plain JVM unit tests.
 */

/** One row of the list. [extra] = the other columns, keyed by header (or by 1-based column number without a header). */
data class BulkRecipient(val name: String, val email: String = "", val extra: Map<String, String> = emptyMap(), val line: Int = 0)

object CsvRecipients {
    const val MAX_ROWS = 500

    enum class Issue { MISSING_NAME, BAD_EMAIL, DUPLICATE, TOO_MANY }

    data class RowIssue(val line: Int, val issue: Issue, val text: String)

    data class Result(val rows: List<BulkRecipient>, val issues: List<RowIssue>, val headers: List<String>)

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val ANGLE = Regex("^(.*?)<([^<>]+)>\\s*$")
    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val NAME_KEYS = setOf("name", "full name", "fullname", "signer", "recipient", "الاسم", "اسم", "الاسم الكامل", "الموقع", "الموقّع")
    private val EMAIL_KEYS = setOf("email", "e-mail", "mail", "email address", "e-mail address", "البريد", "بريد", "البريد الإلكتروني", "البريد الالكتروني", "الإيميل", "الايميل", "ايميل")

    fun isEmail(s: String) = EMAIL.matches(s)

    /** Bytes of an imported file -> text: UTF-8 (with or without BOM), UTF-16 with BOM, else Windows-1256 (Arabic Excel). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (e: CharacterCodingException) {
            val cs = runCatching { Charset.forName("windows-1256") }.getOrDefault(Charsets.ISO_8859_1)
            String(bytes, start, bytes.size - start, cs)
        }
    }

    /** Delimiter of the first non-empty line: comma, semicolon, tab or Arabic comma (outside quotes), comma by default. */
    fun delimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0, '،' to 0)
        var q = false
        for (c in first) { if (c == '"') q = !q else if (!q && c in counts) counts[c] = counts.getValue(c) + 1 }
        return counts.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key ?: ','
    }

    /** RFC 4180 records: quoted fields may contain the delimiter, "" and line breaks. Returns (first line number, cells). */
    fun records(text: String, delim: Char = delimiter(text)): List<Pair<Int, List<String>>> {
        val out = mutableListOf<Pair<Int, List<String>>>()
        val s = text.removePrefix("﻿")
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false; var wasQuoted = false
        var line = 1; var recLine = 1
        var i = 0
        fun endCell() { cells += if (wasQuoted) cell.toString() else cell.toString().trim(); cell.setLength(0); wasQuoted = false }
        fun endRecord() { endCell(); if (cells.any { it.isNotBlank() }) out += recLine to cells; cells = mutableListOf() }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> { if (c == '\n') line++; cell.append(c) }
                }
            } else when (c) {
                '"' -> if (cell.isBlank()) { cell.setLength(0); quoted = true; wasQuoted = true } else cell.append(c)
                delim -> endCell()
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    endRecord(); line++; recLine = line
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || cells.isNotEmpty() || wasQuoted) endRecord()
        return out
    }

    private fun clean(s: String) = s.replace(BIDI, "").trim()
    private fun key(s: String) = clean(s).lowercase().replace('_', ' ').replace('-', ' ').let { if (it == "e mail" || it == "e mail address") it.replace(' ', '-') else it }

    /** Parses a pasted list or CSV file: `name,email[,custom…]` with an optional header row; also "Name <mail@x>" or bare e-mails. */
    fun parse(text: String): Result {
        val recs = records(text)
        if (recs.isEmpty()) return Result(emptyList(), emptyList(), emptyList())
        val head = recs.first().second.map(::key)
        val hasHeader = head.any { it in NAME_KEYS || it in EMAIL_KEYS }
        val headers = if (hasHeader) recs.first().second.map(::clean) else emptyList()
        val nameCol = if (hasHeader) head.indexOfFirst { it in NAME_KEYS } else -1
        val emailCol = if (hasHeader) head.indexOfFirst { it in EMAIL_KEYS } else -1
        val rows = mutableListOf<BulkRecipient>()
        val issues = mutableListOf<RowIssue>()
        val seen = HashSet<String>()
        for ((line, raw) in if (hasHeader) recs.drop(1) else recs) {
            val cells = raw.map(::clean)
            var name: String; var email: String
            val extra = LinkedHashMap<String, String>()
            if (hasHeader) {
                name = cells.getOrNull(nameCol).orEmpty(); email = cells.getOrNull(emailCol).orEmpty()
                cells.forEachIndexed { i, v -> if (i != nameCol && i != emailCol) extra[headers.getOrNull(i)?.ifBlank { null } ?: (i + 1).toString()] = v }
            } else {
                name = cells.getOrNull(0).orEmpty(); email = cells.getOrNull(1).orEmpty()
                if (isEmail(name) && !isEmail(email)) { val t = name; name = email; email = t }
                cells.drop(2).forEachIndexed { i, v -> extra[(i + 3).toString()] = v }
            }
            ANGLE.find(name)?.takeIf { email.isBlank() }?.let { m -> name = clean(m.groupValues[1]).trim('"', ' '); email = clean(m.groupValues[2]) }
            if (name.isBlank() && isEmail(email)) name = email.substringBefore('@')
            val label = listOf(name, email).filter { it.isNotBlank() }.joinToString(" ")
            when {
                name.isBlank() -> issues += RowIssue(line, Issue.MISSING_NAME, raw.joinToString(", "))
                email.isNotBlank() && !isEmail(email) -> issues += RowIssue(line, Issue.BAD_EMAIL, label)
                email.isNotBlank() && !seen.add(email.lowercase()) -> issues += RowIssue(line, Issue.DUPLICATE, label)
                rows.size >= MAX_ROWS -> issues += RowIssue(line, Issue.TOO_MANY, label)
                else -> rows += BulkRecipient(name.take(80), email.take(120), extra, line)
            }
        }
        return Result(rows, issues, headers)
    }
}

/** `{name}`, `{email}` and `{<column>}` placeholders in the title / message, filled per recipient. Unknown ones stay as typed. */
object BulkTemplate {
    private val PH = Regex("\\{([^{}\\n]{1,40})\\}")
    private val NAME = setOf("name", "الاسم", "اسم")
    private val EMAIL = setOf("email", "e-mail", "البريد", "بريد")

    fun fill(template: String, r: BulkRecipient): String = PH.replace(template) { m ->
        val k = m.groupValues[1].trim()
        val lk = k.lowercase()
        when {
            lk in NAME -> r.name
            lk in EMAIL -> r.email
            else -> r.extra.entries.firstOrNull { it.key.equals(k, true) }?.value ?: m.value
        }
    }

    /** Placeholders the user can use with this list (shown as hints). */
    fun available(headers: List<String>, sample: BulkRecipient?): List<String> =
        listOf("{name}", "{email}") + (sample?.extra?.keys ?: headers).map { "{$it}" }
}

enum class BulkState { PENDING, SENT, FAILED, CANCELLED }

data class BulkItem(val row: BulkRecipient, val state: BulkState = BulkState.PENDING, val requestId: String = "", val link: String = "", val error: String = "")

/** Sends one request per item; failed and cancelled items are kept and only those are sent again on the next [run]. */
object BulkSender {
    data class Sent(val id: String, val link: String)

    /**
     * Sends every item not yet [BulkState.SENT]. [send] creates one request (blocking); [retryable] says whether a failure is
     * worth a second immediate attempt (network errors, 5xx). [cancelled] is checked before each item: once it is true the
     * request in flight finishes, items already sent stay sent and every remaining one becomes [BulkState.CANCELLED].
     * Coroutine cancellation exceptions propagate.
     */
    fun run(
        items: List<BulkItem>, send: (BulkRecipient) -> Sent, progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        retryable: (Throwable) -> Boolean = { false }, attempts: Int = 2, cancelled: () -> Boolean = { false },
    ): List<BulkItem> {
        val out = items.toMutableList()
        val todo = items.indices.filter { items[it].state != BulkState.SENT }
        progress(0, todo.size)
        todo.forEachIndexed { n, i ->
            if (cancelled()) { out[i] = items[i].copy(state = BulkState.CANCELLED, error = ""); return@forEachIndexed }
            var last: Throwable? = null
            var result: Sent? = null
            for (a in 1..attempts.coerceAtLeast(1)) {
                try { result = send(items[i].row); break } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException || e is InterruptedException) throw e
                    last = e
                    if (!retryable(e)) break
                }
            }
            out[i] = result?.let { items[i].copy(state = BulkState.SENT, requestId = it.id, link = it.link, error = "") }
                ?: items[i].copy(state = BulkState.FAILED, error = last?.message ?: last?.toString().orEmpty())
            progress(n + 1, todo.size)
        }
        return out
    }

    fun sent(items: List<BulkItem>) = items.count { it.state == BulkState.SENT }
    fun failed(items: List<BulkItem>) = items.count { it.state == BulkState.FAILED }
    fun cancelled(items: List<BulkItem>) = items.count { it.state == BulkState.CANCELLED }

    /** Items a "retry" sends again: failures and the ones a cancel skipped. */
    fun retryable(items: List<BulkItem>) = items.count { it.state == BulkState.FAILED || it.state == BulkState.CANCELLED }
}
