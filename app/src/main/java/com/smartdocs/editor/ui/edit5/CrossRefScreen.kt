package com.smartdocs.editor.ui.edit5

import android.graphics.RectF
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.AutoLinks
import com.smartdocs.editor.core.edit5.LinkOps
import com.smartdocs.editor.core.edit5.LinkSpec
import com.smartdocs.editor.core.edit5.PageLinkInfo
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

/**
 * Cross-Reference panel (Foxit): groups of linked places that jump to each other in a cycle. Create a group by
 * drawing its stops on the pages (in order), then name it; the list shows each group with its stops (go to),
 * rename and remove. Sorted by name or by first page; the text size can be changed.
 */
@Composable
fun CrossRefScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var page by remember(doc.source) { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<AutoLinks.XGroup>>(emptyList()) }
    var pageLinks by remember { mutableStateOf<List<PageLinkInfo>>(emptyList()) }
    var stops by remember { mutableStateOf<List<AutoLinks.XStop>>(emptyList()) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var byPage by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val processing = stringResource(R.string.processing)
    val baseName = stringResource(R.string.nbm_xref_base)
    LaunchedEffect(doc.work, doc.version) {
        val w = doc.work ?: return@LaunchedEffect
        groups = runCatching { AutoLinks.groups(w, doc.password) }.getOrDefault(emptyList())
    }
    LaunchedEffect(doc.work, doc.version, page) {
        val w = doc.work ?: return@LaunchedEffect
        pageLinks = runCatching { LinkOps.list(w, doc.password, page) }.getOrDefault(emptyList()).filter { it.spec.group.isNotBlank() }
    }
    fun run(op: suspend (java.io.File, java.io.File, String?) -> Unit) {
        busy = true
        scope.launch { runCatching { doc.apply(app.documents.tempDir, op) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }; busy = false }
    }
    ToolScaffold(stringResource(R.string.nbm_xref_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            E5TwoPane(options = {
                Text(stringResource(R.string.nbm_xref_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                E5Section(stringResource(R.string.nbm_xref_new_stops, stops.size)) {
                    stops.forEachIndexed { i, s ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${i + 1}. " + stringResource(R.string.e5_link_to_page, s.page + 1), Modifier.weight(1f).clickable { page = s.page })
                            IconButton(onClick = { stops = stops.filterIndexed { k, _ -> k != i } }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                        }
                    }
                    Button(onClick = { naming = true }, enabled = stops.size >= 2) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbm_xref_create)) }
                }
                ChoiceChips(listOf(false, true), byPage, { stringResource(if (it) R.string.nbm_sort_page else R.string.nbm_sort_name) }) { byPage = it }
                SwitchRow(stringResource(R.string.nbm_large_text), big) { big = it }
                E5Section(stringResource(R.string.nbm_xref_groups, groups.size)) {
                    if (groups.isEmpty()) Text(stringResource(R.string.nbm_xref_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val shown = if (byPage) groups.sortedBy { g -> g.links.minOfOrNull { it.first } ?: Int.MAX_VALUE } else groups.sortedBy { it.name.lowercase() }
                    val ts = if (big) 18.sp else 15.sp
                    shown.forEach { g ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(g.name, Modifier.weight(1f), fontSize = ts, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { renaming = g.name }) { Icon(Icons.Default.Edit, stringResource(R.string.nbm_edit)) }
                            IconButton(onClick = { run { s, o, p -> AutoLinks.removeGroup(s, o, p, g.name) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                        g.links.forEachIndexed { i, (p, _, target) ->
                            Text("  ${i + 1}. " + stringResource(R.string.e5_link_to_page, p + 1) + (if (target >= 0) " → " + (target + 1) else ""), fontSize = ts * 0.9f,
                                modifier = Modifier.fillMaxWidth().clickable { page = p }.padding(vertical = 4.dp))
                        }
                    }
                }
            }, preview = {
                PageStepper(page, doc.pageCount) { page = it }
                Text(stringResource(R.string.nbm_xref_draw_hint), style = MaterialTheme.typography.bodySmall)
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(),
                    onRect = { r -> if (r.width() > 4f && r.top - r.bottom > 4f) stops = stops + AutoLinks.XStop(page, info.rectToUser(r)) }) { map ->
                    pageLinks.forEach { l -> e5Box(map(info.rectToDisplay(l.spec.rect)), Color(0xFF00897B)) }
                    stops.filter { it.page == page }.forEach { s -> e5Box(map(info.rectToDisplay(s.rect)), Color(0xFFEF6C00)) }
                }
            })
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed, icon = Icons.Default.Save) {
                runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "crossref") }
            }
        }
    }
    if (busy) com.smartdocs.editor.ui.common.ProgressDialog(processing)
    if (naming) {
        var name by remember { mutableStateOf("$baseName ${groups.size + 1}") }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text(stringResource(R.string.nbm_xref_create)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nbm_xref_name)) }) },
            confirmButton = { Button(enabled = name.isNotBlank(), onClick = {
                naming = false
                val links = AutoLinks.groupLinks(name.trim(), stops, LinkDefaults.apply(ctx, LinkSpec(RectF())))
                stops = emptyList()
                run { s, o, p -> AutoLinks.addGroup(s, o, p, name.trim(), links) }
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } })
    }
    renaming?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.nbm_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nbm_xref_name)) }) },
            confirmButton = { Button(enabled = name.isNotBlank(), onClick = { renaming = null; if (name.trim() != old) run { s, o, p -> AutoLinks.renameGroup(s, o, p, old, name.trim()) } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
