package com.smartdocs.editor.ui.rpf

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.NpfPrefs
import com.smartdocs.editor.core.rpf.DocReading
import com.smartdocs.editor.core.rpf.DocReadingStore
import com.smartdocs.editor.core.rpf.RpfPrefs
import com.smartdocs.editor.core.rpf.ViewMath
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode
import com.smartdocs.editor.ui.reader.view.DocTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Reader-side helpers of reader-prefs used by small hooks in shared files. */
object RpfReader {
    private fun doc(s: ReaderState): DocReading = s.file?.let { DocReadingStore.get(s.context, it.absolutePath) } ?: DocReading()

    /** Reading order for read aloud (ReaderAi6 hook): this document's choice, else the global preference. */
    fun readingOrder(s: ReaderState): String = DocReading.orderOf(doc(s), NpfPrefs.readingOrder)

    /** Page count handed to read aloud (ReaderScreen hook): "visible pages" stops after the page it starts on. */
    fun readLimit(s: ReaderState, page: Int): Int = DocReading.readLimit(doc(s), page, s.pageCount)

    // ---- PDF/A view mode: files whose editing was enabled ----
    private fun pdfaPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("rpf_pdfa_edit", Context.MODE_PRIVATE)
    fun pdfaEditingEnabled(ctx: Context, path: String): Boolean = pdfaPrefs(ctx).getBoolean(path, false)
    fun enablePdfaEditing(s: ReaderState) {
        s.file?.let { pdfaPrefs(s.context).edit().putBoolean(it.absolutePath, true).apply() }
        s.guard.pdfaView = false
    }
}

/**
 * reader-prefs host (one call at the end of ReaderScreen): applies this document's reading options and the PDF/A
 * view mode on open, speaks form fields while Tab-through reading is on, and shows the sheets and dialogs.
 */
@Composable
fun RpfReaderHost(s: ReaderState, switchTo: (DocTab?) -> Unit) {
    val ctx = LocalContext.current
    val r = RpfState.of(s)
    LaunchedEffect(Unit) { RpfPrefs.init(ctx); RpfTabGroups.load(ctx) }
    val file = s.file
    val open = s.session != null
    // #23: this document's layout / zoom / direction over the global defaults
    LaunchedEffect(file, open) {
        if (file == null || !open) return@LaunchedEffect
        val d = DocReadingStore.get(ctx, file.absolutePath)
        applyView(s, d)
    }
    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        // the document's /Direction arrives later (applyInitialView): this document's own choice still wins
        snapshotFlow { s.docRtl }.distinctUntilChanged().collect { cur ->
            val o = DocReading.rtlOf(DocReadingStore.get(ctx, file.absolutePath))
            if (o != null && cur != o) s.docRtl = o
        }
    }
    // §37 / PREF-5: PDF/A view mode
    LaunchedEffect(file, open) {
        if (file == null || !open) return@LaunchedEffect
        val pw = s.password
        val claim = withContext(Dispatchers.IO) {
            runCatching {
                com.smartdocs.editor.core.pdf.PdfOps.loadRaw(file, pw).use { d ->
                    val xmp = d.documentCatalog.metadata?.exportXMPMetadata()?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    com.smartdocs.editor.core.nve.panes.DocNotices.pdfaClaim(xmp)
                }
            }.getOrNull()
        }
        r.pdfaClaim = claim
        s.guard.pdfaView = claim != null && RpfPrefs.pdfaViewMode && !RpfReader.pdfaEditingEnabled(ctx, file.absolutePath)
    }
    RpfFieldSpeaker(s)

    if (r.docSheet) DocReadingSheet(s) { r.docSheet = false }
    r.fullScreenAsk?.let { run -> FullScreenAskDialog(onAnswer = { allow, keep ->
        r.fullScreenAsk = null
        if (keep) RpfPrefs.setString(RpfPrefs.K.FS_REQUEST, if (allow) "allow" else "never")
        if (allow) run()
    }) }
    if (RpfTabGroups.sheetOpen) TabGroupsSheet(s, switchTo) { RpfTabGroups.sheetOpen = false }
}

private fun applyView(s: ReaderState, d: DocReading) {
    if (d.layout.isNotEmpty()) runCatching { ViewMode.valueOf(d.layout) }.getOrNull()?.let { s.viewMode = it }
    when (d.fit) { "page" -> { s.fitPage = true; s.zoom = 1f }; "width" -> { s.fitPage = false; s.zoom = 1f } }
    if (d.zoom > 0f) s.zoom = d.zoom
    DocReading.rtlOf(d)?.let { s.docRtl = it }
}

// ---------------------------------------------------------------- #23 "for this document" sheet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DocReadingSheet(s: ReaderState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val path = s.file?.absolutePath ?: return
    var d by remember { mutableStateOf(DocReadingStore.get(ctx, path)) }
    fun set(v: DocReading) { d = v; DocReadingStore.put(ctx, path, v); applyView(s, v) }
    val def = stringResource(R.string.rpf_default)
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rpf_doc_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rpf_doc_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choices(R.string.rpf_doc_layout, listOf("" to def, ViewMode.SINGLE.name to stringResource(R.string.view_single), ViewMode.CONTINUOUS.name to stringResource(R.string.view_continuous),
                ViewMode.FACING.name to stringResource(R.string.view_facing), ViewMode.COVER.name to stringResource(R.string.npf_cover)), d.layout) { set(d.copy(layout = it)) }
            Choices(R.string.rpf_doc_zoom, listOf("" to def, "width" to stringResource(R.string.fit_width), "page" to stringResource(R.string.fit_page)), if (d.zoom > 0f) "z" else d.fit) { set(d.copy(fit = it, zoom = 0f)) }
            TextButton(onClick = { set(d.copy(zoom = s.zoom.coerceIn(0.25f, 32f), fit = if (s.fitPage) "page" else "width", layout = s.viewMode.name)) }) {
                Text(stringResource(R.string.rpf_doc_use_current, (s.zoom * 100).toInt()))
            }
            Choices(R.string.rpf_doc_direction, listOf("" to def, "ltr" to stringResource(R.string.rpf_dir_ltr), "rtl" to stringResource(R.string.rpf_dir_rtl)), d.direction) { v ->
                set(d.copy(direction = v)); if (v.isEmpty()) s.docRtl = null
            }
            HorizontalDivider()
            Text(stringResource(R.string.rpf_doc_reading), style = MaterialTheme.typography.titleSmall)
            Choices(R.string.rpf_doc_order, listOf("" to def, "infer" to stringResource(R.string.npf_read_infer), "raw" to stringResource(R.string.npf_read_raw)), d.order) { set(d.copy(order = it)) }
            Choices(R.string.rpf_doc_mode, listOf("" to def, "visible" to stringResource(R.string.rpf_mode_visible), "document" to stringResource(R.string.rpf_mode_document)), d.mode) { set(d.copy(mode = it)) }
            OutlinedButton(onClick = { set(DocReading()); s.docRtl = null }, enabled = !d.isDefault) { Text(stringResource(R.string.rpf_doc_reset)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(title: Int, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { (v, l) -> FilterChip(value == v, { onPick(v) }, label = { Text(l) }) } }
}

// ---------------------------------------------------------------- ⋮ menu

/** Items for the reader's ⋮ menu (hook after V5MoreMenuItems). */
@Composable
fun RpfMoreMenuItems(s: ReaderState, close: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.rpf_doc_title)) }, leadingIcon = { Icon(Icons.Default.Tune, null) },
        onClick = { close(); RpfState.of(s).docSheet = true })
}

// ---------------------------------------------------------------- §37 full screen

object RpfFullScreen {
    /** A document (/PageMode FullScreen or the named action) asks for full screen: [go] runs per the preference. */
    fun request(s: ReaderState, go: () -> Unit) {
        when (RpfPrefs.fullScreenRequest) {
            "allow" -> go()
            "never" -> {}
            else -> RpfState.of(s).fullScreenAsk = go
        }
    }
}

@Composable
private fun FullScreenAskDialog(onAnswer: (allow: Boolean, remember: Boolean) -> Unit) {
    var keep by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { onAnswer(false, false) },
        icon = { Icon(Icons.Default.Fullscreen, null) },
        title = { Text(stringResource(R.string.rpf_fs_ask_title)) },
        text = { Column {
            Text(stringResource(R.string.rpf_fs_ask_text))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keep, { keep = it }); Text(stringResource(R.string.rpf_remember_choice)) }
        } },
        confirmButton = { TextButton(onClick = { onAnswer(true, keep) }) { Text(stringResource(R.string.rpf_fs_allow)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false, keep) }) { Text(stringResource(R.string.rpf_fs_deny)) } })
}

/** Previous / next page buttons shown in full screen ("page navigation" item), hook in the page box. */
@Composable
fun RpfFullScreenNav(s: ReaderState, chrome: Boolean, modifier: Modifier = Modifier) {
    if (chrome || !RpfPrefs.fsPageNav) return
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        SmallFloatingActionButton(onClick = { s.vx.flip(-1) }) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.rpf_prev_page)) }
        SmallFloatingActionButton(onClick = { s.vx.flip(1) }) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.rpf_next_page)) }
    }
}

// ---------------------------------------------------------------- §37 always show the page size

@Composable
fun RpfPageSizeChip(s: ReaderState, modifier: Modifier = Modifier) {
    if (!RpfPrefs.showPageSize || s.pageCount == 0) return
    val (w, h) = s.pageSizes[s.currentPage] ?: return
    @Suppress("UNUSED_VARIABLE") val v = NpfPrefs.version.intValue
    val label = ViewMath.pageSizeLabel(if (s.rotation % 180 == 0) w else h, if (s.rotation % 180 == 0) h else w, NpfPrefs.units)
    Surface(modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f), contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------- PDF/A banner

/** Hook in the reader's top banner column: the file is open in PDF/A view mode. */
@Composable
fun RpfBanners(s: ReaderState) {
    val r = RpfState.of(s)
    val claim = r.pdfaClaim ?: return
    if (!s.guard.pdfaView) return
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Inventory, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rpf_pdfa_banner, claim), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            TextButton(onClick = { RpfReader.enablePdfaEditing(s) }) { Text(stringResource(R.string.rpf_pdfa_enable)) }
        }
    }
}
