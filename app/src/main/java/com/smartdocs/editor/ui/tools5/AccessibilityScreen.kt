package com.smartdocs.editor.ui.tools5

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
        "list_items" -> R.string.t5_acc_list_items; "list_body" -> R.string.t5_acc_list_body
        "flicker" -> R.string.pc_acc_flicker; "timed" -> R.string.pc_acc_timed; else -> R.string.t5_acc_heading_nesting
    },
)

/** Foxit "Explain": what a rule checks and how to fix it. */
@Composable
private fun ruleExplain(id: String): String = stringResource(
    when (id) {
        "perm" -> R.string.pc_accx_perm; "image_only" -> R.string.pc_accx_image_only; "tagged" -> R.string.pc_accx_tagged
        "reading_order" -> R.string.pc_accx_reading_order; "lang" -> R.string.pc_accx_lang; "title" -> R.string.pc_accx_title
        "bookmarks" -> R.string.pc_accx_bookmarks; "contrast" -> R.string.pc_accx_contrast; "tagged_content" -> R.string.pc_accx_tagged_content
        "tagged_annots" -> R.string.pc_accx_tagged_annots; "tab_order" -> R.string.pc_accx_tab_order; "encoding" -> R.string.pc_accx_encoding
        "multimedia" -> R.string.pc_accx_multimedia; "scripts" -> R.string.pc_accx_scripts; "nav_links" -> R.string.pc_accx_nav_links
        "flicker" -> R.string.pc_accx_flicker; "timed" -> R.string.pc_accx_timed
        "tagged_fields" -> R.string.pc_accx_tagged_fields; "field_desc" -> R.string.pc_accx_field_desc; "figure_alt" -> R.string.pc_accx_figure_alt
        "nested_alt" -> R.string.pc_accx_nested_alt; "alt_content" -> R.string.pc_accx_alt_content; "alt_hides_annot" -> R.string.pc_accx_alt_hides
        "other_alt" -> R.string.pc_accx_other_alt; "table_rows" -> R.string.pc_accx_table_rows; "table_cells" -> R.string.pc_accx_table_cells
        "table_headers" -> R.string.pc_accx_table_headers; "table_regular" -> R.string.pc_accx_table_regular; "table_summary" -> R.string.pc_accx_table_summary
        "list_items" -> R.string.pc_accx_list_items; "list_body" -> R.string.pc_accx_list_body; else -> R.string.pc_accx_heading_nesting
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
    when (s) { AccStatus.PASS -> R.string.t5_acc_pass; AccStatus.FAIL -> R.string.t5_acc_fail; AccStatus.MANUAL -> R.string.t5_acc_manual; AccStatus.NA -> R.string.t5_acc_na; AccStatus.SKIPPED -> R.string.pc_acc_skipped },
)

private fun statusColor(s: AccStatus) = when (s) { AccStatus.PASS -> T5Green; AccStatus.FAIL -> T5Red; AccStatus.MANUAL -> T5Orange; AccStatus.NA, AccStatus.SKIPPED -> T5Grey }

/** Accessibility checker (30 rules), HTML report, quick fixes, basic auto-tagging and figure alt text (AI hook). */
@Composable
fun AccessibilityScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val snackbar = remember { SnackbarHostState() }
    var file by remember { mutableStateOf(fileOf(uri)) }
    // audit 5 A-01: the file opens with its password (the reader's one first); a full save keeps its protection
    // (PdfOps.load). [origin] is the document the outputs transform (for "save in original").
    var origin by remember { mutableStateOf(fileOf(uri)) }
    var password by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(fileOf(uri))) }
    var needPw by remember { mutableStateOf(false) }
    var wrongPw by remember { mutableStateOf(false) }
    var pwCancelled by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<AccResult>>(emptyList()) }
    var figures by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }   // page, alt
    var fixDialog by remember { mutableStateOf(false) }
    // Foxit 34.2 / 34.6 / 34.8: rule categories, manual verdicts, skipped rules; the tags panel; the recognition report
    var groups by remember { mutableStateOf<Set<AccGroup>?>(null) }
    var verdicts by remember { mutableStateOf<Map<String, AccStatus>>(emptyMap()) }
    var skipped by remember { mutableStateOf<Set<String>>(emptySet()) }
    var explain by remember { mutableStateOf<String?>(null) }
    var tags by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<AccessAutoTag.Report?>(null) }
    var isUa by remember { mutableStateOf(false) }
    val job = rememberT5Job()
    val pick = rememberPdfPicker {
        val f = it.firstOrNull()
        file = f; origin = f; password = com.smartdocs.editor.core.pdf.SessionPasswords.get(f); needPw = false; wrongPw = false; pwCancelled = false
        results = emptyList(); figures = emptyList()
    }
    val checking = stringResource(R.string.t5_acc_checking)
    val fixing = stringResource(R.string.t5_acc_fixing)
    val formulaAlt = stringResource(R.string.pa_formula_alt)
    val fixedN = stringResource(R.string.pa_fixed_n)
    val summaryLabel = stringResource(R.string.pa_table_summary)
    val ruleLabels = AccessRules.rules.associate { it.id to ruleTitle(it.id) }
    val statusLabels = AccStatus.entries.associateWith { statusLabel(it) }
    val groupLabels = AccGroup.entries.associateWith { groupTitle(it) }
    val reportTitle = stringResource(R.string.t5_acc_report_title)

    /** audit 5 A-01: a password problem opens the prompt; any other failure is shown as a sentence, not PdfBox text. */
    fun failed(e: Throwable) {
        if (com.smartdocs.editor.core.edit5.E5Locks.isPasswordError(e)) { wrongPw = !password.isNullOrEmpty(); needPw = true }
        else job.error = ctx.getString(R.string.tte5_acc_failed)
    }
    fun check(f: File) = job.launch(checking) {
        val pw = password
        try {
            val (res, figs) = io {
                com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, pw).use { d ->
                    val facts = AccessInspect.facts(d, PdfInspect.inspect(d))
                    isUa = AccessUa.isUa(d)
                    AccessRules.evaluate(facts) to AccessInspect.figures(d).map { it.page to (it.alt ?: "") }
                }
            }
            results = res; figures = figs; wrongPw = false
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { results = emptyList(); figures = emptyList(); failed(e) }
    }
    // re-checked when the password changes (typed in the prompt)
    LaunchedEffect(file, password) { if (!pwCancelled) file?.let { check(it) } }
    /** The Fix of rule [id]: a direct structure fix on a copy, the fix dialog, the tool that fixes it, or what to check by hand. */
    fun fixRule(id: String) {
        val f = file ?: return
        when {
            id in AccessRules.fixable -> fixDialog = true
            id == "image_only" -> nav.navigate(com.smartdocs.editor.ui.Routes.tool("ocr", android.net.Uri.fromFile(f).toString()))
            id == "perm" -> nav.navigate(com.smartdocs.editor.ui.Routes.tool("unlock", android.net.Uri.fromFile(f).toString()))
            id == "reading_order" -> tags = true
            id in AccessFix2.STRUCTURE_FIXES -> job.launch(fixing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_accessible")
                val n = io {
                    com.smartdocs.editor.core.pdf.PdfOps.load(f, password).use { d ->   // audit 5 A-01: with the password
                        val k = when (id) {
                            "nested_alt" -> AccessFix2.removeNestedAlt(d)
                            "alt_content" -> AccessFix2.removeAltWithoutContent(d)
                            "alt_hides_annot" -> AccessFix2.removeAltHidingAnnots(d)
                            "other_alt" -> AccessFix2.fillOtherAlt(d, formulaAlt)
                            "table_rows" -> AccessFix2.wrapOrphans(d, setOf("TR"), setOf("Table", "THead", "TBody", "TFoot"), "Table")
                            "table_cells" -> AccessFix2.wrapOrphans(d, setOf("TH", "TD"), setOf("TR"), "TR")
                            "list_items" -> AccessFix2.wrapOrphans(d, setOf("LI"), setOf("L"), "L")
                            "list_body" -> AccessFix2.wrapOrphans(d, setOf("Lbl", "LBody"), setOf("LI"), "LI")
                            "table_headers" -> AccessFix2.firstRowHeaders(d)
                            "table_summary" -> AccessFix2.summaries(d) { rows, cols -> summaryLabel.format(rows, cols) }
                            "heading_nesting" -> AccessFix2.fixHeadingSkips(d)
                            "encoding" -> com.smartdocs.editor.core.pdf.ToUnicodeRepair.repair(d).fonts
                            else -> {   // tagged_annots / tagged_fields / nav_links / multimedia
                                if (AccessTags.root(d) == null) AccessAutoTag.tag(d, d.documentCatalog.language ?: "", false)
                                AccessTags.tagAnnotations(d)
                            }
                        }
                        d.save(out); k
                    }
                }
                file = out; result = com.smartdocs.editor.core.pdf.ToolOutputs.record(out, origin ?: f); info = fixedN.format(n)
            }
            else -> explain = id        // contrast, scripts, flicker, timed, table_regular: a person checks; Explain says what to look for
        }
    }
    val tf = file
    if (tags && tf != null) { TagsEditor(nav, tf, onClose = { tags = false }, onSaved = { file = it }, password = password); return }
    val shown = AccessRules.apply(results, groups, verdicts, skipped)
    val attachedMsg = stringResource(R.string.pc_acc_attached)

    ToolScaffold(stringResource(R.string.t5_acc_title), nav, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.t5_input_pdf), pick)
        if (pwCancelled && file != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tte5_password_cancelled), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { pwCancelled = false; needPw = true }) { Text(stringResource(R.string.tte5_retry)) }
        }
        if (results.isNotEmpty()) {
            // checker options: which rule categories count
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(groups == null, { groups = null }, label = { Text(stringResource(R.string.pc_pf_all)) })
                AccGroup.entries.forEach { g -> FilterChip(groups?.contains(g) == true, {
                    val cur = groups ?: emptySet(); val next = if (g in cur) cur - g else cur + g
                    groups = next.ifEmpty { null }
                }, label = { Text(groupLabels[g].orEmpty()) }) }
            }
            if (isUa) Text(stringResource(R.string.pc_acc_is_ua), style = MaterialTheme.typography.bodySmall, color = T5Green)
            val counts = AccessRules.counts(shown)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccStatus.entries.forEach { s -> StatusPill("${statusLabels[s]}: ${counts[s] ?: 0}", statusColor(s)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { fixDialog = true }, enabled = !job.running) { Icon(Icons.Default.Build, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_acc_fix)) }
                OutlinedButton(onClick = {
                    val f = file ?: return@OutlinedButton
                    val html = AccessRules.htmlReport(f.name, shown, com.smartdocs.editor.core.data.AppLang.isArabic(settings), reportTitle, { ruleLabels[it] ?: it }, { statusLabels[it] ?: it.name }, { groupLabels[it] ?: it.name })
                    job.launch(checking) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_accessibility", "html")
                        io { out.writeText(html) }
                        shareFiles(ctx, app.documents, listOf(out), "text/html")
                    }
                }) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_acc_report)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { file?.let { check(it) } }, enabled = !job.running) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pc_acc_check_again)) }
                OutlinedButton(onClick = { tags = true }, enabled = !job.running) { Icon(Icons.Default.AccountTree, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pc_tags_title)) }
                // Foxit 34.4: the report goes into the document as an attachment
                OutlinedButton(onClick = {
                    val f = file ?: return@OutlinedButton
                    val html = AccessRules.htmlReport(f.name, shown, com.smartdocs.editor.core.data.AppLang.isArabic(settings), reportTitle, { ruleLabels[it] ?: it }, { statusLabels[it] ?: it.name }, { groupLabels[it] ?: it.name })
                    val pw = password
                    job.launch(checking) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_report")
                        try {
                            io { com.smartdocs.editor.core.pdf.PdfOps.load(f, pw).use { d -> Embed.addFiles(d, listOf(Attachment(f.nameWithoutExtension + "_accessibility.html", "text/html", html.toByteArray()))); d.save(out) } }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { out.delete(); failed(e); return@launch }
                        file = out; result = out; info = attachedMsg
                    }
                }, enabled = !job.running) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pc_acc_attach_report)) }
            }
            AccGroup.entries.forEach { g ->
                val rows = shown.filter { it.rule.group == g }
                if (rows.isEmpty()) return@forEach
                T5Card(groupLabels[g]) {
                    rows.forEach { r ->
                        var menu by remember(r.rule.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(statusLabels[r.status].orEmpty() + if (r.manual) " ✎" else "", statusColor(r.status))
                            Spacer(Modifier.width(8.dp))
                            Text(ruleLabels[r.rule.id].orEmpty() + if (r.count > 0) " (${r.count})" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (r.status == AccStatus.FAIL && (r.rule.id in AccessRules.fixable || r.rule.id in AccessFix2.STRUCTURE_FIXES)) Icon(Icons.Default.AutoFixHigh, stringResource(R.string.t5_acc_fixable), tint = MaterialTheme.colorScheme.primary)
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_pf_actions, ruleLabels[r.rule.id].orEmpty())) }
                                DropdownMenu(menu, { menu = false }) {
                                    val id = r.rule.id
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pc_acc_explain)) }, onClick = { menu = false; explain = id })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pc_acc_pass_manual)) }, onClick = { menu = false; skipped = skipped - id; verdicts = verdicts + (id to AccStatus.PASS) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pc_acc_fail_manual)) }, onClick = { menu = false; skipped = skipped - id; verdicts = verdicts + (id to AccStatus.FAIL) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pc_acc_skip)) }, onClick = { menu = false; skipped = skipped + id })
                                    if (r.manual) DropdownMenuItem(text = { Text(stringResource(R.string.pc_acc_clear_verdict)) }, onClick = { menu = false; skipped = skipped - id; verdicts = verdicts - id })
                                    // FINAL_CHECK (Foxit 34.7): every failed or manual rule has a Fix
                                    if (r.status == AccStatus.FAIL || r.status == AccStatus.MANUAL) DropdownMenuItem(text = { Text(stringResource(R.string.t5_acc_fix)) }, onClick = { menu = false; fixRule(id) })
                                }
                            }
                        }
                    }
                }
            }
            val ff = file; if (figures.isNotEmpty() && ff != null) FiguresPanel(ff, password, origin, figures, onSaved = { out -> file = out }, onError = { failed(it) })
        }
        Text(stringResource(R.string.t5_acc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    T5JobUi(job, nav, snackbar)
    if (needPw) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { needPw = false; pwCancelled = true },
            title = { Text(stringResource(R.string.password_enter)) },
            text = {
                Column {
                    if (wrongPw) Text(stringResource(R.string.e5_lock_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                }
            },
            confirmButton = { Button(onClick = { needPw = false; pwCancelled = false; if (pw == password) { file?.let { check(it) } } else { password = pw } }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { needPw = false; pwCancelled = true }) { Text(stringResource(R.string.cancel)) } })
    }
    explain?.let { id ->
        AlertDialog(onDismissRequest = { explain = null }, title = { Text(ruleLabels[id].orEmpty()) }, text = { Text(ruleExplain(id)) },
            confirmButton = { TextButton(onClick = { explain = null }) { Text(stringResource(R.string.ok)) } })
    }
    // Foxit 34.36: recognition report after auto-tagging
    report?.let { r ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text(stringResource(R.string.pc_acc_report_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.pc_acc_report_pages, r.taggedPages, r.pages))
                Text(stringResource(R.string.pc_acc_report_counts, r.headings, r.paragraphs, r.lists, r.listItems, r.figures, r.artifacts, r.annotations))
                if (r.tables > 0) Text(stringResource(R.string.pa_report_tables, r.tables))
                if (r.pagesWithoutText > 0) Text(stringResource(R.string.pc_acc_report_scanned, r.pagesWithoutText), color = T5Orange)
                if (r.figures > 0) Text(stringResource(R.string.pc_acc_report_alt), color = T5Orange)
                if (r.retagged) Text(stringResource(R.string.pc_acc_report_retagged))
            }
        }, confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.ok)) } })
    }
    if (fixDialog) {
        val f = file
        val pw = password; val src = origin
        if (f == null) fixDialog = false else FixDialog(f, pw, onDismiss = { fixDialog = false }) { lang, title, tabs, fields, bookmarks, tag, retag, ua ->
            fixDialog = false
            job.launch(fixing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_accessible")
                try { io {
                    com.smartdocs.editor.core.pdf.PdfOps.load(f, pw).use { d ->
                        // headings / lists / artifacts recognition (re-tags a tagged file when asked), with a report
                        if (tag) report = AccessAutoTag.tag(d, lang, retag)
                        if (lang.isNotBlank()) AccessFix.setLanguage(d, lang)
                        if (title.isNotBlank()) AccessFix.setTitle(d, title)
                        if (tabs) AccessFix.tabOrder(d)
                        if (fields) AccessFix.fieldDescriptions(d)
                        if (bookmarks) AccessFix.bookmarksFromHeadings(d)
                        if (ua) AccessUa.markUa(d)
                        d.save(out)
                    }
                } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { out.delete(); failed(e); return@launch }
                // the fixed file is the document transformed: the result sheet may offer "save in original"
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
                file = out
                result = out
            }
        }
    }
}

@Composable
private fun FixDialog(f: File, password: String?, onDismiss: () -> Unit, onApply: (String, String, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit) {
    var lang by remember { mutableStateOf("ar") }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(f) { title = runCatching { io { com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, password).use { AccessFix.suggestTitle(it, f.nameWithoutExtension) } } }.getOrDefault(f.nameWithoutExtension) }
    var tabs by remember { mutableStateOf(true) }
    var fields by remember { mutableStateOf(true) }
    var bookmarks by remember { mutableStateOf(true) }
    var tag by remember { mutableStateOf(true) }
    var retag by remember { mutableStateOf(false) }
    var ua by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.t5_acc_fix)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_title)) })
                OutlinedTextField(lang, { lang = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_lang)) })
                SwitchRow(stringResource(R.string.t5_acc_fix_tags), tag, stringResource(R.string.pc_acc_tag_hint)) { tag = it }
                if (tag) SwitchRow(stringResource(R.string.pc_acc_retag), retag, stringResource(R.string.pc_acc_retag_hint)) { retag = it }
                SwitchRow(stringResource(R.string.pc_acc_mark_ua), ua, stringResource(R.string.pc_acc_mark_ua_hint)) { ua = it }
                SwitchRow(stringResource(R.string.t5_acc_fix_tabs), tabs) { tabs = it }
                SwitchRow(stringResource(R.string.t5_acc_fix_fields), fields) { fields = it }
                SwitchRow(stringResource(R.string.t5_acc_fix_bookmarks), bookmarks) { bookmarks = it }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(lang, title, tabs, fields, bookmarks, tag, retag, ua) }) { Text(stringResource(R.string.t5_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Alt text of tagged figures: edit, AI suggestion (vision hook or page-text prompt), decorative. */
@Composable
private fun FiguresPanel(file: File, password: String?, origin: File?, figures: List<Pair<Int, String>>, onSaved: (File) -> Unit, onError: (Throwable) -> Unit) {
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
                                val s = PdfSession.open(ctx, file, password)
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
                            alts[i] = AltTextAi.suggest(ctx, settings, img, pageText, com.smartdocs.editor.core.data.AppLang.isArabic(settings))
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
                try {
                    io {
                        com.smartdocs.editor.core.pdf.PdfOps.load(file, password).use { d ->
                            AccessInspect.figures(d).forEachIndexed { i, fig ->
                                if (i < alts.size) { if (decorative[i]) AccessFix.markDecorative(fig) else AccessFix.setAlt(fig, alts[i]) }
                            }
                            d.save(out)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { out.delete(); onError(e); return@launchSafe }
                // audit 5 A-01: a transformation of the document (the result sheet may offer "save in original")
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, origin)
                onSaved(out)
            }
        }
    }
}

/** launch that swallows failures (the user sees the unchanged state) and always runs [onEnd]. */
private fun CoroutineScope.launchSafe(onEnd: () -> Unit, block: suspend () -> Unit) {
    launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { } finally { onEnd() } }
}
