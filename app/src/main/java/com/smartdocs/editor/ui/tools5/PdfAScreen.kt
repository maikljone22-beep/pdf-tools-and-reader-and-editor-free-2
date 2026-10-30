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
        "a_version20" -> R.string.pc_pf_a_version20; "x_claim_other" -> R.string.pc_pf_x_claim_other; "x_rgb" -> R.string.pc_pf_x_rgb; "vt_claim" -> R.string.pc_pf_vt_claim; "vt_dpart" -> R.string.pc_pf_vt_dpart
        "x_trapped" -> R.string.pc_pf_x_trapped; "e_claim" -> R.string.pc_pf_e_claim; "hairline" -> R.string.pc_pf_hairline; "overprint" -> R.string.pc_pf_overprint
        "spot" -> R.string.pc_pf_spot; "ppi_low" -> R.string.pc_pf_ppi_low; "invisible" -> R.string.pc_pf_invisible; "courier" -> R.string.pc_pf_courier
        "v_needed" -> R.string.pc_pf_v_needed; "zf_xml" -> R.string.pc_pf_zf_xml; "zf_relationship" -> R.string.pc_pf_zf_relationship; "zf_af" -> R.string.pc_pf_zf_af
        "zf_xmp" -> R.string.pc_pf_zf_xmp; "zf_pdfa3" -> R.string.pc_pf_zf_pdfa3; "zf_ok" -> R.string.pc_pf_zf_ok
        "ua_claim" -> R.string.pa_ua_claim; "ua_tagged" -> R.string.pa_ua_tagged; "ua_marked" -> R.string.pa_ua_marked; "ua_title" -> R.string.pa_ua_title
        "ua_display_title" -> R.string.pa_ua_display_title; "ua_lang" -> R.string.pa_ua_lang; "ua_untagged_content" -> R.string.pa_ua_untagged_content
        "ua_untagged_annots" -> R.string.pa_ua_untagged_annots; "ua_tabs" -> R.string.pa_ua_tabs; "ua_fonts" -> R.string.pa_ua_fonts; "ua_unicode" -> R.string.pa_ua_unicode
        "ua_alt" -> R.string.pa_ua_alt; "ua_other_alt" -> R.string.pa_ua_other_alt; "ua_table_headers" -> R.string.pa_ua_table_headers; "ua_headings" -> R.string.pa_ua_headings
        "ua_field_desc" -> R.string.pa_ua_field_desc; "ua_permission" -> R.string.pa_ua_permission; "ua_xfa" -> R.string.pa_ua_xfa; "ua_rolemap" -> R.string.pa_ua_rolemap
        "ua_note_id" -> R.string.pa_ua_note_id; "ua_ocg_name" -> R.string.pa_ua_ocg_name; "ua_embedded_names" -> R.string.pa_ua_embedded_names
        "ua_ok" -> R.string.pa_ua_ok; "ua_manual" -> R.string.pa_ua_manual
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
    // audit 5 A-01 / A-02: the reader's password first; [origin] is the document the outputs transform
    var origin by remember { mutableStateOf(fileOf(uri)) }
    var password by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(fileOf(uri)).orEmpty()) }
    var rasterPages by remember { mutableStateOf<List<Int>?>(null) }
    var report by remember { mutableStateOf<PreflightReport?>(null) }
    var after by remember { mutableStateOf<Pair<PreflightReport, Set<String>>?>(null) }
    // audit 5 A-02: off by default; when on, the pages that become pictures are listed
    var raster by remember { mutableStateOf(false) }
    var flatten by remember { mutableStateOf(false) }
    var dropAttach by remember { mutableStateOf(true) }
    // Stage G (Foxit 8 / Nitro 20): level, PDF/UA claim, preflight profile + favourites, page range, standards panel
    val prefs = remember { ctx.getSharedPreferences("pc_preflight", android.content.Context.MODE_PRIVATE) }
    var level by remember { mutableStateOf(PdfALevel.A2B) }
    var ua by remember { mutableStateOf(false) }
    var standard by remember { mutableStateOf("A") }   // FINAL_CHECK_2 Low: PDF/A, PDF/E-1 or PDF/X-4
    var lang by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(PreflightProfile.PDFA_2B) }
    var favs by remember { mutableStateOf(prefs.getStringSet("fav", emptySet()).orEmpty()) }
    var range by remember { mutableStateOf("") }
    var claims by remember { mutableStateOf<Claims?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    val job = rememberT5Job()
    val pick = rememberPdfPicker {
        val f = it.firstOrNull()
        file = f; origin = f; password = com.smartdocs.editor.core.pdf.SessionPasswords.get(f).orEmpty(); report = null; after = null; rasterPages = null
    }
    val checking = stringResource(R.string.t5_pf_checking)
    val convertingMsg = stringResource(R.string.t5_pdfa_converting)
    val outliningMsg = stringResource(R.string.ett_fx_running)

    fun check() {
        val f = file ?: return
        val pw = password.ifBlank { null }
        val prof = profile; val rg = range
        job.launch(checking) {
            try {
                report = io { com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, pw).use { d ->
                    claims = Standards.claims(d); pageCount = d.numberOfPages
                    Standards.preflight(d, prof, com.smartdocs.editor.core.edit5.PageSet.parse(rg, d.numberOfPages))
                } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                report = null; claims = null
                error = ctx.getString(if (com.smartdocs.editor.core.edit5.E5Locks.isPasswordError(e)) R.string.tte5_pdfa_password else R.string.tte5_pdfa_failed)
            }
        }
    }
    // audit 5 A-02: checked again when the password changes (after a short pause in typing)
    LaunchedEffect(file, password) { if (file != null) { kotlinx.coroutines.delay(if (password.isEmpty()) 0L else 600L); check() } }
    // audit 5 A-02: the pages the conversion would turn into pictures
    LaunchedEffect(file, password, raster, flatten, level, report) {
        val f = file
        rasterPages = if (f == null || report == null || !(raster || flatten || level.strict1)) null else runCatching {
            val opt = PdfAOptions(rasterizeProblemPages = raster, flattenTransparency = flatten, level = level)
            io { com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, password.ifBlank { null }).use { d -> PdfA.rasterPages(d, opt) } }
        }.getOrNull()
    }

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
                        if (prefix != "fx") TextButton(onClick = {   // FINAL_CHECK_2 Low (B #49): the PDF/VT claim can be removed too
                            val f = file ?: return@TextButton
                            val pw = password.ifBlank { null }; val src = origin
                            job.launch(convertingMsg) {
                                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_no_" + prefix)
                                try { io { com.smartdocs.editor.core.pdf.PdfOps.load(f, pw).use { d -> Standards.removeClaims(d, setOf(prefix)); d.save(out) } } }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { out.delete(); error = ctx.getString(R.string.tte5_pdfa_failed); return@launch }
                                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
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
                            val pw = password.ifBlank { null }; val src = origin
                            job.launch(convertingMsg) {
                                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_v" + v.toString().replace('.', '_'))
                                try { io { com.smartdocs.editor.core.pdf.PdfOps.load(f, pw).use { d -> d.version = v; d.save(out) } } }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { out.delete(); error = ctx.getString(R.string.tte5_pdfa_failed); return@launch }
                                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
                                result = out; profile = PreflightProfile.VERSION; file = out
                            }
                        }, label = { Text(v.toString()) })
                    }
                }
            }
        }
        // edit-text-tools: fixup "convert fonts to outlines" (DONE_pdf39 #187): every visible glyph of the page range
        // becomes a filled path (the edit-mode "text to outlines" engine), written to a new copy
        T5Card(stringResource(R.string.ett_fx_title), Icons.Default.Build) {
            Text(stringResource(R.string.ett_fx_outlines_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val f = file ?: return@OutlinedButton
                val pw = password.ifBlank { null }; val src = origin; val rg = range
                job.launch(outliningMsg) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_outlined")
                    val r = try {
                        val n = io { com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, pw).use { it.numberOfPages } }
                        val pages = com.smartdocs.editor.core.edit5.PageSet.parse(rg, n)
                        com.smartdocs.editor.core.edit.TextEdit5.outlineAll(f, out, pw, pages) { p -> step(pages.indexOf(p), pages.size) }
                    } catch (e: kotlinx.coroutines.CancellationException) { out.delete(); throw e } catch (e: Exception) {
                        out.delete()
                        error = ctx.getString(when {
                            e is IllegalArgumentException && e.message == "no outlines" -> R.string.ett_fx_none
                            com.smartdocs.editor.core.edit5.E5Locks.isPasswordError(e) -> R.string.tte5_pdfa_password
                            else -> R.string.ett_fx_failed
                        })
                        return@launch
                    }
                    com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
                    info = ctx.getString(R.string.ett_fx_done, r.first, r.second)
                    result = out
                }
            }, enabled = file != null && !job.running) { Icon(Icons.Default.Gesture, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ett_fx_outlines)) }
            // FINAL_CHECK_2 Low: fixup "convert colours to grayscale" (the converter's grayscale engine) to a new copy
            OutlinedButton(onClick = {
                val f = file ?: return@OutlinedButton
                val pw = password.ifBlank { null }; val src = origin
                job.launch(outliningMsg) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_gray")
                    try { com.smartdocs.editor.core.convert6.PdfTransforms6.grayscale(f, pw, out, app.documents.tempDir, ctx) { d, t -> step(d, t) } }
                    catch (e: kotlinx.coroutines.CancellationException) { out.delete(); throw e }
                    catch (e: Exception) { out.delete(); error = ctx.getString(if (com.smartdocs.editor.core.edit5.E5Locks.isPasswordError(e)) R.string.tte5_pdfa_password else R.string.ett_fx_failed); return@launch }
                    com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
                    result = out
                }
            }, enabled = file != null && !job.running) { Icon(Icons.Default.FilterBAndW, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_pf_fix_gray)) }
        }
        T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
            SwitchRow(stringResource(R.string.t5_pdfa_raster), raster, stringResource(R.string.t5_pdfa_raster_hint)) { raster = it }
            SwitchRow(stringResource(R.string.t5_pdfa_flatten), flatten, stringResource(R.string.t5_pdfa_flatten_hint)) { flatten = it }
            rasterPages?.takeIf { it.isNotEmpty() }?.let { pages ->
                Text(stringResource(R.string.tte5_pdfa_raster_pages, pages.size, pages.take(20).joinToString(", ") { (it + 1).toString() } + if (pages.size > 20) " …" else ""),
                    style = MaterialTheme.typography.bodySmall, color = T5Orange)
            }
            if (!level.anyAttachments) SwitchRow(stringResource(R.string.t5_pdfa_attach), dropAttach, stringResource(R.string.t5_pdfa_attach_hint)) { dropAttach = it }
            // FINAL_CHECK_2 Low: the standard written — PDF/A (a level), PDF/E-1 or PDF/X-4
            Text(stringResource(R.string.pc_standard), style = MaterialTheme.typography.labelLarge)
            com.smartdocs.editor.ui.tools.ChoiceChips(listOf("A", "E", "X"), standard, { when (it) { "E" -> "PDF/E-1"; "X" -> "PDF/X-4"; else -> "PDF/A" } }) { standard = it }
            if (standard == "A") {
                Text(stringResource(R.string.pc_pdfa_level), style = MaterialTheme.typography.labelLarge)
                com.smartdocs.editor.ui.tools.ChoiceChips(PdfALevel.entries, level, { it.label }) { level = it }
                Text(stringResource(when { level.part4 -> R.string.pc_pdfa_level_4; level.needsTags -> R.string.pc_pdfa_level_a; level.needsUnicode -> R.string.pc_pdfa_level_u; level.strict1 -> R.string.pc_pdfa_level_1; level.anyAttachments -> R.string.pc_pdfa_level_3; else -> R.string.pc_pdfa_level_b }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stringResource(if (standard == "E") R.string.pc_standard_e_hint else R.string.pc_standard_x_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (level.needsTags) OutlinedTextField(lang, { lang = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_lang)) })
            SwitchRow(stringResource(R.string.pc_acc_mark_ua), ua, stringResource(R.string.pc_pdfa_ua_hint)) { ua = it }
        }
        PrimaryButton(stringResource(R.string.t5_pdfa_convert), enabled = file != null && !job.running, icon = Icons.Default.Inventory) {
            val f = file ?: return@PrimaryButton
            val pw = password.ifBlank { null }
            val opt = PdfAOptions(rasterizeProblemPages = raster, flattenTransparency = flatten, removeAttachments = dropAttach && !level.anyAttachments,
                level = level, uaPart = if (ua) 1 else null, lang = lang, standard = standard)
            val src = origin
            job.launch(convertingMsg) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_" + (when (opt.standard) { "E" -> "PDFE1"; "X" -> "PDFX4"; else -> opt.level.label.replace("/", "").replace("-", "") }))
                val r = try { PdfA.convert(ctx, f, pw, out, opt, title = f.nameWithoutExtension) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    out.delete()
                    error = ctx.getString(when { e is PdfA.OwnerRequired -> R.string.tte5_pdfa_owner; com.smartdocs.editor.core.edit5.E5Locks.isPasswordError(e) -> R.string.tte5_pdfa_password; else -> R.string.tte5_pdfa_failed })
                    return@launch
                }
                // the converted file is the document transformed: the result sheet may offer "save in original"
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
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
    PreflightProfile.PDFA_4 -> R.string.pc_prof_a4; PreflightProfile.PDFX_1A -> R.string.pc_prof_x1a; PreflightProfile.PDFX_3 -> R.string.pc_prof_x3; PreflightProfile.PDFVT -> R.string.pc_prof_vt
    PreflightProfile.PRINT -> R.string.pc_prof_print; PreflightProfile.VERSION -> R.string.pc_prof_version; PreflightProfile.ZUGFERD -> R.string.pc_prof_zugferd
    PreflightProfile.PDFUA_1 -> R.string.pa_prof_ua1
})
