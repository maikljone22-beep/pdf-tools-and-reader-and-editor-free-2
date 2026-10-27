package com.smartdocs.editor.ui.nve.file

import android.content.Context
import android.print.PrintAttributes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.nve.PrintExtras
import com.smartdocs.editor.core.nve.PrintPresets
import com.smartdocs.editor.core.util.PdfPrint
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintOptions
import com.smartdocs.editor.core.view5.PrintPrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** NVE-file: printing helpers shared by the reader's print sheet and the batch print screen. */
object NvePrint {
    private const val PREFS = "nve_print"

    fun userPresets(ctx: Context): List<PrintPresets.Preset> = PrintPresets.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("presets", null))

    fun saveUserPresets(ctx: Context, list: List<PrintPresets.Preset>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("presets", PrintPresets.encode(list)).apply()
    }

    fun builtIns(ctx: Context) = PrintPresets.builtIns(listOf(R.string.nf_pp_default, R.string.nf_pp_handout, R.string.nf_pp_booklet, R.string.nf_pp_draft, R.string.nf_pp_review).map { ctx.getString(it) })

    /** The document's /ViewerPreferences print preset (read on IO). */
    suspend fun docPreset(file: File, pw: String?): PrintPresets.DocPreset? = withContext(Dispatchers.IO) {
        runCatching { com.smartdocs.editor.core.pdf.PdfOps.loadRaw(file, pw).use { PrintPresets.docPreset(it) } }.getOrNull()
    }

    private fun media(o: PrintOptions): PrintAttributes.MediaSize? = if (o.paperFromPage) null else when (PrintPresets.paperIndex(o.paper)) {
        1 -> PrintAttributes.MediaSize.NA_LETTER; 2 -> PrintAttributes.MediaSize.ISO_A3; 3 -> PrintAttributes.MediaSize.ISO_A5; 4 -> PrintAttributes.MediaSize.NA_LEGAL
        else -> PrintAttributes.MediaSize.ISO_A4
    }

    /** Prepared PDF for [o] (the source itself when nothing changes) and its page count. */
    suspend fun prepare(ctx: Context, src: File, pw: String?, o: PrintOptions, pageCount: Int): Pair<File, Int> {
        if (PrintPrep.isPlain(o, pageCount)) return src to pageCount
        val app = ctx.applicationContext as SmartDocsApp
        val out = app.documents.newTemp("print")
        val labels = PrintExtras.Labels(ctx.getString(R.string.nf_summary_title), ctx.getString(R.string.nf_summary_page), ctx.getString(R.string.nf_summary_none))
        val n = PrintPrep.build(src, pw, o, out, labels) { d -> com.smartdocs.editor.core.pdf.PdfOps.arabicFont(ctx, d) }
        return out to n
    }

    /** Prepare and hand to the system print dialog. */
    suspend fun print(ctx: Context, src: File, pw: String?, o: PrintOptions, pageCount: Int, jobName: String) {
        val (f, n) = prepare(ctx, src, pw, o, pageCount)
        PdfPrint.print(ctx, f, jobName, n, monochrome = o.grayscale, duplex = o.duplex, media = media(o))
    }

    /** One print job for several files: each prepared with [o], then concatenated. */
    suspend fun printMany(ctx: Context, files: List<Pair<File, String?>>, o: PrintOptions, combined: Boolean, jobName: String) {
        if (!combined) { files.forEach { (f, pw) -> print(ctx, f, pw, o.copy(range = ""), -1, f.nameWithoutExtension) }; return }
        val app = ctx.applicationContext as SmartDocsApp
        val out = app.documents.newTemp("print_batch")
        val parts = files.map { (f, pw) -> prepareAlways(ctx, f, pw, o.copy(range = "")) }
        val n = withContext(Dispatchers.IO) {
            val m = com.tom_roush.pdfbox.multipdf.PDFMergerUtility()
            parts.forEach { m.addSource(it) }
            m.destinationFileName = out.absolutePath
            m.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(out).use { it.numberOfPages }
        }
        PdfPrint.print(ctx, out, jobName, n, monochrome = o.grayscale, duplex = o.duplex, media = media(o))
    }

    /** Always a decrypted copy (merging needs readable inputs). */
    private suspend fun prepareAlways(ctx: Context, src: File, pw: String?, o: PrintOptions): File {
        val app = ctx.applicationContext as SmartDocsApp
        val out = app.documents.newTemp("print_part")
        val labels = PrintExtras.Labels(ctx.getString(R.string.nf_summary_title), ctx.getString(R.string.nf_summary_page), ctx.getString(R.string.nf_summary_none))
        PrintPrep.build(src, pw, o, out, labels) { d -> com.smartdocs.editor.core.pdf.PdfOps.arabicFont(ctx, d) }
        return out
    }
}

/** Preset chooser: built-in + user presets; save the current options as a new preset; delete user presets. */
@Composable
fun NvePresetRow(current: PrintOptions, onPick: (PrintOptions) -> Unit) {
    val ctx = LocalContext.current
    var user by remember { mutableStateOf(NvePrint.userPresets(ctx)) }
    val built = remember { NvePrint.builtIns(ctx) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<PrintPresets.Preset?>(null) }
    Text(stringResource(R.string.nf_presets), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        (built + user).forEach { p ->
            AssistChip(onClick = { onPick(p.options.copy(range = current.range, paper = if (p.builtIn) current.paper else p.options.paper)) },
                label = { Text(p.name) }, leadingIcon = { Icon(if (p.builtIn) Icons.Default.Tune else Icons.Default.Bookmark, null, Modifier.size(16.dp)) })
            if (!p.builtIn) IconButton(onClick = { menu = p }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete), Modifier.size(18.dp)) }
        }
        TextButton(onClick = { naming = true; name = "" }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nf_preset_save)) }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false },
        title = { Text(stringResource(R.string.nf_preset_save)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nf_preset_name)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            user = user.filter { it.name != name.trim() } + PrintPresets.Preset(name.trim(), current.copy(range = ""))
            NvePrint.saveUserPresets(ctx, user); naming = false
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } })
    menu?.let { p -> AlertDialog(onDismissRequest = { menu = null },
        title = { Text(p.name) }, text = { Text(stringResource(R.string.nf_preset_delete_q)) },
        confirmButton = { TextButton(onClick = { user = user - p; NvePrint.saveUserPresets(ctx, user); menu = null }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { menu = null }) { Text(stringResource(R.string.cancel)) } }) }
}

/**
 * Every print option except the page range: odd/even/reverse, comments, markups only, comment summary, grey,
 * pages per sheet (fixed or custom N x M), booklet (sides, binding), page scaling, auto-rotate, centre, paper.
 */
@Composable
fun NvePrintOptionsEditor(o: PrintOptions, onChange: (PrintOptions) -> Unit) {
    fun set(f: (PrintOptions) -> PrintOptions) = onChange(f(o))
    Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(o.subset == PrintLayout.Subset.ALL, { set { it.copy(subset = PrintLayout.Subset.ALL) } }, label = { Text(stringResource(R.string.v5_odd_even_all)) })
        FilterChip(o.subset == PrintLayout.Subset.ODD, { set { it.copy(subset = PrintLayout.Subset.ODD) } }, label = { Text(stringResource(R.string.v5_odd)) })
        FilterChip(o.subset == PrintLayout.Subset.EVEN, { set { it.copy(subset = PrintLayout.Subset.EVEN) } }, label = { Text(stringResource(R.string.v5_even)) })
        FilterChip(o.reverse, { set { it.copy(reverse = !it.reverse) } }, label = { Text(stringResource(R.string.v5_reverse)) })
    }
    SwitchLine(stringResource(R.string.v5_print_comments), o.annotations) { b -> set { it.copy(annotations = b) } }
    SwitchLine(stringResource(R.string.nf_markups_only), o.markupsOnly) { b -> set { it.copy(markupsOnly = b) } }
    SwitchLine(stringResource(R.string.nf_comment_summary), o.commentSummary) { b -> set { it.copy(commentSummary = b) } }
    SwitchLine(stringResource(R.string.nf_grayscale), o.grayscale) { b -> set { it.copy(grayscale = b) } }
    HorizontalDivider()
    Text(stringResource(R.string.v5_layout), style = MaterialTheme.typography.labelLarge)
    SwitchLine(stringResource(R.string.v5_booklet), o.booklet) { b -> set { it.copy(booklet = b) } }
    if (o.booklet) {
        Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(o.rightBinding, { set { it.copy(rightBinding = true) } }, label = { Text(stringResource(R.string.v5_bind_right)) })
            FilterChip(!o.rightBinding, { set { it.copy(rightBinding = false) } }, label = { Text(stringResource(R.string.v5_bind_left)) })
        }
        Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(PrintLayout.BookletSides.BOTH to R.string.nf_sides_both, PrintLayout.BookletSides.FRONT to R.string.nf_sides_front, PrintLayout.BookletSides.BACK to R.string.nf_sides_back).forEach { (v, l) ->
                FilterChip(o.bookletSides == v, { set { it.copy(bookletSides = v) } }, label = { Text(stringResource(l)) })
            }
        }
        if (o.bookletSides != PrintLayout.BookletSides.BOTH) Text(stringResource(R.string.nf_sides_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.v5_per_sheet), style = MaterialTheme.typography.labelMedium)
        Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4, 6, 9, 16).forEach { n -> FilterChip(o.perSheet == n, { set { it.copy(perSheet = n) } }, label = { Text(n.toString()) }) }
            FilterChip(o.perSheet == PrintLayout.CUSTOM_GRID, { set { it.copy(perSheet = PrintLayout.CUSTOM_GRID) } }, label = { Text(stringResource(R.string.nf_custom_grid)) })
        }
        if (o.perSheet == PrintLayout.CUSTOM_GRID) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IntField(o.cols, stringResource(R.string.nf_cols), Modifier.weight(1f)) { v -> set { it.copy(cols = v.coerceIn(1, 10)) } }
            Text("×")
            IntField(o.rows, stringResource(R.string.nf_rows), Modifier.weight(1f)) { v -> set { it.copy(rows = v.coerceIn(1, 10)) } }
        }
        if (o.perSheet != 1) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(o.borders, { b -> set { it.copy(borders = b) } }); Text(stringResource(R.string.v5_borders)) }
        if (o.perSheet == 1) {
            Text(stringResource(R.string.nf_scaling), style = MaterialTheme.typography.labelMedium)
            Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(PrintLayout.Scaling.NONE to R.string.nf_scale_none, PrintLayout.Scaling.FIT to R.string.nf_scale_fit, PrintLayout.Scaling.ACTUAL to R.string.nf_scale_actual,
                    PrintLayout.Scaling.SHRINK to R.string.nf_scale_shrink, PrintLayout.Scaling.CUSTOM to R.string.nf_scale_custom).forEach { (v, l) ->
                    FilterChip(o.scaling == v, { set { it.copy(scaling = v) } }, label = { Text(stringResource(l)) })
                }
            }
            if (o.scaling == PrintLayout.Scaling.CUSTOM) IntField(o.customScale, stringResource(R.string.nf_scale_percent), Modifier.fillMaxWidth()) { v -> set { it.copy(customScale = v.coerceIn(5, 800)) } }
            if (o.scaling != PrintLayout.Scaling.NONE) {
                SwitchLine(stringResource(R.string.nf_auto_rotate), o.autoRotate) { b -> set { it.copy(autoRotate = b) } }
                SwitchLine(stringResource(R.string.nf_center), o.center) { b -> set { it.copy(center = b) } }
            }
        }
    }
    Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PrintPresets.PAPERS.forEachIndexed { i, (n, r) -> FilterChip(!o.paperFromPage && PrintPresets.paperIndex(o.paper) == i, { set { it.copy(paper = r, paperFromPage = false) } }, label = { Text(n) }) }
        FilterChip(o.paperFromPage, { set { it.copy(paperFromPage = !it.paperFromPage) } }, label = { Text(stringResource(R.string.nf_paper_by_page)) })
        if (!o.booklet && !(o.perSheet == 1 && o.autoRotate && o.scaling != PrintLayout.Scaling.NONE)) FilterChip(o.landscape, { set { it.copy(landscape = !it.landscape) } }, label = { Text(stringResource(R.string.v5_landscape)) })
    }
    Text(stringResource(R.string.nf_duplex), style = MaterialTheme.typography.labelMedium)
    Row(hs(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("" to R.string.nf_duplex_default, "Simplex" to R.string.nf_duplex_none, "DuplexFlipLongEdge" to R.string.nf_duplex_long, "DuplexFlipShortEdge" to R.string.nf_duplex_short).forEach { (v, l) ->
            FilterChip(o.duplex == v, { set { it.copy(duplex = v) } }, label = { Text(stringResource(l)) })
        }
    }
    if (o.copies > 1) Text(stringResource(R.string.nf_copies_n, o.copies), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun hs(): Modifier = Modifier.horizontalScroll(rememberScrollState())

@Composable
internal fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(on, onChange)
    }
}

@Composable
internal fun IntField(value: Int, label: String, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { t -> text = t; com.smartdocs.editor.core.edit5.Digits.toWestern(t).trim().toIntOrNull()?.let(onChange) }, modifier, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
