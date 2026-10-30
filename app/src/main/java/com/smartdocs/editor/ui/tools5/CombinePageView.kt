package com.smartdocs.editor.ui.tools5

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.SourceKind
import com.smartdocs.editor.core.tools5.ToPdf
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.CombineEntry
import com.smartdocs.editor.core.tools5.CombinePages
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val MAX_THUMBS_PER_FILE = 60

/**
 * FINAL_CHECK_2 #1 — Combine's page view: every page of the PDF entries as a tile (thumbnail, file, page number) in
 * the order they will be combined; select tiles and move them up / down / to the start or end, or drop them — pages
 * of one file can go between the pages of another. "Apply" folds the sequence back into the entry list
 * ([CombinePages.fold]); non-PDF files and attachments keep their place.
 */
@Composable
fun CombinePageView(entries: List<CombineEntry>, onDismiss: () -> Unit, onApply: (List<CombineEntry>) -> Unit) {
    val ctx = LocalContext.current
    val counts = remember { mutableStateMapOf<Int, Int>() }
    val thumbs = remember { mutableStateMapOf<Pair<Int, Int>, Bitmap>() }
    var refs by remember { mutableStateOf<List<CombinePages.Ref>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(entries) {
        entries.forEachIndexed { i, e ->
            if (e.attach || ToPdf.kindOf(e.file.name) != SourceKind.PDF) return@forEachIndexed
            val s = runCatching { PdfSession.open(ctx, e.file, e.password) }.getOrNull() ?: return@forEachIndexed
            try {
                counts[i] = s.pageCount
                for (p in 0 until minOf(s.pageCount, MAX_THUMBS_PER_FILE)) {
                    val (w, h) = s.pageSize(p)
                    val tw = 160; val th = (tw * h / w.coerceAtLeast(1f)).toInt().coerceIn(1, 360)
                    runCatching { s.renderOwned(p, tw, th, annotations = true) }.getOrNull()?.let { thumbs[i to p] = it }
                }
            } finally { withContext(NonCancellable) { s.closeAsync() } }
        }
        refs = CombinePages.expand(entries, counts)
        loaded = true
    }
    DisposableEffect(Unit) { onDispose { thumbs.values.forEach { if (!it.isRecycled) it.recycle() }; thumbs.clear() } }
    fun move(delta: Int) { val next = CombinePages.move(refs, picked, delta); if (next !== refs) { picked = CombinePages.movedIndexes(picked, delta, refs.size); refs = next } }
    fun toEdge(start: Boolean) {
        val sel = picked.filter { it in refs.indices }.sorted().map { refs[it] }
        if (sel.isEmpty()) return
        val rest = refs.filterIndexed { i, _ -> i !in picked }
        refs = if (start) sel + rest else rest + sel
        picked = if (start) sel.indices.toSet() else (rest.size until refs.size).toSet()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cmb_page_view)) },
        text = {
            Column {
                Text(stringResource(R.string.cmb_page_view_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cmb_pages_n, refs.size, picked.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { toEdge(true) }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.VerticalAlignTop, stringResource(R.string.cmb_to_start)) }
                    IconButton(onClick = { move(-1) }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                    IconButton(onClick = { move(1) }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.t5_move_down)) }
                    IconButton(onClick = { toEdge(false) }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.VerticalAlignBottom, stringResource(R.string.cmb_to_end)) }
                    IconButton(onClick = { refs = refs.filterIndexed { i, _ -> i !in picked }; picked = emptySet() }, enabled = picked.isNotEmpty()) { Icon(Icons.Default.Delete, stringResource(R.string.t5_remove)) }
                }
                if (!loaded) Text(stringResource(R.string.processing), style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.heightIn(max = 440.dp)) {
                    itemsIndexed(refs, key = { i, r -> "${r.entry}:${r.page}:$i" }) { i, r ->
                        val on = i in picked
                        val e = entries.getOrNull(r.entry)
                        Box(Modifier.padding(3.dp).border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            .clickable { picked = if (on) picked - i else picked + i }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val b = thumbs[r.entry to r.page]
                                if (b != null && !b.isRecycled) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().alpha(if (on) 1f else 0.9f))
                                else Box(Modifier.fillMaxWidth().aspectRatio(0.7f))
                                Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                                Text((e?.file?.nameWithoutExtension ?: "?") + " · " + (r.page + 1), Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (on) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = loaded, onClick = { onApply(CombinePages.fold(refs, entries, counts)) }) { Text(stringResource(R.string.t5_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
