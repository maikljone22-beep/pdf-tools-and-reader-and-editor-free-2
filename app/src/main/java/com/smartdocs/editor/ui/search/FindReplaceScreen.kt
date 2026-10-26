package com.smartdocs.editor.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.search.FindReplace
import com.smartdocs.editor.core.search.ReplaceMatch
import com.smartdocs.editor.core.search.SearchMode
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.core.search.TextMatcher
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.launch
import java.io.File

private fun matchId(m: ReplaceMatch) = "${m.page}:${m.blockId}"

/** Tool `find_replace`: find text in the page content and replace it (content-edit engine), into a new file. */
@Composable
fun FindReplaceScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val doc = rememberDocHolder(uri)
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(SearchOptions(norm = SearchPrefs.load(ctx))) }
    var range by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<ReplaceMatch>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searching by remember { mutableStateOf(false) }
    var searchedPage by remember { mutableIntStateOf(-1) }
    var searched by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var replacedCount by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> doc.file = f; matches = emptyList(); checked = emptySet(); searched = false } }
    val badRegex = options.mode == SearchMode.REGEX && find.isNotBlank() && !TextMatcher.isValidRegex(find)

    fun doFind() {
        val f = doc.file ?: return
        val s = doc.session ?: return
        if (find.isBlank() || badRegex) return
        scope.launch {
            searching = true
            val pages = PdfOps.parseRange(range, s.pageCount).ifEmpty { (0 until s.pageCount).toList() }
            matches = runCatching { FindReplace.find(f, doc.password, find, options, pages) { searchedPage = it } }.getOrDefault(emptyList())
            checked = matches.map(::matchId).toSet()
            searching = false; searched = true
        }
    }
    val msg = stringResource(R.string.s5_replacing)
    fun doReplace() {
        val src = doc.file ?: return
        val sel = matches.filter { matchId(it) in checked }
        if (sel.isEmpty()) return
        runner.run(msg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_replaced")
            replacedCount = FindReplace.apply(ctx, src, out, doc.password, sel, find, options, replace, app.documents.tempDir) { d, t -> runner.message = "$msg $d/$t" }
            runCatching { app.documents.touchRecent(out) }
            out
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }
    LaunchedEffect(doc.error) { doc.error?.let { snack.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.s5_replace_title), onBack = { nav.popBackStack() }) },
        bottomBar = {
            if (matches.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val n = matches.filter { matchId(it) in checked }.sumOf { it.ranges.size }
                    Text(stringResource(R.string.s5_n_occurrences, n), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { confirm = true }, enabled = n > 0 && !runner.running) { Icon(Icons.Default.FindReplace, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_replace_all)) }
                }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth > 700.dp
            val controls: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilePickRow(doc.file, stringResource(R.string.open_file), pick)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(find, { find = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.s5_find)) },
                        isError = badRegex, supportingText = if (badRegex) { { Text(stringResource(R.string.s5_bad_regex)) } } else null)
                    OutlinedTextField(replace, { replace = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.s5_replace_with)) })
                    SearchOptionsPanel(options, { options = it }, modes = listOf(SearchMode.PHRASE, SearchMode.REGEX), showStem = false)
                    PageRangeField(range) { range = it }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::doFind, enabled = doc.session != null && find.isNotBlank() && !badRegex && !searching, modifier = Modifier.fillMaxWidth()) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (searching && searchedPage >= 0) stringResource(R.string.pg_page_n, searchedPage + 1) else stringResource(R.string.s5_find))
                    }
                    Text(stringResource(R.string.s5_replace_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (searched && matches.isEmpty() && !searching) Text(stringResource(R.string.pg_redact_no_hits), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val bg = MaterialTheme.colorScheme.tertiaryContainer
            val list: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.s5_blocks, matches.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { checked = matches.map(::matchId).toSet() }) { Text(stringResource(R.string.select_all)) }
                        TextButton(onClick = { checked = emptySet() }) { Text(stringResource(R.string.s5_select_none)) }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(matches, key = { matchId(it) }) { mt ->
                            val id = matchId(mt)
                            ListItem(
                                modifier = Modifier.clickable { checked = if (id in checked) checked - id else checked + id },
                                leadingContent = { Checkbox(id in checked, { checked = if (id in checked) checked - id else checked + id }) },
                                headlineContent = { Text(highlighted(mt.text, mt.ranges, bg), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = {
                                    Column {
                                        Text(stringResource(R.string.pg_page_n, mt.page + 1) + " · " + stringResource(R.string.s5_n_occurrences, mt.ranges.size), style = MaterialTheme.typography.labelSmall)
                                        if (mt.fontWarning) Text(stringResource(R.string.s5_font_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        if (mt.lossy) Text(stringResource(R.string.s5_lossy_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (tablet) Row(Modifier.fillMaxSize()) { controls(Modifier.width(380.dp).fillMaxHeight()); list(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize()) {
                controls(Modifier.fillMaxWidth().heightIn(max = if (matches.isEmpty()) 2000.dp else 320.dp))
                if (matches.isNotEmpty()) list(Modifier.weight(1f))
            }
        }
    }
    if (confirm) {
        val n = matches.filter { matchId(it) in checked }.sumOf { it.ranges.size }
        val warn = matches.any { matchId(it) in checked && it.fontWarning }
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.s5_replace_all)) },
            text = { Text(stringResource(R.string.s5_replace_confirm, n, find, replace) + if (warn) "\n\n" + stringResource(R.string.s5_font_warning) else "") },
            confirmButton = { TextButton(onClick = { confirm = false; doReplace() }) { Text(stringResource(R.string.apply)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }, extraInfo = stringResource(R.string.s5_replaced_n, replacedCount)) }
}
