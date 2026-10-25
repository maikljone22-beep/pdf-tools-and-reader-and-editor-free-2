package com.smartdocs.editor.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert.ConvertCancelled
import com.smartdocs.editor.core.convert.ConvertEngine
import com.smartdocs.editor.core.convert.ConvertOptions
import com.smartdocs.editor.core.convert.ConvertStats
import com.smartdocs.editor.core.convert.TargetFormat
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.TileColors
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.PageRangeField
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Output formats offered by the "from PDF" panel. [target] is null for images (rendered with PDFium). */
internal enum class CvFormat(val target: TargetFormat?) { WORD(TargetFormat.DOCX), EXCEL(TargetFormat.XLSX), PPT(TargetFormat.PPTX), HTML(TargetFormat.HTML), TXT(TargetFormat.TXT), IMAGES(null) }

/** Options chosen in the options sheet. */
internal data class CvOptions(
    val range: String = "",
    val detectTables: Boolean = true,
    val includeImages: Boolean = true,
    val singleSheet: Boolean = false,
    val pptEditable: Boolean = true,
    val png: Boolean = false,
    val dpi: Int = 150,
    val zip: Boolean = true,
)

/** Running conversion state shown in the progress dialog. */
internal class CvRun {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var label by mutableStateOf("")
    val cancel = AtomicBoolean(false)
    var job: Job? = null
}

@Composable
internal fun CvFormat.title(): String = when (this) {
    CvFormat.WORD -> stringResource(R.string.cv_word); CvFormat.EXCEL -> stringResource(R.string.cv_excel)
    CvFormat.PPT -> stringResource(R.string.cv_ppt); CvFormat.HTML -> stringResource(R.string.cv_html)
    CvFormat.TXT -> stringResource(R.string.cv_txt); CvFormat.IMAGES -> stringResource(R.string.cv_images)
}

@Composable
private fun CvFormat.subtitle(): String = when (this) {
    CvFormat.WORD -> stringResource(R.string.cv_word_sub); CvFormat.EXCEL -> stringResource(R.string.cv_excel_sub)
    CvFormat.PPT -> stringResource(R.string.cv_ppt_sub); CvFormat.HTML -> stringResource(R.string.cv_html_sub)
    CvFormat.TXT -> stringResource(R.string.cv_txt_sub); CvFormat.IMAGES -> stringResource(R.string.cv_images_sub)
}

private fun CvFormat.icon(): ImageVector = when (this) {
    CvFormat.WORD -> Icons.Default.Description; CvFormat.EXCEL -> Icons.Default.TableChart
    CvFormat.PPT -> Icons.Default.Slideshow; CvFormat.HTML -> Icons.Default.Code
    CvFormat.TXT -> Icons.Default.TextSnippet; CvFormat.IMAGES -> Icons.Default.Image
}

@Composable
private fun CvFormat.tile(): TileColors = when (this) {
    CvFormat.WORD -> Brand.tiles.blue; CvFormat.EXCEL -> Brand.tiles.green; CvFormat.PPT -> Brand.tiles.amber
    CvFormat.HTML -> Brand.tiles.purple; CvFormat.TXT -> Brand.tiles.teal; CvFormat.IMAGES -> Brand.tiles.rose
}

/** "From PDF" tab: format cards → options sheet → on-device conversion with page progress → ResultSheet. */
@Composable
internal fun ConvertFromPdfPanel(nav: NavHostController, uri: String?, snackbar: SnackbarHostState) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by rememberInputFile(uri)
    var format by remember { mutableStateOf<CvFormat?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    val options = remember { mutableStateMapOf<CvFormat, CvOptions>() }
    var password by remember { mutableStateOf("") }
    var needPassword by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var resultInfo by remember { mutableStateOf<String?>(null) }
    var imageResults by remember { mutableStateOf<List<File>>(emptyList()) }
    val run = remember { CvRun() }
    val pick = rememberPdfPicker { file = it.firstOrNull(); needPassword = false; password = "" }

    val sheetFmt = stringResource(R.string.cv_sheet_name)
    val allPagesName = stringResource(R.string.cv_all_pages_sheet)
    val pwMsg = stringResource(R.string.cv_password_needed)
    val failedFmt = stringResource(R.string.cv_failed)
    val cancelledMsg = stringResource(R.string.cv_cancelled)
    val oomMsg = stringResource(R.string.cv_out_of_memory)
    val badRange = stringResource(R.string.cv_bad_range)
    val statsFmt = stringResource(R.string.cv_stats)
    val scannedMsg = stringResource(R.string.cv_scanned_warning)
    val titles = CvFormat.entries.associateWith { it.title() }
    val convertingFmt = stringResource(R.string.cv_converting)

    fun start(fmt: CvFormat, o: CvOptions) {
        val src = file ?: return
        showSheet = false
        run.cancel.set(false); run.done = 0; run.total = 0; run.running = true
        run.label = convertingFmt.format(titles[fmt])
        run.job = scope.launch {
            try {
                val pw = password.ifBlank { null }
                val count = withContext(Dispatchers.IO) { ConvertEngine.pageCount(src, pw) }
                val pages = PdfOps.parseRange(o.range, count)
                if (pages.isEmpty()) { snackbar.showSnackbar(badRange); return@launch }
                val target = fmt.target
                if (target == null) {
                    val files = renderImages(ctx, src, pw, pages, o, app.documents.docsDir, { d, t -> run.done = d; run.total = t }, { run.cancel.get() })
                    if (o.zip) {
                        val zip = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_images", "zip")
                        withContext(Dispatchers.IO) {
                            ZipOutputStream(zip.outputStream().buffered()).use { z -> files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); f.delete() } }
                            files.firstOrNull()?.parentFile?.let { d -> if (d.listFiles().isNullOrEmpty()) d.delete() }
                        }
                        resultInfo = null; result = zip
                    } else imageResults = files
                } else {
                    val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension, target.ext)
                    val opts = ConvertOptions(
                        format = target, pages = pages, xlsxSingleSheet = o.singleSheet, pptxEditable = o.pptEditable,
                        detectTables = o.detectTables, includeImages = o.includeImages,
                        sheetName = { n -> sheetFmt.format(n) }, singleSheetName = allPagesName,
                    )
                    val stats: ConvertStats = ConvertEngine.convert(ctx, src, pw, opts, out, { d, t -> run.done = d; run.total = t }, { run.cancel.get() })
                    val scanned = stats.textPages == 0 && !(target == TargetFormat.PPTX && !o.pptEditable)
                    resultInfo = statsFmt.format(stats.pages, stats.tables, stats.images) + if (scanned) "\n$scannedMsg" else ""
                    result = out
                }
                needPassword = false
            } catch (e: ConvertEngine.PasswordNeeded) {
                needPassword = true; snackbar.showSnackbar(pwMsg)
            } catch (e: PdfSession.Companion.PasswordRequiredException) {
                needPassword = true; snackbar.showSnackbar(pwMsg)
            } catch (e: ConvertCancelled) {
                snackbar.showSnackbar(cancelledMsg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                snackbar.showSnackbar(oomMsg)
            } catch (e: Exception) {
                snackbar.showSnackbar(failedFmt.format(e.message ?: e.javaClass.simpleName))
            } finally {
                run.running = false
            }
        }
    }

    FilePickRow(file, stringResource(R.string.tl_input_file), pick)
    if (needPassword) {
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cv_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Lock, null) }, shape = RoundedCornerShape(16.dp))
    }
    FieldLabel(stringResource(R.string.cv_pick_format))
    FormatGrid(format) { f -> format = f; if (file != null) showSheet = true }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.OfflineBolt, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.cv_offline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    val f = format
    PrimaryButton(if (f != null) stringResource(R.string.cv_options_title, f.title()) else stringResource(R.string.cv_convert), enabled = file != null && f != null, icon = Icons.Default.SwapHoriz) {
        showSheet = true
    }
    if (imageResults.isNotEmpty()) ResultsList(imageResults, nav, if (options[CvFormat.IMAGES]?.png == true) "image/png" else "image/jpeg")

    if (showSheet && f != null) {
        ConvertOptionsSheet(f, options[f] ?: CvOptions(), onChange = { options[f] = it }, onDismiss = { showSheet = false }, onConvert = { start(f, options[f] ?: CvOptions()) })
    }
    if (run.running) ConvertProgressDialog(run) { run.cancel.set(true) }
    result?.let { r -> ResultSheet(r, nav, onDismiss = { result = null }, extraInfo = resultInfo) }
}

@Composable
private fun FormatGrid(selected: CvFormat?, onPick: (CvFormat) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth > 700.dp) 6 else if (maxWidth > 420.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CvFormat.entries.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { f -> FormatCard(f, f == selected, Modifier.weight(1f)) { onPick(f) } }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FormatCard(f: CvFormat, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(f.icon(), f.tile(), size = 46.dp, iconSize = 24.dp)
            Spacer(Modifier.height(8.dp))
            Text(f.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(f.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvertOptionsSheet(f: CvFormat, o: CvOptions, onChange: (CvOptions) -> Unit, onDismiss: () -> Unit, onConvert: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(f.icon(), f.tile(), size = 40.dp, iconSize = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.cv_options_title, f.title()), style = MaterialTheme.typography.titleLarge)
            }
            when (f) {
                CvFormat.WORD, CvFormat.HTML -> {
                    SwitchRow(stringResource(R.string.cv_detect_tables), stringResource(R.string.cv_detect_tables_sub), o.detectTables) { onChange(o.copy(detectTables = it)) }
                    SwitchRow(stringResource(R.string.cv_include_images), stringResource(R.string.cv_include_images_sub), o.includeImages) { onChange(o.copy(includeImages = it)) }
                }
                CvFormat.EXCEL -> {
                    FieldLabel(stringResource(R.string.cv_sheets))
                    ChoiceChips(listOf(false, true), o.singleSheet, { if (it) stringResource(R.string.cv_single_sheet) else stringResource(R.string.cv_sheet_per_page) }) { onChange(o.copy(singleSheet = it)) }
                    SwitchRow(stringResource(R.string.cv_detect_tables), stringResource(R.string.cv_detect_tables_sub), o.detectTables) { onChange(o.copy(detectTables = it)) }
                }
                CvFormat.PPT -> {
                    FieldLabel(stringResource(R.string.cv_ppt_mode))
                    ModeOption(stringResource(R.string.cv_ppt_editable), stringResource(R.string.cv_ppt_editable_sub), Icons.Default.EditNote, o.pptEditable) { onChange(o.copy(pptEditable = true)) }
                    ModeOption(stringResource(R.string.cv_ppt_image), stringResource(R.string.cv_ppt_image_sub), Icons.Default.Image, !o.pptEditable) { onChange(o.copy(pptEditable = false)) }
                    if (o.pptEditable) {
                        SwitchRow(stringResource(R.string.cv_detect_tables), stringResource(R.string.cv_detect_tables_sub), o.detectTables) { onChange(o.copy(detectTables = it)) }
                        SwitchRow(stringResource(R.string.cv_include_images), stringResource(R.string.cv_include_images_sub), o.includeImages) { onChange(o.copy(includeImages = it)) }
                    }
                }
                CvFormat.TXT -> SwitchRow(stringResource(R.string.cv_detect_tables), stringResource(R.string.cv_detect_tables_sub), o.detectTables) { onChange(o.copy(detectTables = it)) }
                CvFormat.IMAGES -> {
                    FieldLabel(stringResource(R.string.cv_img_format))
                    ChoiceChips(listOf(false, true), o.png, { if (it) stringResource(R.string.format_png) else stringResource(R.string.format_jpg) }) { onChange(o.copy(png = it)) }
                    FieldLabel(stringResource(R.string.cv_img_dpi))
                    ChoiceChips(listOf(72, 150, 300), o.dpi, { "$it" }) { onChange(o.copy(dpi = it)) }
                    FieldLabel(stringResource(R.string.cv_img_output))
                    ChoiceChips(listOf(true, false), o.zip, { if (it) stringResource(R.string.cv_img_zip) else stringResource(R.string.cv_img_separate) }) { onChange(o.copy(zip = it)) }
                }
            }
            PageRangeField(o.range) { onChange(o.copy(range = it)) }
            PrimaryButton(stringResource(R.string.cv_convert), icon = Icons.Default.SwapHoriz, onClick = onConvert)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun ModeOption(title: String, subtitle: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick)
        }
    }
}

@Composable
private fun ConvertProgressDialog(run: CvRun, onCancel: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp).widthIn(min = 260.dp, max = 360.dp)) {
                Text(run.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                val trackMod = Modifier.fillMaxWidth().height(6.dp)
                if (run.total <= 0) LinearProgressIndicator(trackMod)
                else LinearProgressIndicator(progress = { run.done.toFloat() / run.total }, modifier = trackMod)
                if (run.total > 0) Text(stringResource(R.string.cv_progress_pages, run.done.coerceAtMost(run.total), run.total), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onCancel, enabled = !run.cancel.get(), modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cv_cancel)) }
            }
        }
    }
}

/** Renders [pages] with PDFium into `<docsDir>/<name>_images/` with progress and cancellation. */
private suspend fun renderImages(
    ctx: android.content.Context, src: File, password: String?, pages: List<Int>, o: CvOptions, docsDir: File,
    progress: (Int, Int) -> Unit, cancelled: () -> Boolean,
): List<File> = withContext(Dispatchers.IO) {
    val dir = File(docsDir, src.nameWithoutExtension + "_images").apply { mkdirs() }
    val s = PdfSession.open(ctx, src, password)
    try {
        val out = ArrayList<File>()
        progress(0, pages.size)
        pages.forEachIndexed { k, i ->
            if (cancelled()) { out.forEach { it.delete() }; throw ConvertCancelled() }
            val (w, h) = s.pageSize(i)
            // keep a single page bitmap under ~100 MP so 300 dpi on large pages does not exhaust memory
            val scale = minOf(o.dpi / 72f, kotlin.math.sqrt(1.0e8 / (w * h)).toFloat())
            val bmp = s.render(i, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            val f = File(dir, "page_${i + 1}." + if (o.png) "png" else "jpg")
            f.outputStream().use { bmp.compress(if (o.png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            s.invalidateCache()
            out += f
            progress(k + 1, pages.size)
        }
        out
    } finally { s.closeAsync() }
}
