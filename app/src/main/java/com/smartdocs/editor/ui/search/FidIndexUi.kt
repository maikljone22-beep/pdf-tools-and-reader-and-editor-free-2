package com.smartdocs.editor.ui.search

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.search.FidIndexes
import com.smartdocs.editor.core.search.FileResult
import com.smartdocs.editor.core.search.FullTextIndex
import com.smartdocs.editor.core.search.IndexDef
import com.smartdocs.editor.core.search.IndexStats
import com.smartdocs.editor.core.search.IndexWorker
import com.smartdocs.editor.core.search.PartKind
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.ui.nve.search.NveIndexPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** fix/files-index: the defined indexes and the ones the global search looks in. */
class FidIndexState(ctx: Context) {
    private val app = ctx.applicationContext
    var defs by mutableStateOf(FidIndexes.list(app))
        private set
    var selected by mutableStateOf(FidIndexes.selected(app))
        private set
    var showManager by mutableStateOf(false)

    fun reload() { defs = FidIndexes.list(app); selected = FidIndexes.selected(app) }

    fun toggle(id: String) {
        val n = if (id in selected) (selected - id).ifEmpty { selected } else selected + id
        FidIndexes.setSelected(app, n); selected = n
    }

    /** Searches every selected index; a file found in several is listed once. */
    suspend fun search(query: String, options: SearchOptions, kinds: Set<PartKind>): List<FileResult> =
        FidIndexes.merge(selected.map { id -> runCatching { FullTextIndex.get(app, id).search(query, options, kinds) }.getOrDefault(emptyList()) })

    /** Totals over the selected indexes. */
    suspend fun stats(): IndexStats = withContext(Dispatchers.IO) {
        selected.map { id -> runCatching { FullTextIndex.get(app, id).stats() }.getOrDefault(IndexStats(0, 0, 0)) }
            .fold(IndexStats(0, 0, 0)) { a, b -> IndexStats(a.docs + b.docs, a.locked + b.locked, a.parts + b.parts) }
    }
}

/** The catalog title of [d] (the default index: "App index"). */
@Composable
fun fidTitle(d: IndexDef): String = d.title.ifBlank { if (d.id == FidIndexes.DEFAULT) stringResource(R.string.fid_app_index) else stringResource(R.string.fid_untitled) }

/** "Search in:" chips, one per index (several can be on), and the index manager. */
@Composable
fun FidIndexChips(st: FidIndexState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.fid_search_in), style = MaterialTheme.typography.labelMedium)
        st.defs.forEach { d -> FilterChip(d.id in st.selected, { st.toggle(d.id) }, label = { Text(fidTitle(d), maxLines = 1) }) }
        AssistChip({ st.showManager = true }, label = { Text(stringResource(R.string.fid_manage)) }, leadingIcon = { Icon(Icons.Default.LibraryBooks, null, Modifier.size(16.dp)) })
    }
    if (st.showManager) FidIndexManager(st) { st.showManager = false }
}

/** Index catalog: add / rename / delete indexes, their include folders and whether they take the app's files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FidIndexManager(st: FidIndexState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<IndexDef?>(null) }      // title dialog (new index when id is blank)
    var deleting by remember { mutableStateOf<IndexDef?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        val id = pickFor; pickFor = null
        if (u == null || id == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        FidIndexes.get(ctx, id)?.let { d -> FidIndexes.put(ctx, d.copy(folders = (d.folders + u.toString()).distinct())) }
        st.reload()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.fid_manage), style = MaterialTheme.typography.titleLarge)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            st.defs.forEach { d ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fidTitle(d), Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { editing = d }) { Icon(Icons.Default.Edit, stringResource(R.string.fid_rename)) }
                            if (d.id != FidIndexes.DEFAULT) IconButton(onClick = { deleting = d }) { Icon(Icons.Default.Delete, stringResource(R.string.fid_delete)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.fid_app_sources), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(d.appSources, { v -> FidIndexes.put(ctx, d.copy(appSources = v)); st.reload() })
                        }
                        Text(stringResource(R.string.fid_include_folders), style = MaterialTheme.typography.labelLarge)
                        if (d.folders.isEmpty()) Text(stringResource(R.string.fid_no_folders), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        d.folders.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(folderLabel(f), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { FidIndexes.put(ctx, d.copy(folders = d.folders - f)); st.reload() }) { Icon(Icons.Default.Close, stringResource(R.string.fid_remove_folder)) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { pickFor = d.id; pickFolder.launch(null) }) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.fid_add_folder)) }
                            TextButton(onClick = { IndexWorker.runNow(ctx, force = true, index = d.id); msg = ctx.getString(R.string.ns_rebuild_started) }) {
                                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.fid_rebuild))
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = IndexDef("") }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.fid_new_index)) }
            Text(stringResource(R.string.fid_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
    editing?.let { d ->
        var t by remember(d) { mutableStateOf(d.title) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(stringResource(if (d.id.isBlank()) R.string.fid_new_index else R.string.fid_rename)) },
            text = { OutlinedTextField(t, { t = it }, label = { Text(stringResource(R.string.fid_title)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (d.id.isBlank()) { val n = FidIndexes.add(ctx, t.trim()); FidIndexes.setSelected(ctx, st.selected + n.id) }
                else FidIndexes.put(ctx, d.copy(title = t.trim()))
                st.reload(); editing = null
            }) { Text(stringResource(R.string.fid_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleting?.let { d ->
        AlertDialog(onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.fid_delete_confirm, fidTitle(d))) },
            confirmButton = { TextButton(onClick = { FidIndexes.delete(ctx, d.id); st.reload(); deleting = null }) { Text(stringResource(R.string.fid_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** A readable folder name: the path of a SAF tree when it can be worked out, else the uri. */
private fun folderLabel(f: String): String =
    if (f.startsWith("content:")) NveIndexPrefs.treePath(android.net.Uri.parse(f)) ?: f else f
