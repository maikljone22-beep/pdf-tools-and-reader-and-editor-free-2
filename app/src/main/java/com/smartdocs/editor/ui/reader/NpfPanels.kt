package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.NpfDocPanels
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The extra panels of the INFO tab (nav-prefs, Foxit §40). */
enum class NpfPanel(val label: Int) {
    INFO(R.string.npf_p_info), LAYERS(R.string.npf_p_layers), SECURITY(R.string.npf_p_security), FIELDS(R.string.npf_p_fields),
    DESTS(R.string.npf_p_dests), TAGS(R.string.npf_p_tags), ARTICLES(R.string.npf_p_articles), STANDARDS(R.string.npf_p_standards),
    ACCESS(R.string.npf_p_access), OCR(R.string.npf_p_ocr), CONNECTED(R.string.npf_p_connected),
}

/**
 * nav-prefs: the reader's INFO panel with a chip row of further navigation panels — layers (show / hide in the
 * default view), security settings (method and permissions), form fields, named destinations (go, add for the
 * current page, delete, sort), tags / reading order (structure tree), article threads, standards (PDF/A, UA, X, E,
 * tagged), accessibility check summary, recognition report (pages without a text layer) and the connected-document
 * status. Hook: `ReaderPanel.INFO -> NpfInfoPanels(state, onJump)` in ReaderPanels.
 */
@Composable
fun NpfInfoPanels(state: ReaderState, onJump: (Int) -> Unit) {
    var panel by remember { mutableStateOf(NpfPanel.INFO) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NpfPanel.entries.forEach { p -> FilterChip(selected = p == panel, onClick = { panel = p }, label = { Text(stringResource(p.label)) }) }
        }
        Box(Modifier.fillMaxWidth()) {
            when (panel) {
                NpfPanel.INFO -> DocInfoContent(state)
                else -> NpfPanelBody(state, panel, onJump)
            }
        }
    }
}

@Composable
private fun NpfPanelBody(state: ReaderState, panel: NpfPanel, onJump: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var data by remember(panel) { mutableStateOf<Any?>(null) }
    val f = state.file
    val signedMsg = stringResource(R.string.npf_p_signed)
    LaunchedEffect(panel, f, state.renderVersion, version) {
        data = null
        val file = f ?: return@LaunchedEffect
        data = withContext(Dispatchers.IO) {
            runCatching {
                PdfOps.load(file, state.password).use { d ->
                    when (panel) {
                        NpfPanel.LAYERS -> NpfDocPanels.layers(d)
                        NpfPanel.SECURITY -> NpfDocPanels.security(d)
                        NpfPanel.FIELDS -> NpfDocPanels.fields(d)
                        NpfPanel.DESTS -> NpfDocPanels.destinations(d)
                        NpfPanel.TAGS -> NpfDocPanels.structure(d)
                        NpfPanel.ARTICLES -> NpfDocPanels.articles(d)
                        NpfPanel.STANDARDS -> NpfDocPanels.standards(d)
                        NpfPanel.ACCESS -> com.smartdocs.editor.core.tools5.AccessRules.counts(
                            com.smartdocs.editor.core.tools5.AccessRules.evaluate(com.smartdocs.editor.core.tools5.AccessInspect.facts(d, com.smartdocs.editor.core.tools5.PdfInspect.inspect(d), 50)))
                        else -> Unit
                    }
                }
            }.getOrNull()
        }
    }
    /** Catalog-level change written through the reader (undo / write-back); refused on signed files. */
    fun change(op: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit) {
        val file = f ?: return
        scope.launch {
            val signed = withContext(Dispatchers.IO) { SignedSave.isSigned(file, state.password) }
            if (signed) { message = signedMsg; return@launch }
            runCatching {
                state.applyInPlace { src, out, pw -> withContext(Dispatchers.IO) { PdfOps.load(src, pw).use { d -> op(d); d.save(out) } } }
            }.onFailure { message = it.message ?: it.toString() }
            version++
        }
    }
    val nav = com.smartdocs.editor.ui.view5.V5State.of(state).env?.nav
    // audit 5 T-06 / OCR-7: the panel buttons open their tool like the Tools tab does (actions.navigateTool): the
    // tool's restriction check, pending edits saved first, one undo step on return — never a bare nav.navigate
    val navigateTool = com.smartdocs.editor.ui.view5.V5State.of(state).env?.actions?.navigateTool
    fun openTool(id: String) { if (navigateTool != null) navigateTool(id) else nav?.navigate(Routes.tool(id, state.fileUri)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        when (panel) {
            NpfPanel.LAYERS -> {
                NpfRows(data as? List<*>, R.string.npf_p_none_layers) { l ->
                    l as NpfDocPanels.Layer
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, Modifier.weight(1f)); Switch(l.on, { v -> change { NpfDocPanels.setLayer(it, l.index, v) } })
                    }
                }
                // Nitro 18 / Foxit 22: properties, order, merge, delete, import … in the Layers tool
                androidx.compose.material3.TextButton(onClick = { openTool("e5_layers") }) { Text(stringResource(R.string.pc_ly_more)) }
            }
            NpfPanel.SECURITY -> (data as? NpfDocPanels.Security)?.let { s ->
                Column {
                    NpfRow(stringResource(R.string.npf_sec_method), if (!s.encrypted) stringResource(R.string.npf_sec_none) else "${s.filter} V${s.version} R${s.revision} · ${s.keyBits}-bit")
                    listOf(R.string.npf_sec_print to s.print, R.string.npf_sec_print_high to s.printHigh, R.string.npf_sec_modify to s.modify,
                        R.string.npf_sec_copy to s.copy, R.string.npf_sec_a11y to s.extractForA11y, R.string.npf_sec_annot to s.annotate,
                        R.string.npf_sec_fill to s.fill, R.string.npf_sec_assemble to s.assemble).forEach { (l, ok) ->
                        NpfRow(stringResource(l), stringResource(if (ok) R.string.npf_allowed else R.string.npf_not_allowed))
                    }
                }
            } ?: NpfLoading()
            NpfPanel.FIELDS -> NpfRows(data as? List<*>, R.string.npf_p_none_fields) { r ->
                r as NpfDocPanels.FieldRow
                ListItem(headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(r.type, r.value.takeIf { it.isNotBlank() }, stringResource(R.string.npf_required).takeIf { r.required },
                        stringResource(R.string.npf_read_only).takeIf { r.readOnly }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (r.page >= 0) Text((r.page + 1).toString()) },
                    modifier = Modifier.clickable(enabled = r.page >= 0) { onJump(r.page) })
            }
            NpfPanel.DESTS -> {
                var sortByName by remember { mutableStateOf(true) }
                var adding by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.npf_dest_add)) }
                    IconButton(onClick = { sortByName = !sortByName }) { Icon(Icons.Default.SortByAlpha, stringResource(R.string.npf_dest_sort)) }
                }
                val list = (data as? List<*>)?.filterIsInstance<NpfDocPanels.Dest>()?.let { l -> if (sortByName) l.sortedBy { it.name.lowercase() } else l.sortedBy { it.page } }
                NpfRows(list, R.string.npf_p_none_dests) { d ->
                    d as NpfDocPanels.Dest
                    ListItem(headlineContent = { Text(d.name) }, supportingContent = { if (d.page >= 0) Text(stringResource(R.string.npf_page_n, d.page + 1)) },
                        trailingContent = { IconButton(onClick = { change { NpfDocPanels.removeDestination(it, d.name) } }) { Icon(Icons.Default.Delete, stringResource(R.string.npf_dest_delete)) } },
                        modifier = Modifier.clickable(enabled = d.page >= 0) { onJump(d.page) })
                }
                if (adding) {
                    var name by remember { mutableStateOf("") }
                    AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.npf_dest_add)) },
                        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.npf_dest_name, state.currentPage + 1)) }) },
                        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { adding = false; val p = state.currentPage; change { NpfDocPanels.putDestination(it, name.trim(), p) } }) { Text(stringResource(R.string.ok)) } },
                        dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } })
                }
            }
            NpfPanel.TAGS -> NpfRows(data as? List<*>, R.string.npf_p_none_tags) { r ->
                r as NpfDocPanels.StructRow
                Text("  ".repeat(r.depth.coerceAtMost(12)) + "<${r.type}>" + (if (r.text.isNotBlank()) " ${r.text}" else "") + (if (r.page >= 0) "  · ${r.page + 1}" else ""),
                    Modifier.fillMaxWidth().clickable(enabled = r.page >= 0) { onJump(r.page) }.padding(vertical = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            NpfPanel.ARTICLES -> NpfRows(data as? List<*>, R.string.npf_p_none_articles) { a ->
                a as NpfDocPanels.Article
                ListItem(headlineContent = { Text(a.title) }, supportingContent = { Text(stringResource(R.string.npf_article_beads, a.beads)) },
                    modifier = Modifier.clickable(enabled = a.firstPage >= 0) { onJump(a.firstPage) })
            }
            NpfPanel.STANDARDS -> (data as? NpfDocPanels.Standards)?.let { s ->
                Column {
                    NpfRow("PDF", s.pdfVersion)
                    NpfRow("PDF/A", s.pdfa ?: "—"); NpfRow("PDF/UA", s.pdfua ?: "—"); NpfRow("PDF/X", s.pdfx ?: "—"); NpfRow("PDF/E", s.pdfe ?: "—")
                    NpfRow(stringResource(R.string.npf_tagged), stringResource(if (s.tagged) R.string.npf_yes else R.string.npf_no))
                    if (s.pdfa != null) Text(stringResource(R.string.npf_pdfa_claim), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { openTool("pdfa") }) { Text(stringResource(R.string.npf_open_preflight)) }
                }
            } ?: NpfLoading()
            NpfPanel.ACCESS -> {
                @Suppress("UNCHECKED_CAST") val counts = data as? Map<com.smartdocs.editor.core.tools5.AccStatus, Int>
                if (counts == null) NpfLoading() else Column {
                    NpfRow(stringResource(R.string.npf_acc_pass), (counts[com.smartdocs.editor.core.tools5.AccStatus.PASS] ?: 0).toString())
                    NpfRow(stringResource(R.string.npf_acc_fail), (counts[com.smartdocs.editor.core.tools5.AccStatus.FAIL] ?: 0).toString())
                    NpfRow(stringResource(R.string.npf_acc_manual), (counts[com.smartdocs.editor.core.tools5.AccStatus.MANUAL] ?: 0).toString())
                    OutlinedButton(onClick = { openTool("accessibility") }) { Text(stringResource(R.string.npf_acc_open)) }
                }
            }
            NpfPanel.OCR -> OcrReport(state, onJump, onOcr = { openTool("ocr") })
            NpfPanel.CONNECTED -> Column {
                val c = state.connected
                Text(stringResource(if (c == null) R.string.npf_conn_none else R.string.npf_conn_yes), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { openTool("connected") }) { Text(stringResource(R.string.npf_conn_open)) }
            }
            NpfPanel.INFO -> Unit
        }
        com.smartdocs.editor.ui.nve.NveMoreFor(state, panel.name)   // NVE: rename destinations, structure tree with alt text, article editor
    }
}

/** tab-view-core V-37: OCR reports per file version. */
private val OCR_REPORTS = java.util.concurrent.ConcurrentHashMap<String, List<Int>>()

/** Recognition report: pages whose text layer is empty (scanned) — tap to go there, or run OCR. */
@Composable
private fun OcrReport(state: ReaderState, onJump: (Int) -> Unit, onOcr: () -> Unit) {
    // tab-view-core V-37: off the main thread, cached per file version (a save that changes nothing keeps it), and
    // cancelled with the panel (LaunchedEffect leaves the composition)
    val key = state.file?.let { "${it.absolutePath}|${it.lastModified()}|${it.length()}" }
    var empty by remember(state.renderVersion) { mutableStateOf<List<Int>?>(key?.let { OCR_REPORTS[it] }) }
    LaunchedEffect(state.renderVersion, state.pageCount, key) {
        key?.let { k -> OCR_REPORTS[k]?.let { empty = it; return@LaunchedEffect } }
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { (0 until state.pageCount.coerceAtMost(500)).filter { kotlinx.coroutines.yield(); state.pageText(it).isBlank() } }
        key?.let { k -> if (OCR_REPORTS.size > 16) OCR_REPORTS.clear(); OCR_REPORTS[k] = r }
        empty = r
    }
    val e = empty
    if (e == null) { NpfLoading(); return }
    Column {
        Text(stringResource(R.string.npf_ocr_summary, state.pageCount - e.size, e.size), style = MaterialTheme.typography.bodyMedium)
        if (e.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                e.take(200).forEach { p -> TextButton(onClick = { onJump(p) }) { Text((p + 1).toString()) } }
            }
            OutlinedButton(onClick = onOcr) { Text(stringResource(R.string.tool_ocr)) }
        }
    }
}

@Composable
private fun NpfRows(list: List<*>?, empty: Int, row: @Composable (Any) -> Unit) {
    when {
        list == null -> NpfLoading()
        list.isEmpty() -> Text(stringResource(empty), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) { items(list.filterNotNull()) { row(it) } }
    }
}

@Composable
private fun NpfLoading() { Text("…", Modifier.padding(12.dp)) }

@Composable
private fun NpfRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
