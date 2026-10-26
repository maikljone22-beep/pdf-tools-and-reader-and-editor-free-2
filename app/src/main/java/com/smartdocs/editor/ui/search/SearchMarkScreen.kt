package com.smartdocs.editor.ui.search

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.core.search.Found
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.core.search.SearchRequest
import com.smartdocs.editor.core.search.TextSearch
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private fun foundId(f: Found) = "${f.hit.page}:${f.hit.charIndex}:${f.hit.length}"

/** Tool `search_highlight`: find text or patterns, check hits, and mark them with real markup annotations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchMarkScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val doc = rememberDocHolder(uri)
    var query by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(SearchOptions(norm = SearchPrefs.load(ctx))) }
    var patterns by remember { mutableStateOf<Set<PatternKind>>(emptySet()) }
    var showPatterns by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(AnnotKind.HIGHLIGHT) }
    var color by remember { mutableIntStateOf(0xFFEB3B) }
    var result by remember { mutableStateOf<File?>(null) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> doc.file = f; found = emptyList(); checked = emptySet(); searched = false } }

    fun doSearch() {
        val s = doc.session ?: return
        scope.launch {
            searching = true
            found = runCatching { TextSearch.search(s, SearchRequest(query = query, options = options, patterns = patterns)) }.getOrDefault(emptyList())
            checked = found.map(::foundId).toSet()
            searching = false; searched = true
        }
    }
    val msg = stringResource(R.string.s5_marking)
    fun doApply() {
        val src = doc.file ?: return
        val sel = found.filter { foundId(it) in checked }
        if (sel.isEmpty()) return
        runner.run(msg) {
            val author = runCatching { app.settings.settings.first().authorName }.getOrDefault("")
            val annots = sel.map { f -> Annot(kind = kind, page = f.hit.page, color = color, opacity = if (kind == AnnotKind.HIGHLIGHT) 0.45f else 1f, rects = f.hit.rects, text = f.hit.snippet.trim(), author = author) }
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_marked")
            AnnotationWriter.commit(ctx, src, annots, out, doc.password).orThrow()
            runCatching { app.documents.touchRecent(out) }
            out
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }
    LaunchedEffect(doc.error) { doc.error?.let { snack.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.s5_mark_title), onBack = { nav.popBackStack() }) },
        bottomBar = {
            if (found.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val kinds = listOf(AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY)
                        kinds.forEachIndexed { i, k ->
                            SegmentedButton(kind == k, { kind = k }, SegmentedButtonDefaults.itemShape(i, kinds.size)) { Text(markupLabel(k), maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    ColorRow(color, { color = it })
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = ::doApply, enabled = checked.isNotEmpty() && !runner.running, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.BorderColor, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.s5_mark_apply, found.count { foundId(it) in checked }))
                    }
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
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.s5_query_hint)) },
                        trailingIcon = { IconButton(onClick = ::doSearch, enabled = doc.session != null) { Icon(Icons.Default.Search, stringResource(R.string.search)) } })
                    SearchOptionsPanel(options, { options = it })
                    TextButton(onClick = { showPatterns = !showPatterns }) {
                        Icon(if (showPatterns) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        Text(stringResource(R.string.s5_patterns) + if (patterns.isNotEmpty()) " (${patterns.size})" else "")
                    }
                    if (showPatterns) PatternChips(patterns, { patterns = it })
                    Button(onClick = ::doSearch, enabled = doc.session != null && (query.isNotBlank() || patterns.isNotEmpty()) && !searching, modifier = Modifier.fillMaxWidth()) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.search))
                    }
                    if (searched && found.isEmpty() && !searching) Text(stringResource(R.string.pg_redact_no_hits), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val rows = found.map { f -> HitRow(foundId(f), f.hit.page, f.hit.snippet, f.kind?.let { patternLabel(it) } ?: "") }
            val list: @Composable (Modifier) -> Unit = { m ->
                HitList(rows, checked, { id -> checked = if (id in checked) checked - id else checked + id }, { all -> checked = if (all) rows.map { it.id }.toSet() else emptySet() }, m)
            }
            if (tablet) Row(Modifier.fillMaxSize()) { controls(Modifier.width(380.dp).fillMaxHeight()); list(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize()) {
                controls(Modifier.fillMaxWidth().heightIn(max = if (found.isEmpty()) 2000.dp else 300.dp))
                if (found.isNotEmpty()) list(Modifier.weight(1f))
            }
        }
    }
    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
}

@Composable
fun markupLabel(k: AnnotKind): String = stringResource(when (k) {
    AnnotKind.UNDERLINE -> R.string.s5_underline
    AnnotKind.STRIKEOUT -> R.string.s5_strikeout
    AnnotKind.SQUIGGLY -> R.string.s5_squiggly
    else -> R.string.s5_highlight
})
