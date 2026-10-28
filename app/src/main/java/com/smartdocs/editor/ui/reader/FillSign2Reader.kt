package com.smartdocs.editor.ui.reader

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.data.SignatureMetaStore
import com.smartdocs.editor.core.fillsign2.FillSignPrefs
import com.smartdocs.editor.core.fillsign2.FsGeom
import com.smartdocs.editor.core.fillsign2.MultiPage
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.sign.PagingSeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/*
 * nitro-fillsign2, reader side: copy / cut / paste of placed signature stamps, "Set current size as default",
 * "Place on multiple pages", "Apply All Signatures" (+ "Flatten signatures when saving") and the standalone paging
 * seal for ink Fill & Sign.
 */

/** In-app clipboard for one placed image stamp (signature, initials, stamp, picture); lives for the process. */
object StampClipboard {
    /**
     * The copied stamp. Tab audit D31: a saved signature whose image file exists is kept by its path only (the bitmap
     * is decoded again on paste), so a closed reader's bitmaps are not held for the rest of the process.
     */
    var annot by mutableStateOf<Annot?>(null)
        private set

    fun put(a: Annot) { annot = if (a.sigPath?.let { File(it).exists() } == true) a.copy(image = null) else a }

    /** Long-press on the page with something on the clipboard: a "Paste here" bar at that PDF point. */
    data class Offer(val owner: ReaderState, val page: Int, val x: Float, val y: Float)
    var offer by mutableStateOf<Offer?>(null)

    /** Tab audit D31: the reader [owner] closed: its "Paste here" offer (which holds the reader) goes. */
    fun release(owner: ReaderState) { if (offer?.owner === owner) offer = null }
}

private fun Annot.box(): MultiPage.Box { val r = PageGeometry.normPdf(rects.first()); return MultiPage.Box(r.left, r.bottom, r.right, r.top) }
private fun MultiPage.Box.rect() = RectF(left, top, right, bottom)

/** Tab audit D9 / D10: crop box (user space) and /Rotate of [page] from the known page size and frame. */
fun ReaderState.pageBoxOf(page: Int): FsGeom.PageBox {
    val (w, h) = pageSizes[page] ?: (595f to 842f)
    val fr = pageFrames[page]
    return FsGeom.PageBox.fromDisplayed(w, h, fr?.rotate ?: 0, fr?.cropX ?: 0f, fr?.cropY ?: 0f)
}

/** [pageBoxOf] once the page size is measured. */
suspend fun ReaderState.pageBoxAt(page: Int): FsGeom.PageBox { pageSize(page); return pageBoxOf(page) }

fun ReaderState.copyStamp(a: Annot) { if (a.kind == AnnotKind.STAMP_IMAGE && a.rects.isNotEmpty()) StampClipboard.put(a) }

fun ReaderState.cutStamp(a: Annot) { if (a.kind != AnnotKind.STAMP_IMAGE || a.rects.isEmpty()) return; copyStamp(a); edit.delete() }

/** The clipboard stamp with its bitmap (a signature kept by path is decoded again); null when it cannot be read. */
private suspend fun clipboardStamp(): Annot? {
    val src = StampClipboard.annot ?: return null
    if (src.image != null) return src
    val path = src.sigPath ?: return null
    val bmp = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() } ?: return null
    return src.copy(image = bmp)
}

/** Paste on the current page: the same spot when it fits in its crop box, otherwise the centre of the page. */
fun ReaderState.pasteStamp() {
    val page = currentPage
    scope.launch {
        val src = clipboardStamp() ?: return@launch
        val b = MultiPage.pasteBox(src.box(), pageBoxAt(page))
        val c = src.copy(id = System.nanoTime(), page = page, rects = listOf(b.rect()), created = System.currentTimeMillis(), nm = null, groupId = null)
        addAnnot(c); edit.selectPending(c)
    }
}

/** Long-press "Paste here": the clipboard stamp centred on PDF point ([x], [y]) of [page]. */
fun ReaderState.pasteStampAt(page: Int, x: Float, y: Float) {
    StampClipboard.offer = null
    scope.launch {
        val src = clipboardStamp() ?: return@launch
        val b = MultiPage.boxAt(src.box(), x, y, pageBoxAt(page))
        val c = src.copy(id = System.nanoTime(), page = page, rects = listOf(b.rect()), created = System.currentTimeMillis(), nm = null, groupId = null)
        addAnnot(c); edit.selectPending(c)
    }
}

/** Copies [a] to the pages of [set] (except its own) at the same relative position, as one undo step. */
fun ReaderState.placeOnPages(a: Annot, set: MultiPage.PageSet, range: String, done: (Int) -> Unit) {
    if (a.rects.isEmpty()) return
    scope.launch {
        val pages = MultiPage.pages(set, range, pageCount, except = a.page)
        if (pages.isEmpty()) { done(0); return@launch }
        // tab audit D10: crop boxes in user space (the displayed size of a turned page moved the stamp)
        val from = pageBoxAt(a.page)
        val boxes = pages.associateWith { pageBoxAt(it) }
        val now = System.currentTimeMillis(); val base = System.nanoTime()
        batchAnnots { list ->
            pages.forEachIndexed { i, p ->
                val b = MultiPage.relocate(a.box(), from, boxes.getValue(p))
                list += a.copy(id = base + i + 1, page = p, rects = listOf(b.rect()), created = now, nm = null, groupId = null)
            }
        }
        done(pages.size)
    }
}

/*
 * Tab audit D14: on a signed / certified file these operations are no longer refused up front with the generic
 * "save a copy" message (whose copy then lacked the seal / the flattening). They run, and the reader's one
 * signed-file gate in applyInPlace (SignedGuard, tab-view-core V-06) keeps the result as ReaderState.signedCopy:
 * "Save a copy" exports the document WITH the seal / flattened signatures; the signed original stays untouched.
 *
 * Tab audit D13: every failure is reported (a guard refusal, an IO error, a failed pre-save); "nothing to apply" /
 * "no seals" only after a run that succeeded. [BLOCKED] tells the caller that an error or the signed dialog took over.
 */
const val BLOCKED = -1

/** The op deleted its output on purpose because there was nothing to do (applyInPlace then throws "empty result"). */
private fun isEmptyResult(e: Exception) = e is IllegalStateException && e.message == "empty result"

/** "Apply All Signatures": every placed signature / initials stamp (pending ones are saved first) becomes page content. */
fun ReaderState.applyAllSignatures(onResult: (Int) -> Unit) {
    scope.launch {
        val label = context.getString(R.string.nf2_apply_all)
        var n = 0
        val r = try {
            applyInPlace(label = { label }) { src, out, pw -> n = PdfOps.flattenSignatureStamps(src, out, pw); if (n == 0) withContext(Dispatchers.IO) { out.delete() } }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (n > 0 || !isEmptyResult(e)) { error = e.message ?: e.toString(); onResult(BLOCKED); return@launch }
            null
        }
        // a result of null after flattening: the signed gate kept it as a copy, or the pending edits could not be saved
        onResult(if (r == null && (n > 0 || signedBlock != null || dirty)) BLOCKED else n)
    }
}

/** Standalone paging seal (no digital signature): strips of [imagePath] across the pages of [range] (blank = all). */
fun ReaderState.addPagingSeal(imagePath: String, sizePt: Float, yFraction: Float, range: String, onDone: (Boolean) -> Unit) {
    scope.launch {
        val ok = try {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) } ?: throw IllegalArgumentException(context.getString(R.string.nf2_seal_no_image))
            val pages = if (range.isBlank()) null else com.smartdocs.editor.core.share5.ShareChoice.parseRange(range, pageCount).ifEmpty { throw IllegalArgumentException(context.getString(R.string.nf2_bad_range)) }
            val label = context.getString(R.string.nf2_seal_add)
            applyInPlace(label = { label }) { src, out, pw -> withContext(Dispatchers.IO) { PagingSeal.apply(src, out, bmp, sizePt, yFraction, pages, pw) } } != null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: e.toString(); false }
        onDone(ok)
    }
}

/** Removes every paging seal this app added; [onDone] gets the number of pages changed (or [BLOCKED]). */
fun ReaderState.removePagingSeals(onDone: (Int) -> Unit) {
    scope.launch {
        var n = 0
        val r = try {
            val label = context.getString(R.string.nf2_seal_remove)
            applyInPlace(label = { label }) { src, out, pw -> n = withContext(Dispatchers.IO) { PagingSeal.remove(src, out, pw) }; if (n == 0) withContext(Dispatchers.IO) { out.delete() } }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (n > 0 || !isEmptyResult(e)) { error = e.message ?: e.toString(); onDone(BLOCKED); return@launch }
            null
        }
        onDone(if (r == null && (n > 0 || signedBlock != null || dirty)) BLOCKED else n)
    }
}

// ================= UI =================

/** The saved signature a placed stamp came from (a stamp read back from the file knows only its reference). */
@Composable
fun rememberStampSigPath(annot: Annot?): String? {
    if (annot == null) return null
    val sigs by com.smartdocs.editor.LocalApp.current.db.signatures().all().collectAsState(initial = emptyList())
    return annot.sigPath ?: com.smartdocs.editor.core.fillsign2.StampRead.resolve(annot.sigRef, sigs.map { it.path })
}

/**
 * Tab audit D15: runs [action] at once, or after the PIN when [sigPath] is a password-locked saved signature (a
 * placed locked signature can no longer be pasted or copied to other pages without it).
 */
fun com.smartdocs.editor.ui.signature.SignatureGate.requestFor(sigPath: String?, action: () -> Unit) {
    if (sigPath == null) action() else request(sigPath, action)
}

/** Extra buttons of the selection bar for an image stamp: cut, copy, set size as default, place on multiple pages. */
@Composable
fun StampActionButtons(state: ReaderState, annot: Annot?, enabled: Boolean) {
    if (annot?.kind != AnnotKind.STAMP_IMAGE) return
    val ctx = state.context
    var multi by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.nf2_copied)
    val sigPath = rememberStampSigPath(annot)
    val gate = com.smartdocs.editor.ui.signature.rememberSignatureGate()
    val sizeSaved = stringResource(R.string.nf2_size_saved)
    IconButton(onClick = { state.cutStamp(annot) }, enabled = enabled) { Icon(Icons.Default.ContentCut, stringResource(R.string.nf2_cut)) }
    IconButton(onClick = { state.copyStamp(annot); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }, enabled = enabled) { Icon(Icons.Default.CopyAll, stringResource(R.string.nf2_copy_clip)) }
    sigPath?.let { path ->
        IconButton(onClick = {
            val r = PageGeometry.normPdf(annot.rects.first())
            SignatureMetaStore.setWidth(ctx, path, r.right - r.left)
            Toast.makeText(ctx, sizeSaved, Toast.LENGTH_SHORT).show()
        }, enabled = enabled) { Icon(Icons.Default.AspectRatio, stringResource(R.string.nf2_set_default_size)) }
    }
    // tab audit D15: copying a locked signature to other pages asks for its PIN
    IconButton(onClick = { gate.requestFor(sigPath) { multi = true } }, enabled = enabled && state.pageCount > 1) { Icon(Icons.Default.FileCopy, stringResource(R.string.nf2_multi_pages)) }
    if (multi) MultiPageDialog(state.pageCount, onDismiss = { multi = false }) { set, range ->
        multi = false
        state.placeOnPages(annot, set, range) { n -> Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.nf2_placed_on_pages, n, n), Toast.LENGTH_SHORT).show() }
    }
}

@Composable
fun MultiPageDialog(pageCount: Int, onDismiss: () -> Unit, onApply: (MultiPage.PageSet, String) -> Unit) {
    var set by remember { mutableStateOf(MultiPage.PageSet.ALL) }
    var range by remember { mutableStateOf("") }
    val custom = set == MultiPage.PageSet.CUSTOM
    val valid = !custom || MultiPage.pages(set, range, pageCount).isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.FileCopy, null) },
        title = { Text(stringResource(R.string.nf2_multi_pages)) },
        text = {
            Column {
                Text(stringResource(R.string.nf2_multi_pages_msg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(MultiPage.PageSet.ALL to R.string.nf2_pages_all, MultiPage.PageSet.ODD to R.string.nf2_pages_odd,
                    MultiPage.PageSet.EVEN to R.string.nf2_pages_even, MultiPage.PageSet.CUSTOM to R.string.nf2_pages_custom).forEach { (s, l) ->
                    Row(Modifier.fillMaxWidth().clickable { set = s }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(set == s, { set = s }); Text(stringResource(l))
                    }
                }
                if (custom) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, isError = range.isNotBlank() && !valid,
                    placeholder = { Text("1-3, 5") }, label = { Text(stringResource(R.string.nf2_pages_range, pageCount)) })
            }
        },
        confirmButton = { TextButton(onClick = { onApply(set, range) }, enabled = valid) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Tab audit D24: the one "place on multiple pages" of the reader. The placement bar (signature / stamp / picture
 * being placed) and the stamp selection bar both use it: [MultiPageDialog] + [placeOnPages] (crop box / rotation
 * aware, one undo step). [annot] is the stamp just placed.
 */
@Composable
fun PlaceOnPagesDialog(state: ReaderState, annot: Annot, onDismiss: () -> Unit) {
    val ctx = state.context
    MultiPageDialog(state.pageCount, onDismiss = onDismiss) { set, range ->
        onDismiss()
        state.placeOnPages(annot, set, range) { n -> Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.nf2_placed_on_pages, n, n), Toast.LENGTH_SHORT).show() }
    }
}

/** Paste chip for the Fill & Sign tab (and any toolbar): shown while a stamp is on the clipboard. */
@Composable
fun PasteStampChip(state: ReaderState) {
    val clip = StampClipboard.annot ?: return
    val sigPath = rememberStampSigPath(clip)
    val gate = com.smartdocs.editor.ui.signature.rememberSignatureGate()
    // tab audit D15: pasting a locked signature asks for its PIN
    ToolChip(Icons.Default.ContentPaste, stringResource(R.string.nf2_paste)) { gate.requestFor(sigPath) { state.pasteStamp() } }
}

/** The "Paste here" bar of a long-press on [page] (layout space of the page box). */
@Composable
fun PasteHereBar(state: ReaderState, page: Int, g: PageGeometry) {
    val o = StampClipboard.offer?.takeIf { it.owner === state && it.page == page } ?: return
    val clip = StampClipboard.annot ?: return
    val sigPath = rememberStampSigPath(clip)
    val gate = com.smartdocs.editor.ui.signature.rememberSignatureGate()
    val at = g.pdfRectToLayout(RectF(o.x, o.y, o.x, o.y))
    Surface(
        // tab audit D11: layout px from the left edge in every layout direction (offset mirrors in the Arabic UI)
        Modifier.absoluteOffset { androidx.compose.ui.unit.IntOffset((at.left - 60f).roundToInt().coerceAtLeast(0), (at.top - 140f).roundToInt().coerceAtLeast(0)) },
        shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { gate.requestFor(sigPath) { state.pasteStampAt(o.page, o.x, o.y) } }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf2_paste_here)) }
            IconButton(onClick = { StampClipboard.offer = null }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        }
    }
}

/**
 * Tab audit D15 / D28: thumbnail of a saved signature. A password-locked one is blurred (API 31+) or replaced by a
 * lock (older Android, where blur does nothing), and every thumbnail has a spoken label for TalkBack.
 */
@Composable
fun SignatureThumb(s: SavedSignature, index: Int, locked: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(if (s.isInitials) R.string.tfr_thumb_initials else R.string.tfr_thumb_signature, index + 1) +
        if (locked) ", " + stringResource(R.string.tfr_thumb_locked) else ""
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        val blurOk = android.os.Build.VERSION.SDK_INT >= 31
        if (!locked || blurOk) coil.compose.AsyncImage(model = File(s.path), contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(4.dp).then(if (locked) Modifier.blur(10.dp) else Modifier))
        if (locked) Icon(Icons.Default.Lock, null, Modifier.align(if (blurOk) Alignment.TopStart else Alignment.Center).padding(2.dp).size(if (blurOk) 12.dp else 20.dp), tint = Color.Gray)
        if (s.isInitials) Text(stringResource(R.string.sig_initials), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp), color = Color.Gray)
    }
}

/** Chips added to the Sign tab: paste, Apply All Signatures, paging seal. */
@Composable
fun FillSign2SignChips(state: ReaderState, sigs: List<SavedSignature>) {
    val ctx = state.context
    var apply by remember { mutableStateOf(false) }
    var seal by remember { mutableStateOf(false) }
    PasteStampChip(state)
    ToolChip(Icons.Default.Layers, stringResource(R.string.nf2_apply_all), enabled = !state.saving) { apply = true }
    ToolChip(Icons.Default.ViewWeek, stringResource(R.string.nf2_paging_seal), enabled = !state.saving) { seal = true }
    if (apply) ApplyAllDialog(onDismiss = { apply = false }) {
        apply = false
        state.applyAllSignatures { n ->
            if (n != BLOCKED) Toast.makeText(ctx, if (n > 0) ctx.resources.getQuantityString(R.plurals.nf2_applied, n, n) else ctx.getString(R.string.nf2_none_to_apply), Toast.LENGTH_SHORT).show()
        }
    }
    if (seal) PagingSealDialog(state, sigs, onDismiss = { seal = false })
}

@Composable
private fun ApplyAllDialog(onDismiss: () -> Unit, onApply: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var onSave by remember { mutableStateOf(FillSignPrefs.flattenOnSave(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Layers, null) },
        title = { Text(stringResource(R.string.nf2_apply_all)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nf2_apply_all_msg), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(onSave, { onSave = it })
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nf2_flatten_on_save), Modifier.weight(1f))
                }
            }
        },
        // tab audit D30: the switch is stored only when the user applies (Cancel leaves the preference as it was)
        confirmButton = { TextButton(onClick = { FillSignPrefs.setFlattenOnSave(ctx, onSave); onApply() }) { Text(stringResource(R.string.nf2_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PagingSealDialog(state: ReaderState, sigs: List<SavedSignature>, onDismiss: () -> Unit) {
    val ctx = state.context
    val gate = com.smartdocs.editor.ui.signature.rememberSignatureGate()
    var path by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableFloatStateOf(110f) }
    var y by remember { mutableFloatStateOf(0.5f) }
    var range by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pick = com.smartdocs.editor.ui.common.rememberImagePicker(multiple = false) { files -> files.firstOrNull()?.let { path = it.absolutePath } }
    val meta by remember { SignatureMetaStore.flow(ctx) }.collectAsState()
    val usable = sigs.filter { File(it.path).exists() || meta[it.path]?.locked == true }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.ViewWeek, null) },
        title = { Text(stringResource(R.string.nf2_paging_seal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nf2_seal_msg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    usable.forEachIndexed { i, s ->
                        // tab audit D15: a locked signature is chosen only after its PIN, and its thumbnail is blurred
                        SignatureThumb(s, i, meta[s.path]?.locked == true,
                            Modifier.size(72.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                                .border(2.dp, if (path == s.path) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { gate.request(s.path) { path = s.path } })
                    }
                    OutlinedButton(onClick = pick) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nf2_seal_image)) }
                }
                Text("${stringResource(R.string.nf2_seal_size)}: ${size.roundToInt()} pt", style = MaterialTheme.typography.labelMedium)
                Slider(size, { size = it }, valueRange = 40f..200f)
                Text("${stringResource(R.string.nf2_seal_position)}: ${(y * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(y, { y = it }, valueRange = 0.05f..0.95f)
                OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.nf2_pages_all)) },
                    label = { Text(stringResource(R.string.nf2_pages_range, state.pageCount)) })
                TextButton(onClick = {
                    busy = true
                    state.removePagingSeals { n ->
                        busy = false
                        if (n != BLOCKED) Toast.makeText(ctx, if (n > 0) ctx.resources.getQuantityString(R.plurals.nf2_seal_removed, n, n) else ctx.getString(R.string.nf2_seal_none), Toast.LENGTH_SHORT).show()
                        if (n != 0) onDismiss()
                    }
                }, enabled = !busy) { Icon(Icons.Default.LayersClear, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nf2_seal_remove)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = path ?: return@TextButton
                busy = true
                // the chosen signature already passed the PIN gate when it was picked
                state.addPagingSeal(p, size, y, range) { ok -> busy = false; if (ok || state.signedBlock != null) onDismiss() }
            }, enabled = !busy && path != null) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.nf2_seal_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
