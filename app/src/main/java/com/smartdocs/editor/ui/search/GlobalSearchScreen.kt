package com.smartdocs.editor.ui.search

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.search.FileResult
import com.smartdocs.editor.core.search.FullTextIndex
import com.smartdocs.editor.core.search.IndexStats
import com.smartdocs.editor.core.search.IndexWorker
import com.smartdocs.editor.core.search.PartKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.nve.search.searchScope
import com.smartdocs.editor.ui.nve.search.view
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Tool `search_all`: search inside every indexed document (FTS index), grouped by file. */
@Composable
fun GlobalSearchScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val index = remember { FullTextIndex.get(ctx) }
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(SearchOptions(norm = SearchPrefs.load(ctx))) }
    var kinds by remember { mutableStateOf(PartKind.values().toSet()) }
    var showOptions by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FileResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<IndexStats?>(null) }
    val progress by FullTextIndex.progressFlow.collectAsState()
    val nve = remember { com.smartdocs.editor.ui.nve.search.NveGlobalSearchState() }   // NVE-search: scopes, sort, filter, criteria, export

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { runCatching { index.stats() }.getOrNull() }
        if ((stats?.docs ?: 0) == 0) IndexWorker.runNow(ctx)
    }
    LaunchedEffect(progress.running) { if (!progress.running) stats = withContext(Dispatchers.IO) { runCatching { index.stats() }.getOrNull() } }
    LaunchedEffect(query, options, kinds, progress.running, nve.scope, nve.folder) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        searching = true
        results = runCatching {
            if (nve.scope == com.smartdocs.editor.ui.nve.search.NveScope.INDEX) index.search(query, options, kinds)
            else nve.searchScope(ctx, query, options, kinds)
        }.getOrDefault(emptyList())
        searching = false
    }
    // NVE-search: metadata for "additional criteria" and the date sort
    LaunchedEffect(results, nve.sort, nve.criteria) {
        if (results.isNotEmpty() && (nve.criteria.active || nve.sort == com.smartdocs.editor.core.nve.NveSearchCore.Sort.DATE))
            nve.meta = com.smartdocs.editor.ui.nve.search.NveScopeSearch.metaOf(results.filter { it.path !in nve.meta }, nve.local) + nve.meta
    }
    val shown = remember(results, nve.sort, nve.nameFilter, nve.criteria, nve.meta, kinds) { nve.view(results, kinds) }

    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.s5_global_title), onBack = { nav.popBackStack() }) {
            IconButton(onClick = { showOptions = !showOptions }) { Icon(Icons.Default.Tune, stringResource(R.string.s5_options)) }
            IconButton(onClick = { IndexWorker.runNow(ctx, force = true) }, enabled = !progress.running) { Icon(Icons.Default.Refresh, stringResource(R.string.s5_reindex)) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.s5_global_hint)) }, singleLine = true, shape = RoundedCornerShape(24.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PartKind.values().forEach { k ->
                    FilterChip(k in kinds, { kinds = if (k in kinds) (kinds - k).ifEmpty { setOf(k) } else kinds + k }, label = { Text(kindLabel(k)) })
                }
            }
            com.smartdocs.editor.ui.nve.search.NveGlobalSearchBar(nve, query, shown)   // NVE-search
            if (showOptions) SearchOptionsPanel(options, { options = it }, Modifier.padding(horizontal = 16.dp))
            if (progress.running) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.s5_indexing, progress.done, progress.total) + (progress.current?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            } else stats?.let { s ->
                Text(stringResource(R.string.s5_index_stats, s.docs, s.locked), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (query.isNotBlank() && !searching && shown.isEmpty()) {
                Text(stringResource(R.string.pg_redact_no_hits), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val bg = MaterialTheme.colorScheme.tertiaryContainer
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // file position in the keys: two index rows for one path (re-indexed copy) must not produce duplicate keys
                shown.forEachIndexed { fi, fr ->
                    item(key = "h:$fi:" + fr.path) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(fr.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.s5_n_hits, fr.total), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    items(fr.hits.mapIndexed { i, h -> i to h }, key = { "r:$fi:" + fr.path + ":" + it.first }) { (_, h) ->
                        ListItem(
                            modifier = Modifier.clickable {
                                nav.navigate(Routes.reader(if (fr.path.startsWith("content:")) fr.path else Uri.fromFile(File(fr.path)).toString(), h.page.coerceAtLeast(0)))
                            },
                            headlineContent = { Text(highlighted(h.snippet, h.highlights, bg), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = {
                                Text((if (h.page >= 0) stringResource(R.string.pg_page_n, h.page + 1) + " · " else "") + kindLabel(h.kind), style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun kindLabel(k: PartKind): String = stringResource(when (k) {
    PartKind.TEXT -> R.string.s5_scope_text
    PartKind.COMMENTS -> R.string.s5_scope_comments
    PartKind.BOOKMARK -> R.string.s5_scope_bookmarks
    PartKind.FIELD -> R.string.s5_scope_fields
    PartKind.ATTACHMENT -> R.string.s5_scope_attachments
})
