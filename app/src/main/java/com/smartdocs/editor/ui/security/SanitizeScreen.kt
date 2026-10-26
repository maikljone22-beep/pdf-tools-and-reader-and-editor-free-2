package com.smartdocs.editor.ui.security

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
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    var file by remember { mutableStateOf(fileOf(uri)) }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<SanitizeItem>>(emptySet()) }
    var expanded by remember { mutableStateOf<SanitizeItem?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> file = f; password = null } }

    LaunchedEffect(file, password) {
        scan = null
        val f = file ?: return@LaunchedEffect
        scanning = true
        try {
            val r = Sanitizer.scan(f, password)
            scan = r
            // everything found is checked, except the OCR layer (it keeps a scan searchable)
            selected = r.findings.keys.filter { !(it == SanitizeItem.INVISIBLE_TEXT && r.ocrLayer) }.toSet()
        } catch (e: Exception) {
            val m = e.message.orEmpty().lowercase()
            if ("password" in m || e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) askPassword = true else snack.showSnackbar(e.message ?: e.toString())
        } finally { scanning = false }
    }
    val cleaningMsg = stringResource(R.string.s5_cleaning)
    val itemNames = SanitizeItem.values().associateWith { sanitizeLabel(it) }
    fun clean() {
        val src = file ?: return
        val items = selected
        runner.run(cleaningMsg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_clean")
            val r = Sanitizer.cleanFile(src, out, password, items)
            report = r.entries.joinToString("\n") { (k, v) -> "• ${itemNames[k]}" + if (v > 0) ": $v" else "" }.ifBlank { null }
            runCatching { app.documents.touchRecent(out) }
            out
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
            }
            if (scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.s5_scanning), Modifier.padding(16.dp)) }
            val r = scan
            if (r != null) {
                if (r.findings.isEmpty()) EmptyState(Icons.Default.VerifiedUser, stringResource(R.string.s5_nothing_found))
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(SanitizeItem.values().filter { it in r.findings }, key = { it.name }) { k ->
                        val f = r.findings.getValue(k)
                        Column {
                            ListItem(
                                modifier = Modifier.clickable { expanded = if (expanded == k) null else k },
                                leadingContent = { Checkbox(k in selected, { selected = if (it) selected + k else selected - k }) },
                                headlineContent = { Text(sanitizeLabel(k), fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Column {
                                        Text(stringResource(R.string.s5_found_n, f.count), style = MaterialTheme.typography.labelSmall)
                                        if (k == SanitizeItem.INVISIBLE_TEXT && r.ocrLayer) Text(stringResource(R.string.s5_ocr_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        if (k == SanitizeItem.FORMS) Text(stringResource(R.string.s5_forms_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                trailingContent = { if (f.details.isNotEmpty()) Icon(if (expanded == k) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
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
})
