package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.layout.*
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
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch
import java.io.File

/** Column picker for one form field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnDropdown(field: String, columns: List<String>, value: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(R.string.t5_mm_none)
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value ?: none, onValueChange = {}, readOnly = true, label = { Text(field, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(none) }, onClick = { onPick(null); open = false })
            columns.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onPick(c); open = false }) }
        }
    }
}

/** Mail merge: fillable PDF template + CSV / XLSX → filled copies (one per row, or one merged PDF). */
@Composable
fun MailMergeScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var template by remember { mutableStateOf(fileOf(uri)) }
    var dataFile by remember { mutableStateOf<File?>(null) }
    var fields by remember { mutableStateOf<List<String>>(emptyList()) }
    var table by remember { mutableStateOf<MergeTable?>(null) }
    var mapping by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var nameColumn by remember { mutableStateOf<String?>(null) }
    var merged by remember { mutableStateOf(false) }
    var flatten by remember { mutableStateOf(false) }
    val job = rememberT5Job()
    val noForm = stringResource(R.string.t5_mm_no_form)
    val badData = stringResource(R.string.t5_mm_bad_data)
    val pickTemplate = rememberPdfPicker { template = it.firstOrNull() }
    val pickData = rememberAnyFilePicker(false, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/tab-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) { dataFile = it.firstOrNull() }

    LaunchedEffect(template) {
        val t = template ?: return@LaunchedEffect
        fields = runCatching { MailMerge.templateFields(t, null) }.getOrDefault(emptyList())
        if (fields.isEmpty()) snackbar.showSnackbar(noForm)
    }
    LaunchedEffect(dataFile) {
        val d = dataFile ?: return@LaunchedEffect
        table = runCatching { io { MailMerge.readTable(d) } }.getOrNull()
        if (table == null) snackbar.showSnackbar(badData)
    }
    LaunchedEffect(fields, table) {
        val t = table ?: return@LaunchedEffect
        mapping = MergeMapping.auto(fields, t.header)
        nameColumn = nameColumn ?: t.header.firstOrNull()
    }
    val running = stringResource(R.string.t5_mm_running)

    ToolScaffold(stringResource(R.string.t5_mm_title), nav, snackbar = snackbar) {
        FilePickRow(template, stringResource(R.string.t5_mm_template), pickTemplate)
        Surface(onClick = pickData, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TableChart, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.t5_mm_data), style = MaterialTheme.typography.labelMedium)
                    Text(dataFile?.name ?: stringResource(R.string.t5_mm_pick_data), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
            }
        }
        val t = table
        if (t != null && fields.isNotEmpty()) {
            Text(stringResource(R.string.t5_mm_summary, t.rows.size, t.header.size, fields.size), style = MaterialTheme.typography.bodySmall)
            T5Card(stringResource(R.string.t5_mm_mapping), Icons.Default.Link) {
                fields.forEach { f -> ColumnDropdown(f, t.header, mapping[f]) { c -> mapping = mapping + (f to c) } }
            }
            T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
                ColumnDropdown(stringResource(R.string.t5_mm_name_column), t.header, nameColumn) { nameColumn = it }
                SwitchRow(stringResource(R.string.t5_mm_merged), merged, stringResource(R.string.t5_mm_merged_hint)) { merged = it }
                SwitchRow(stringResource(R.string.t5_mm_flatten), flatten) { flatten = it }
            }
            PrimaryButton(stringResource(R.string.t5_mm_run, t.rows.size), enabled = !job.running && t.rows.isNotEmpty() && mapping.values.any { it != null }, icon = Icons.Default.MergeType) {
                val tpl = template ?: return@PrimaryButton
                val m = mapping; val nc = nameColumn; val mg = merged; val fl = flatten
                job.launch(running) {
                    val dir = File(app.documents.docsDir, tpl.nameWithoutExtension + "_merge")
                    val out = MailMerge.run(ctx, tpl, null, t, m, if (mg) app.documents.docsDir else dir, tpl.nameWithoutExtension, nc, mg, fl) { d, n -> step(d, n) }
                    if (out.size == 1) result = out[0] else results = out
                }
            }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
    }
    T5JobUi(job, nav, snackbar)
}

/** PDF portfolio: open one (list / open / share / extract parts) or create one from files. */
@Composable
fun PortfolioScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var source by remember { mutableStateOf(fileOf(uri)) }
    var parts by remember { mutableStateOf<List<PortfolioPart>>(emptyList()) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    val job = rememberT5Job()
    val pickPortfolio = rememberPdfPicker { source = it.firstOrNull() }
    val pickFiles = rememberAnyFilePicker(true, arrayOf("*/*")) { picked -> files = files + picked }
    LaunchedEffect(source) { val s = source ?: return@LaunchedEffect; parts = runCatching { Portfolio.parts(s) }.getOrDefault(emptyList()) }
    val creating = stringResource(R.string.t5_pf_creating)
    val defaultTitle = stringResource(R.string.t5_portfolio_default)

    ToolScaffold(stringResource(R.string.t5_portfolio_title), nav, snackbar = snackbar) {
        T5Card(stringResource(R.string.t5_portfolio_open), Icons.Default.FolderOpen) {
            FilePickRow(source, stringResource(R.string.t5_input_pdf), pickPortfolio)
            if (source != null && parts.isEmpty()) Text(stringResource(R.string.t5_portfolio_none), style = MaterialTheme.typography.bodySmall)
            parts.forEach { p ->
                ListItem(
                    headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(DocumentRepository.formatSize(p.size) + (if (p.description.isNotBlank()) " · " + p.description else ""), maxLines = 1) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                val s = source ?: return@IconButton
                                scope.launch {
                                    val f = Portfolio.extract(s, p.name, File(app.documents.docsDir, s.nameWithoutExtension + "_parts")) ?: return@launch
                                    if (f.extension.equals("pdf", true)) openPdf(nav, f) else shareFiles(ctx, app.documents, listOf(f), p.mime)
                                }
                            }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.t5_open)) }
                            IconButton(onClick = {
                                val s = source ?: return@IconButton
                                scope.launch { Portfolio.extract(s, p.name, File(app.documents.docsDir, s.nameWithoutExtension + "_parts"))?.let { shareFiles(ctx, app.documents, listOf(it), p.mime) } }
                            }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                        }
                    },
                )
            }
            if (parts.isNotEmpty()) OutlinedButton(onClick = {
                val s = source ?: return@OutlinedButton
                job.launch(creating) {
                    val dir = File(app.documents.docsDir, s.nameWithoutExtension + "_parts")
                    results = parts.mapNotNull { Portfolio.extract(s, it.name, dir) }
                }
            }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Unarchive, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_extract_all)) }
        }
        T5Card(stringResource(R.string.t5_portfolio_create), Icons.Default.CreateNewFolder) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_portfolio_name)) })
            OutlinedButton(onClick = pickFiles, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_add_files)) }
            if (files.isNotEmpty()) FileListEditor(files, { files = it })
            PrimaryButton(stringResource(R.string.t5_portfolio_make), enabled = files.isNotEmpty() && !job.running, icon = Icons.Default.FolderZip) {
                val list = files.toList(); val t = title.ifBlank { defaultTitle }
                job.launch(creating) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, t)
                    result = Portfolio.create(ctx, t, list, out, coverHeader = ctx.getString(R.string.t5_portfolio_cover, list.size))
                }
            }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "*/*")
    }
    T5JobUi(job, nav, snackbar)
}
