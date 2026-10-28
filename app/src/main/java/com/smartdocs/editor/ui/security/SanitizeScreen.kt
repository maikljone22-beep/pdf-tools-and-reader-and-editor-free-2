package com.smartdocs.editor.ui.security

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.redact.Sanitizer
import com.smartdocs.editor.core.redact.ScanResult
import com.smartdocs.editor.ui.common.*
import com.smartdocs.editor.ui.search.PasswordDialog
import com.smartdocs.editor.ui.search.fileOf
import kotlinx.coroutines.launch
import java.io.File

/** Tool `sanitize`: scan → checklist with counts and details → clean → `_clean.pdf` with a report. */
@Composable
fun SanitizeScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    var file by remember { mutableStateOf(fileOf(uri)) }
    // T-09: the password the reader opened this document with
    var password by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(file)) }
    var signed by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<SanitizeItem>>(emptySet()) }
    var expanded by remember { mutableStateOf<SanitizeItem?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> file = f; password = null } }

    val failedMsg = stringResource(R.string.ttrd_failed)
    LaunchedEffect(file, password) {
        scan = null
        signed = false
        val f = file ?: return@LaunchedEffect
        scanning = true
        // audit 5 R-4: warn before cleaning a signed file (the output keeps no signature values)
        val pw = password
        signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedSave.isSigned(f, pw) }
        try {
            val r = Sanitizer.scan(f, password)
            scan = r
            // everything found is checked, except the OCR layer (it keeps a scan searchable) and the opt-in items (tags)
            selected = r.findings.keys.filter { !(it == SanitizeItem.INVISIBLE_TEXT && r.ocrLayer) && it !in SanitizeItem.OPT_IN }.toSet()
        } catch (e: Exception) {
            val m = e.message.orEmpty().lowercase()
            if ("password" in m || e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) askPassword = true else snack.showSnackbar(failedMsg)
        } finally { scanning = false }
    }
    val cleaningMsg = stringResource(R.string.s5_cleaning)
    val itemNames = SanitizeItem.values().associateWith { sanitizeLabel(it) }
    val skipText = rememberSkipText()
    fun clean() {
        val src = file ?: return
        val items = selected
        runner.run(cleaningMsg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_clean")
            val skipped = ArrayList<com.smartdocs.editor.core.redact.SanitizeSkip>()
            val r = try { Sanitizer.cleanFile(src, out, password, items, ctx, skipped) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { throw IllegalStateException(failedMsg, e) }
            // audit 5 R-8: what could not be removed is reported, not hidden in a smaller count
            report = (r.entries.map { (k, v) -> "• ${itemNames[k]}" + if (v > 0) ": $v" else "" } + listOfNotNull(skipText(skipped)))
                .joinToString("\n").ifBlank { null }
            runCatching { app.documents.touchRecent(out) }
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.s5_sanitize_title), onBack = { nav.popBackStack() }) {
            IconButton(onClick = { val f = file; file = null; scope.launch { file = f } }, enabled = file != null && !scanning) { Icon(Icons.Default.Refresh, stringResource(R.string.s5_rescan)) }
        } },
        bottomBar = {
            if (scan != null) Surface(tonalElevation = 3.dp) {
                Button(onClick = ::clean, enabled = selected.isNotEmpty() && !runner.running, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Icon(Icons.Default.CleaningServices, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_clean_n, selected.size))
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilePickRow(file, stringResource(R.string.open_file), pick)
                Text(stringResource(R.string.s5_sanitize_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (signed) Text(stringResource(R.string.ttrd_signed_warning), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.s5_scanning), Modifier.padding(16.dp)) }
            val r = scan
            if (r != null) {
                if (r.findings.isEmpty()) EmptyState(Icons.Default.VerifiedUser, stringResource(R.string.s5_nothing_found))
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(SanitizeItem.values().filter { it in r.findings }, key = { it.name }) { k ->
                        val f = r.findings.getValue(k)
                        Column {
                            // audit 5 P-L: the row toggles the item (TalkBack reads its label); details open with their own button
                            ListItem(
                                modifier = Modifier.toggleable(value = k in selected, role = androidx.compose.ui.semantics.Role.Checkbox) { selected = if (it) selected + k else selected - k },
                                leadingContent = { Checkbox(k in selected, onCheckedChange = null) },
                                headlineContent = { Text(sanitizeLabel(k), fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Column {
                                        Text(stringResource(R.string.s5_found_n, f.count), style = MaterialTheme.typography.labelSmall)
                                        if (k == SanitizeItem.INVISIBLE_TEXT && r.ocrLayer) Text(stringResource(R.string.s5_ocr_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        if (k == SanitizeItem.FORMS) Text(stringResource(R.string.s5_forms_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (k == SanitizeItem.STRUCTURE) Text(stringResource(R.string.nt_san_structure_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (k == SanitizeItem.OVERLAPPING) Text(stringResource(R.string.nt_san_overlapping_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                trailingContent = { if (f.details.isNotEmpty()) IconButton(onClick = { expanded = if (expanded == k) null else k }) {
                                    Icon(if (expanded == k) Icons.Default.ExpandLess else Icons.Default.ExpandMore, stringResource(R.string.ttrd_details))
                                } },
                            )
                            if (expanded == k) Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp)) {
                                f.details.forEach { d -> Text(d, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (askPassword) PasswordDialog(onDismiss = { askPassword = false }) { password = it; askPassword = false }
    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }, extraInfo = report) }
}

@Composable
fun sanitizeLabel(k: SanitizeItem): String = stringResource(when (k) {
    SanitizeItem.METADATA -> R.string.s5_san_metadata
    SanitizeItem.ATTACHMENTS -> R.string.s5_san_attachments
    SanitizeItem.COMMENTS -> R.string.s5_san_comments
    SanitizeItem.FORMS -> R.string.s5_san_forms
    SanitizeItem.JAVASCRIPT -> R.string.s5_san_js
    SanitizeItem.LINKS -> R.string.s5_san_links
    SanitizeItem.BOOKMARKS -> R.string.s5_san_bookmarks
    SanitizeItem.PRIVATE_DATA -> R.string.s5_san_private
    SanitizeItem.THUMBNAILS -> R.string.s5_san_thumbs
    SanitizeItem.HIDDEN_LAYERS -> R.string.s5_san_layers
    SanitizeItem.INVISIBLE_TEXT -> R.string.s5_san_invisible
    SanitizeItem.HIDDEN_TEXT -> R.string.s5_san_hidden_text
    SanitizeItem.CROPPED -> R.string.s5_san_cropped
    SanitizeItem.REVISIONS -> R.string.s5_san_revisions
    SanitizeItem.SEARCH_INDEX -> R.string.nt_san_search_index
    SanitizeItem.STRUCTURE -> R.string.nt_san_structure
    SanitizeItem.MULTIMEDIA -> R.string.nt_san_multimedia
    SanitizeItem.OVERLAPPING -> R.string.nt_san_overlapping
})
