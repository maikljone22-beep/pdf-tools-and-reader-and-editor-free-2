package com.smartdocs.editor.ui.convert6

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.CombinePlus
import com.smartdocs.editor.core.tools5.FileSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures before "images → PDF" (user request): thumbnails in page order instead of a file list. A tap opens a menu
 * like the page organizer's — view, rotate, move (forward / back / first / last / to a number), remove; sort by name or
 * date taken, reverse. Rotation writes a rotated copy in the temp folder (the picked file is never changed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageGridEditor(files: List<File>, onChange: (List<File>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<Int?>(null) }
    var view by remember { mutableStateOf<Int?>(null) }
    var moveAsk by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sortKey by remember { mutableStateOf<FileSort?>(null) }
    var desc by remember { mutableStateOf(false) }
    fun move(from: Int, to: Int) {
        if (from !in files.indices) return
        val l = files.toMutableList(); val f = l.removeAt(from); l.add(to.coerceIn(0, l.size), f); onChange(l)
    }
    fun rotate(i: Int, deg: Int) {
        val src = files.getOrNull(i) ?: return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { rotatedCopy(ctx, src, deg) }.getOrNull() }
            busy = false
            if (out != null) onChange(files.toMutableList().also { if (i < it.size && it[i] == src) it[i] = out })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pc_sort), style = MaterialTheme.typography.labelLarge)
            fun sortBy(by: FileSort) { val d = sortKey == by && !desc; onChange(CombinePlus.sortFiles(files, by, d)); sortKey = by; desc = d }
            TextButton(onClick = { sortBy(FileSort.NAME) }) { Text(stringResource(R.string.pc_sort_name)) }
            TextButton(onClick = { sortBy(FileSort.TIME) }) { Text(stringResource(R.string.pc_sort_time)) }
            TextButton(onClick = { onChange(files.reversed()) }) { Icon(Icons.Default.SwapVert, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_reverse_order)) }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        // lazy: only the visible thumbnails are loaded (1000+ pictures must not run the memory out)
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(104.dp),
            Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files.size, key = { k -> files[k].absolutePath + "#" + k }) { i ->
                val f = files[i]
                Box {
                    Column(Modifier.width(104.dp).clip(RoundedCornerShape(10.dp)).clickable { menu = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(104.dp, 136.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
                            AsyncImage(model = f, contentDescription = f.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Surface(Modifier.padding(4.dp).align(Alignment.TopStart), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                                Text("${i + 1}", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    DropdownMenu(expanded = menu == i, onDismissRequest = { menu = null }) {
                        Text(stringResource(R.string.tpn_menu_page, i + 1), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        HorizontalDivider()
                        @Composable fun item(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, act: () -> Unit) =
                            DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = { menu = null; act() })
                        item(R.string.tpn_view, Icons.Default.Visibility) { view = i }
                        item(R.string.tpn_rotate_left, Icons.AutoMirrored.Filled.RotateLeft, !busy) { rotate(i, -90) }
                        item(R.string.tpn_rotate_right, Icons.AutoMirrored.Filled.RotateRight, !busy) { rotate(i, 90) }
                        item(R.string.t5_move_up, Icons.Default.KeyboardArrowUp, i > 0) { move(i, i - 1) }
                        item(R.string.t5_move_down, Icons.Default.KeyboardArrowDown, i < files.lastIndex) { move(i, i + 1) }
                        item(R.string.img_move_first, Icons.Default.VerticalAlignTop, i > 0) { move(i, 0) }
                        item(R.string.img_move_last, Icons.Default.VerticalAlignBottom, i < files.lastIndex) { move(i, files.lastIndex) }
                        item(R.string.pg_move_to, Icons.Default.Pin, files.size > 1) { moveAsk = i }
                        item(R.string.t5_remove, Icons.Default.Delete) { onChange(files.filterIndexed { k, _ -> k != i }) }
                    }
                }
            }
        }
    }
    view?.let { i0 ->
        val i = i0.coerceIn(0, (files.size - 1).coerceAtLeast(0))
        if (files.isEmpty()) { view = null; return@let }
        // the viewer carries the picture's menu as a toolbar
        val tools = listOf(
            Triple(stringResource(R.string.tpn_rotate_left), Icons.AutoMirrored.Filled.RotateLeft, { rotate(i, -90) }),
            Triple(stringResource(R.string.tpn_rotate_right), Icons.AutoMirrored.Filled.RotateRight, { rotate(i, 90) }),
            Triple(stringResource(R.string.t5_move_up), Icons.Default.KeyboardArrowUp, { if (i > 0) { move(i, i - 1); view = i - 1 } }),
            Triple(stringResource(R.string.t5_move_down), Icons.Default.KeyboardArrowDown, { if (i < files.lastIndex) { move(i, i + 1); view = i + 1 } }),
            Triple(stringResource(R.string.pg_move_to), Icons.Default.Pin, { moveAsk = i }),
            Triple(stringResource(R.string.t5_remove), Icons.Default.Delete, { onChange(files.filterIndexed { k, _ -> k != i }) }),
        )
        ImageViewDialog(files, i, tools, onDismiss = { view = null }) { view = it }
    }
    moveAsk?.let { i ->
        var text by remember(i) { mutableStateOf("") }
        val target = com.smartdocs.editor.core.edit5.Digits.toWestern(text).trim().toIntOrNull()
        val ok = target != null && target in 1..files.size
        AlertDialog(onDismissRequest = { moveAsk = null }, title = { Text(stringResource(R.string.pg_move_title, i + 1)) },
            text = { OutlinedTextField(text, { text = it.take(6) }, singleLine = true, label = { Text(stringResource(R.string.pg_move_label, files.size)) }, isError = text.isNotBlank() && !ok,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)) },
            confirmButton = { TextButton(onClick = { moveAsk = null; move(i, target!! - 1) }, enabled = ok) { Text(stringResource(R.string.pg_move_go)) } },
            dismissButton = { TextButton(onClick = { moveAsk = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ImageViewDialog(files: List<File>, i: Int, tools: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>, onDismiss: () -> Unit, onIndex: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202124)).systemBarsPadding()) {
            AsyncImage(model = files.getOrNull(i), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000))) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    tools.forEach { (label, icon, act) -> com.smartdocs.editor.ui.reader.ToolChip(icon, label, onClick = act) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onIndex(i - 1) }, enabled = i > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, null, tint = Color.White) }
                Text("${i + 1} / ${files.size}", color = Color.White)
                IconButton(onClick = { onIndex(i + 1) }, enabled = i < files.lastIndex) { Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = Color.White) }
            }
            }
        }
    }
}

/** A copy of [src] turned by [deg] (EXIF orientation applied first), JPEG in the app's temp folder. */
private fun rotatedCopy(ctx: android.content.Context, src: File, deg: Int): File {
    val app = ctx.applicationContext as com.smartdocs.editor.SmartDocsApp
    val b = com.smartdocs.editor.core.pdf.PdfOps.decodeScaled(src, 4000) ?: error("decode")
    val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    if (r !== b) b.recycle()
    val out = File(app.documents.tempDir, "img_rot_${System.nanoTime()}__" + src.nameWithoutExtension.substringAfter("__").take(60) + ".jpg")
    out.outputStream().use { r.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    r.recycle()
    return out
}
