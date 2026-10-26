package com.smartdocs.editor.ui.tools5

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun ruleTitle(id: String): String = stringResource(
    when (id) {
        "perm" -> R.string.t5_acc_perm; "image_only" -> R.string.t5_acc_image_only; "tagged" -> R.string.t5_acc_tagged
        "reading_order" -> R.string.t5_acc_reading_order; "lang" -> R.string.t5_acc_lang; "title" -> R.string.t5_acc_title_rule
        "bookmarks" -> R.string.t5_acc_bookmarks; "contrast" -> R.string.t5_acc_contrast; "tagged_content" -> R.string.t5_acc_tagged_content
        "tagged_annots" -> R.string.t5_acc_tagged_annots; "tab_order" -> R.string.t5_acc_tab_order; "encoding" -> R.string.t5_acc_encoding
        "multimedia" -> R.string.t5_acc_multimedia; "scripts" -> R.string.t5_acc_scripts; "nav_links" -> R.string.t5_acc_nav_links
        "tagged_fields" -> R.string.t5_acc_tagged_fields; "field_desc" -> R.string.t5_acc_field_desc; "figure_alt" -> R.string.t5_acc_figure_alt
        "nested_alt" -> R.string.t5_acc_nested_alt; "alt_content" -> R.string.t5_acc_alt_content; "alt_hides_annot" -> R.string.t5_acc_alt_hides
        "other_alt" -> R.string.t5_acc_other_alt; "table_rows" -> R.string.t5_acc_table_rows; "table_cells" -> R.string.t5_acc_table_cells
        "table_headers" -> R.string.t5_acc_table_headers; "table_regular" -> R.string.t5_acc_table_regular; "table_summary" -> R.string.t5_acc_table_summary
        "list_items" -> R.string.t5_acc_list_items; "list_body" -> R.string.t5_acc_list_body; else -> R.string.t5_acc_heading_nesting
    },
)

@Composable
private fun groupTitle(g: AccGroup): String = stringResource(
    when (g) {
        AccGroup.DOCUMENT -> R.string.t5_accg_document; AccGroup.PAGE -> R.string.t5_accg_page; AccGroup.FORMS -> R.string.t5_accg_forms
        AccGroup.ALT -> R.string.t5_accg_alt; AccGroup.TABLES -> R.string.t5_accg_tables; AccGroup.LISTS -> R.string.t5_accg_lists
        AccGroup.HEADINGS -> R.string.t5_accg_headings
    },
)

@Composable
private fun statusLabel(s: AccStatus): String = stringResource(
    when (s) { AccStatus.PASS -> R.string.t5_acc_pass; AccStatus.FAIL -> R.string.t5_acc_fail; AccStatus.MANUAL -> R.string.t5_acc_manual; AccStatus.NA -> R.string.t5_acc_na },
)

private fun statusColor(s: AccStatus) = when (s) { AccStatus.PASS -> T5Green; AccStatus.FAIL -> T5Red; AccStatus.MANUAL -> T5Orange; AccStatus.NA -> T5Grey }

/** Accessibility checker (30 rules), HTML report, quick fixes, basic auto-tagging and figure alt text (AI hook). */
@Composable
fun AccessibilityScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val snackbar = remember { SnackbarHostState() }
    var file by remember { mutableStateOf(fileOf(uri)) }
    var results by remember { mutableStateOf<List<AccResult>>(emptyList()) }
    var figures by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }   // page, alt
    var fixDialog by remember { mutableStateOf(false) }
    val job = rememberT5Job()
    val pick = rememberPdfPicker { file = it.firstOrNull(); results = emptyList(); figures = emptyList() }
    val checking = stringResource(R.string.t5_acc_checking)
    val fixing = stringResource(R.string.t5_acc_fixing)
    val ruleLabels = AccessRules.rules.associate { it.id to ruleTitle(it.id) }
    val statusLabels = AccStatus.entries.associateWith { statusLabel(it) }
    val groupLabels = AccGroup.entries.associateWith { groupTitle(it) }
    val reportTitle = stringResource(R.string.t5_acc_report_title)

    fun check(f: File) = job.launch(checking) {
        val (res, figs) = io {
            PDDocument.load(f).use { d ->
                val facts = AccessInspect.facts(d, PdfInspect.inspect(d))
                AccessRules.evaluate(facts) to AccessInspect.figures(d).map { it.page to (it.alt ?: "") }
            }
        }
        results = res; figures = figs
    }
    LaunchedEffect(file) { file?.let { check(it) } }

    ToolScaffold(stringResource(R.string.t5_acc_title), nav, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.t5_input_pdf), pick)
        if (results.isNotEmpty()) {
            val counts = AccessRules.counts(results)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccStatus.entries.forEach { s -> StatusPill("${statusLabels[s]}: ${counts[s] ?: 0}", statusColor(s)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { fixDialog = true }, enabled = !job.running) { Icon(Icons.Default.Build, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_acc_fix)) }
                OutlinedButton(onClick = {
                    val f = file ?: return@OutlinedButton
                    val html = AccessRules.htmlReport(f.name, results, settings.language == "ar", reportTitle, { ruleLabels[it] ?: it }, { statusLabels[it] ?: it.name }, { groupLabels[it] ?: it.name })
                    job.launch(checking) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_accessibility", "html")
                        io { out.writeText(html) }
                        shareFiles(ctx, app.documents, listOf(out), "text/html")
                    }
                }) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_acc_report)) }
            }
            AccGroup.entries.forEach { g ->
                val rows = results.filter { it.rule.group == g }
                if (rows.isEmpty()) return@forEach
                T5Card(groupLabels[g]) {
                    rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(statusLabels[r.status].orEmpty(), statusColor(r.status))
                            Spacer(Modifier.width(8.dp))
                            Text(ruleLabels[r.rule.id].orEmpty() + if (r.count > 0) " (${r.count})" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (r.status == AccStatus.FAIL && r.rule.id in AccessRules.fixable) Icon(Icons.Default.AutoFixHigh, stringResource(R.string.t5_acc_fixable), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            val ff = file; if (figures.isNotEmpty() && ff != null) FiguresPanel(ff, figures, onSaved = { out -> file = out })
        }
        Text(stringResource(R.string.t5_acc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    T5JobUi(job, nav, snackbar)
    if (fixDialog) {
        val f = file
        if (f == null) fixDialog = false else FixDialog(f, onDismiss = { fixDialog = false }) { lang, title, tabs, fields, bookmarks, tag ->
            fixDialog = false
            job.launch(fixing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_accessible")
                io {
                    PDDocument.load(f).use { d ->
                        if (tag) AccessFix.autoTag(d, lang)
                        if (lang.isNotBlank()) AccessFix.setLanguage(d, lang)
                        if (title.isNotBlank()) AccessFix.setTitle(d, title)
                        if (tabs) AccessFix.tabOrder(d)
                        if (fields) AccessFix.fieldDescriptions(d)
                        if (bookmarks) AccessFix.bookmarksFromHeadings(d)
                        d.save(out)
                    }
                }
                file = out
                result = out
            }
        }
    }
}

@Composable
private fun FixDialog(f: File, onDismiss: () -> Unit, onApply: (String, String, Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var lang by remember { mutableStateOf("ar") }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(f) { title = runCatching { io { PDDocument.load(f).use { AccessFix.suggestTitle(it, f.nameWithoutExtension) } } }.getOrDefault(f.nameWithoutExtension) }
    var tabs by remember { mutableStateOf(true) }
    var fields by remember { mutableStateOf(true) }
    var bookmarks by remember { mutableStateOf(true) }
    var tag by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.t5_acc_fix)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_title)) })
                OutlinedTextField(lang, { lang = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_lang)) })
                SwitchRow(stringResource(R.string.t5_acc_fix_tags), tag, stringResource(R.string.t5_acc_fix_tags_hint)) { tag = it }
                SwitchRow(stringResource(R.string.t5_acc_fix_tabs), tabs) { tabs = it }
                SwitchRow(stringResource(R.string.t5_acc_fix_fields), fields) { fields = it }
                SwitchRow(stringResource(R.string.t5_acc_fix_bookmarks), bookmarks) { bookmarks = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(lang, title, tabs, fields, bookmarks, tag) }) { Text(stringResource(R.string.t5_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Alt text of tagged figures: edit, AI suggestion (vision hook or page-text prompt), decorative. */
@Composable
private fun FiguresPanel(file: File, figures: List<Pair<Int, String>>, onSaved: (File) -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val alts = remember(figures) { mutableStateListOf<String>().apply { addAll(figures.map { it.second }) } }
    val decorative = remember(figures) { mutableStateListOf<Boolean>().apply { addAll(figures.map { false }) } }
    var busy by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    T5Card(stringResource(R.string.t5_acc_figures, figures.size), Icons.Default.Image) {
        figures.forEachIndexed { i, (page, _) ->
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.t5_acc_figure_n, i + 1, page + 1), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(alts[i], { alts[i] = it }, Modifier.fillMaxWidth(), enabled = !decorative[i], label = { Text(stringResource(R.string.t5_acc_alt)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (AltTextAi.available(settings)) TextButton(onClick = {
                        busy = i
                        scope.launchSafe({ busy = -1 }) {
                            var pageText = ""; var img: Bitmap? = null
                            if (page >= 0) runCatching {
                                val s = PdfSession.open(ctx, file, null)
                                try {
                                    pageText = s.text(page)
                                    // the page picture goes to the vision hook when one is installed (T5Hooks.describeImage)
                                    if (T5Hooks.describeImage != null) {
                                        val (w, h) = s.pageSize(page)
                                        img = s.render(page, 1000, (1000 * h / w).toInt().coerceAtLeast(1), annotations = false).copy(Bitmap.Config.ARGB_8888, false)
                                        s.invalidateCache()
                                    }
                                } finally { s.closeAsync() }
                            }
                            alts[i] = AltTextAi.suggest(ctx, settings, img, pageText, settings.language == "ar")
                        }
                    }, enabled = busy < 0) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.t5_acc_ai_alt)) }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.t5_acc_decorative), style = MaterialTheme.typography.bodySmall)
                    Checkbox(decorative[i], { decorative[i] = it })
                }
            }
        }
        PrimaryButton(stringResource(R.string.t5_acc_save_alt), enabled = busy < 0) {
            scope.launchSafe({}) {
                val out = app.documents.uniqueFile(app.documents.docsDir, file.nameWithoutExtension + "_alt")
                io {
                    PDDocument.load(file).use { d ->
                        AccessInspect.figures(d).forEachIndexed { i, fig ->
                            if (i < alts.size) { if (decorative[i]) AccessFix.markDecorative(fig) else AccessFix.setAlt(fig, alts[i]) }
                        }
                        d.save(out)
                    }
                }
                onSaved(out)
            }
        }
    }
}

/** launch that swallows failures (the user sees the unchanged state) and always runs [onEnd]. */
private fun CoroutineScope.launchSafe(onEnd: () -> Unit, block: suspend () -> Unit) {
    launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { } finally { onEnd() } }
}
