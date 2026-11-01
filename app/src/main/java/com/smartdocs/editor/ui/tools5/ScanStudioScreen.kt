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
import androidx.compose.ui.res.pluralStringResource
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
        ScanKind.BATCH -> R.string.t5_kind_batch; ScanKind.FORM -> R.string.t5_kind_form
    },
)

/** Scan studio: kinds, per-page filters, auto OCR, smart names, business card → contact, receipt → CSV, batch. */
@Composable
fun ScanStudioScreen(nav: NavHostController, @Suppress("UNUSED_PARAMETER") uri: String?, autoStart: Boolean = false) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { ctx.getSharedPreferences("t5_scan", Context.MODE_PRIVATE) }
    var kind by remember { mutableStateOf(runCatching { ScanKind.valueOf(prefs.getString("kind", "DOCUMENT")!!) }.getOrDefault(ScanKind.DOCUMENT)) }
    var groups by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ScanGroupsSaver) { mutableStateOf<List<List<ScanPage>>>(emptyList()) }
    var paper by remember { mutableStateOf(ScanPaper.FIT) }
    // full camera resolution and light compression (bigger files) instead of 2400 px / JPEG 82
    var highQuality by remember { mutableStateOf(ctx.getSharedPreferences("scan_studio", 0).getBoolean("high_quality", true)) }
    var ownCamera by remember { mutableStateOf(ctx.getSharedPreferences("scan_studio", 0).getBoolean("own_camera", true)) }
    var cameraOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var started by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // the camera was refused: this launch uses Google's scanner
    var useGoogleOnce by remember { mutableStateOf(false) }
    var lastScan by remember { mutableStateOf<File?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<ScanProjects.Project?>(null) }
    var askClear by remember { mutableStateOf(false) }
    /** The picture copies in the temp folder (camera / Google scanner) — not pages reopened from a saved project. */
    fun dropTemp(pages: List<ScanPage>) {
        val tmp = runCatching { app.documents.tempDir.canonicalPath }.getOrNull() ?: return
        pages.forEach { p -> runCatching { if (p.file.canonicalPath.startsWith(tmp)) p.file.delete() } }
    }
    var recent by remember { mutableStateOf(emptyList<File>()) }
    fun refreshRecent() { recent = (app.documents.scansDir.listFiles() ?: emptyArray()).filter { it.extension.equals("pdf", true) }.sortedByDescending { it.lastModified() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { refreshRecent() } }
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

    /** A new kind; pages still on the old kind's defaults follow it (deep audit E8). */
    fun changeKind(nk: ScanKind) {
        val old = kind
        groups = groups.map { g -> g.map { p -> if (p.edit.filter == old.defaultFilter() && p.edit.flatten == (old == ScanKind.BOOK)) p.copy(edit = p.edit.copy(filter = nk.defaultFilter(), flatten = nk == ScanKind.BOOK)) else p } }
        kind = nk; savePrefs()
    }

    fun addPictures(files: List<File>, newGroup: Boolean, crops: List<FloatArray?> = emptyList()) {
        val pages = files.mapIndexed { i, f -> ScanPage(f, PageEdit(filter = kind.defaultFilter(), flatten = kind == ScanKind.BOOK, crop = crops.getOrNull(i))) }
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
        val limit = when (kind) { ScanKind.ID_CARD, ScanKind.BUSINESS_CARD -> 2; ScanKind.RECEIPT -> 5; else -> 0 }   // 0 = no page limit
        if (ownCamera && !useGoogleOnce) { cameraOpen = true; return }
        useGoogleOnce = false
        val act = ctx.activity() ?: run { scope.launch { snackbar.showSnackbar(unavailable) }; return }
        runCatching {
            val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).apply { if (limit > 0) setPageLimit(limit) }
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { s -> runCatching { scanLauncher.launch(IntentSenderRequest.Builder(s).build()) } }
                .addOnFailureListener { scope.launch { snackbar.showSnackbar(unavailable) } }
        }.onFailure { scope.launch { snackbar.showSnackbar(unavailable) } }
    }
    // S3: photos from the gallery get their page edges found and corrected like camera photos (they were used as is)
    val pickGallery = rememberImagePicker { files ->
        scope.launch {
            val crops = withContext(Dispatchers.Default) { files.map { f -> runCatching { ScanEdges.detect(f) }.getOrNull() } }
            addPictures(files, kind == ScanKind.BATCH, crops)
        }
    }
    if (cameraOpen) ScanCameraScreen(
        pageLimit = when (kind) { ScanKind.ID_CARD, ScanKind.BUSINESS_CARD -> 2; ScanKind.RECEIPT -> 5; else -> 0 },
        onDone = { shots -> cameraOpen = false; if (shots.isNotEmpty()) addPictures(shots.map { it.first }, kind == ScanKind.BATCH, shots.map { (_, q) -> q?.let { it.x + it.y } }) },
        onClose = { cameraOpen = false },
        onUseGoogle = { cameraOpen = false; useGoogleOnce = true; launchScanner() },
        kind = kind,
        onKind = { nk -> changeKind(nk) },
        onGallery = { shots -> cameraOpen = false; if (shots.isNotEmpty()) addPictures(shots.map { it.first }, kind == ScanKind.BATCH, shots.map { (_, q) -> q?.let { it.x + it.y } }); pickGallery() },
    )
    // S1: opened from "Scan" — straight to the camera, once (not again when coming back)
    LaunchedEffect(Unit) { if (autoStart && !started && groups.isEmpty()) { started = true; launchScanner() } }

    val savingMsg = stringResource(R.string.t5_scan_saving)
    val prefixes = mapOf(ScanKind.RECEIPT to stringResource(R.string.t5_prefix_receipt), ScanKind.BUSINESS_CARD to stringResource(R.string.t5_prefix_card), ScanKind.ID_CARD to stringResource(R.string.t5_prefix_id), ScanKind.WHITEBOARD to stringResource(R.string.t5_prefix_board), ScanKind.FORM to stringResource(R.string.t5_prefix_form))
    fun save() {
        val all = groups.filter { it.isNotEmpty() }
        if (all.isEmpty()) return
        savePrefs()
        val needText = autoOcr || kind == ScanKind.BUSINESS_CARD || kind == ScanKind.RECEIPT || kind == ScanKind.FORM || smartName   // FINAL_CHECK_2 #16: a form needs its text for field recognition
        val k = kind; val p = paper; val rf = rightFirst; val typed = name.trim()
        val rb = removeBlank; val tt = title; val au = author; val pf = portfolio; val kp = keepProject; val pa = pdfa
        prefs.edit().putBoolean("keep_project", kp).putBoolean("pdfa", pa).apply()
        val keep = all.map { g -> g.map { it.file to it.edit } }
        // "save multiple files": every group is cut into files of N pages
        val docs = all.flatMap { ScanExtras.chunks(it, perFile) }
        job.launch(savingMsg) {
            val engine = if (needText) OcrEngine(ctx.applicationContext) else null
            try {
                val made = ArrayList<File>(); var lastText = ""; var fieldsMade = 0
                docs.forEachIndexed { gi, g ->
                    val today = t5Today()
                    val tmpOut = app.documents.newTemp("t5scan_out", "pdf")
                    val ocrLangs = if (!needText) null else if (langs == "auto") detectLangs(engine!!, g.first()) else langs
                    val pictures = ScanBuilder.pagePictures(g.map { it.file to it.edit }, k, rf, maxDim = if (highQuality) 3508 else 2400)
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
                    // FINAL_CHECK_2 #16 scan a paper form: the recognised lines, boxes and labels become fillable fields
                    if (k == ScanKind.FORM) fieldsMade += withContext(Dispatchers.IO) {
                        runCatching {
                            val ff = app.documents.newTemp("t5scan_form", "pdf")
                            val n = com.smartdocs.editor.core.pdf.PdfOps.loadRaw(tmpOut, null).use { d -> com.smartdocs.editor.core.formsx.FieldRecognition.recognizeAll(d).also { if (it > 0) d.save(ff) } }
                            if (n > 0) { ff.copyTo(tmpOut, true) }; ff.delete(); n
                        }.getOrDefault(0)
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
                if (k == ScanKind.FORM) info = if (fieldsMade > 0) ctx.getString(R.string.t5_form_fields_made, fieldsMade) else ctx.getString(R.string.t5_form_fields_none)
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
                val savedPages = all.flatten()
                withContext(Dispatchers.IO) { dropTemp(savedPages) }
                groups = emptyList(); name = ""
                lastScan = made.firstOrNull { it.extension.equals("pdf", true) }
                withContext(Dispatchers.IO) { refreshRecent() }
            } finally { engine?.close() }
        }
    }

    ToolScaffold(stringResource(R.string.t5_scan_title), nav, snackbar = snackbar, actions = {
        if (groups.isNotEmpty()) IconButton(onClick = { askClear = true }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sc_delete_all_pages)) }
    }, onBack = { if (groups.isNotEmpty()) askDiscard = true else nav.popBackStack() }) {
        FieldLabel(stringResource(R.string.t5_scan_kind))
        ChoiceChips(ScanKind.entries, kind, { it.label() }) { nk -> changeKind(nk) }
        Text(stringResource(kindHint(kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launchScanner() }, Modifier.weight(1f)) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(6.dp)); Text(stringResource(if (groups.isEmpty()) R.string.t5_scan_start else R.string.t5_scan_more)) }
            OutlinedButton(onClick = pickGallery, Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_from_gallery)) }
        }
        // S1: how pictures are taken — visible before scanning
        var camSettings by remember { mutableStateOf(false) }
        TextButton(onClick = { camSettings = !camSettings }) {
            Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.sc_capture_settings) + " · " + stringResource(if (ownCamera) R.string.sc_own_camera else R.string.sc_google_scanner))
            Icon(if (camSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (camSettings) {
            SwitchRow(stringResource(R.string.sc_own_camera), ownCamera, stringResource(R.string.sc_own_camera_sub)) {
                ownCamera = it; ctx.getSharedPreferences("scan_studio", 0).edit().putBoolean("own_camera", it).apply()
            }
            SwitchRow(stringResource(R.string.sc_high_quality), highQuality, stringResource(R.string.sc_high_quality_sub)) {
                highQuality = it; ctx.getSharedPreferences("scan_studio", 0).edit().putBoolean("high_quality", it).apply()
            }
        }
        if (groups.isNotEmpty()) Text(stringResource(R.string.sc_tap_to_edit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    IconButton(onClick = { projectToDelete = pr }) { Icon(Icons.Default.Delete, stringResource(R.string.pc_preset_delete, pr.name)) }
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
                var morePaper by remember { mutableStateOf(!paper.common) }
                ChoiceChips(ScanPaper.entries.filter { morePaper || it.common }, paper, { when (it) {
                    ScanPaper.FIT -> stringResource(R.string.t5_paper_fit); ScanPaper.A4 -> stringResource(R.string.t5_paper_a4); ScanPaper.LETTER -> stringResource(R.string.t5_paper_letter)
                    ScanPaper.CARD -> stringResource(R.string.pc_paper_card); ScanPaper.TABLOID -> "Tabloid"; ScanPaper.EXECUTIVE -> "Executive"
                    else -> it.name.replace("JIS_", "JIS ")
                } }) { paper = it }
                TextButton(onClick = { morePaper = !morePaper }) { Text(stringResource(if (morePaper) R.string.t5_paper_less else R.string.t5_paper_more)) }
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
            // FINAL_CHECK_2 Low (K11): QR / barcodes on the scanned pages
            var codes by remember { mutableStateOf<List<Pair<Int, com.smartdocs.editor.core.tools5.BarcodeScan.Code>>?>(null) }
            var reading by remember { mutableStateOf(false) }
            OutlinedButton(enabled = !reading, onClick = {
                reading = true
                val files = groups.flatten().map { it.file }
                scope.launch {
                    codes = withContext(Dispatchers.IO) {
                        files.flatMapIndexed { i, f ->
                            runCatching {
                                val b = ScanImage.load(f, 1600) ?: return@runCatching emptyList()
                                val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); val w = b.width; val h = b.height; b.recycle()
                                com.smartdocs.editor.core.tools5.BarcodeScan.decode(px, w, h).map { i to it }
                            }.getOrDefault(emptyList())
                        }
                    }
                    reading = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text(stringResource(if (reading) R.string.processing else R.string.sc_read_codes)) }
            codes?.let { found -> BarcodeResultsDialog(found) { codes = null } }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
        lastScan?.takeIf { it.exists() }?.let { f -> com.smartdocs.editor.ui.scanner.ScanAiBar(f, nav) { lastScan = null } }
        // S7: one history — the saved scans (PDF files) are listed here too
        if (groups.isEmpty() && recent.isNotEmpty()) T5Card(stringResource(R.string.pg_scan_previous), Icons.Default.PictureAsPdf) {
            var all by remember { mutableStateOf(false) }
            (if (all) recent else recent.take(5)).forEach { f -> RecentScanRow(f, nav) { withContext(Dispatchers.IO) { f.delete(); refreshRecent() } } }
            if (recent.size > 5) TextButton(onClick = { all = !all }) { Text(stringResource(if (all) R.string.t5_paper_less else R.string.sc_show_all, recent.size)) }
        }
    }
    T5JobUi(job, nav, snackbar)
    // leaving with unsaved pages asks first (they were lost without a word)
    androidx.activity.compose.BackHandler(enabled = groups.isNotEmpty() && !cameraOpen) { askDiscard = true }
    if (askDiscard || askClear) {
        val n = groups.sumOf { it.size }
        AlertDialog(onDismissRequest = { askDiscard = false; askClear = false },
            title = { Text(stringResource(if (askDiscard) R.string.sc_discard_title else R.string.sc_delete_all_pages)) },
            text = { Text(pluralStringResource(R.plurals.sc_pages_will_go, n, n)) },
            confirmButton = { TextButton(onClick = {
                val leaving = askDiscard; askDiscard = false; askClear = false
                val old = groups.flatten(); groups = emptyList()
                scope.launch(Dispatchers.IO) { dropTemp(old) }
                if (leaving) nav.popBackStack()
            }) { Text(stringResource(if (askDiscard) R.string.sc_discard else R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDiscard = false; askClear = false }) { Text(stringResource(R.string.cancel)) } })
    }
    var askDeleteProject by remember { mutableStateOf<ScanProjects.Project?>(null) }
    askDeleteProject?.let { pr ->
        AlertDialog(onDismissRequest = { askDeleteProject = null }, title = { Text(stringResource(R.string.delete)) }, text = { Text(pr.name) },
            confirmButton = { TextButton(onClick = { askDeleteProject = null; projects = projects - pr; scope.launch(Dispatchers.IO) { ScanProjects.delete(pr) } }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDeleteProject = null }) { Text(stringResource(R.string.cancel)) } })
    }
    projectToDelete?.let { askDeleteProject = it; projectToDelete = null }
    editing?.let { (gi, pi) ->
        val page = groups.getOrNull(gi)?.getOrNull(pi)
        fun keep(e: PageEdit) { groups = groups.mapIndexed { i, g -> if (i != gi) g else g.mapIndexed { j, p -> if (j == pi) p.copy(edit = e) else p } } }
        val size = groups.getOrNull(gi)?.size ?: 0
        if (page == null) editing = null else key(gi, pi) { ScanPageEditor(
            page = page, index = pi, count = size,
            onApply = { e, all -> groups = groups.mapIndexed { i, g -> if (i != gi) g else g.mapIndexed { j, p -> if (j == pi || all) p.copy(edit = if (j == pi) e else p.edit.copy(filter = e.filter, brightness = e.brightness, contrast = e.contrast)) else p } }; editing = null },
            onDelete = { groups = groups.mapIndexed { i, g -> if (i != gi) g else g.filterIndexed { j, _ -> j != pi } }.filter { it.isNotEmpty() }; editing = null },
            // the editor stays open on the moved page with its edits (moving used to close it and drop them)
            onMove = { e, d -> keep(e); val t = (pi + d).coerceIn(0, size - 1); groups = groups.mapIndexed { i, g -> if (i != gi) g else g.toMutableList().apply { add(t, removeAt(pi)) } }; editing = gi to t },
            onGo = { e, d -> keep(e); editing = gi to (pi + d).coerceIn(0, size - 1) },
            onDismiss = { editing = null },
        ) }
    }
    contact?.let { c -> ContactSheet(c, onDismiss = { contact = null }) }
    receipt?.let { (r, f) -> ReceiptSheet(r, f, onDismiss = { receipt = null }) }
}

private fun kindHint(k: ScanKind): Int = when (k) {
    ScanKind.DOCUMENT -> R.string.t5_kind_document_hint; ScanKind.ID_CARD -> R.string.t5_kind_id_hint; ScanKind.BUSINESS_CARD -> R.string.t5_kind_card_hint
    ScanKind.RECEIPT -> R.string.t5_kind_receipt_hint; ScanKind.WHITEBOARD -> R.string.t5_kind_board_hint; ScanKind.BOOK -> R.string.t5_kind_book_hint
    ScanKind.BATCH -> R.string.t5_kind_batch_hint; ScanKind.FORM -> R.string.t5_kind_form_hint
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


/** A saved scan (PDF): open, OCR, share, delete. */
@Composable
private fun RecentScanRow(f: File, nav: NavHostController, onDelete: suspend () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { nav.navigate(com.smartdocs.editor.ui.Routes.reader(android.net.Uri.fromFile(f).toString())) },
        leadingContent = { Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(f.name, maxLines = 1) },
        supportingContent = { Text("${com.smartdocs.editor.core.files.DocumentRepository.formatSize(f.length())} · ${com.smartdocs.editor.core.files.DocumentRepository.formatDate(f.lastModified())}", maxLines = 1) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_cd_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.Routes.reader(android.net.Uri.fromFile(f).toString())) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tool_ocr)) }, onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.Routes.tool("ocr", android.net.Uri.fromFile(f).toString())) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(listOf(f)), null)) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; confirm = true })
                }
            }
        },
    )
    // the old list deleted at once; a scan is someone's only copy of a paper
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.delete)) }, text = { Text(f.name) },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { onDelete() } }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

/** The scanned pages survive leaving the screen and process death (files + edits as a ScanProjects manifest). */
internal val ScanGroupsSaver: androidx.compose.runtime.saveable.Saver<List<List<ScanPage>>, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { v -> ScanProjects.encode("", v.map { g -> g.map { it.file.absolutePath to it.edit } }) },
        restore = { s -> ScanProjects.decode(s).second.map { g -> g.mapNotNull { (p, e) -> File(p).takeIf { it.isFile }?.let { ScanPage(it, e) } } }.filter { it.isNotEmpty() } },
    )
