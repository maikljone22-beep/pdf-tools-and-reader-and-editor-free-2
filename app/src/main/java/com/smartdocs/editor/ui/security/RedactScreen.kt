package com.smartdocs.editor.ui.security

import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SearchHit
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.launch
import java.io.File

private enum class RedactMode { SEARCH, MANUAL }

/** Redaction tool: search & redact, or draw regions by hand; black boxes are burned in by [PdfOps.redact]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()

    var file by remember { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
    var session by remember { mutableStateOf<PdfSession?>(null) }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(RedactMode.SEARCH) }
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var regions by remember { mutableStateOf<List<RedactRegion>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    DisposableEffect(Unit) { onDispose { session?.close() } }
    LaunchedEffect(file, password) {
        session?.closeAsync(); session = null; hits = emptyList(); regions = emptyList(); page = 0
        val f = file ?: return@LaunchedEffect
        try { session = PdfSession.open(ctx, f, password) }
        catch (e: PdfSession.Companion.PasswordRequiredException) { askPassword = true }
        catch (e: Exception) { snack.showSnackbar(e.message ?: e.toString()) }
    }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> file = f } }

    fun hitId(h: SearchHit) = "s:${h.page}:${h.charIndex}:${h.length}"
    fun normalized(r: RectF) = RectF(minOf(r.left, r.right), maxOf(r.top, r.bottom), maxOf(r.left, r.right), minOf(r.top, r.bottom))
    fun toggleHit(h: SearchHit) {
        val id = hitId(h)
        regions = if (regions.any { it.id == id }) regions.filterNot { it.id == id || it.id.startsWith("$id:") }
        else regions + h.rects.mapIndexed { i, r -> RedactRegion(if (i == 0) id else "$id:$i", h.page, normalized(r), h.snippet) }
    }
    fun isSelected(h: SearchHit) = regions.any { it.id == hitId(h) }

    fun search() {
        val s = session ?: return
        scope.launch { searching = true; hits = runCatching { s.search(query, matchCase) }.getOrDefault(emptyList()); searching = false }
    }
    val applyingMsg = stringResource(R.string.pg_redact_applying)
    val noRegions = stringResource(R.string.pg_redact_no_regions)
    fun apply() {
        val src = file ?: return
        if (regions.isEmpty()) { scope.launch { snack.showSnackbar(noRegions) }; return }
        val map = regions.groupBy { it.page }.mapValues { (_, l) -> l.map { it.rect } }
        runner.run(applyingMsg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_redacted")
            PdfOps.redact(ctx, src, out, map, password)
            runCatching { app.documents.touchRecent(out) }
            out
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.pg_redact_title), onBack = { nav.popBackStack() }) {
            if (regions.isNotEmpty()) IconButton(onClick = { regions = emptyList() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.pg_redact_clear)) }
        } },
        bottomBar = {
            if (session != null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.redact_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.pg_redact_regions, regions.size) + " · " + stringResource(R.string.pg_redact_pages_affected, regions.map { it.page }.distinct().size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Button(onClick = { confirm = true }, enabled = regions.isNotEmpty() && !runner.running) { Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.redact_apply)) }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilePickRow(file, stringResource(R.string.open_file), pick)
                if (session != null) {
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(mode == RedactMode.SEARCH, { mode = RedactMode.SEARCH }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.redact_search)) }
                        SegmentedButton(mode == RedactMode.MANUAL, { mode = RedactMode.MANUAL }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.pg_redact_manual)) }
                    }
                }
            }
            val s = session
            if (s != null) BoxWithConstraints(Modifier.fillMaxSize()) {
                val tablet = maxWidth > 700.dp
                if (tablet) Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (mode == RedactMode.SEARCH) SearchPane(query, { query = it }, matchCase, { matchCase = it }, searching, hits, ::isSelected, ::toggleHit, ::search, onJump = { page = it })
                        else RedactManualView(s, page, { page = it.coerceIn(0, s.pageCount - 1) }, regions, onAdd = { r -> regions = regions + RedactRegion("m:${System.nanoTime()}", page, r, "") }, Modifier.fillMaxSize())
                    }
                    RegionList(regions, onRemove = { id -> regions = regions.filterNot { it.id == id } }, onJump = { page = it; mode = RedactMode.MANUAL }, Modifier.width(300.dp).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (mode == RedactMode.SEARCH) SearchPane(query, { query = it }, matchCase, { matchCase = it }, searching, hits, ::isSelected, ::toggleHit, ::search, onJump = { page = it })
                        else RedactManualView(s, page, { page = it.coerceIn(0, s.pageCount - 1) }, regions, onAdd = { r -> regions = regions + RedactRegion("m:${System.nanoTime()}", page, r, "") }, Modifier.fillMaxSize())
                    }
                    if (regions.isNotEmpty() && mode == RedactMode.MANUAL) RegionList(regions, onRemove = { id -> regions = regions.filterNot { it.id == id } }, onJump = { page = it }, Modifier.heightIn(max = 160.dp))
                }
            }
        }
    }

    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.redact_apply)) }, text = { Text(stringResource(R.string.redact_warning)) },
        confirmButton = { TextButton(onClick = { confirm = false; apply() }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
    if (askPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { askPassword = false }, title = { Text(stringResource(R.string.password_enter)) },
            text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.password)) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) },
            confirmButton = { TextButton(onClick = { password = pw; askPassword = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SearchPane(
    query: String, onQuery: (String) -> Unit, matchCase: Boolean, onMatchCase: (Boolean) -> Unit, searching: Boolean,
    hits: List<SearchHit>, isSelected: (SearchHit) -> Boolean, onToggle: (SearchHit) -> Unit, onSearch: () -> Unit, onJump: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.pg_redact_search_hint)) }, singleLine = true,
                trailingIcon = { IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) } })
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(matchCase, onMatchCase); Text(stringResource(R.string.case_sensitive), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else if (hits.isNotEmpty()) {
                Text(stringResource(R.string.pg_redact_hits, hits.size), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { hits.forEach { if (!isSelected(it)) onToggle(it) } }) { Text(stringResource(R.string.select_all)) }
            }
        }
        if (hits.isEmpty() && query.isNotBlank() && !searching) Text(stringResource(R.string.pg_redact_no_hits), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits, key = { "${it.page}:${it.charIndex}:${it.length}" }) { h ->
                val sel = isSelected(h)
                ListItem(
                    modifier = Modifier.clickable { onToggle(h) },
                    leadingContent = { Checkbox(sel, { onToggle(h) }) },
                    headlineContent = { Text(h.snippet, maxLines = 2) },
                    supportingContent = { Text(stringResource(R.string.pg_page_n, h.page + 1), style = MaterialTheme.typography.labelSmall) },
                    trailingContent = { IconButton(onClick = { onJump(h.page) }) { Icon(Icons.Default.Visibility, null) } },
                )
            }
        }
    }
}

@Composable
private fun RegionList(regions: List<RedactRegion>, onRemove: (String) -> Unit, onJump: (Int) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.pg_redact_regions, regions.size), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
        if (regions.isEmpty()) Text(stringResource(R.string.pg_redact_no_regions), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(regions, key = { it.id }) { r ->
                ListItem(
                    modifier = Modifier.clickable { onJump(r.page) },
                    headlineContent = { Text(r.label.ifBlank { stringResource(R.string.pg_redact_region_n, regions.indexOf(r) + 1, r.page + 1) }, maxLines = 1) },
                    supportingContent = { Text(stringResource(R.string.pg_page_n, r.page + 1) + " · %.0f×%.0f pt".format(r.rect.width(), r.rect.top - r.rect.bottom), style = MaterialTheme.typography.labelSmall) },
                    trailingContent = { IconButton(onClick = { onRemove(r.id) }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) } },
                )
            }
        }
    }
}
