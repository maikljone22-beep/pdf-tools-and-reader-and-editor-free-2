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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
    // red team P4: the kind is chosen per scan (an ID card scanned once made the next homework scan stop at 2 pages)
    var kind by remember { mutableStateOf(ScanKind.DOCUMENT) }
    var groups by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ScanGroupsSaver) { mutableStateOf<List<List<ScanPage>>>(emptyList()) }
    var paper by remember { mutableStateOf(runCatching { ScanPaper.valueOf(prefs.getString("paper", "FIT")!!) }.getOrDefault(ScanPaper.FIT)) }
    // full camera resolution and light compression (bigger files) instead of 2400 px / JPEG 82
    var sizeLevel by remember { mutableIntStateOf(ctx.getSharedPreferences("scan_studio", 0).let { sp -> sp.getInt("size", if (sp.contains("high_quality") && sp.getBoolean("high_quality", true)) 2 else 1) }) }
    var asImages by remember { mutableStateOf(false) }      // red team P4: the format is chosen per scan
    // scanner essentials: Word output, add to an existing PDF, upright pages, fingers off book edges
    var asWord by remember { mutableStateOf(false) }
    var wordFont by remember { mutableStateOf(prefs.getString("word_font", "Simplified Arabic") ?: "Simplified Arabic") }
    var appendTo by remember { mutableStateOf<android.net.Uri?>(null) }
    var appendName by remember { mutableStateOf("") }
    var autoRotate by remember { mutableStateOf(prefs.getBoolean("auto_rotate", true)) }
    var removeFingers by remember { mutableStateOf(prefs.getBoolean("fingers", true)) }
    var orienting by remember { mutableIntStateOf(0) }
    // audit B6: saving stops the turning still to do (it used to hold the save button for every page)
    var orientGen by remember { mutableIntStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    var nextNewGroup by remember { mutableStateOf(false) }
    // audit B16: kept across process death with the pages (the next save used to copy the project again)
    var openedProject by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ScanProjects.Project?, String>(
        save = { p -> p?.let { "${it.dir.path}\n${it.name}\n${it.time}\n${it.pages}" } ?: "" },
        restore = { s -> s.split('\n').takeIf { it.size == 4 }?.let { ScanProjects.Project(File(it[0]), it[1], it[2].toLongOrNull() ?: 0L, it[3].toIntOrNull() ?: 0) }?.takeIf { it.dir.isDirectory } },
    )) { mutableStateOf<ScanProjects.Project?>(null) }
    var recognised by remember { mutableStateOf("") }
    var resultMime by remember { mutableStateOf("application/pdf") }
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
    fun refreshRecent() {
        val all = app.documents.scansDir.listFiles() ?: emptyArray()
        // half-written results (".part_…", left by a killed save) are hidden, and removed once an hour old
        all.filter { it.name.startsWith(".part_") && System.currentTimeMillis() - it.lastModified() > 3_600_000L }.forEach { it.delete() }
        recent = all.filter { !it.name.startsWith(".") && it.extension.lowercase() in setOf("pdf", "docx", "jpg") }.sortedByDescending { it.lastModified() }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            refreshRecent()
            if (groups.isEmpty()) { val old = System.currentTimeMillis() - 24 * 3600_000L
                app.documents.tempDir.listFiles { f -> (f.name.startsWith("cam_") || f.name.startsWith("t5scan_")) && f.lastModified() < old }?.forEach { it.delete() } }
        }
    }
    var autoOcr by remember { mutableStateOf(prefs.getBoolean("ocr", true)) }
    var langs by remember { mutableStateOf(prefs.getString("langs", "auto") ?: "auto") }
    var smartName by remember { mutableStateOf(prefs.getBoolean("smart", true)) }
    var rightFirst by remember { mutableStateOf(prefs.getBoolean("rtl_book", true)) }
    var name by remember { mutableStateOf("") }
    // Foxit 5: blank-page removal, N pages per file, document information, portfolio of the results, presets
    var removeBlank by remember { mutableStateOf(prefs.getBoolean("blank", false)) }
    var perFile by remember { mutableStateOf(0) }      // red team P4: per scan
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
    fun savePrefs() = prefs.edit().putBoolean("ocr", autoOcr).putString("langs", langs).putBoolean("smart", smartName).putBoolean("rtl_book", rightFirst)
        .putBoolean("blank", removeBlank).putString("author", author).apply()

    /** A new kind; pages still on the old kind's defaults follow it (deep audit E8). */
    fun changeKind(nk: ScanKind) {
        val old = kind
        groups = groups.map { g -> g.map { p -> if (p.edit.filter == old.defaultFilter() && p.edit.flatten == (old == ScanKind.BOOK)) p.copy(edit = p.edit.copy(filter = nk.defaultFilter(), flatten = nk == ScanKind.BOOK)) else p } }
        kind = nk; savePrefs()
    }

    /** Pages turned upright one by one in the background; a page the user already turned is left alone. */
    fun orient(pages: List<ScanPage>) {
        if (pages.isEmpty()) return
        orienting += pages.size
        var finished = 0
        val gen = orientGen
        scope.launch {
            val engine = withContext(Dispatchers.IO) {
                runCatching { OcrEngine(ctx.applicationContext).takeIf { it.isLanguageInstalled("ara") && it.isLanguageInstalled("eng") } }.getOrNull()
            }
            try {
                val ready = engine != null && runCatching { engine.init("ara+eng") }.getOrDefault(false)
                for (p in pages) {
                    if (gen != orientGen) break
                    try {
                        val turn = withContext(Dispatchers.Default) {
                            val b = ScanImage.preview(p.file, p.edit.copy(filter = ScanFilter.GRAY, rotation = 0, erase = emptyList(), flatten = false), 900) ?: return@withContext 0
                            try { ScanOrient.turnFor(b, if (ready) engine else null) } finally { b.recycle() }
                        }
                        if (turn != 0) groups = groups.map { g -> g.map { q -> if (q.file == p.file && q.edit.rotation == 0) q.copy(edit = q.edit.copy(rotation = turn)) else q } }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
                    orienting = (orienting - 1).coerceAtLeast(0); finished++
                }
            } finally {
                // pages not reached (screen left, cancelled) no longer hold the save button
                orienting = (orienting - (pages.size - finished)).coerceAtLeast(0); engine?.close()
            }
        }
    }

    fun addPictures(files: List<File>, newGroup: Boolean, crops: List<FloatArray?> = emptyList()) {
        val pages = files.mapIndexed { i, f -> ScanPage(f, PageEdit(filter = kind.defaultFilter(), flatten = kind == ScanKind.BOOK, crop = crops.getOrNull(i))) }
        groups = if (newGroup || nextNewGroup || groups.isEmpty() || kind == ScanKind.BATCH) groups + listOf(pages) else groups.dropLast(1) + listOf(groups.last() + pages)
        nextNewGroup = false
        if (autoRotate && kind != ScanKind.BOOK && kind != ScanKind.ID_CARD && kind != ScanKind.BUSINESS_CARD) orient(pages)
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) scope.launch {
                val files = withContext(Dispatchers.IO) {
                    uris.mapIndexedNotNull { i, u ->
                        // audit A10: a picture that cannot be opened is no page (it used to vanish silently later)
                        runCatching { val f = app.documents.newTemp("t5scan_$i", "jpg"); ctx.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { s.copyTo(it) } }; f.takeIf { it.length() > 0 } ?: run { f.delete(); null } }.getOrNull()
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
    val pickAppend = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            appendTo = u
            appendName = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, u)?.name }.getOrNull() ?: "PDF"
        }
    }
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
    val pageDeletedMsg = stringResource(R.string.sc_page_deleted)
    val undoLabel = stringResource(R.string.sc_undo)
    val savingImagesMsg = stringResource(R.string.sc_saving_images)
    val savingWordMsg = stringResource(R.string.sc_saving_word)
    val prefixes = mapOf(ScanKind.RECEIPT to stringResource(R.string.t5_prefix_receipt), ScanKind.BUSINESS_CARD to stringResource(R.string.t5_prefix_card), ScanKind.ID_CARD to stringResource(R.string.t5_prefix_id), ScanKind.WHITEBOARD to stringResource(R.string.t5_prefix_board), ScanKind.FORM to stringResource(R.string.t5_prefix_form))
    var askDownload by remember { mutableStateOf(false) }
    val textSkippedMsg = stringResource(R.string.sc_text_skipped)
    val downloadingFmt = stringResource(R.string.ocr_downloading)
    /**
     * [noText]: save without reading the text (the user declined the model download). Red team P1: the first PDF save
     * fetched tens of MB of language models with no word — now it asks once, with the size.
     */
    fun save(noText: Boolean = false) {
        // audit B5: an undo offered before the save would bring back pages whose pictures the save removes
        snackbar.currentSnackbarData?.dismiss()
        orientGen++
        val all = groups.filter { it.isNotEmpty() }
        if (all.isEmpty()) return
        savePrefs()
        val fmt0 = ScanSavePlan.format(asImages, asWord, kind)
        if (!noText && ScanSavePlan.needsText(fmt0, autoOcr, kind) && !prefs.getBoolean("ocr_dl_ok", false)) {
            val wanted = (if (langs == "auto") "ara+eng" else langs).split('+')
            val eng = OcrEngine(ctx.applicationContext)
            val missing = try { wanted.any { !eng.isLanguageInstalled(it) } } finally { eng.close() }
            if (missing) { askDownload = true; return }
        }
        // FINAL_CHECK_2 #16: a form needs its text for field recognition. Smart naming no longer forces OCR (switching OCR off
        // did not make saving faster, scanner audit U14): without text it names by kind and date. Pictures need no text.
        // the save decisions live in ScanSavePlan (tested)
        val fmt = ScanSavePlan.format(asImages, asWord, kind)
        val word = fmt == ScanSavePlan.Format.WORD
        val imgs = fmt == ScanSavePlan.Format.JPG
        // Word needs the text of every page
        val needText = !noText && ScanSavePlan.needsText(fmt, autoOcr, kind)
        val fingers = removeFingers
        val target = appendTo.takeIf { !imgs && !word }; val targetName = appendName
        val k = kind; val rf = rightFirst; val typed = name.trim()
        // deep audit E17: the ID card page is an A4 sheet at real card size — another paper shrank or mis-scaled it
        val p = if (k == ScanKind.ID_CARD) ScanPaper.A4 else paper
        val lvl = sizeLevel; val reopened = openedProject
        val rb = removeBlank; val tt = title; val au = author; val pf = portfolio && !imgs; val kp = keepProject; val pa = pdfa
        prefs.edit().putBoolean("keep_project", kp).putBoolean("pdfa", pa).apply()
        val keep = all.map { g -> g.map { it.file to it.edit } }
        // "save multiple files": every group is cut into files of N pages
        // deep audit E16: "pages per file" counts output pages — an ID card is two photos, a book photo two pages
        // audit A3: adding to an existing PDF makes one document, whatever "pages per file" says
        val chunk = ScanSavePlan.chunk(perFile, k, target != null, all.size)
        val docs = all.flatMap { ScanExtras.chunks(it, chunk) }
        job.launch(when { imgs -> savingImagesMsg; word -> savingWordMsg; else -> savingMsg }) {
            val engine = if (needText) OcrEngine(ctx.applicationContext) else null
            try {
                val made = ArrayList<File>(); var lastText = ""; var fieldsMade = 0
                val downloadFailedMsg = ctx.getString(R.string.pg_ocr_download_failed)
                val failed = ArrayList<List<ScanPage>>(); val texts = StringBuilder(); val done = ArrayList<List<ScanPage>>(); var firstError: String? = null
                docs.forEachIndexed { gi, g ->
                  // a failing document no longer stops the others; it stays on screen for another try (audit U30)
                  try {
                    if (imgs) {
                        // scanner audit U23: the pages as JPEG pictures (no PDF)
                        val stamp = typed.ifEmpty { (prefixes[k] ?: "Scan") + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) } + if (docs.size > 1) "_${gi + 1}" else ""
                        var n = 0
                        withContext(Dispatchers.IO) {
                            ScanBuilder.pagePictures(g.map { it.file to it.edit }, k, rf, maxDim = sizeDim(lvl), removeFingers = fingers).forEach { o ->
                                n++
                                // audit A6: written aside first, so a killed app never leaves half a picture in Scans
                                // phase 5 R3: next to the result (the temp folder is on another storage, where a rename fails)
                                val tmp = File(app.documents.scansDir, ".part_${System.nanoTime()}.jpg")
                                try { tmp.outputStream().use { o.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, sizeQuality(lvl), it) } } finally { o.bitmap.recycle() }
                                val f = app.documents.uniqueFile(app.documents.scansDir, "${stamp}_$n", "jpg")
                                if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
                                made += f
                            }
                        }
                        step((gi + 1) * 100, docs.size * 100)
                        done += g      // phase 5 R1: JPG and Word documents count as saved for a cancel too
                        return@forEachIndexed
                    }
                    val today = t5Today()
                    val tmpOut = app.documents.newTemp("t5scan_out", "pdf")
                    val ocrLangs = if (!needText) null else if (langs == "auto") detectLangs(engine!!, g.first()) else langs
                    // photo → faithful Word (specs/SCAN_TO_WORD_PLAN.md phase 1): straight from the page pictures, no PDF
                    // in between — page size, sizes, bold, colour, ruled tables and figures kept
                    if (word) {
                        tmpOut.delete()
                        val l = ocrLangs ?: "ara+eng"
                        // audit B4: the language models may be downloaded here — say so, with progress
                        try {
                            engine!!.ensureLanguages(l) { lang, f -> message = ctx.getString(R.string.ocr_downloading, OcrEngine.label(lang)); progress = f }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                            throw IllegalStateException(ctx.getString(R.string.pg_ocr_download_failed), e)
                        }
                        message = savingWordMsg
                        // Arabic pages: the stock model and the fine-tuned one together (34 % fewer wrong letters on a bold
                        // school-book page than the fine-tuned one alone, gaps/SCAN2WORD_REVIEW.md)
                        val wl = if ("ara" in l.split('+')) "$l+${OcrEngine.ARABIC_FT}" else l
                        if (!engine!!.init(wl)) throw IllegalStateException(ctx.getString(R.string.sc_save_failed))
                        val wbase = typed.ifEmpty { (prefixes[k] ?: "Scan") + "_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) } + if (docs.size > 1) "_${gi + 1}" else ""
                        val outDocx = app.documents.uniqueFile(app.documents.scansDir, wbase, "docx")
                        val pics = ScanBuilder.pagePictures(g.map { it.file to it.edit }, k, rf, maxDim = maxOf(sizeDim(lvl), 2400), removeFingers = fingers)
                        // audit A6: the document is written aside and moved into Scans only when complete
                        val tmpDocx = File(app.documents.scansDir, ".part_${System.nanoTime()}.docx")
                        // audit B10: a book photo gives two pages
                        val wordPages = (if (k == ScanKind.BOOK) g.size * 2 else g.size).coerceAtLeast(1)
                        val wr = try {
                            com.smartdocs.editor.core.scan2word.ScanToWordRunner.convert(pics, engine, tmpDocx, wbase, wordFont, "Times New Roman") { n -> step(gi * 100 + minOf(99, n * 100 / wordPages), docs.size * 100) }
                        } catch (e: Throwable) { tmpDocx.delete(); throw e }
                        withContext(Dispatchers.IO) { if (!tmpDocx.renameTo(outDocx)) { tmpDocx.copyTo(outDocx, true); tmpDocx.delete() } }
                        runCatching { app.documents.touchRecent(outDocx, wr.pages) }
                        made += outDocx; lastText = wr.text
                        if (wr.text.isNotBlank()) { if (texts.isNotEmpty()) texts.append("\n\n"); texts.append(wr.text) }
                        done += g
                        return@forEachIndexed
                    }
                    val pictures = ScanBuilder.pagePictures(g.map { it.file to it.edit }, k, rf, maxDim = sizeDim(lvl), removeFingers = fingers)
                    val r = ScanBuilder.build(ctx, pictures, tmpOut, ScanBuildOptions(paper = p, quality = sizeQuality(lvl), ocrLangs = ocrLangs), engine,
                        onPage = { n -> message = savingMsg; step(gi * 100 + n, docs.size * 100) },
                        onDownload = { lang, f -> message = String.format(downloadingFmt, OcrEngine.label(lang)); progress = f })
                    // red team P2: text wanted but the recogniser could not be readied (offline) — say so, instead of a
                    // silent PDF without text
                    if (r.textSkipped && info == null) info = textSkippedMsg
                    val base = when {
                        typed.isNotEmpty() -> if (docs.size > 1) "${typed}_${gi + 1}" else typed
                        smartName && r.text.isNotBlank() -> SmartNaming.suggest(r.text, k, today, prefixes)
                        else -> (prefixes[k]?.let { "${it}_" } ?: "Scan_") + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + if (docs.size > 1) "_${gi + 1}" else ""
                    }
                    var pageCount = r.pages
                    // file work off the main thread (deep audit E10); the page count follows the removed blank pages (E19)
                    if (rb) runCatching {
                        val clean = app.documents.newTemp("t5scan_clean", "pdf")
                        val removed = com.smartdocs.editor.core.edit5.PageOrganize.deleteBlank(ctx, tmpOut, clean, null)
                        if (removed.isNotEmpty()) { withContext(Dispatchers.IO) { clean.copyTo(tmpOut, true) }; pageCount -= removed.size }
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
                    // scanner essentials: add the pages at the end of an existing PDF (written back when the app may)
                    if (target != null && docs.size == 1) {
                        val local = withContext(Dispatchers.IO) { app.documents.localFile(target) }
                        val merged = app.documents.newTemp("t5scan_merge", "pdf")
                        com.smartdocs.editor.core.pdf.PdfOps.merge(listOf(local, tmpOut), merged)
                        tmpOut.delete()
                        val wrote = withContext(Dispatchers.IO) {
                            val ok = runCatching { ctx.contentResolver.openOutputStream(target, "wt")!!.use { o -> merged.inputStream().use { it.copyTo(o) } } }.isSuccess
                            // audit A4: "wt" emptied the user's file first — a failed write puts the original back
                            if (!ok) runCatching { ctx.contentResolver.openOutputStream(target, "wt")!!.use { o -> local.inputStream().use { it.copyTo(o) } } }
                            ok
                        }
                        val out = app.documents.uniqueFile(app.documents.scansDir, targetName.substringBeforeLast('.').ifBlank { base })
                        withContext(Dispatchers.IO) { if (!merged.renameTo(out)) { merged.copyTo(out, true); merged.delete() } }
                        info = ctx.getString(if (wrote) R.string.sc_appended_to else R.string.sc_appended_copy, targetName)
                        runCatching { app.documents.touchRecent(out) }
                        made += out; lastText = r.text
                        if (r.text.isNotBlank()) { if (texts.isNotEmpty()) texts.append("\n\n"); texts.append(r.text) }
                        done += g
                        return@forEachIndexed
                    }
                    val out = app.documents.uniqueFile(app.documents.scansDir, base)
                    withContext(Dispatchers.IO) { if (!tmpOut.renameTo(out)) { tmpOut.copyTo(out, true); tmpOut.delete() } }
                    runCatching { app.documents.touchRecent(out, pageCount) }
                    made += out; lastText = r.text
                    if (r.text.isNotBlank()) { if (texts.isNotEmpty()) texts.append("\n\n"); texts.append(r.text) }
                    done += g
                  } catch (e: kotlinx.coroutines.CancellationException) {
                    // audit B15: documents saved before the cancel leave the screen, so trying again does not save them twice
                    if (done.isNotEmpty()) {
                        groups = ScanSavePlan.remainingAfterCancel(groups, done)
                        // phase 5 R1: the rest is no longer the reopened project — saving it must not replace (delete) it
                        openedProject = null
                    }
                    throw e
                  } catch (e: Throwable) { failed += g; if (firstError == null) firstError = (e as? IllegalStateException)?.message?.takeIf { m -> m == downloadFailedMsg || m.any { it in '\u0600'..'\u06FF' } } }
                }
                recognised = texts.toString()
                resultMime = when { imgs -> "image/jpeg"; word -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; else -> "application/pdf" }
                if (target != null && failed.isEmpty()) { appendTo = null; appendName = "" }
                // audit B4: the reason we know (no connection for the language download…) instead of a bare "failed"
                if (made.isEmpty()) throw IllegalStateException(firstError ?: ctx.getString(R.string.sc_save_failed))
                // when OCR was only needed for naming / parsing, the text layer stays: it makes the scan searchable
                when {
                    (imgs || word) && made.size == 1 -> result = made.first()      // red team P14: one file → the result sheet
                    imgs || word -> results = made
                    k == ScanKind.BUSINESS_CARD -> contact = BusinessCardParser.parse(lastText)
                    k == ScanKind.RECEIPT -> receipt = ReceiptParser.parse(lastText) to made.last()
                    made.size == 1 -> result = made.first()
                    else -> results = made
                }
                if (k == ScanKind.FORM) info = if (fieldsMade > 0) ctx.resources.getQuantityString(R.plurals.sc_form_fields_made, fieldsMade, fieldsMade) else ctx.getString(R.string.t5_form_fields_none)
                if (!imgs && (k == ScanKind.BUSINESS_CARD || k == ScanKind.RECEIPT)) results = made
                // Foxit "create a portfolio for all results"
                if (pf && made.size > 1) {
                    // audit A2: the documents are saved already — a failing portfolio must not make the pages save again
                    val po = app.documents.uniqueFile(app.documents.scansDir, (typed.ifEmpty { "Scans" }) + "_portfolio")
                    if (runCatching { com.smartdocs.editor.core.tools5.Portfolio.create(ctx, typed.ifEmpty { "Scans" }, made, po) }.isSuccess) results = made + po
                    else { po.delete(); info = ctx.getString(R.string.sc_save_failed) }
                }
                // only the documents that were saved are kept as a project; a reopened project is replaced (not copied
                // again) — and only when everything was saved, so its pictures stay while some pages still need a retry
                val failedSet = failed.flatten().map { it.file }.toSet()
                val keepSaved = keep.map { g -> g.filter { it.first !in failedSet } }.filter { it.isNotEmpty() }
                if (kp && keepSaved.isNotEmpty()) withContext(Dispatchers.IO) {
                    runCatching { ScanProjects.save(ctx, made.firstOrNull()?.nameWithoutExtension ?: "Scan", keepSaved, replacing = reopened.takeIf { failed.isEmpty() }) }
                    projects = runCatching { ScanProjects.list(ctx) }.getOrDefault(projects)
                }
                if (failed.isEmpty()) openedProject = null
                val savedPages = all.flatten() - failed.flatten().toSet()
                withContext(Dispatchers.IO) { dropTemp(savedPages) }
                groups = failed.toList(); if (failed.isEmpty()) name = ""
                if (failed.isNotEmpty()) info = ctx.getString(R.string.sc_saved_partly, docs.size - failed.size, docs.size)
                lastScan = made.firstOrNull { it.extension.equals("pdf", true) }
                withContext(Dispatchers.IO) { refreshRecent() }
            } finally { engine?.close() }
        }
    }

    ToolScaffold(stringResource(R.string.t5_scan_title), nav, snackbar = snackbar, actions = {
        if (groups.isNotEmpty()) IconButton(onClick = { askClear = true }, enabled = !job.running) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sc_delete_all_pages)) }
    }, onBack = { if (job.running) Unit else if (groups.isNotEmpty()) askDiscard = true else nav.popBackStack() },
    // red team P5: Save was two screens below the pages — it stays at the bottom now
    bottomBar = if (groups.isEmpty()) null else ({
        val fmtWord = asWord && !kindNeedsPdf(kind); val fmtJpg = asImages && !fmtWord && !kindNeedsPdf(kind)
        PrimaryButton(stringResource(when { fmtJpg -> R.string.sc_save_images; fmtWord -> R.string.sc_save_word; appendTo != null && groups.size == 1 -> R.string.sc_save_append; else -> R.string.t5_scan_save }),
            enabled = !job.running,
            icon = when { fmtJpg -> Icons.Default.Image; fmtWord -> Icons.Default.Description; else -> Icons.Default.PictureAsPdf }) { save() }
    })) {
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
            SwitchRow(stringResource(R.string.sc_auto_rotate), autoRotate, stringResource(R.string.sc_auto_rotate_sub)) { autoRotate = it; prefs.edit().putBoolean("auto_rotate", it).apply() }
        }
        if (groups.isNotEmpty()) Text(stringResource(R.string.sc_tap_to_edit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        groups.forEachIndexed { gi, g ->
            T5Card(if (groups.size > 1) stringResource(R.string.sc_doc_n, gi + 1) + " · " + pluralStringResource(R.plurals.sc_pages_n, g.size, g.size) else pluralStringResource(R.plurals.sc_pages_n, g.size, g.size), Icons.Default.Collections) {
                // scanner essentials: long-press a page and drag it to a new place
                DraggablePageRow(g, onEdit = { pi -> editing = gi to pi }) { from, to ->
                    groups = groups.mapIndexed { i, gg -> if (i != gi) gg else gg.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) } }
                }
            }
        }
        // a second document (e.g. the backs of double-sided pages) — this was only possible with the "batch" kind
        if (groups.isNotEmpty()) {
            TextButton(onClick = { nextNewGroup = true; launchScanner() }) { Icon(Icons.Default.LibraryAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sc_new_document)) }
            if (groups.size == 1) Text(stringResource(R.string.sc_duplex_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // manual duplex: the first document holds the fronts, the second the backs
        if (groups.size == 2) T5Card(stringResource(R.string.pc_duplex), Icons.Default.FlipToBack) {
            Text(stringResource(R.string.pc_duplex_help), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.pc_duplex_reversed), backsReversed) { backsReversed = it }
            OutlinedButton(onClick = { groups = listOf(ScanExtras.duplex(groups[0], groups[1], backsReversed)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.pc_duplex_merge)) }
        }
        if (groups.isEmpty() && projects.isNotEmpty()) T5Card(stringResource(R.string.pc_saved_scans), Icons.Default.History) {
            var allProjects by remember { mutableStateOf(false) }
            val total by produceState(0L, projects) { value = withContext(Dispatchers.IO) { projects.sumOf { p -> p.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } } }
            Text(stringResource(R.string.sc_projects_size, com.smartdocs.editor.core.files.DocumentRepository.formatSize(total)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (if (allProjects) projects else projects.take(6)).forEach { pr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${pr.name} (${pluralStringResource(R.plurals.sc_pages_n, pr.pages, pr.pages)})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    TextButton(onClick = { scope.launch {
                        val g = withContext(Dispatchers.IO) { runCatching { ScanProjects.load(pr) }.getOrDefault(emptyList()) }
                        groups = g.map { pages -> pages.map { (f, e) -> ScanPage(f, e) } }; name = pr.name; openedProject = pr
                    } }) { Text(stringResource(R.string.pc_saved_scan_open)) }
                    IconButton(onClick = { projectToDelete = pr }) { Icon(Icons.Default.Delete, stringResource(R.string.pc_preset_delete, pr.name)) }
                }
            }
            if (projects.size > 6) TextButton(onClick = { allProjects = !allProjects }) { Text(if (allProjects) stringResource(R.string.t5_paper_less) else stringResource(R.string.sc_show_all, projects.size)) }
        }
        // red team: the presets card shows only when a preset exists; saving one is under "advanced"
        if (presets.isNotEmpty()) T5Card(stringResource(R.string.pc_presets), Icons.Default.Bookmarks) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { ps ->
                    val curFormat = if (asWord) 2 else if (asImages) 1 else 0
                    val active = kind == ps.kind && paper == ps.paper && autoOcr == ps.ocr && langs == ps.langs && removeBlank == ps.removeBlank && perFile == ps.perFile && author == ps.author &&
                        (ps.format == null || ps.format == curFormat) && (ps.wordFont == null || ps.wordFont == wordFont) && (ps.size == null || ps.size == sizeLevel) && (ps.fingers == null || ps.fingers == removeFingers)
                    val appliedMsg = stringResource(R.string.sc_preset_applied, ps.name)
                    InputChip(active, onClick = {
                        changeKind(ps.kind); paper = ps.paper; autoOcr = ps.ocr; langs = ps.langs; removeBlank = ps.removeBlank; perFile = ps.perFile; author = ps.author; savePrefs()
                        ps.format?.let { f -> asImages = f == 1; asWord = f == 2 }
                        ps.wordFont?.let { wordFont = it }; ps.size?.let { sizeLevel = it }; ps.fingers?.let { removeFingers = it }
                        prefs.edit().putString("word_font", wordFont).putBoolean("fingers", removeFingers).apply()
                        ctx.getSharedPreferences("scan_studio", 0).edit().putInt("size", sizeLevel).apply()
                        scope.launch { snackbar.showSnackbar(appliedMsg) }
                    }, label = { Text(ps.name) }, trailingIcon = {
                        IconButton(onClick = { savePresets(presets - ps) }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.pc_preset_delete, ps.name), Modifier.size(18.dp)) }
                    })
                }
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
                } }) { paper = it; prefs.edit().putString("paper", it.name).apply() }
                TextButton(onClick = { morePaper = !morePaper }) { Text(stringResource(if (morePaper) R.string.t5_paper_less else R.string.t5_paper_more)) }
                // file size: small for sharing (WhatsApp, slow data) / medium / best quality (A4 at 300 dpi) — red team P6:
                // a save decision, so it sits here (it was under the capture settings), with a rough size
                FieldLabel(stringResource(R.string.sc_size))
                val pagesN = groups.sumOf { it.size } * (if (kind == ScanKind.BOOK) 2 else 1)
                ChoiceChips(listOf(0, 1, 2), sizeLevel, { stringResource(when (it) { 0 -> R.string.sc_size_small; 1 -> R.string.sc_size_medium; else -> R.string.sc_size_best }) + " · " + sizeEstimate(it, pagesN) }) {
                    sizeLevel = it; ctx.getSharedPreferences("scan_studio", 0).edit().putInt("size", it).apply()
                }
                // scanner audit U23: PDF or pictures
                FieldLabel(stringResource(R.string.sc_format))
                if (kindNeedsPdf(kind)) Text(stringResource(R.string.sc_format_pdf_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else ChoiceChips(listOf(0, 1, 2), if (asWord) 2 else if (asImages) 1 else 0, { stringResource(when (it) { 1 -> R.string.sc_format_jpg; 2 -> R.string.sc_format_word; else -> R.string.sc_format_pdf }) }) {
                    asImages = it == 1; asWord = it == 2
                }
                // audit B8 / B9: options follow the format really saved (card / receipt / form are always PDF)
                val outWord = asWord && !kindNeedsPdf(kind); val outJpg = asImages && !outWord && !kindNeedsPdf(kind); val outPdf = !outWord && !outJpg
                if (outPdf) SwitchRow(stringResource(R.string.t5_auto_ocr), autoOcr, stringResource(R.string.t5_auto_ocr_hint)) { autoOcr = it }
                if (outWord) Text(stringResource(R.string.sc_word_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (outWord) {
                    // the nearest of the common Arabic text faces (a photo cannot tell the exact font)
                    FieldLabel(stringResource(R.string.sc_word_font))
                    ChoiceChips(listOf("Simplified Arabic", "Traditional Arabic", "Arial"), wordFont, { it }) { wordFont = it; prefs.edit().putString("word_font", it).apply() }
                }
                // add to an existing PDF (one document only)
                if (outPdf && groups.size == 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (appendTo != null) stringResource(R.string.sc_append_target, appendName) else stringResource(R.string.sc_append_none), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (appendTo != null) IconButton(onClick = { appendTo = null; appendName = "" }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    TextButton(onClick = { runCatching { pickAppend.launch(arrayOf("application/pdf")) } }) { Text(stringResource(R.string.sc_append_pick)) }
                }
                if (kind == ScanKind.BOOK) SwitchRow(stringResource(R.string.sc_fingers), removeFingers, stringResource(R.string.sc_fingers_sub)) { removeFingers = it; prefs.edit().putBoolean("fingers", it).apply() }
                // audit B3: Word always reads the text, so its language is shown even with OCR switched off
                if ((autoOcr && outPdf) || outWord || kind == ScanKind.BUSINESS_CARD || kind == ScanKind.RECEIPT) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto", "ara", "eng", "ara+eng", "fra", "ara+fra").forEach { l ->
                            FilterChip(langs == l, { langs = l }, label = { Text(if (l == "auto") stringResource(R.string.t5_lang_auto) else l.split('+').joinToString(" + ") { OcrEngine.label(it) }) })
                        }
                    }
                }
                if (kind == ScanKind.BOOK) SwitchRow(stringResource(R.string.t5_book_rtl), rightFirst, stringResource(R.string.t5_book_rtl_hint)) { rightFirst = it }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_file_name_optional)) },
                    supportingText = { if (name.isBlank()) Text(stringResource(if (smartName && autoOcr && outPdf) R.string.sc_name_auto_text else R.string.sc_name_auto_date)) })
                // the rest, folded (15 options in one card were too many)
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.sc_advanced)); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
                if (advanced) {
                    if (outPdf) SwitchRow(stringResource(R.string.t5_smart_name), smartName, stringResource(R.string.t5_smart_name_hint)) { smartName = it; savePrefs() }
                    if (outPdf) SwitchRow(stringResource(R.string.pc_scan_remove_blank), removeBlank) { removeBlank = it; savePrefs() }
                    // audit B2: adding to an existing PDF makes one document, so "pages per file" is hidden meanwhile
                    if (appendTo == null || groups.size != 1) OutlinedTextField(if (perFile > 0) perFile.toString() else "", { t -> perFile = com.smartdocs.editor.core.edit5.Digits.toWestern(t).filter { it.isDigit() }.take(3).toIntOrNull() ?: 0; savePrefs() },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_scan_per_file)) }, placeholder = { Text(stringResource(R.string.pc_scan_per_file_all)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    if (outPdf) {
                        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_doc_title)) })
                        OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_doc_author)) })
                        SwitchRow(stringResource(R.string.pc_save_pdfa), pdfa, stringResource(R.string.pc_save_pdfa_sub)) { pdfa = it; prefs.edit().putBoolean("pdfa", it).apply() }
                    }
                    // phase 5 R5: Word documents can be bundled too (the switch was hidden for Word but still applied)
                    if (!outJpg && (groups.size > 1 || perFile > 0 || kind == ScanKind.BATCH)) SwitchRow(stringResource(R.string.pc_scan_portfolio), portfolio) { portfolio = it }
                    SwitchRow(stringResource(R.string.pc_keep_project), keepProject, stringResource(R.string.pc_keep_project_sub)) { keepProject = it; prefs.edit().putBoolean("keep_project", it).apply() }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(presetName, { presetName = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_preset_name)) })
                        TextButton(enabled = presetName.isNotBlank(), onClick = {
                            val ps = ScanPreset(presetName.trim(), kind, paper, autoOcr, langs, removeBlank, perFile, author, if (asWord) 2 else if (asImages) 1 else 0, wordFont, sizeLevel, removeFingers)
                            savePresets(presets.filter { it.name != ps.name } + ps); presetName = ""
                        }) { Text(stringResource(R.string.pc_preset_save)) }
                    }
                }
            }
            if (orienting > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sc_orienting), style = MaterialTheme.typography.bodySmall)
            }
            // FINAL_CHECK_2 Low (K11): QR / barcodes on the scanned pages (red team P13: under "advanced", not beside Save)
            var codes by remember { mutableStateOf<List<Pair<Int, com.smartdocs.editor.core.tools5.BarcodeScan.Code>>?>(null) }
            var reading by remember { mutableStateOf(false) }
            if (advanced) OutlinedButton(enabled = !reading, onClick = {
                reading = true
                val files = groups.flatten().map { it.file }
                scope.launch {
                    codes = withContext(Dispatchers.IO) {
                        files.flatMapIndexed { i, f ->
                            runCatching {
                                val e = groups.flatten().firstOrNull { it.file == f }?.edit
                                val b0 = ScanImage.load(f, ScanImage.loadDim(1600, e?.crop)) ?: return@runCatching emptyList()
                                val b = ScanImage.cropped(b0, e?.crop, 1600)
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
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, resultMime)
        if (recognised.isNotBlank() && groups.isEmpty()) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            val copiedMsg = stringResource(R.string.sc_text_copied)
            OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(recognised)); scope.launch { snackbar.showSnackbar(copiedMsg) } }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sc_copy_text))
            }
        }
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
    androidx.activity.compose.BackHandler(enabled = groups.isNotEmpty() && !cameraOpen) { if (!job.running) askDiscard = true }
    if (askDiscard || askClear) {
        val n = groups.sumOf { it.size }
        AlertDialog(onDismissRequest = { askDiscard = false; askClear = false },
            title = { Text(stringResource(if (askDiscard) R.string.sc_discard_title else R.string.sc_delete_all_pages)) },
            text = { Text(pluralStringResource(R.plurals.sc_pages_will_go, n, n)) },
            confirmButton = { TextButton(onClick = {
                val leaving = askDiscard; askDiscard = false; askClear = false
                val old = groups.flatten(); groups = emptyList()
                // audit B1: the next scan is a new document — saving it must not replace (delete) the project opened before
                openedProject = null; name = ""
                snackbar.currentSnackbarData?.dismiss()
                scope.launch(Dispatchers.IO) { dropTemp(old) }
                if (leaving) nav.popBackStack()
            }) { Text(stringResource(if (askDiscard) R.string.sc_discard else R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDiscard = false; askClear = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (askDownload) AlertDialog(onDismissRequest = { askDownload = false },
        title = { Text(stringResource(R.string.sc_ocr_download_title)) }, text = { Text(stringResource(R.string.sc_ocr_download_text)) },
        confirmButton = { TextButton(onClick = { askDownload = false; prefs.edit().putBoolean("ocr_dl_ok", true).apply(); save() }) { Text(stringResource(R.string.sc_ocr_download_go)) } },
        dismissButton = { TextButton(onClick = { askDownload = false; save(noText = true) }) { Text(stringResource(R.string.sc_ocr_download_skip)) } })
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
            onDelete = {
                val before = groups; val opened = openedProject; val nameBefore = name
                val after = groups.mapIndexed { i, g -> if (i != gi) g else g.filterIndexed { j, _ -> j != pi } }.filter { it.isNotEmpty() }
                groups = after; editing = null
                // audit B1: with no page left the next scan is a new document (the opened project is not replaced)
                if (after.isEmpty()) { openedProject = null; name = "" }
                // scanner essentials: a deleted page can be brought back — audit B5: only while nothing else changed
                scope.launch { if (snackbar.showSnackbar(pageDeletedMsg, actionLabel = undoLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed && groups === after) { groups = before; openedProject = opened; name = nameBefore } }
            },
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
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
        // audit B19: Word and picture results are listed too (the reader opens every type)
        leadingContent = { Icon(when (f.extension.lowercase()) { "docx" -> Icons.Default.Description; "jpg" -> Icons.Default.Image; else -> Icons.Default.PictureAsPdf }, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(f.name, maxLines = 1) },
        supportingContent = { Text("${com.smartdocs.editor.core.files.DocumentRepository.formatSize(f.length())} · ${com.smartdocs.editor.core.files.DocumentRepository.formatDate(f.lastModified())}", maxLines = 1) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_cd_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.Routes.reader(android.net.Uri.fromFile(f).toString())) })
                    if (f.extension.equals("pdf", true)) DropdownMenuItem(text = { Text(stringResource(R.string.tool_ocr)) }, onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.Routes.tool("ocr", android.net.Uri.fromFile(f).toString())) })
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

/** Picture side for the size choice: small (sharing), medium, best (A4 at 300 dpi). */
internal fun sizeDim(level: Int) = when (level) { 0 -> 1600; 1 -> 2400; else -> 3508 }
internal fun sizeQuality(level: Int) = when (level) { 0 -> 72; 1 -> 82; else -> 92 }
/** A rough file size for [pages] pages at a size level (measured on text pages: ~0.25 / 0.6 / 1.6 MB a page). */
internal fun sizeEstimate(level: Int, pages: Int): String {
    val mb = pages.coerceAtLeast(1) * when (level) { 0 -> 0.25f; 1 -> 0.6f; else -> 1.6f }
    return if (mb < 1f) "${(mb * 1000).toInt()} KB" else "%.1f MB".format(java.util.Locale.US, mb)
}

/** Kinds whose result is read from the text (contact, receipt CSV, fillable fields) need a PDF. */
internal fun kindNeedsPdf(k: ScanKind) = ScanSavePlan.needsPdf(k)

/** The pages of one document in a row: tap = edit, long-press and drag = move (right-to-left aware). */
@Composable
private fun DraggablePageRow(pages: List<ScanPage>, onEdit: (Int) -> Unit, onMove: (Int, Int) -> Unit) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.toPx() }   // 92 dp page + 8 dp gap
    var dragging by remember { mutableStateOf(-1) }
    var dx by remember { mutableStateOf(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(pages, key = { _, p -> p.file.absolutePath }) { pi, p ->
            val moving = dragging == pi
            Box(Modifier
                .zIndex(if (moving) 1f else 0f)
                .graphicsLayer { if (moving) { translationX = dx; scaleX = 1.06f; scaleY = 1.06f; alpha = 0.9f } }
                .pointerInput(pages.size, pi) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = pi; dx = 0f; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDragEnd = {
                            val shift = Math.round(dx / step) * (if (rtl) -1 else 1)
                            val to = (pi + shift).coerceIn(0, pages.size - 1)
                            dragging = -1; dx = 0f
                            if (to != pi) onMove(pi, to)
                        },
                        onDragCancel = { dragging = -1; dx = 0f },
                    ) { change, d -> change.consume(); dx += d.x }
                }) {
                PageThumb(p, pi) { onEdit(pi) }
            }
        }
    }
}
