package com.smartdocs.editor.ui.tools5

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.tools5.CombinePick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Combine thumbnail view (row 111): the pages of [file] as thumbnails; tapping one picks / unpicks it, OK writes the
 * picked pages back as the entry's range. Thumbnails are this dialog's own bitmaps (renderOwned), recycled when it closes.
 */
@Composable
fun CombinePagePicker(file: File, password: String?, range: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val ctx = LocalContext.current
    var count by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap>() }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(file) {
        val s = runCatching { PdfSession.open(ctx, file, password) }.getOrNull() ?: run { failed = true; return@LaunchedEffect }
        try {
            count = s.pageCount
            picked = CombinePick.pagesOf(range, count)
            for (i in 0 until minOf(count, MAX_THUMBS)) {
                val (w, h) = s.pageSize(i)
                val tw = 180; val th = (tw * h / w.coerceAtLeast(1f)).toInt().coerceIn(1, 400)
                runCatching { s.renderOwned(i, tw, th, annotations = true) }.getOrNull()?.let { thumbs[i] = it }
            }
        } finally { withContext(NonCancellable) { s.closeAsync() } }
    }
    DisposableEffect(Unit) { onDispose { thumbs.values.forEach { if (!it.isRecycled) it.recycle() }; thumbs.clear() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cv9_pick_pages)) },
        text = {
            Column {
                if (failed) Text(stringResource(R.string.cv9_pick_failed), color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cv9_picked, picked.size, count), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { picked = (0 until count).toSet() }) { Text(stringResource(R.string.cv9_pick_all)) }
                    TextButton(onClick = { picked = emptySet() }) { Text(stringResource(R.string.cv9_pick_none)) }
                }
                if (count > MAX_THUMBS) Text(stringResource(R.string.cv9_pick_more, MAX_THUMBS), style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed((0 until minOf(count, MAX_THUMBS)).toList()) { _, i ->
                        val on = i in picked
                        Box(Modifier.padding(4.dp).border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            .clickable(onClickLabel = (i + 1).toString()) { picked = if (on) picked - i else picked + i }) {
                            val b = thumbs[i]
                            if (b != null && !b.isRecycled) Image(b.asImageBitmap(), stringResource(R.string.cv9_page_n, i + 1), Modifier.fillMaxWidth().alpha(if (on) 1f else 0.45f))
                            else Box(Modifier.fillMaxWidth().aspectRatio(0.7f))
                            Text("${i + 1}", Modifier.align(Alignment.BottomCenter).padding(2.dp), style = MaterialTheme.typography.labelSmall)
                            if (on) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onPicked(CombinePick.rangeOf(picked, count)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Thumbnails rendered at most (longer documents: the range field still takes any page). */
private const val MAX_THUMBS = 120
