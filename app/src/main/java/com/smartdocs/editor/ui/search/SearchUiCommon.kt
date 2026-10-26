package com.smartdocs.editor.ui.search

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.search.NormOptions
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.search.SearchMode
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import java.io.File

/** The document a search tool works on: file, open PDFium session and password prompt. */
class DocHolder(initial: File?) {
    var file by mutableStateOf(initial)
    var session by mutableStateOf<PdfSession?>(null)
    var password by mutableStateOf<String?>(null)
    var askPassword by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Bumped whenever a (re)opened session is ready. */
    var generation by mutableIntStateOf(0)
}

fun fileOf(uri: String?): File? = uri?.takeIf { it.isNotBlank() }?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }?.takeIf { it.exists() }

/** Opens [DocHolder.file] whenever it or the password changes; closes the session on dispose. */
@Composable
fun rememberDocHolder(uri: String?): DocHolder {
    val ctx = LocalContext.current
    val h = remember { DocHolder(fileOf(uri)) }
    DisposableEffect(Unit) { onDispose { h.session?.close() } }
    LaunchedEffect(h.file, h.password) {
        h.session?.closeAsync(); h.session = null; h.error = null
        val f = h.file ?: return@LaunchedEffect
        try { h.session = PdfSession.open(ctx, f, h.password); h.generation++ }
        catch (e: PdfSession.Companion.PasswordRequiredException) { h.askPassword = true }
        catch (e: Exception) { h.error = e.message ?: e.toString() }
    }
    if (h.askPassword) PasswordDialog(onDismiss = { h.askPassword = false }) { h.password = it; h.askPassword = false }
    return h
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.password_enter)) },
        text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation()) },
        confirmButton = { TextButton(onClick = { onOk(pw) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/**
 * Search options: mode (phrase / any / all words / regex), proximity for "all words", whole word, case,
 * light stemming and the stored Arabic switches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchOptionsPanel(options: SearchOptions, onChange: (SearchOptions) -> Unit, modifier: Modifier = Modifier, modes: List<SearchMode> = SearchMode.values().toList(), showStem: Boolean = true) {
    val ctx = LocalContext.current
    fun setNorm(n: NormOptions) { SearchPrefs.save(ctx, n); onChange(options.copy(norm = n)) }
    Column(modifier) {
        if (modes.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEach { m ->
                FilterChip(options.mode == m, { onChange(options.copy(mode = m)) }, label = { Text(modeLabel(m)) })
            }
        }
        if (options.mode == SearchMode.ALL_WORDS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.s5_proximity), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(if (options.proximity <= 0) stringResource(R.string.s5_proximity_any) else stringResource(R.string.s5_proximity_n, options.proximity), style = MaterialTheme.typography.labelMedium)
            }
            Slider(options.proximity.toFloat(), { onChange(options.copy(proximity = it.toInt())) }, valueRange = 0f..30f, steps = 29)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckRow(stringResource(R.string.whole_words), options.wholeWord) { onChange(options.copy(wholeWord = it)) }
            CheckRow(stringResource(R.string.case_sensitive), options.norm.matchCase) { onChange(options.copy(norm = options.norm.copy(matchCase = it))) }
            if (showStem && options.mode != SearchMode.REGEX) CheckRow(stringResource(R.string.s5_stem), options.stem) { onChange(options.copy(stem = it)) }
            CheckRow(stringResource(R.string.sr_ignore_diacritics), options.norm.ignoreDiacritics) { setNorm(options.norm.copy(ignoreDiacritics = it)) }
            CheckRow(stringResource(R.string.sr_unify_letters), options.norm.unifyLetters) { setNorm(options.norm.copy(unifyLetters = it)) }
            CheckRow(stringResource(R.string.sr_ta_marbuta), options.norm.taMarbuta) { setNorm(options.norm.copy(taMarbuta = it)) }
            CheckRow(stringResource(R.string.sr_unify_digits), options.norm.unifyDigits) { setNorm(options.norm.copy(unifyDigits = it)) }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
fun modeLabel(m: SearchMode): String = stringResource(when (m) {
    SearchMode.PHRASE -> R.string.s5_mode_phrase
    SearchMode.ANY_WORDS -> R.string.s5_mode_any
    SearchMode.ALL_WORDS -> R.string.s5_mode_all
    SearchMode.REGEX -> R.string.s5_mode_regex
})

@Composable
fun patternLabel(k: PatternKind): String = stringResource(when (k) {
    PatternKind.PHONE_YE -> R.string.s5_pat_phone_ye
    PatternKind.PHONE_SA -> R.string.s5_pat_phone_sa
    PatternKind.PHONE_GULF -> R.string.s5_pat_phone_gulf
    PatternKind.PHONE_EG -> R.string.s5_pat_phone_eg
    PatternKind.PHONE_INTL -> R.string.s5_pat_phone_intl
    PatternKind.EMAIL -> R.string.s5_pat_email
    PatternKind.URL -> R.string.s5_pat_url
    PatternKind.CARD -> R.string.s5_pat_card
    PatternKind.IBAN -> R.string.s5_pat_iban
    PatternKind.NATIONAL_ID -> R.string.s5_pat_id
    PatternKind.PASSPORT -> R.string.s5_pat_passport
    PatternKind.DATE -> R.string.s5_pat_date
    PatternKind.DATE_HIJRI -> R.string.s5_pat_hijri
    PatternKind.DATE_MONTH_NAME -> R.string.s5_pat_month
    PatternKind.AMOUNT -> R.string.s5_pat_amount
})

/** Chips for the pattern library. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternChips(selected: Set<PatternKind>, onChange: (Set<PatternKind>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.s5_patterns), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(if (selected.size == PatternKind.values().size) emptySet() else PatternKind.values().toSet()) }) {
                Text(stringResource(if (selected.size == PatternKind.values().size) R.string.s5_select_none else R.string.select_all))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PatternKind.values().forEach { k ->
                FilterChip(k in selected, { onChange(if (k in selected) selected - k else selected + k) }, label = { Text(patternLabel(k)) })
            }
        }
    }
}

/** [text] with [ranges] in bold on a tinted background. */
fun highlighted(text: String, ranges: List<IntRange>, bg: Color): AnnotatedString = buildAnnotatedString {
    var pos = 0
    for (r in ranges.sortedBy { it.first }) {
        val s = r.first.coerceIn(0, text.length); val e = (r.last + 1).coerceIn(0, text.length)
        if (s < pos || e <= s) continue
        append(text.substring(pos, s))
        pushStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold)); append(text.substring(s, e)); pop()
        pos = e
    }
    if (pos < text.length) append(text.substring(pos))
}

/** Colour choices shared by the search tools (0xRRGGBB). */
val MarkColors = listOf(0xFFEB3B, 0x8BC34A, 0x4FC3F7, 0xF48FB1, 0xFF9800, 0xE53935, 0x000000, 0xFFFFFF, 0x9E9E9E, 0x3F51B5)

@Composable
fun ColorRow(selected: Int, onSelect: (Int) -> Unit, colors: List<Int> = MarkColors, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { c ->
            val col = Color(0xFF000000 or c.toLong())
            Surface(
                onClick = { onSelect(c) },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = col,
                border = androidx.compose.foundation.BorderStroke(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.size(34.dp),
            ) {}
        }
    }
}
