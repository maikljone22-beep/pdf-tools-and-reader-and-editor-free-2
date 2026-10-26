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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.tom_roush.pdfbox.pdmodel.PDDocument

@Composable
private fun findingTitle(code: String): String = stringResource(
    when (code) {
        "encrypted" -> R.string.t5_pf_encrypted; "fonts" -> R.string.t5_pf_fonts; "js" -> R.string.t5_pf_js; "xmp" -> R.string.t5_pf_xmp
        "intent" -> R.string.t5_pf_intent; "cmyk" -> R.string.t5_pf_cmyk; "attachments" -> R.string.t5_pf_attachments; "multimedia" -> R.string.t5_pf_multimedia
        "lzw" -> R.string.t5_pf_lzw; "xfa" -> R.string.t5_pf_xfa; "annot_ap" -> R.string.t5_pf_annot_ap; "version" -> R.string.t5_pf_version
        "transparency" -> R.string.t5_pf_transparency; "unicode" -> R.string.t5_pf_unicode; "linearize" -> R.string.t5_pf_linearize
        else -> R.string.t5_pf_other
    },
)

/** Preflight findings list (shared by the PDF/A screen and its self-check after conversion). */
@Composable
fun PreflightList(report: PreflightReport, fixed: Set<String> = emptySet()) {
    val ok = stringResource(R.string.t5_pf_ok); val err = stringResource(R.string.t5_pf_error); val warn = stringResource(R.string.t5_pf_warning); val info = stringResource(R.string.t5_pf_info)
    T5Card(stringResource(R.string.t5_pf_report), Icons.Default.FactCheck) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (report.passes) stringResource(R.string.t5_pf_passes) else stringResource(R.string.t5_pf_fails, report.errors), if (report.passes) T5Green else T5Red)
            Spacer(Modifier.width(8.dp))
            report.facts.pdfaClaim?.let { StatusPill(it, MaterialTheme.colorScheme.primary) }
        }
        Text(stringResource(R.string.t5_pf_facts, report.facts.pages, report.facts.fonts.size, report.facts.images, report.facts.version.toString()), style = MaterialTheme.typography.bodySmall)
        if (fixed.isNotEmpty()) Text(stringResource(R.string.t5_pf_fixed_list, fixed.size), style = MaterialTheme.typography.bodySmall, color = T5Green)
        report.findings.forEach { f ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                val (label, color) = when (f.severity) { Severity.ERROR -> err to T5Red; Severity.WARNING -> warn to T5Orange; Severity.INFO -> info to T5Grey }
                StatusPill(if (f.fixed) ok else label, if (f.fixed) T5Green else color)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(findingTitle(f.code) + if (f.count > 0) " (${f.count})" else "", style = MaterialTheme.typography.bodyMedium)
                    if (f.pages.isNotEmpty()) Text(stringResource(R.string.t5_pf_pages, f.pages.take(12).joinToString { (it + 1).toString() }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (f.detail.isNotBlank()) Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
            }
        }
    }
}

/** PDF/A-2b: preflight report, conversion options, conversion with a self-check of the result. */
@Composable
fun PdfAScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var file by remember { mutableStateOf(fileOf(uri)) }
    var password by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<PreflightReport?>(null) }
    var after by remember { mutableStateOf<Pair<PreflightReport, Set<String>>?>(null) }
    var raster by remember { mutableStateOf(true) }
    var flatten by remember { mutableStateOf(false) }
    var dropAttach by remember { mutableStateOf(true) }
    val job = rememberT5Job()
    val pick = rememberPdfPicker { file = it.firstOrNull(); report = null; after = null }
    val checking = stringResource(R.string.t5_pf_checking)
    val convertingMsg = stringResource(R.string.t5_pdfa_converting)

    fun check() {
        val f = file ?: return
        val pw = password.ifBlank { null }
        job.launch(checking) { report = io { (if (pw == null) PDDocument.load(f) else PDDocument.load(f, pw)).use { PdfA.preflight(it) } } }
    }
    LaunchedEffect(file) { if (file != null) check() }

    ToolScaffold(stringResource(R.string.t5_pdfa_title), nav, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.t5_input_pdf), pick)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_password_if_any)) })
        Text(stringResource(R.string.t5_pdfa_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        report?.let { PreflightList(it) }
        T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
            SwitchRow(stringResource(R.string.t5_pdfa_raster), raster, stringResource(R.string.t5_pdfa_raster_hint)) { raster = it }
            SwitchRow(stringResource(R.string.t5_pdfa_flatten), flatten, stringResource(R.string.t5_pdfa_flatten_hint)) { flatten = it }
            SwitchRow(stringResource(R.string.t5_pdfa_attach), dropAttach, stringResource(R.string.t5_pdfa_attach_hint)) { dropAttach = it }
        }
        PrimaryButton(stringResource(R.string.t5_pdfa_convert), enabled = file != null && !job.running, icon = Icons.Default.Inventory) {
            val f = file ?: return@PrimaryButton
            val pw = password.ifBlank { null }
            val opt = PdfAOptions(rasterizeProblemPages = raster, flattenTransparency = flatten, removeAttachments = dropAttach)
            job.launch(convertingMsg) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_PDFA")
                val r = PdfA.convert(ctx, f, pw, out, opt, title = f.nameWithoutExtension)
                after = r
                info = if (r.first.passes) ctx.getString(R.string.t5_pdfa_ok) else ctx.getString(R.string.t5_pdfa_partial, r.first.errors)
                result = out
            }
        }
        after?.let { (r, fixed) ->
            Text(stringResource(R.string.t5_pdfa_selfcheck), style = MaterialTheme.typography.titleSmall)
            PreflightList(r, fixed)
        }
        Text(stringResource(R.string.t5_pdfa_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    T5JobUi(job, nav, snackbar)
}

