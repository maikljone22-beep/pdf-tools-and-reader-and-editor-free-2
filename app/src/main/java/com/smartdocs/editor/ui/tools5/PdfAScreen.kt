package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.clickable
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
        // Stage G: level, PDF/X, PDF/E, print, version and Factur-X checks
        "a_layers" -> R.string.pc_pf_a_layers; "a_version14" -> R.string.pc_pf_a_version14; "a_jpx" -> R.string.pc_pf_a_jpx; "a_tags" -> R.string.pc_pf_a_tags
        "a_claim_other" -> R.string.pc_pf_a_claim_other; "x_claim" -> R.string.pc_pf_x_claim; "x_intent" -> R.string.pc_pf_x_intent; "x_trim" -> R.string.pc_pf_x_trim
        "x_trapped" -> R.string.pc_pf_x_trapped; "e_claim" -> R.string.pc_pf_e_claim; "hairline" -> R.string.pc_pf_hairline; "overprint" -> R.string.pc_pf_overprint
        "spot" -> R.string.pc_pf_spot; "ppi_low" -> R.string.pc_pf_ppi_low; "invisible" -> R.string.pc_pf_invisible; "courier" -> R.string.pc_pf_courier
        "v_needed" -> R.string.pc_pf_v_needed; "zf_xml" -> R.string.pc_pf_zf_xml; "zf_relationship" -> R.string.pc_pf_zf_relationship; "zf_af" -> R.string.pc_pf_zf_af
        "zf_xmp" -> R.string.pc_pf_zf_xmp; "zf_pdfa3" -> R.string.pc_pf_zf_pdfa3; "zf_ok" -> R.string.pc_pf_zf_ok
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Stage G (Foxit 8 / Nitro 20): level, PDF/UA claim, preflight profile + favourites, page range, standards panel
    val prefs = remember { ctx.getSharedPreferences("pc_preflight", android.content.Context.MODE_PRIVATE) }
    var level by remember { mutableStateOf(PdfALevel.A2B) }
    var ua by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(PreflightProfile.PDFA_2B) }
    var favs by remember { mutableStateOf(prefs.getStringSet("fav", emptySet()).orEmpty()) }
    var range by remember { mutableStateOf("") }
    var claims by remember { mutableStateOf<Claims?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    val job = rememberT5Job()
    val pick = rememberPdfPicker { file = it.firstOrNull(); report = null; after = null }
    val checking = stringResource(R.string.t5_pf_checking)
    val convertingMsg = stringResource(R.string.t5_pdfa_converting)

    fun check() {
        val f = file ?: return
        val pw = password.ifBlank { null }
        val prof = profile; val rg = range
        job.launch(checking) {
            report = io { (if (pw == null) PDDocument.load(f) else PDDocument.load(f, pw)).use { d ->
                claims = Standards.claims(d); pageCount = d.numberOfPages
                Standards.preflight(d, prof, com.smartdocs.editor.core.edit5.PageSet.parse(rg, d.numberOfPages))
            } }
        }
    }
    LaunchedEffect(file) { if (file != null) check() }

    ToolScaffold(stringResource(R.string.t5_pdfa_title), nav, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.t5_input_pdf), pick)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_password_if_any)) })
        Text(stringResource(R.string.t5_pdfa_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // ---- preflight profile, favourites first, page range
        T5Card(stringResource(R.string.pc_pf_profile), Icons.Default.Checklist) {
            val ordered = PreflightProfile.entries.sortedBy { if (it.code in favs) 0 else 1 }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ordered.forEach { p ->
                    FilterChip(profile == p, { profile = p; check() }, label = { Text(profileLabel(p)) }, trailingIcon = {
                        Icon(if (p.code in favs) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.pc_pf_favourite),
                            Modifier.size(18.dp).clickable { favs = if (p.code in favs) favs - p.code else favs + p.code; prefs.edit().putStringSet("fav", favs).apply() })
                    })
                }
            }
            OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
            OutlinedButton(onClick = { check() }, enabled = file != null && !job.running) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pc_acc_check_again)) }
        }
        report?.let { PreflightList(it) }
        // ---- report export (Foxit "Create report")
        report?.let { r ->
            val labels = r.findings.associate { it.code to findingTitle(it.code) }
            val profName = profileLabel(profile)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("html", "txt", "xml").forEach { fmt ->
                    OutlinedButton(onClick = {
                        val f = file ?: return@OutlinedButton
                        job.launch(checking) {
                            val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_preflight", fmt)
                            io { out.writeText(Standards.reportText(f.name, profName, r, { labels[it] ?: it }, fmt)) }
                            shareFiles(ctx, app.documents, listOf(out), if (fmt == "html") "text/html" else if (fmt == "xml") "application/xml" else "text/plain")
                        }
                    }) { Text(fmt.uppercase()) }
                }
            }
        }
        // ---- standards panel: what the file claims, remove a claim, save as a PDF version
        claims?.let { c ->
            T5Card(stringResource(R.string.pc_std_title), Icons.Default.Verified) {
                val rows = listOf("pdfaid" to c.pdfa, "pdfuaid" to c.pdfua, "pdfxid" to c.pdfx, "pdfe" to c.pdfe, "pdfvtid" to c.pdfvt, "fx" to c.facturX)
                if (rows.all { it.second == null }) Text(stringResource(R.string.pc_std_none), style = MaterialTheme.typography.bodySmall)
                rows.forEach { (prefix, v) ->
                    if (v != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (prefix != "pdfvtid" && prefix != "fx") TextButton(onClick = {
                            val f = file ?: return@TextButton
                            job.launch(convertingMsg) {
                                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_no_" + prefix)
                                io { PDDocument.load(f).use { d -> Standards.removeClaims(d, setOf(prefix)); d.save(out) } }
                                file = out; result = out
                            }
                        }) { Text(stringResource(R.string.pc_std_remove)) }
                    }
                }
                Text(stringResource(R.string.pc_std_version), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.4f, 1.5f, 1.6f, 1.7f).forEach { v ->
                        AssistChip(onClick = {
                            val f = file ?: return@AssistChip
                            job.launch(convertingMsg) {
                                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_v" + v.toString().replace('.', '_'))
                                io { PDDocument.load(f).use { d -> d.version = v; d.save(out) } }
                                result = out; profile = PreflightProfile.VERSION; file = out
                            }
                        }, label = { Text(v.toString()) })
                    }
                }
            }
        }
        T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
            SwitchRow(stringResource(R.string.t5_pdfa_raster), raster, stringResource(R.string.t5_pdfa_raster_hint)) { raster = it }
            SwitchRow(stringResource(R.string.t5_pdfa_flatten), flatten, stringResource(R.string.t5_pdfa_flatten_hint)) { flatten = it }
            if (!level.anyAttachments) SwitchRow(stringResource(R.string.t5_pdfa_attach), dropAttach, stringResource(R.string.t5_pdfa_attach_hint)) { dropAttach = it }
            Text(stringResource(R.string.pc_pdfa_level), style = MaterialTheme.typography.labelLarge)
            com.smartdocs.editor.ui.tools.ChoiceChips(PdfALevel.entries, level, { it.label }) { level = it }
            Text(stringResource(when { level.needsTags -> R.string.pc_pdfa_level_a; level.needsUnicode -> R.string.pc_pdfa_level_u; level.strict1 -> R.string.pc_pdfa_level_1; level.anyAttachments -> R.string.pc_pdfa_level_3; else -> R.string.pc_pdfa_level_b }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (level.needsTags) OutlinedTextField(lang, { lang = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_lang)) })
            SwitchRow(stringResource(R.string.pc_acc_mark_ua), ua, stringResource(R.string.pc_pdfa_ua_hint)) { ua = it }
        }
        PrimaryButton(stringResource(R.string.t5_pdfa_convert), enabled = file != null && !job.running, icon = Icons.Default.Inventory) {
            val f = file ?: return@PrimaryButton
            val pw = password.ifBlank { null }
            val opt = PdfAOptions(rasterizeProblemPages = raster, flattenTransparency = flatten, removeAttachments = dropAttach && !level.anyAttachments,
                level = level, uaPart = if (ua) 1 else null, lang = lang)
            job.launch(convertingMsg) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_" + opt.level.label.replace("/", "").replace("-", ""))
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


@Composable
private fun profileLabel(p: PreflightProfile): String = stringResource(when (p) {
    PreflightProfile.PDFA_1B -> R.string.pc_prof_a1b; PreflightProfile.PDFA_2B -> R.string.pc_prof_a2b; PreflightProfile.PDFA_2U -> R.string.pc_prof_a2u
    PreflightProfile.PDFA_3B -> R.string.pc_prof_a3b; PreflightProfile.PDFX_4 -> R.string.pc_prof_x4; PreflightProfile.PDFE_1 -> R.string.pc_prof_e1
    PreflightProfile.PRINT -> R.string.pc_prof_print; PreflightProfile.VERSION -> R.string.pc_prof_version; PreflightProfile.ZUGFERD -> R.string.pc_prof_zugferd
})
