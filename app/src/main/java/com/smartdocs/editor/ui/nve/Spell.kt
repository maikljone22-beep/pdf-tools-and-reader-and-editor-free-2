package com.smartdocs.editor.ui.nve

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One misspelt word of a checked text: [start]..[end) in that text, with the checker's suggestions. */
data class SpellIssue(val start: Int, val end: Int, val word: String, val suggestions: List<String>)

/**
 * Spell checking through the system spell checker service (android.view.textservice): sentence-level suggestions
 * first, per-word suggestions as a fallback. Mixed Arabic / Latin text is checked with an Arabic session for the
 * Arabic words and the user's default spell checker language for the rest. Reusable from any text field.
 */
object NveSpell {
    /** False after a check found no spell checker service on the device (the UI says so). */
    @Volatile var available: Boolean = true
        private set

    private const val TIMEOUT_MS = 3000L
    private val WORD = Regex("[\\p{L}\\p{M}']+")

    private fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    suspend fun check(context: Context, text: String, locale: Locale? = null): List<SpellIssue> {
        if (text.isBlank()) return emptyList()
        val hasAr = text.any { isArabic(it) }
        val hasLat = text.any { it.isLetter() && !isArabic(it) }
        if (locale != null) return session(context, text, locale, false) ?: emptyList()
        val out = ArrayList<SpellIssue>()
        if (hasLat || !hasAr) {
            session(context, text, null, true)?.let { l -> out += l.filter { i -> i.word.none { isArabic(it) } } }
        }
        if (hasAr) {
            session(context, text, Locale("ar"), false)?.let { l -> out += l.filter { i -> i.word.any { isArabic(it) } } }
        }
        return out.distinctBy { it.start }.sortedBy { it.start }
    }

    /** One spell checker session on [text]; null when no service is available or it timed out. */
    private suspend fun session(context: Context, text: String, locale: Locale?, referToSettings: Boolean): List<SpellIssue>? = withContext(Dispatchers.Main) {
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
        if (tsm == null) { available = false; return@withContext null }
        var result: List<SpellIssue>? = null
        var session: SpellCheckerSession? = null
        try {
            result = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<List<SpellIssue>?> { cont ->
                    var wordMode = false
                    val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                            if (!cont.isActive) return
                            cont.resume(fromWords(text, results))
                        }
                        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                            if (!cont.isActive) return
                            val issues = fromSentences(text, results)
                            val gotAny = results?.any { it != null && it.suggestionsCount > 0 } == true
                            if (!gotAny && !wordMode) {
                                // checkers that do not implement sentence suggestions: ask word by word
                                wordMode = true
                                val words = WORD.findAll(text).map { TextInfo(it.value) }.toList()
                                if (words.isEmpty()) { cont.resume(emptyList()); return }
                                @Suppress("DEPRECATION")
                                runCatching { session?.getSuggestions(words.toTypedArray(), 5, true) }.onFailure { cont.resume(issues) }
                            } else cont.resume(issues)
                        }
                    }
                    @Suppress("DEPRECATION")
                    val s = runCatching { tsm.newSpellCheckerSession(null, locale, listener, referToSettings) }.getOrNull()
                    session = s
                    if (s == null) { available = false; cont.resume(null); return@suspendCancellableCoroutine }
                    available = true
                    runCatching { s.getSentenceSuggestions(arrayOf(TextInfo(text)), 5) }.onFailure { cont.resume(null) }
                }
            }
        } finally { runCatching { session?.close() } }
        result
    }

    private fun typo(si: SuggestionsInfo): Boolean {
        val a = si.suggestionsAttributes
        return (a and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0 &&
            ((a and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0 || si.suggestionsCount > 0)
    }

    private fun suggestions(si: SuggestionsInfo): List<String> = (0 until si.suggestionsCount.coerceAtLeast(0)).mapNotNull { si.getSuggestionAt(it) }

    private fun fromSentences(text: String, results: Array<out SentenceSuggestionsInfo>?): List<SpellIssue> {
        val out = ArrayList<SpellIssue>()
        results?.filterNotNull()?.forEach { ssi ->
            for (i in 0 until ssi.suggestionsCount) {
                val si = ssi.getSuggestionsInfoAt(i) ?: continue
                val off = ssi.getOffsetAt(i); val len = ssi.getLengthAt(i)
                if (off < 0 || len <= 0 || off + len > text.length) continue
                if (typo(si)) out += SpellIssue(off, off + len, text.substring(off, off + len), suggestions(si))
            }
        }
        return out
    }

    private fun fromWords(text: String, results: Array<out SuggestionsInfo>?): List<SpellIssue> {
        val words = WORD.findAll(text).toList()
        val out = ArrayList<SpellIssue>()
        results?.forEachIndexed { i, si ->
            val m = words.getOrNull(i) ?: return@forEachIndexed
            if (si != null && typo(si)) out += SpellIssue(m.range.first, m.range.last + 1, m.value, suggestions(si))
        }
        return out
    }

    /** [text] with [issue] replaced by [by]. */
    fun replace(text: String, issue: SpellIssue, by: String): String =
        if (issue.end <= text.length && text.substring(issue.start, issue.end) == issue.word) text.substring(0, issue.start) + by + text.substring(issue.end) else text
}

/** Underlines the misspelt words (red) of a text field; offsets are unchanged. */
fun spellTransformation(issues: List<SpellIssue>): VisualTransformation = VisualTransformation { s ->
    if (issues.isEmpty()) return@VisualTransformation TransformedText(s, OffsetMapping.Identity)
    val b = AnnotatedString.Builder(s)
    issues.forEach { i -> if (i.end <= s.length) b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = Color(0xFFD32F2F)), i.start, i.end) }
    TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
}

/**
 * Spell-check button for any text field: checks [text], then steps through the misspelt words with the
 * checker's suggestions (replace, replace all, ignore, ignore all). [onFixed] receives the corrected text.
 */
@Composable
fun SpellCheckButton(text: String, onFixed: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SpellRun?>(null) }
    var info by remember { mutableStateOf<Int?>(null) }
    IconButton(onClick = {
        if (running) return@IconButton
        running = true
        scope.launch {
            val issues = runCatching { NveSpell.check(ctx, text) }.getOrDefault(emptyList())
            running = false
            when {
                !NveSpell.available -> info = R.string.nt_spell_unavailable
                issues.isEmpty() -> info = R.string.nt_spell_none
                else -> session = SpellRun(text, issues)
            }
        }
    }, modifier = modifier) {
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Spellcheck, stringResource(R.string.nt_spell_check))
    }
    info?.let { m ->
        AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.ok)) } },
            title = { Text(stringResource(R.string.nt_spell_check)) }, text = { Text(stringResource(m)) })
    }
    session?.let { run ->
        SpellDialog(run, onDone = { fixed -> session = null; if (fixed != text) onFixed(fixed) })
    }
}

/** Working state of one spell-check pass. */
private class SpellRun(text: String, issues: List<SpellIssue>) {
    var text by mutableStateOf(text)
    var issues by mutableStateOf(issues)
    var index by mutableIntStateOf(0)
    val current: SpellIssue? get() = issues.getOrNull(index)

    private fun shiftAfter(at: Int, delta: Int) {
        issues = issues.map { if (it.start > at) it.copy(start = it.start + delta, end = it.end + delta) else it }
    }

    fun replace(by: String) {
        val i = current ?: return
        val t = NveSpell.replace(text, i, by)
        if (t != text) { text = t; shiftAfter(i.start, by.length - i.word.length) }
        index++
    }

    fun replaceAll(by: String) {
        val w = current?.word ?: return
        var i = index
        while (i < issues.size) {
            val cur = issues[i]
            if (cur.word == w) {
                val t = NveSpell.replace(text, cur, by)
                if (t != text) { text = t; shiftAfter(cur.start, by.length - cur.word.length) }
                issues = issues.toMutableList().also { it.removeAt(i) }
            } else i++
        }
    }

    fun ignore() { index++ }
    fun ignoreAll() { val w = current?.word ?: return; issues = issues.filterIndexed { k, it -> k < index || it.word != w } }
}

@Composable
private fun SpellDialog(run: SpellRun, onDone: (String) -> Unit) {
    val cur = run.current
    if (cur == null) { LaunchedEffect(Unit) { onDone(run.text) }; return }
    var custom by remember(cur) { mutableStateOf(cur.suggestions.firstOrNull() ?: cur.word) }
    AlertDialog(
        onDismissRequest = { onDone(run.text) },
        title = { Text(stringResource(R.string.nt_spell_check)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nt_spell_progress, run.index + 1, run.issues.size), style = MaterialTheme.typography.labelMedium)
                // the word in its context
                val ctxStart = (cur.start - 30).coerceAtLeast(0); val ctxEnd = (cur.end + 30).coerceAtMost(run.text.length)
                Text(buildAnnotatedString {
                    append(run.text.substring(ctxStart, cur.start.coerceAtMost(run.text.length)))
                    pushStyle(SpanStyle(color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline))
                    append(cur.word); pop()
                    if (cur.end <= ctxEnd) append(run.text.substring(cur.end, ctxEnd))
                }, style = MaterialTheme.typography.bodyMedium)
                if (cur.suggestions.isEmpty()) Text(stringResource(R.string.nt_spell_no_suggestions), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cur.suggestions.forEach { s -> SuggestionChip(onClick = { custom = s }, label = { Text(s) }) }
                }
                OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text(stringResource(R.string.nt_spell_replace_with)) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { run.ignore() }) { Text(stringResource(R.string.nt_spell_ignore)) }
                    TextButton(onClick = { run.ignoreAll() }) { Text(stringResource(R.string.nt_spell_ignore_all)) }
                    TextButton(onClick = { run.replaceAll(custom) }) { Text(stringResource(R.string.nt_spell_replace_all)) }
                }
            }
        },
        confirmButton = { Button(onClick = { run.replace(custom) }) { Text(stringResource(R.string.nt_spell_replace)) } },
        dismissButton = { TextButton(onClick = { onDone(run.text) }) { Text(stringResource(R.string.done)) } },
    )
}
