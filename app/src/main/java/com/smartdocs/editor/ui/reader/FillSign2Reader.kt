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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.data.SignatureMetaStore
import com.smartdocs.editor.core.fillsign2.FillSignPrefs
import com.smartdocs.editor.core.fillsign2.MultiPage
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedDocumentException
import com.smartdocs.editor.core.pdf.SignedSave
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
    var annot by mutableStateOf<Annot?>(null)
        private set
    /** Size of the page it was copied from (PDF points). */
    var pageSize: Pair<Float, Float>? = null
        private set

    fun put(a: Annot, size: Pair<Float, Float>?) { annot = a; pageSize = size }

    /** Long-press on the page with something on the clipboard: a "Paste here" bar at that PDF point. */
    data class Offer(val owner: ReaderState, val page: Int, val x: Float, val y: Float)
    var offer by mutableStateOf<Offer?>(null)
}

private fun Annot.box(): MultiPage.Box { val r = PageGeometry.normPdf(rects.first()); return MultiPage.Box(r.left, r.bottom, r.right, r.top) }
private fun MultiPage.Box.rect() = RectF(left, top, right, bottom)

fun ReaderState.copyStamp(a: Annot) { if (a.kind == AnnotKind.STAMP_IMAGE && a.rects.isNotEmpty()) StampClipboard.put(a, pageSizes[a.page]) }

fun ReaderState.cutStamp(a: Annot) { if (a.kind != AnnotKind.STAMP_IMAGE || a.rects.isEmpty()) return; copyStamp(a); edit.delete() }

/** Paste on the current page: the same spot when it fits there, otherwise the centre of the page. */
fun ReaderState.pasteStamp() {
    val src = StampClipboard.annot ?: return
    val page = currentPage
    scope.launch {
        val (w, h) = pageSize(page)
        val b = MultiPage.pasteBox(src.box(), w, h)
        val c = src.copy(id = System.nanoTime(), page = page, rects = listOf(b.rect()), created = System.currentTimeMillis(), nm = null, groupId = null)
        addAnnot(c); edit.selectPending(c)
    }
}

/** Long-press "Paste here": the clipboard stamp centred on PDF point ([x], [y]) of [page]. */
fun ReaderState.pasteStampAt(page: Int, x: Float, y: Float) {
    val src = StampClipboard.annot ?: return
    StampClipboard.offer = null
    scope.launch {
        val (w, h) = pageSize(page)
        val b = MultiPage.boxAt(src.box(), x, y, w, h)
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
        val (fw, fh) = pageSize(a.page)
        val sizes = pages.associateWith { pageSize(it) }
        val now = System.currentTimeMillis(); val base = System.nanoTime()
        batchAnnots { list ->
            pages.forEachIndexed { i, p ->
                val (tw, th) = sizes.getValue(p)
                val b = MultiPage.relocate(a.box(), fw, fh, tw, th)
                list += a.copy(id = base + i + 1, page = p, rects = listOf(b.rect()), created = now, nm = null, groupId = null)
            }
        }
        done(pages.size)
    }
}

/**
 * True (and the "this file is signed, save a copy" dialog is shown) when the open file carries a digital signature:
 * flattening or adding page content would break it.
 */
private suspend fun ReaderState.blockedBySignature(): Boolean {
    val f = file ?: return true
    if (!withContext(Dispatchers.IO) { SignedSave.isSigned(f, password) }) return false
    signedBlock = SignedDocumentException.Reason.INCREMENTAL_FAILED
    return true
}

/** "Apply All Signatures": every placed signature / initials stamp (pending ones are saved first) becomes page content. */
fun ReaderState.applyAllSignatures(onResult: (Int) -> Unit) {
    scope.launch {
        if (blockedBySignature()) return@launch
        val label = context.getString(R.string.nf2_apply_all)
        var n = 0
        try {
            applyInPlace(label = { label }) { src, out, pw -> n = PdfOps.flattenSignatureStamps(src, out, pw); if (n == 0) withContext(Dispatchers.IO) { out.delete() } }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { if (n > 0) error = e.message ?: e.toString() }
        onResult(n)
    }
}

/** Standalone paging seal (no digital signature): strips of [imagePath] across the pages of [range] (blank = all). */
fun ReaderState.addPagingSeal(imagePath: String, sizePt: Float, yFraction: Float, range: String, onDone: (Boolean) -> Unit) {
    scope.launch {
        if (blockedBySignature()) { onDone(false); return@launch }
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

/** Removes every paging seal this app added; [onDone] gets the number of pages changed. */
fun ReaderState.removePagingSeals(onDone: (Int) -> Unit) {
    scope.launch {
        if (blockedBySignature()) { onDone(0); return@launch }
        var n = 0
        try {
            val label = context.getString(R.string.nf2_seal_remove)
            applyInPlace(label = { label }) { src, out, pw -> n = withContext(Dispatchers.IO) { PagingSeal.remove(src, out, pw) } }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { if (n > 0) error = e.message ?: e.toString() }
        onDone(n)
    }
}

// ================= UI =================

/** Extra buttons of the selection bar for an image stamp: cut, copy, set size as default, place on multiple pages. */
@Composable
fun StampActionButtons(state: ReaderState, annot: Annot?, enabled: Boolean) {
    if (annot?.kind != AnnotKind.STAMP_IMAGE) return
    val ctx = state.context
    var multi by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.nf2_copied)
    // a signature read back from the file knows only its reference: find the saved signature it came from
    val sigs by com.smartdocs.editor.LocalApp.current.db.signatures().all().collectAsState(initial = emptyList())
    val sigPath = annot.sigPath ?: com.smartdocs.editor.core.fillsign2.StampRead.resolve(annot.sigRef, sigs.map { it.path })
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
    IconButton(onClick = { multi = true }, enabled = enabled && state.pageCount > 1) { Icon(Icons.Default.FileCopy, stringResource(R.string.nf2_multi_pages)) }
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

/** Paste chip for the Fill & Sign tab (and any toolbar): shown while a stamp is on the clipboard. */
@Composable
fun PasteStampChip(state: ReaderState) {
    if (StampClipboard.annot != null) ToolChip(Icons.Default.ContentPaste, stringResource(R.string.nf2_paste)) { state.pasteStamp() }
}

/** The "Paste here" bar of a long-press on [page] (layout space of the page box). */
@Composable
fun PasteHereBar(state: ReaderState, page: Int, g: PageGeometry) {
    val o = StampClipboard.offer?.takeIf { it.owner === state && it.page == page } ?: return
    if (StampClipboard.annot == null) return
    val at = g.pdfRectToLayout(RectF(o.x, o.y, o.x, o.y))
    Surface(
        Modifier.offset { androidx.compose.ui.unit.IntOffset((at.left - 60f).roundToInt().coerceAtLeast(0), (at.top - 140f).roundToInt().coerceAtLeast(0)) },
        shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.pasteStampAt(o.page, o.x, o.y) }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf2_paste_here)) }
            IconButton(onClick = { StampClipboard.offer = null }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        }
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
            Toast.makeText(ctx, if (n > 0) ctx.resources.getQuantityString(R.plurals.nf2_applied, n, n) else ctx.getString(R.string.nf2_none_to_apply), Toast.LENGTH_SHORT).show()
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
                    Switch(onSave, { onSave = it; FillSignPrefs.setFlattenOnSave(ctx, it) })
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nf2_flatten_on_save), Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text(stringResource(R.string.nf2_apply)) } },
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
    val usable = sigs.filter { File(it.path).exists() || SignatureMetaStore.get(ctx, it.path).locked }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.ViewWeek, null) },
        title = { Text(stringResource(R.string.nf2_paging_seal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nf2_seal_msg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    usable.forEach { s ->
                        Box(
                            Modifier.size(72.dp, 44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                                .border(2.dp, if (path == s.path) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { gate.request(s.path) { path = s.path } },
                            contentAlignment = Alignment.Center,
                        ) { coil.compose.AsyncImage(model = File(s.path), contentDescription = null, modifier = Modifier.fillMaxSize().padding(3.dp)) }
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
                        Toast.makeText(ctx, if (n > 0) ctx.resources.getQuantityString(R.plurals.nf2_seal_removed, n, n) else ctx.getString(R.string.nf2_seal_none), Toast.LENGTH_SHORT).show()
                        if (n > 0) onDismiss()
                    }
                }, enabled = !busy) { Icon(Icons.Default.LayersClear, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nf2_seal_remove)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = path ?: return@TextButton
                busy = true
                state.addPagingSeal(p, size, y, range) { ok -> busy = false; if (ok) onDismiss() }
            }, enabled = !busy && path != null) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.nf2_seal_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
