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
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.PageRangeField
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.FieldLabel
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold

@Composable
private fun SpaceCat.label(): String = stringResource(
    when (this) {
        SpaceCat.IMAGES -> R.string.t5_sp_images; SpaceCat.FONTS -> R.string.t5_sp_fonts; SpaceCat.CONTENT -> R.string.t5_sp_content
        SpaceCat.FORMS_XOBJECTS -> R.string.t5_sp_forms; SpaceCat.METADATA -> R.string.t5_sp_metadata; SpaceCat.ATTACHMENTS -> R.string.t5_sp_attachments
        SpaceCat.THUMBNAILS -> R.string.t5_sp_thumbs; SpaceCat.COLOR -> R.string.t5_sp_color; SpaceCat.ANNOTATIONS -> R.string.t5_sp_annots
        SpaceCat.OTHER -> R.string.t5_sp_other; SpaceCat.OVERHEAD -> R.string.t5_sp_overhead
    },
)

/** Bytes per category as bars (before / after). */
@Composable
fun SpaceReport(before: SpaceAudit, after: SpaceAudit?) {
    T5Card(stringResource(R.string.t5_space_title), Icons.Default.PieChart) {
        Text(
            if (after == null) DocumentRepository.formatSize(before.fileSize)
            else stringResource(R.string.t5_space_saved, DocumentRepository.formatSize(before.fileSize), DocumentRepository.formatSize(after.fileSize),
                (100 - (after.fileSize * 100 / before.fileSize.coerceAtLeast(1))).toInt()),
            style = MaterialTheme.typography.titleSmall,
        )
        SpaceCat.entries.filter { (before.bytes[it] ?: 0L) > 0 || (after?.bytes?.get(it) ?: 0L) > 0 }.sortedByDescending { before.bytes[it] ?: 0L }.forEach { c ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(DocumentRepository.formatSize(before.bytes[c] ?: 0L) + (after?.let { " → " + DocumentRepository.formatSize(it.bytes[c] ?: 0L) } ?: ""), style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(progress = { before.share(c) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                after?.let { a -> LinearProgressIndicator(progress = { a.share(c) * a.fileSize / before.fileSize.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = T5Green) }
            }
        }
    }
}

/**
 * Advanced optimizer with a space audit; "fast web view" is shown as not available (PdfBox 2.0). Nitro additions:
 * settings per image class (colour / grey / 1-bit with CCITT G4 / G3), JPEG or ZIP, unembedding fonts, removing
 * annotations / forms / links / bookmarks / named destinations, troubleshooting tips, and protected inputs.
 */
@Composable
fun OptimizeScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val snackbar = remember { SnackbarHostState() }
    var file by remember { mutableStateOf(fileOf(uri)) }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<SpaceAudit?>(null) }
    var after by remember { mutableStateOf<SpaceAudit?>(null) }
    var tips by remember { mutableStateOf<List<OptimizeTip>>(emptyList()) }
    var o by remember { mutableStateOf(OptimizeOptions()) }
    val job = rememberT5Job()
    val pick = rememberPdfPicker { file = it.firstOrNull(); password = null; audit = null; after = null; tips = emptyList() }
    val analysing = stringResource(R.string.t5_space_analysing)
    val optimizing = stringResource(R.string.t5_optimizing)
    LaunchedEffect(file, password) {
        val f = file ?: return@LaunchedEffect
        val pw = password
        job.launch(analysing) {
            try { audit = Optimizer.auditFile(f, pw) }
            catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { askPassword = true }
        }
    }
    if (askPassword) com.smartdocs.editor.ui.search.PasswordDialog(onDismiss = { askPassword = false }) { password = it; askPassword = false }

    ToolScaffold(stringResource(R.string.t5_opt_title), nav, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.t5_input_pdf), pick)
        audit?.let { SpaceReport(it, after) }
        T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
            if (o.separateClasses) FieldLabel(stringResource(R.string.nt_opt_color_images))
            LabeledSlider(stringResource(R.string.t5_opt_dpi), o.imageDpi.toFloat(), 72f..300f, { o = o.copy(imageDpi = it.toInt()) })
            LabeledSlider(stringResource(R.string.t5_opt_quality), o.jpegQuality.toFloat(), 30f..95f, { o = o.copy(jpegQuality = it.toInt()) })
            CompressionChips(o.colorMode) { o = o.copy(colorMode = it) }
            SwitchRow(stringResource(R.string.nt_opt_separate), o.separateClasses) { o = o.copy(separateClasses = it) }
            if (o.separateClasses) {
                FieldLabel(stringResource(R.string.nt_opt_gray_images))
                LabeledSlider(stringResource(R.string.t5_opt_dpi), o.grayDpi.toFloat(), 72f..300f, { o = o.copy(grayDpi = it.toInt()) })
                LabeledSlider(stringResource(R.string.t5_opt_quality), o.grayQuality.toFloat(), 30f..95f, { o = o.copy(grayQuality = it.toInt()) })
                CompressionChips(o.grayMode) { o = o.copy(grayMode = it) }
                FieldLabel(stringResource(R.string.nt_opt_mono_images))
                LabeledSlider(stringResource(R.string.t5_opt_dpi), o.monoDpi.toFloat(), 150f..600f, { o = o.copy(monoDpi = it.toInt()) })
            }
            if (o.separateClasses) FieldLabel(stringResource(R.string.nt_opt_mono_compression))
            else Text(stringResource(R.string.nt_opt_mono_compression), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(MonoCompression.entries, o.monoMode, { stringResource(when (it) {
                MonoCompression.KEEP -> R.string.nt_opt_keep; MonoCompression.CCITT_G4 -> R.string.nt_opt_g4; MonoCompression.CCITT_G3 -> R.string.nt_opt_g3; MonoCompression.ZIP -> R.string.nt_opt_zip }) }) { o = o.copy(monoMode = it) }
            SwitchRow(stringResource(R.string.t5_opt_gray), o.grayImages) { o = o.copy(grayImages = it) }
            SwitchRow(stringResource(R.string.t5_opt_dedup), o.dedupImages) { o = o.copy(dedupImages = it) }
            SwitchRow(stringResource(R.string.t5_opt_meta), o.stripMetadata) { o = o.copy(stripMetadata = it) }
            SwitchRow(stringResource(R.string.t5_opt_js), o.stripJavaScript) { o = o.copy(stripJavaScript = it) }
            SwitchRow(stringResource(R.string.t5_opt_thumbs), o.stripThumbnails) { o = o.copy(stripThumbnails = it) }
            SwitchRow(stringResource(R.string.t5_opt_attach), o.stripAttachments) { o = o.copy(stripAttachments = it) }
            SwitchRow(stringResource(R.string.t5_opt_flate), o.flateUncompressed) { o = o.copy(flateUncompressed = it) }
            SwitchRow(stringResource(R.string.t5_opt_smaller), o.onlyIfSmaller) { o = o.copy(onlyIfSmaller = it) }
            FieldLabel(stringResource(R.string.nt_opt_fonts))
            ChoiceChips(FontUnembed.entries, o.unembedFonts, { stringResource(when (it) {
                FontUnembed.NONE -> R.string.nt_opt_fonts_keep; FontUnembed.STANDARD -> R.string.nt_opt_fonts_standard; FontUnembed.ALL -> R.string.nt_opt_fonts_all }) }) { o = o.copy(unembedFonts = it) }
            if (o.unembedFonts == FontUnembed.ALL) Text(stringResource(R.string.nt_opt_fonts_all_warn), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            FieldLabel(stringResource(R.string.nt_opt_remove))
            SwitchRow(stringResource(R.string.nt_opt_rm_annots), o.removeAnnotations) { o = o.copy(removeAnnotations = it) }
            SwitchRow(stringResource(R.string.nt_opt_rm_forms), o.removeForms) { o = o.copy(removeForms = it) }
            SwitchRow(stringResource(R.string.nt_opt_rm_links), o.removeLinks) { o = o.copy(removeLinks = it) }
            SwitchRow(stringResource(R.string.nt_opt_rm_bookmarks), o.removeBookmarks) { o = o.copy(removeBookmarks = it) }
            SwitchRow(stringResource(R.string.nt_opt_rm_dests), o.removeNamedDests) { o = o.copy(removeNamedDests = it) }
            if (password != null) SwitchRow(stringResource(R.string.nt_opt_keep_protection), o.keepProtection) { o = o.copy(keepProtection = it) }
            SwitchRow(stringResource(R.string.t5_opt_fastweb), false, stringResource(R.string.t5_opt_fastweb_na), enabled = false) {}
        }
        if (tips.isNotEmpty()) T5Card(stringResource(R.string.nt_opt_tips), Icons.Default.Lightbulb) {
            tips.forEach { t -> Text("• " + stringResource(tipText(t)), style = MaterialTheme.typography.bodySmall) }
        }
        PrimaryButton(stringResource(R.string.t5_optimize), enabled = file != null && !job.running, icon = Icons.Default.Compress) {
            val f = file ?: return@PrimaryButton
            val opts = o; val pw = password
            job.launch(optimizing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_optimized")
                val r = Optimizer.optimize(f, pw, out, opts) { d, t -> step(d, t) }
                audit = r.before; after = r.after; tips = r.tips
                info = (if (r.keptOriginal) app.getString(R.string.t5_opt_kept) else app.getString(R.string.t5_opt_images_changed, r.imagesChanged)) +
                    (if (r.fontsUnembedded > 0) "\n" + app.getString(R.string.nt_opt_fonts_done, r.fontsUnembedded) else "")
                result = out
            }
        }
    }
    T5JobUi(job, nav, snackbar)
}

@Composable
private fun CompressionChips(mode: ImgCompression, onMode: (ImgCompression) -> Unit) =
    ChoiceChips(ImgCompression.entries, mode, { stringResource(when (it) { ImgCompression.AUTO -> R.string.nt_opt_auto; ImgCompression.JPEG -> R.string.nt_opt_jpeg; ImgCompression.ZIP -> R.string.nt_opt_zip }) }, onMode)

private fun tipText(t: OptimizeTip) = when (t) {
    OptimizeTip.LOWER_DPI -> R.string.nt_tip_dpi; OptimizeTip.LOWER_QUALITY -> R.string.nt_tip_quality; OptimizeTip.GRAY_IMAGES -> R.string.nt_tip_gray
    OptimizeTip.MONO_CCITT -> R.string.nt_tip_ccitt; OptimizeTip.FONTS -> R.string.nt_tip_fonts; OptimizeTip.ATTACHMENTS -> R.string.nt_tip_attach
    OptimizeTip.THUMBNAILS -> R.string.nt_tip_thumbs; OptimizeTip.ANNOTATIONS -> R.string.nt_tip_annots; OptimizeTip.FORMS -> R.string.nt_tip_forms
    OptimizeTip.CONTENT -> R.string.nt_tip_content; OptimizeTip.ALREADY_SMALL -> R.string.nt_tip_small
}

/** Export pages to PNG / JPG / WebP at any dpi (colour or grey, optional ZIP) and to RTF. */
@Composable
fun ExportPlusScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var file by remember { mutableStateOf(fileOf(uri)) }
    var tab by remember { mutableIntStateOf(0) }
    var o by remember { mutableStateOf(ImageExportOptions()) }
    var range by remember { mutableStateOf("") }
    val job = rememberT5Job()
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val exporting = stringResource(R.string.t5_exporting)

    ToolScaffold(stringResource(R.string.t5_export_title), nav, snackbar = snackbar) {
        FilePickRow(file, stringResource(R.string.t5_input_pdf), pick)
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.t5_tab_images)) })
            Tab(tab == 1, { tab = 1 }, text = { Text("RTF") })
        }
        PageRangeField(range) { range = it }
        if (tab == 0) {
            FieldLabel(stringResource(R.string.t5_format))
            ChoiceChips(ImageFormat.entries, o.format, { it.name }) { o = o.copy(format = it) }
            LabeledSlider(stringResource(R.string.t5_dpi), o.dpi.toFloat(), 72f..600f, { o = o.copy(dpi = (it / 6).toInt() * 6) })
            if (o.format != ImageFormat.PNG) LabeledSlider(stringResource(R.string.t5_opt_quality), o.quality.toFloat(), 40f..100f, { o = o.copy(quality = it.toInt()) })
            SwitchRow(stringResource(R.string.t5_gray), o.gray) { o = o.copy(gray = it) }
            SwitchRow(stringResource(R.string.t5_with_annots), o.annotations) { o = o.copy(annotations = it) }
            SwitchRow(stringResource(R.string.t5_zip), o.zip) { o = o.copy(zip = it) }
            PrimaryButton(stringResource(R.string.t5_export), enabled = file != null && !job.running, icon = Icons.Default.Image) {
                val f = file ?: return@PrimaryButton
                val opts = o; val r = range
                job.launch(exporting) {
                    val n = PdfOps.pageCount(f)
                    val pages = if (r.isBlank()) emptyList() else PdfOps.parseRange(r, n)
                    val dir = java.io.File(app.documents.docsDir, f.nameWithoutExtension + "_" + opts.format.ext)
                    val out = ExportPlus.images(ctx, f, null, opts.copy(pages = pages), dir) { d, t -> step(d, t) }
                    if (out.size == 1) result = out[0] else results = out
                }
            }
            if (job.results.isNotEmpty()) ResultsList(job.results, nav, if (o.format == ImageFormat.PNG) "image/png" else if (o.format == ImageFormat.JPG) "image/jpeg" else "image/webp")
        } else {
            Text(stringResource(R.string.t5_rtf_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(stringResource(R.string.t5_export_rtf), enabled = file != null && !job.running, icon = Icons.Default.Description) {
                val f = file ?: return@PrimaryButton
                val r = range
                job.launch(exporting) {
                    val n = PdfOps.pageCount(f)
                    val pages = if (r.isBlank()) emptyList() else PdfOps.parseRange(r, n)
                    val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension, "rtf")
                    result = ExportPlus.rtf(f, null, out, pages) { d, t -> step(d, t) }
                }
            }
        }
    }
    T5JobUi(job, nav, snackbar)
}
