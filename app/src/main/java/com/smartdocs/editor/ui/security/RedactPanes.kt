package com.smartdocs.editor.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.redact.RedactMark
import com.smartdocs.editor.core.redact.RedactMath
import com.smartdocs.editor.core.redact.RedactPrefs
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.search.SearchMode
import com.smartdocs.editor.core.search.TextMatcher
import com.smartdocs.editor.ui.search.HitList
import com.smartdocs.editor.ui.search.HitRow
import com.smartdocs.editor.ui.search.PatternChips
import com.smartdocs.editor.ui.search.SearchOptionsPanel
import com.smartdocs.editor.ui.search.patternLabel

/** Search & redact: query, word list, names, patterns, custom regex, keep-last-N, page range, profiles, hits. */
@Composable
fun RedactSearchPane(
    st: RedactState, canSearch: Boolean, onSearch: () -> Unit, onMark: () -> Unit, onBatch: () -> Unit, modifier: Modifier = Modifier,
    onPii: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var showMore by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(RedactPrefs.profiles(ctx)) }
    var profileMenu by remember { mutableStateOf(false) }
    var saveProfile by remember { mutableStateOf(false) }
    val badRegex = st.regexText.lines().any { it.isNotBlank() && !TextMatcher.isValidRegex(it.trim()) } ||
        (st.options.mode == SearchMode.REGEX && st.query.isNotBlank() && !TextMatcher.isValidRegex(st.query))
    val rows = st.found.map { f -> HitRow(foundKey(f), f.hit.page, f.hit.snippet, f.kind?.let { patternLabel(it) } ?: when (f.source) { "w" -> stringResource(R.string.s5_from_list); "re" -> "regex"; else -> "" }) }
    Column(modifier) {
        Column(Modifier.weight(1f, fill = false).heightIn(max = if (st.found.isEmpty()) 4000.dp else 330.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(st.query, { st.query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.pg_redact_search_hint)) },
                trailingIcon = { IconButton(onClick = onSearch, enabled = canSearch && !badRegex) { Icon(Icons.Default.Search, stringResource(R.string.search)) } })
            SearchOptionsPanel(st.options, { st.options = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showMore = !showMore }) {
                    Icon(if (showMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Text(stringResource(R.string.s5_more_inputs))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { profiles = RedactPrefs.profiles(ctx); profileMenu = true }) { Icon(Icons.Default.Bookmarks, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.s5_profiles)) }
                    DropdownMenu(profileMenu, { profileMenu = false }) {
                        profiles.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { st.loadProfile(p); profileMenu = false },
                                trailingIcon = { IconButton(onClick = { RedactPrefs.deleteProfile(ctx, p.name); profiles = RedactPrefs.profiles(ctx) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } })
                        }
                        if (profiles.isNotEmpty()) HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.s5_profile_save)) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { profileMenu = false; saveProfile = true })
                    }
                }
            }
            if (showMore) {
                OutlinedTextField(st.wordsText, { st.wordsText = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                    label = { Text(stringResource(R.string.s5_word_list)) }, supportingText = { Text(stringResource(R.string.s5_one_per_line)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(st.useNames, { st.useNames = it })
                    Text(stringResource(R.string.s5_names_list), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                if (st.useNames) OutlinedTextField(st.names, { st.names = it; st.saveNames() }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                    label = { Text(stringResource(R.string.s5_names)) })
                PatternChips(st.patterns, { st.patterns = it })
                OutlinedTextField(st.regexText, { st.regexText = it }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, isError = badRegex,
                    label = { Text(stringResource(R.string.s5_custom_regex)) }, supportingText = { Text(stringResource(if (badRegex) R.string.s5_bad_regex else R.string.s5_one_per_line)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.s5_keep_last), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(if (st.keepLast > 0) st.keepLast.toString() else "", { v -> st.keepLast = v.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0 },
                        Modifier.width(90.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), placeholder = { Text("0") })
                }
                OutlinedTextField(st.pageRange, { st.pageRange = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.pages_hint)) })
                TextButton(onClick = onBatch) { Icon(Icons.Default.LibraryBooks, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_batch)) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSearch, enabled = canSearch && !st.request().isEmpty && !badRegex && !st.searching, modifier = Modifier.weight(1f)) {
                    if (st.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.search))
                }
                OutlinedButton(onClick = { st.patterns = PatternKind.values().toSet(); onSearch() }, enabled = canSearch && !st.searching, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_auto_detect), maxLines = 1)
                }
            }
            if (onPii != null) OutlinedButton(onClick = onPii, enabled = canSearch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PersonSearch, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_pii_detect), maxLines = 1)
            }
            if (st.searched && st.found.isEmpty() && !st.searching) Text(stringResource(R.string.pg_redact_no_hits), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st.found.isNotEmpty()) {
            HitList(rows, st.checked, { id -> st.checked = if (id in st.checked) st.checked - id else st.checked + id },
                { all -> st.checked = if (all) rows.map { it.id }.toSet() else emptySet() }, Modifier.weight(1f))
            Button(onClick = onMark, enabled = st.checked.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Icon(Icons.Default.Rectangle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_mark_checked, st.checked.size))
            }
        }
    }
    if (saveProfile) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saveProfile = false }, title = { Text(stringResource(R.string.s5_profile_save)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.s5_profile_name)) }) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { RedactPrefs.saveProfile(ctx, st.profile(name.trim())); profiles = RedactPrefs.profiles(ctx) }; saveProfile = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { saveProfile = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Whole-page marks: a page range plus all / odd / even. */
@Composable
fun RedactPagesPane(st: RedactState, pageCount: Int, modifier: Modifier = Modifier) {
    var spec by remember { mutableStateOf("") }
    var parity by remember { mutableStateOf(RedactMath.PageParity.ALL) }
    val pages = RedactMath.pages(spec, pageCount, parity)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.s5_pages_note), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(spec, { spec = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.pages_hint)) })
        ParityRow(parity) { parity = it }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { st.addPageMarks(pages) }, enabled = pages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Article, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_mark_pages, pages.size))
        }
    }
}

/** Review list of every mark: swatch, overlay text, page; properties / repeat on pages / delete. */
@Composable
fun RedactReviewList(st: RedactState, onJump: (Int) -> Unit, onEdit: (RedactMark) -> Unit, onRepeat: (RedactMark) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.s5_marks_n, st.marks.size) + if (st.excluded.isNotEmpty()) " · " + stringResource(R.string.s5_marks_off, st.excluded.size) else "",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
        if (st.marks.isEmpty()) Text(stringResource(R.string.s5_no_marks), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(st.marks.sortedBy { it.page }, key = { it.id }) { m ->
                ListItem(
                    modifier = Modifier.clickable { onJump(m.page) },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(m.id !in st.excluded, { st.toggleIncluded(m.id) })
                        Box(Modifier.size(28.dp).background(Color(0xFF000000 or m.style.fill.toLong())).border(2.dp, Color(0xFF000000 or (m.style.outline ?: 0x9E9E9E).toLong())))
                        }
                    },
                    headlineContent = {
                        Text(when {
                            m.wholePage -> stringResource(R.string.s5_whole_page)
                            m.label.isNotBlank() -> m.label
                            else -> stringResource(R.string.s5_area)
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.pg_page_n, m.page + 1) + (if (m.style.overlay.isNotBlank()) " · «" + m.style.overlay + "»" else ""), style = MaterialTheme.typography.labelSmall)
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onEdit(m) }) { Icon(Icons.Default.Palette, stringResource(R.string.s5_properties)) }
                            if (!m.wholePage) IconButton(onClick = { onRepeat(m) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.s5_repeat_pages)) }
                            IconButton(onClick = { st.remove(m.id) }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                        }
                    },
                )
            }
        }
    }
}
