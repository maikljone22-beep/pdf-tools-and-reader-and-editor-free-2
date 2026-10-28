package com.smartdocs.editor.ui.nve.search

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.NveSearchCore
import com.smartdocs.editor.core.search.FullTextIndex
import com.smartdocs.editor.core.search.IndexWorker
import com.smartdocs.editor.core.search.PartKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.search.kindLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

// ============================================================ reader find bar

/** Reader find bar: chips "also search in Bookmarks / Comments / Form fields / Attachments" (persisted). */
@Composable
fun NveFindScopes(state: ReaderState) {
    val ctx = LocalContext.current
    var scopes by remember { mutableStateOf(NveFind.scopes(ctx)) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ns_find_also), style = MaterialTheme.typography.labelMedium)
        listOf(PartKind.BOOKMARK, PartKind.COMMENTS, PartKind.FIELD, PartKind.ATTACHMENT).forEach { k ->
            FilterChip(k in scopes, { scopes = if (k in scopes) scopes - k else scopes + k; NveFind.setScopes(ctx, scopes); NveFindBus.version++ },
                label = { Text(kindLabel(k)) }, modifier = Modifier.height(30.dp))
        }
    }
}

/** Bumped when the find scopes change, so [NveFindExtraResults] reruns. */
object NveFindBus { var version by mutableIntStateOf(0) }

/** Hits of the reader query in bookmarks / comments / fields / attachments of the open document (tap = jump). */
@Composable
fun NveFindExtraResults(state: ReaderState, query: String, onJump: (Int) -> Unit) {
    val ctx = LocalContext.current
    val ver = NveFindBus.version
    var hits by remember { mutableStateOf<List<NveFind.Hit>>(emptyList()) }
    val pending = state.annots.filter { it.text.isNotBlank() }.map { it.page to it.text }
    LaunchedEffect(query, ver, state.renderVersion, pending.size) {
        val f = state.file
        val scopes = NveFind.scopes(ctx)
        if (f == null || query.isBlank() || scopes.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        hits = runCatching { NveFind.extraHits(f, state.password, query, SearchOptions(norm = SearchPrefs.load(ctx, state.matchCase), wholeWord = state.wholeWords), scopes, pending) }.getOrDefault(emptyList())
    }
    if (hits.isEmpty()) return
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ns_find_extra_n, hits.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            items(hits) { h ->
                ListItem(
                    headlineContent = { Text(mark(h.snippet, h.range, bg), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                    supportingContent = { Text(kindLabel(h.kind) + if (h.page >= 0) " · " + stringResource(R.string.pg_page_n, h.page + 1) else "", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.clickable(enabled = h.page >= 0) { onJump(h.page) },
                )
            }
        }
    }
}

private fun mark(s: String, r: IntRange, bg: Color) = buildAnnotatedString {
    val a = r.first.coerceIn(0, s.length); val b = (r.last + 1).coerceIn(a, s.length)
    append(s.substring(0, a)); withStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold)) { append(s.substring(a, b)) }; append(s.substring(b))
}

private val HIT_COLORS = listOf(0xFFC107, 0xFFEB3B, 0x8BC34A, 0x00BCD4, 0x2196F3, 0xE91E63, 0xFF5722, 0x9C27B0)

/** "Highlight all text" properties: hit colour, current-hit colour, opacity. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NveHitStyleSettings(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var c by remember { mutableIntStateOf(NveHitStyle.color(ctx)) }
    var cur by remember { mutableIntStateOf(NveHitStyle.currentColor(ctx)) }
    var a by remember { mutableFloatStateOf(NveHitStyle.alpha(ctx)) }
    fun save() = NveHitStyle.save(ctx, c, cur, a)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ns_hit_color), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { HIT_COLORS.forEach { col -> Dot(col, col == c) { c = col; save() } } }
        Text(stringResource(R.string.ns_hit_current_color), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { HIT_COLORS.forEach { col -> Dot(col, col == cur) { cur = col; save() } } }
        Text(stringResource(R.string.ns_hit_opacity, (a * 100).toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(a, { a = it }, valueRange = 0.1f..0.9f, onValueChangeFinished = { save() })
    }
}

@Composable
private fun Dot(rgb: Int, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000 or rgb.toLong()))
        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape).clickable(onClick = onClick))
}

// ============================================================ global search extras

/** Where the global search looks. */
enum class NveScope { INDEX, OPEN_TABS, FOLDER }

/** Sort / filter / criteria / export state of the global search screen. */
class NveGlobalSearchState {
    var scope by mutableStateOf(NveScope.INDEX)
    var folder by mutableStateOf<android.net.Uri?>(null)
    var sort by mutableStateOf(NveSearchCore.Sort.RELEVANCE)
    var nameFilter by mutableStateOf("")
    var criteria by mutableStateOf(NveSearchCore.Criteria())
    var meta by mutableStateOf<Map<String, NveSearchCore.Meta>>(emptyMap())
    /** Content uri → local copy, for folder results. */
    var local by mutableStateOf<Map<String, java.io.File>>(emptyMap())
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var showCriteria by mutableStateOf(false)
    var showIndex by mutableStateOf(false)
}

/** Applies filter / criteria / sort to raw results (meta must be loaded for criteria and date sort). */
fun NveGlobalSearchState.view(raw: List<com.smartdocs.editor.core.search.FileResult>, kinds: Set<PartKind>): List<com.smartdocs.editor.core.search.FileResult> {
    val f = NveSearchCore.filter(raw, nameFilter, kinds).filter { NveSearchCore.matches(meta[it.path], criteria) }
    return NveSearchCore.sort(f, sort, meta)
}

/** Runs the search for the non-index scopes (open tabs / folder). */
suspend fun NveGlobalSearchState.searchScope(ctx: android.content.Context, query: String, options: SearchOptions, kinds: Set<PartKind>): List<com.smartdocs.editor.core.search.FileResult> {
    val files: List<Pair<String, java.io.File>> = when (scope) {
        NveScope.INDEX -> return emptyList()
        NveScope.OPEN_TABS -> com.smartdocs.editor.ui.reader.view.ReaderTabs.let { t -> t.load(ctx); t.tabs.toList() }
            .mapNotNull { tab -> android.net.Uri.parse(tab.uri).path?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { it.absolutePath to it } }
        NveScope.FOLDER -> {
            val tree = folder ?: return emptyList()
            val list = if (local.isNotEmpty() && local.values.all { it.exists() }) local.entries.map { it.key to it.value } else NveScopeSearch.folderFiles(ctx, tree)
            local = list.toMap(); list
        }
    }
    return NveScopeSearch.searchFiles(ctx, files, query, options, kinds, onProgress = { a, b -> progress = a to b }).also { progress = null }
}

/** Toolbar under the global search field: scope, sort, name filter, criteria, save CSV / PDF, index management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveGlobalSearchBar(st: NveGlobalSearchState, query: String, results: List<com.smartdocs.editor.core.search.FileResult>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; st.folder = u; st.local = emptyMap(); st.scope = NveScope.FOLDER }
    }
    val header = listOf(stringResource(R.string.ns_csv_query), stringResource(R.string.ns_csv_file), stringResource(R.string.ns_csv_path),
        stringResource(R.string.ns_csv_page), stringResource(R.string.ns_csv_where), stringResource(R.string.ns_csv_snippet))
    val kindNames = PartKind.entries.associateWith { kindLabel(it) }
    val title = stringResource(R.string.ns_report_title)
    val pageWord = stringResource(R.string.ns_page_word)
    val saved = stringResource(R.string.ns_saved)
    val failed = stringResource(R.string.ns_failed)
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        if (u != null) scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(u, "w")?.use { it.write(NveSearchCore.csv(query, results, header) { k -> kindNames[k] ?: k.name }.toByteArray(Charsets.UTF_8)) } } }.isSuccess
            android.widget.Toast.makeText(ctx, if (ok) saved else failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        if (u != null) scope.launch {
            val ok = runCatching {
                val tmp = java.io.File(ctx.cacheDir, "nve_search_report.pdf")
                NveSearchReport.write(ctx, tmp, title, query, results, pageWord) { k -> kindNames[k] ?: k.name }
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(u, "w")?.use { o -> tmp.inputStream().use { it.copyTo(o) } }; tmp.delete() }
            }.isSuccess
            android.widget.Toast.makeText(ctx, if (ok) saved else failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var sortMenu by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(st.scope == NveScope.INDEX, { st.scope = NveScope.INDEX }, label = { Text(stringResource(R.string.ns_scope_index)) }, leadingIcon = { Icon(Icons.Default.Storage, null, Modifier.size(16.dp)) })
            FilterChip(st.scope == NveScope.OPEN_TABS, { st.scope = NveScope.OPEN_TABS }, label = { Text(stringResource(R.string.ns_scope_tabs)) }, leadingIcon = { Icon(Icons.Default.Tab, null, Modifier.size(16.dp)) })
            FilterChip(st.scope == NveScope.FOLDER, { pickFolder.launch(st.folder) },
                label = { Text(st.folder?.let { NveIndexPrefs.treePath(it)?.substringAfterLast('/') } ?: stringResource(R.string.ns_scope_folder)) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp)) })
            VerticalDivider(Modifier.height(24.dp))
            Box {
                AssistChip({ sortMenu = true }, label = { Text(stringResource(sortLabel(st.sort))) }, leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)) })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    NveSearchCore.Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(stringResource(sortLabel(s))) }, onClick = { st.sort = s; sortMenu = false },
                        leadingIcon = { if (s == st.sort) Icon(Icons.Default.Check, null) }) }
                }
            }
            AssistChip({ st.showCriteria = true }, label = { Text(stringResource(R.string.ns_criteria)) },
                leadingIcon = { Icon(if (st.criteria.active) Icons.Default.FilterAlt else Icons.Default.FilterList, null, Modifier.size(16.dp)) })
            Box {
                AssistChip({ saveMenu = true }, enabled = results.isNotEmpty(), label = { Text(stringResource(R.string.ns_save_results)) }, leadingIcon = { Icon(Icons.Default.SaveAlt, null, Modifier.size(16.dp)) })
                DropdownMenu(saveMenu, { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("CSV") }, onClick = { saveMenu = false; saveCsv.launch("search_results.csv") }, leadingIcon = { Icon(Icons.Default.TableChart, null) })
                    DropdownMenuItem(text = { Text("PDF") }, onClick = { saveMenu = false; savePdf.launch("search_results.pdf") }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) })
                }
            }
            AssistChip({ st.showIndex = true }, label = { Text(stringResource(R.string.ns_index_manage)) }, leadingIcon = { Icon(Icons.Default.Build, null, Modifier.size(16.dp)) })
        }
        OutlinedTextField(st.nameFilter, { st.nameFilter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
            placeholder = { Text(stringResource(R.string.ns_filter_name)) }, leadingIcon = { Icon(Icons.Default.FilterList, null) },
            textStyle = MaterialTheme.typography.bodySmall)
        st.progress?.let { (a, b) -> LinearProgressIndicator(progress = { if (b > 0) a.toFloat() / b else 0f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
    }
    if (st.showCriteria) NveCriteriaSheet(st) { st.showCriteria = false }
    if (st.showIndex) NveIndexSheet { st.showIndex = false }
}

private fun sortLabel(s: NveSearchCore.Sort) = when (s) {
    NveSearchCore.Sort.RELEVANCE -> R.string.ns_sort_relevance
    NveSearchCore.Sort.NAME -> R.string.ns_sort_name
    NveSearchCore.Sort.DATE -> R.string.ns_sort_date
    NveSearchCore.Sort.LOCATION -> R.string.ns_sort_location
}

/** Additional criteria: author, title, subject, keywords, created / modified date range (yyyy-mm-dd). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveCriteriaSheet(st: NveGlobalSearchState, onDismiss: () -> Unit) {
    val c = st.criteria
    var title by remember { mutableStateOf(c.title) }; var author by remember { mutableStateOf(c.author) }
    var subject by remember { mutableStateOf(c.subject) }; var keywords by remember { mutableStateOf(c.keywords) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    fun d(v: Long?) = v?.let { fmt.format(java.util.Date(it)) } ?: ""
    var cf by remember { mutableStateOf(d(c.createdFrom)) }; var ct by remember { mutableStateOf(d(c.createdTo)) }
    var mf by remember { mutableStateOf(d(c.modifiedFrom)) }; var mt by remember { mutableStateOf(d(c.modifiedTo)) }
    fun parse(s: String, end: Boolean): Long? = runCatching { fmt.parse(s.trim())!!.time + if (end) 86_399_999L else 0L }.getOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ns_criteria), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ns_c_title)) }, singleLine = true)
            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ns_c_author)) }, singleLine = true)
            OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ns_c_subject)) }, singleLine = true)
            OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ns_c_keywords)) }, singleLine = true)
            Text(stringResource(R.string.ns_c_dates_hint), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cf, { cf = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ns_c_created_from)) }, singleLine = true)
                OutlinedTextField(ct, { ct = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ns_c_created_to)) }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(mf, { mf = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ns_c_modified_from)) }, singleLine = true)
                OutlinedTextField(mt, { mt = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ns_c_modified_to)) }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { st.criteria = NveSearchCore.Criteria(); onDismiss() }) { Text(stringResource(R.string.ns_clear)) }
                Button(onClick = {
                    st.criteria = NveSearchCore.Criteria(title, author, subject, keywords, parse(cf, false), parse(ct, true), parse(mf, false), parse(mt, true)); onDismiss()
                }) { Text(stringResource(R.string.ns_apply)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Index management: rebuild, purge, backup / restore, stop words, numbers, excluded folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveIndexSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var o by remember { mutableStateOf(NveIndexPrefs.load(ctx)) }
    var stops by remember { mutableStateOf(o.stopWords.joinToString(" ")) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }
    val done = stringResource(R.string.ns_saved); val failed = stringResource(R.string.ns_failed)
    fun persist(n: NveSearchCore.IndexOptions) { o = n; NveIndexPrefs.save(ctx, n) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u != null) scope.launch { msg = if (runCatching { NveIndexBackup.export(ctx, u) }.isSuccess) done else failed }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { msg = runCatching { NveIndexBackup.import(ctx, u) }.fold({ done }, { failed + ": " + (it.message ?: "") }) }
    }
    val excludeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        val p = u?.let { NveIndexPrefs.treePath(it) } ?: return@rememberLauncherForActivityResult
        persist(o.copy(excluded = (o.excluded + p).distinct()))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ns_index_manage), style = MaterialTheme.typography.titleLarge)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { IndexWorker.runNow(ctx, force = true); msg = ctx.getString(R.string.ns_rebuild_started) }) { Text(stringResource(R.string.ns_rebuild)) }
                OutlinedButton(onClick = { confirmPurge = true }) { Text(stringResource(R.string.ns_purge)) }
                OutlinedButton(onClick = { scope.launch { val n = withContext(Dispatchers.IO) { runCatching { FullTextIndex.get(ctx).prune() }.getOrDefault(0) }; msg = ctx.getString(R.string.ns_pruned, n) } }) { Text(stringResource(R.string.ns_prune)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("smartdocs_search_index.db") }) { Icon(Icons.Default.Upload, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ns_backup)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ns_restore)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.ns_stop_words), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(stops, { stops = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.ns_stop_words_hint)) }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(o.includeNumbers, { persist(o.copy(includeNumbers = it)) }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ns_include_numbers))
            }
            com.smartdocs.editor.ui.search.IndexExtrasSection()   // fc-sync: metadata, width, titles, size limit, log
            Text(stringResource(R.string.ns_excluded), style = MaterialTheme.typography.labelLarge)
            o.excluded.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { persist(o.copy(excluded = o.excluded - p)) }) { Icon(Icons.Default.Close, stringResource(R.string.ns_remove)) }
                }
            }
            TextButton(onClick = { excludeLauncher.launch(null) }) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ns_add_excluded)) }
            Text(stringResource(R.string.ns_options_apply_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.ns_embedded_index_na), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { persist(o.copy(stopWords = stops.split(Regex("[\\s,،]+")).filter { it.isNotBlank() }.toSet())); onDismiss() }) { Text(stringResource(R.string.ns_apply)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmPurge) AlertDialog(onDismissRequest = { confirmPurge = false },
        text = { Text(stringResource(R.string.ns_purge_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmPurge = false
            scope.launch { withContext(Dispatchers.IO) { runCatching { FullTextIndex.get(ctx).clear() } }; msg = done }
        }) { Text(stringResource(R.string.ns_purge)) } },
        dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text(stringResource(R.string.cancel)) } })
}
