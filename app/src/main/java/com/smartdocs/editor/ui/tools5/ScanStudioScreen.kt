package com.smartdocs.editor.ui.tools5

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.FieldLabel
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One captured picture and its edits. */
data class ScanPage(val file: File, val edit: PageEdit)

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun ScanKind.label(): String = stringResource(
    when (this) {
        ScanKind.DOCUMENT -> R.string.t5_kind_document; ScanKind.ID_CARD -> R.string.t5_kind_id; ScanKind.BUSINESS_CARD -> R.string.t5_kind_card
        ScanKind.RECEIPT -> R.string.t5_kind_receipt; ScanKind.WHITEBOARD -> R.string.t5_kind_board; ScanKind.BOOK -> R.string.t5_kind_book
        ScanKind.BATCH -> R.string.t5_kind_batch
    },
)

/** Scan studio: kinds, per-page filters, auto OCR, smart names, business card → contact, receipt → CSV, batch. */
@Composable
fun ScanStudioScreen(nav: NavHostController, @Suppress("UNUSED_PARAMETER") uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { ctx.getSharedPreferences("t5_scan", Context.MODE_PRIVATE) }
    var kind by remember { mutableStateOf(runCatching { ScanKind.valueOf(prefs.getString("kind", "DOCUMENT")!!) }.getOrDefault(ScanKind.DOCUMENT)) }
    var groups by remember { mutableStateOf<List<List<ScanPage>>>(emptyList()) }
    var paper by remember { mutableStateOf(ScanPaper.FIT) }
    // full camera resolution and light compression (bigger files) instead of 2400 px / JPEG 82
    var highQuality by remember { mutableStateOf(ctx.getSharedPreferences("scan_studio", 0).getBoolean("high_quality", true)) }
    var autoOcr by remember { mutableStateOf(prefs.getBoolean("ocr", true)) }
    var langs by remember { mutableStateOf(prefs.getString("langs", "auto") ?: "auto") }
    var smartName by remember { mutableStateOf(prefs.getBoolean("smart", true)) }
    var rightFirst by remember { mutableStateOf(prefs.getBoolean("rtl_book", true)) }
    var name by remember { mutableStateOf("") }
    // Foxit 5: blank-page removal, N pages per file, document information, portfolio of the results, presets
    var removeBlank by remember { mutableStateOf(prefs.getBoolean("blank", false)) }
    var perFile by remember { mutableStateOf(prefs.getInt("per_file", 0)) }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf(prefs.getString("author", "") ?: "") }
    var portfolio by remember { mutableStateOf(false) }
    var pdfa by remember { mutableStateOf(prefs.getBoolean("pdfa", false)) }   // Nitro 20 / Foxit 8: PDF/A output at scan time
    var backsReversed by remember { mutableStateOf(true) }
    var presets by remember { mutableStateOf(ScanPreset.decodeAll(prefs.getString("presets", "") ?: "")) }
    var presetName by remember { mutableStateOf("") }
    // old plan K3: scans kept for later editing
    var keepProject by remember { mutableStateOf(prefs.getBoolean("keep_project", true)) }
    var projects by remember { mutableStateOf(emptyList<ScanProjects.Project>()) }
    LaunchedEffect(Unit) { projects = withContext(Dispatchers.IO) { runCatching { ScanProjects.list(ctx) }.getOrDefault(emptyList()) } }
    fun savePresets(list: List<ScanPreset>) { presets = list; prefs.edit().putString("presets", ScanPreset.encodeAll(list)).apply() }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var contact by remember { mutableStateOf<Contact?>(null) }
    var receipt by remember { mutableStateOf<Pair<Receipt, File>?>(null) }
    val job = rememberT5Job()
    fun savePrefs() = prefs.edit().putString("kind", kind.name).putBoolean("ocr", autoOcr).putString("langs", langs).putBoolean("smart", smartName).putBoolean("rtl_book", rightFirst)
        .putBoolean("blank", removeBlank).putInt("per_file", perFile).putString("author", author).apply()

    fun addPictures(files: List<File>, newGroup: Boolean) {
        val pages = files.map { ScanPage(it, PageEdit(filter = kind.defaultFilter(), flatten = kind == ScanKind.BOOK)) }
        groups = if (newGroup || groups.isEmpty() || kind == ScanKind.BATCH) groups + listOf(pages) else groups.dropLast(1) + listOf(groups.last() + pages)
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) scope.launch {
                val files = withContext(Dispatchers.IO) {
                    uris.mapIndexedNotNull { i, u ->
                        runCatching { val f = app.documents.newTemp("t5scan_$i", "jpg"); ctx.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { s.copyTo(it) } }; f }.getOrNull()
                    }
                }
                addPictures(files, kind == ScanKind.BATCH)
            }
        }
    }
    val unavailable = stringResource(R.string.t5_scan_unavailable)
    fun launchScanner() {
        val act = ctx.activity() ?: run { scope.launch { snackbar.showSnackbar(unavailable) }; return }
        runCatching {
            val limit = when (kind) { ScanKind.ID_CARD, ScanKind.BUSINESS_CARD -> 2; ScanKind.RECEIPT -> 5; else -> 0 }   // 0 = no page limit
            val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).apply { if (limit > 0) setPageLimit(limit) }
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { s -> runCatching { scanLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
                .addOnFailureListener { scope.launch { snackbar.showSnackbar(unavailable) } }
        }.onFailure { scope.launch { snackbar.showSnackbar(unavailable) } }
    }
    val pickGallery = rememberImagePicker { files -> addPictures(files, kind == ScanKind.BATCH) }

    val savingMsg = stringResource(R.string.t5_scan_saving)
    val prefixes = mapOf(ScanKind.RECEIPT to stringResource(R.string.t5_prefix_receipt), ScanKind.BUSINESS_CARD to stringResource(R.string.t5_prefix_card), ScanKind.ID_CARD to stringResource(R.string.t5_prefix_id), ScanKind.WHITEBOARD to stringResource(R.string.t5_prefix_board))
    fun save() {
        val all = groups.filter { it.isNotEmpty() }
        if (all.isEmpty()) return
        savePrefs()
        val needText = autoOcr || kind == ScanKind.BUSINESS_CARD || kind == ScanKind.RECEIPT || smartName
        val k = kind; val p = paper; val rf = rightFirst; val typed = name.trim()
        val rb = removeBlank; val tt = title; val au = author; val pf = portfolio; val kp = keepProject; val pa = pdfa
        prefs.edit().putBoolean("keep_project", kp).putBoolean("pdfa", pa).apply()
        val keep = all.map { g -> g.map { it.file to it.edit } }
        // "save multiple files": every group is cut into files of N pages
        val docs = all.flatMap { ScanExtras.chunks(it, perFile) }
        job.launch(savingMsg) {
            val engine = if (needText) OcrEngine(ctx.applicationContext) else null
            try {
                val made = ArrayList<File>(); var lastText = ""
                docs.forEachIndexed { gi, g ->
                    val today = t5Today()
                    val tmpOut = app.documents.newTemp("t5scan_out", "pdf")
                    val ocrLangs = if (!needText) null else if (langs == "auto") detectLangs(engine!!, g.first()) else langs
                    val pictures = ScanBuilder.pagePictures(g.map { it.file to it.edit }, k, rf, maxDim = if (highQuality) 4096 else 2400)
                    val r = ScanBuilder.build(ctx, pictures, tmpOut, ScanBuildOptions(paper = p, quality = if (highQuality) 92 else 82, ocrLangs = ocrLangs), engine) { n -> step(gi * 100 + n, docs.size * 100) }
                    val base = when {
                        typed.isNotEmpty() -> if (docs.size > 1) "${typed}_${gi + 1}" else typed
                        smartName && r.text.isNotBlank() -> SmartNaming.suggest(r.text, k, today, prefixes)
                        else -> (prefixes[k]?.let { "${it}_" } ?: "Scan_") + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + if (docs.size > 1) "_${gi + 1}" else ""
                    }
                    if (rb) runCatching {
                        val clean = app.documents.newTemp("t5scan_clean", "pdf")
                        if (com.smartdocs.editor.core.edit5.PageOrganize.deleteBlank(ctx, tmpOut, clean, null).isNotEmpty()) { clean.copyTo(tmpOut, true) }
                        clean.delete()
                    }
                    withContext(Dispatchers.IO) { runCatching { ScanExtras.setInfo(tmpOut, if (docs.size > 1 && tt.isNotBlank()) "$tt (${gi + 1})" else tt, au) } }
                    if (pa) runCatching {
                        val a = app.documents.newTemp("t5scan_pdfa", "pdf")
                        PdfA.convert(ctx, tmpOut, null, a, PdfAOptions(level = PdfALevel.A2B), title = tt.ifBlank { null })
                        withContext(Dispatchers.IO) { a.copyTo(tmpOut, true); a.delete() }
                    }
                    val out = app.documents.uniqueFile(app.documents.scansDir, base)
                    withContext(Dispatchers.IO) { if (!tmpOut.renameTo(out)) { tmpOut.copyTo(out, true); tmpOut.delete() } }
                    runCatching { app.documents.touchRecent(out, r.pages) }
                    made += out; lastText = r.text
                }
                // when OCR was only needed for naming / parsing, the text layer stays: it makes the scan searchable
                when {
                    k == ScanKind.BUSINESS_CARD -> contact = BusinessCardParser.parse(lastText)
                    k == ScanKind.RECEIPT -> receipt = ReceiptParser.parse(lastText) to made.last()
                    made.size == 1 -> result = made.first()
                    else -> results = made
                }
                if (k == ScanKind.BUSINESS_CARD || k == ScanKind.RECEIPT) results = made
                // Foxit "create a portfolio for all results"
                if (pf && made.size > 1) {
                    val po = app.documents.uniqueFile(app.documents.scansDir, (typed.ifEmpty { "Scans" }) + "_portfolio")
                    com.smartdocs.editor.core.tools5.Portfolio.create(ctx, typed.ifEmpty { "Scans" }, made, po)
                    results = made + po
                }
                if (kp) withContext(Dispatchers.IO) {
                    runCatching { ScanProjects.save(ctx, made.firstOrNull()?.nameWithoutExtension ?: "Scan", keep) }
                    projects = runCatching { ScanProjects.list(ctx) }.getOrDefault(projects)
                }
                groups = emptyList(); name = ""
            } finally { engine?.close() }
        }
    }

    ToolScaffold(stringResource(R.string.t5_scan_title), nav, snackbar = snackbar, actions = {
        if (groups.isNotEmpty()) IconButton(onClick = { groups = emptyList() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.t5_clear)) }
    }) {
        FieldLabel(stringResource(R.string.t5_scan_kind))
        ChoiceChips(ScanKind.entries, kind, { it.label() }) { kind = it; savePrefs() }
        Text(stringResource(kindHint(kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launchScanner() }, Modifier.weight(1f)) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text(stringResource(if (groups.isEmpty()) R.string.t5_scan_start else R.string.t5_scan_more)) }
            OutlinedButton(onClick = pickGallery, Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_from_gallery)) }
        }
        groups.forEachIndexed { gi, g ->
            T5Card(if (groups.size > 1) stringResource(R.string.t5_scan_doc_n, gi + 1, g.size) else stringResource(R.string.t5_scan_pages_n, g.size), Icons.Default.Collections) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(g, key = { i, p -> "$i:" + p.file.absolutePath }) { pi, p -> PageThumb(p, pi) { editing = gi to pi } }
                }
            }
        }
        // manual duplex: the first document holds the fronts, the second the backs
        if (groups.size == 2) T5Card(stringResource(R.string.pc_duplex), Icons.Default.FlipToBack) {
            Text(stringResource(R.string.pc_duplex_help), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.pc_duplex_reversed), backsReversed) { backsReversed = it }
            OutlinedButton(onClick = { groups = listOf(ScanExtras.duplex(groups[0], groups[1], backsReversed)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.pc_duplex_merge)) }
        }
        if (groups.isEmpty() && projects.isNotEmpty()) T5Card(stringResource(R.string.pc_saved_scans), Icons.Default.History) {
            projects.take(6).forEach { pr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pc_saved_scan_row, pr.name, pr.pages), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    TextButton(onClick = { scope.launch {
                        val g = withContext(Dispatchers.IO) { runCatching { ScanProjects.load(pr) }.getOrDefault(emptyList()) }
                        groups = g.map { pages -> pages.map { (f, e) -> ScanPage(f, e) } }; name = pr.name
                    } }) { Text(stringResource(R.string.pc_saved_scan_open)) }
                    IconButton(onClick = { ScanProjects.delete(pr); projects = projects - pr }) { Icon(Icons.Default.Delete, stringResource(R.string.pc_preset_delete, pr.name)) }
                }
            }
        }
        T5Card(stringResource(R.string.pc_presets), Icons.Default.Bookmarks) {
            if (presets.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { ps ->
                    InputChip(false, onClick = {
                        kind = ps.kind; paper = ps.paper; autoOcr = ps.ocr; langs = ps.langs; removeBlank = ps.removeBlank; perFile = ps.perFile; author = ps.author; savePrefs()
                    }, label = { Text(ps.name) }, trailingIcon = {
                        Icon(Icons.Default.Close, stringResource(R.string.pc_preset_delete, ps.name), Modifier.size(18.dp).clickable { savePresets(presets - ps) })
                    })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(presetName, { presetName = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_preset_name)) })
                TextButton(enabled = presetName.isNotBlank(), onClick = {
                    val ps = ScanPreset(presetName.trim(), kind, paper, autoOcr, langs, removeBlank, perFile, author)
                    savePresets(presets.filter { it.name != ps.name } + ps); presetName = ""
                }) { Text(stringResource(R.string.pc_preset_save)) }
            }
        }
        if (groups.isNotEmpty()) {
            T5Card(stringResource(R.string.t5_scan_output), Icons.Default.Tune) {
                FieldLabel(stringResource(R.string.t5_paper))
                ChoiceChips(ScanPaper.entries, paper, { when (it) {
                    ScanPaper.FIT -> stringResource(R.string.t5_paper_fit); ScanPaper.A4 -> stringResource(R.string.t5_paper_a4); ScanPaper.LETTER -> stringResource(R.string.t5_paper_letter)
                    ScanPaper.CARD -> stringResource(R.string.pc_paper_card); else -> it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.let { n -> if (n.length == 2) n.uppercase() else n }
                } }) { paper = it }
                SwitchRow(stringResource(R.string.sc_high_quality), highQuality, stringResource(R.string.sc_high_quality_sub)) {
                    highQuality = it; ctx.getSharedPreferences("scan_studio", 0).edit().putBoolean("high_quality", it).apply()
                }
                SwitchRow(stringResource(R.string.pc_scan_remove_blank), removeBlank) { removeBlank = it }
                OutlinedTextField(if (perFile > 0) perFile.toString() else "", { t -> perFile = com.smartdocs.editor.core.edit5.Digits.toWestern(t).filter { it.isDigit() }.take(3).toIntOrNull() ?: 0 },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_scan_per_file)) }, placeholder = { Text(stringResource(R.string.pc_scan_per_file_all)) })
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_doc_title)) })
                OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_doc_author)) })
                SwitchRow(stringResource(R.string.pc_save_pdfa), pdfa, stringResource(R.string.pc_save_pdfa_sub)) { pdfa = it }
                SwitchRow(stringResource(R.string.pc_keep_project), keepProject, stringResource(R.string.pc_keep_project_sub)) { keepProject = it }
                if (groups.size > 1 || perFile > 0 || kind == ScanKind.BATCH) SwitchRow(stringResource(R.string.pc_scan_portfolio), portfolio) { portfolio = it }
                SwitchRow(stringResource(R.string.t5_auto_ocr), autoOcr, stringResource(R.string.t5_auto_ocr_hint)) { autoOcr = it }
                if (autoOcr || kind == ScanKind.BUSINESS_CARD || kind == ScanKind.RECEIPT) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto", "ara", "eng", "ara+eng", "fra", "ara+fra").forEach { l ->
                            FilterChip(langs == l, { langs = l }, label = { Text(if (l == "auto") stringResource(R.string.t5_lang_auto) else l.split('+').joinToString(" + ") { OcrEngine.label(it) }) })
                        }
                    }
                }
                SwitchRow(stringResource(R.string.t5_smart_name), smartName, stringResource(R.string.t5_smart_name_hint)) { smartName = it }
                if (kind == ScanKind.BOOK) SwitchRow(stringResource(R.string.t5_book_rtl), rightFirst, stringResource(R.string.t5_book_rtl_hint)) { rightFirst = it }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_file_name_optional)) })
            }
            PrimaryButton(stringResource(R.string.t5_scan_save), enabled = !job.running, icon = Icons.Default.PictureAsPdf) { save() }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
    }
    T5JobUi(job, nav, snackbar)
    editing?.let { (gi, pi) ->
        val page = groups.getOrNull(gi)?.getOrNull(pi)
        if (page == null) editing = null else ScanPageEditor(
            page = page,
            onApply = { e, all -> groups = groups.mapIndexed { i, g -> if (i != gi) g else g.mapIndexed { j, p -> if (j == pi || all) p.copy(edit = if (j == pi) e else p.edit.copy(filter = e.filter, brightness = e.brightness, contrast = e.contrast)) else p } }; editing = null },
            onDelete = { groups = groups.mapIndexed { i, g -> if (i != gi) g else g.filterIndexed { j, _ -> j != pi } }.filter { it.isNotEmpty() }; editing = null },
            onMove = { d -> groups = groups.mapIndexed { i, g -> if (i != gi) g else g.toMutableList().apply { val t = (pi + d).coerceIn(0, size - 1); add(t, removeAt(pi)) } }; editing = null },
            onDismiss = { editing = null },
        )
    }
    contact?.let { c -> ContactSheet(c, onDismiss = { contact = null }) }
    receipt?.let { (r, f) -> ReceiptSheet(r, f, onDismiss = { receipt = null }) }
}

private fun kindHint(k: ScanKind): Int = when (k) {
    ScanKind.DOCUMENT -> R.string.t5_kind_document_hint; ScanKind.ID_CARD -> R.string.t5_kind_id_hint; ScanKind.BUSINESS_CARD -> R.string.t5_kind_card_hint
    ScanKind.RECEIPT -> R.string.t5_kind_receipt_hint; ScanKind.WHITEBOARD -> R.string.t5_kind_board_hint; ScanKind.BOOK -> R.string.t5_kind_book_hint
    ScanKind.BATCH -> R.string.t5_kind_batch_hint
}

/** Language auto-detection on the first page of a group. */
private suspend fun detectLangs(engine: OcrEngine, first: ScanPage): String = withContext(Dispatchers.IO) {
    val b = runCatching { ScanImage.load(first.file, 1600) }.getOrNull() ?: return@withContext "ara+eng"
    val e = runCatching { ScanImage.apply(b, first.edit) }.getOrNull() ?: return@withContext "ara+eng"
    try { OcrRunner.detectLanguages(engine, e) } catch (x: Exception) { "ara+eng" } finally { e.recycle() }
}

@Composable
private fun PageThumb(p: ScanPage, index: Int, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, p) { value = withContext(Dispatchers.Default) { runCatching { ScanImage.preview(p.file, p.edit, 360) }.getOrNull() } }
    val label = stringResource(R.string.t5_page_n, index + 1)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp).clickable(onClickLabel = stringResource(R.string.t5_edit_page), onClick = onClick)) {
        Box(Modifier.size(88.dp, 118.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

