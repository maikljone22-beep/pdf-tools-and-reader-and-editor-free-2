package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.DestView
import com.smartdocs.editor.core.edit5.NamedDest
import com.smartdocs.editor.core.edit5.NamedDests
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

/**
 * Destinations panel (Foxit): the document's named destinations, sorted by name or page. Tap = go to (the preview
 * shows its page), edit = rename / set to the previewed page / change the view, delete; "New" makes one at the
 * previewed page. Links and bookmarks that use a renamed destination are re-pointed.
 */
@Composable
fun DestinationsScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var page by remember(doc.source) { mutableIntStateOf(0) }
    var dests by remember { mutableStateOf<List<NamedDest>>(emptyList()) }
    var byPage by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NamedDest?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val processing = stringResource(R.string.processing)
    val baseName = stringResource(R.string.nbm_dest_base)
    LaunchedEffect(doc.work, doc.version) {
        val w = doc.work ?: return@LaunchedEffect
        dests = runCatching { NamedDests.load(w, doc.password) }.getOrDefault(emptyList())
    }
    fun run(ops: List<NamedDests.Op>) {
        busy = true
        scope.launch {
            runCatching { doc.apply(app.documents.tempDir) { s, o, p -> NamedDests.edit(s, o, p, ops) } }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            busy = false
        }
    }
    ToolScaffold(stringResource(R.string.nbm_dest_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            E5TwoPane(options = {
                Text(stringResource(R.string.nbm_dest_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { isNew = true; editing = NamedDest(NamedDests.freeName(dests.map { it.name }, baseName), page, DestView(DestFit.FIT)) }) {
                        Icon(Icons.Default.AddLocationAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbm_dest_new))
                    }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.nbm_search)) })
                ChoiceChips(listOf(false, true), byPage, { stringResource(if (it) R.string.nbm_sort_page else R.string.nbm_sort_name) }) { byPage = it }
                E5Section(stringResource(R.string.nbm_dest_count, dests.size)) {
                    val shown = NamedDests.sorted(dests, byPage).filter { query.isBlank() || it.name.contains(query, true) }
                    if (shown.isEmpty()) Text(stringResource(R.string.nbm_dest_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    shown.forEach { d ->
                        ListItem(headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (d.page >= 0) stringResource(R.string.e5_link_to_page, d.page + 1) + " • " + stringResource(nbmFitLabel(d.view.fit)) else stringResource(R.string.nbm_dest_missing_page)) },
                            leadingContent = { Icon(Icons.Default.Place, null, tint = if (d.page == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = { IconButton(onClick = { isNew = false; editing = d }) { Icon(Icons.Default.Edit, stringResource(R.string.nbm_edit)) } },
                            modifier = Modifier.clickable { if (d.page >= 0) page = d.page })
                    }
                }
            }, preview = {
                PageStepper(page, doc.pageCount) { page = it }
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth()) { map ->
                    dests.filter { it.page == page && it.view.fit == DestFit.FITR }.forEach { d ->
                        val r = d.view.rect ?: return@forEach
                        e5Box(map(info.rectToDisplay(android.graphics.RectF(r[0], r[3], r[2], r[1]))), Color(0xFF6A1B9A), fill = false)
                    }
                }
            })
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed, icon = Icons.Default.Save) {
                runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "destinations") }
            }
        }
    }
    if (busy) com.smartdocs.editor.ui.common.ProgressDialog(processing)
    editing?.let { d ->
        DestDialog(d, isNew, page, doc.pageCount, taken = dests.map { it.name }.toSet() - d.name, onDismiss = { editing = null },
            onDelete = if (isNew) null else ({ editing = null; run(listOf(NamedDests.Op.Delete(d.name))) })) { name, pg, view ->
            editing = null
            val ops = ArrayList<NamedDests.Op>()
            if (!isNew && name != d.name) ops += NamedDests.Op.Rename(d.name, name)
            ops += NamedDests.Op.Set(name, pg, view)
            run(ops)
        }
    }
}

@Composable
private fun DestDialog(d: NamedDest, isNew: Boolean, previewPage: Int, pageCount: Int, taken: Set<String>, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (String, Int, DestView) -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    var pageText by remember { mutableStateOf((d.page.coerceAtLeast(0) + 1).toString()) }
    var view by remember { mutableStateOf(d.view) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (isNew) R.string.nbm_dest_new else R.string.nbm_dest_edit)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_dest_name)) },
                isError = name.trim() in taken, supportingText = { if (name.trim() in taken) Text(stringResource(R.string.nbm_dest_taken)) })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pageText, { pageText = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_link_page_of, pageCount)) })
                TextButton(onClick = { pageText = (previewPage + 1).toString() }) { Text(stringResource(R.string.nbm_dest_set_here)) }
            }
            DestViewFields(view) { view = it }
        }
    }, confirmButton = {
        Button(enabled = name.isNotBlank() && name.trim() !in taken, onClick = { onSave(name.trim(), ((pageText.toIntOrNull() ?: 1) - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)), view) }) { Text(stringResource(R.string.save)) }
    }, dismissButton = {
        Row {
            if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    })
}
