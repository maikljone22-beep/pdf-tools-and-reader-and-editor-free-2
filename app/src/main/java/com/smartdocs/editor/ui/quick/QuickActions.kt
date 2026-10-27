package com.smartdocs.editor.ui.quick

import android.graphics.Color as AColor
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.ocr.OcrSheet
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay

/**
 * Everything the one-step tools show over the reader: the compact sheets, confirmations and the progress
 * dialog. Errors / info go to [snack]. Place it once in the reader; the done bar is [QuickDoneBar].
 */
@Composable
fun QuickActionHost(c: QuickController, snack: SnackbarHostState, onTool: ((String) -> Unit)? = null) {
    LaunchedEffect(c.message) { c.message?.let { snack.showSnackbar(it); c.message = null } }
    val close = { c.sheet = null }
    when (c.sheet) {
        QuickAction.COMPRESS -> CompressSheet(c, close)
        QuickAction.WATERMARK -> WatermarkSheet(c, close)
        QuickAction.PAGE_NUMBERS -> PageNumbersSheet(c, close)
        QuickAction.PROTECT -> ProtectSheet(c, close)
        QuickAction.EXTRACT -> ExtractSheet(c, close)
        QuickAction.PROPERTIES -> PropertiesSheet(c, close)
        QuickAction.OCR -> OcrSheet(
            pageCount = c.state.pageCount, currentPage = c.state.currentPage, lockedPage = c.ocrLockedPage,
            isInstalled = { c.isLanguageInstalled(it) }, onRun = { c.ocr(it) }, onDismiss = close,
            onArea = onTool?.let { go -> { close(); go("ocr_zone") } },
        )
        else -> Unit
    }
    when (c.confirm) {
        QuickAction.FLATTEN -> {
            var raster by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { c.confirm = null },
                title = { Text(stringResource(R.string.qk_fl_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.qk_fl_text))
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().clickable { raster = !raster }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(raster, { raster = it }); Text(stringResource(R.string.qk_fl_raster), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { c.flatten(raster) }) { Text(stringResource(R.string.qk_fl_btn)) } },
                dismissButton = { TextButton(onClick = { c.confirm = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        QuickAction.DELETE_PAGE -> AlertDialog(
            onDismissRequest = { c.confirm = null },
            title = { Text(stringResource(R.string.qk_del_title, c.state.currentPage + 1)) },
            text = { Text(stringResource(R.string.qk_del_text)) },
            confirmButton = { TextButton(onClick = { c.deleteCurrent() }) { Text(stringResource(R.string.qk_del_btn), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { c.confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
        else -> Unit
    }
    if (c.running) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(24.dp).widthIn(min = 240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val p = c.progress
                if (p == null || p <= 0f) CircularProgressIndicator() else CircularProgressIndicator(progress = { p })
                Spacer(Modifier.height(16.dp))
                Text(c.progressText.ifBlank { stringResource(R.string.qk_working) }, textAlign = TextAlign.Center)
                if (c.cancellable) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { c.cancel() }) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/**
 * "تم" bar shown above the reader's bottom toolbar after a one-step action: undo + save as copy for in-place
 * changes, open + share for new files. Disappears by itself after a few seconds; the change stays undoable from the
 * reader's unified undo / redo (top bar, history sheet).
 */
@Composable
fun QuickDoneBar(c: QuickController, nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val d = c.done
    LaunchedEffect(d) { if (d != null) { delay(if (d.backup != null) 10_000 else 8_000); if (c.done === d) c.dismissDone() } }
    AnimatedVisibility(d != null, enter = fadeIn(), exit = fadeOut()) {
        val done = d ?: return@AnimatedVisibility
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp,
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.inversePrimary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_done), style = MaterialTheme.typography.labelLarge)
                    Text(done.message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val action = MaterialTheme.colorScheme.inversePrimary
                if (done.backup != null) {
                    TextButton(onClick = { c.undo() }) { Text(stringResource(R.string.qk_undo), color = action) }
                    TextButton(onClick = { c.saveAsCopy() }) { Text(stringResource(R.string.qk_save_copy), color = action) }
                } else done.newFile?.let { f ->
                    TextButton(onClick = { c.dismissDone(); nav.navigate(Routes.reader(Uri.fromFile(f).toString(), 0)) }) { Text(stringResource(R.string.qk_open), color = action) }
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(android.content.Intent.createChooser(app.documents.shareIntent(listOf(f)), null)) }
                    }) { Text(stringResource(R.string.qk_share), color = action) }
                }
                IconButton(onClick = { c.dismissDone() }, Modifier.size(36.dp)) { Icon(Icons.Default.Close, stringResource(R.string.qk_close), Modifier.size(18.dp)) }
            }
        }
    }
}

/**
 * Non-blocking hint when the document looks scanned: the first pages have no text layer but do have ink.
 * One button opens the OCR sheet. Dismissed for the rest of the session.
 */
@Composable
fun OcrSuggestionBanner(state: ReaderState, onOcr: () -> Unit, modifier: Modifier = Modifier) {
    var show by remember(state.uriString) { mutableStateOf(false) }
    var dismissed by rememberSaveable(state.uriString) { mutableStateOf(false) }
    LaunchedEffect(state.session, state.renderVersion) {
        val s = state.session ?: return@LaunchedEffect
        if (dismissed || state.pageCount == 0) { show = false; return@LaunchedEffect }
        val n = minOf(3, state.pageCount)
        val noText = (0 until n).all { state.pageText(it).isBlank() }
        show = noText && runCatching {
            // a blank new document has no text either: require visible content on the first page
            val (w, h) = state.pageSize(0)
            val bw = 60; val bh = (bw * h / w).toInt().coerceAtLeast(1)
            val b = s.render(0, bw, bh, annotations = false)
            val px = IntArray(bw * bh); b.getPixels(px, 0, bw, 0, 0, bw, bh)
            px.count { (AColor.red(it) + AColor.green(it) + AColor.blue(it)) < 600 } > px.size / 200
        }.getOrDefault(false)
    }
    AnimatedVisibility(show && !dismissed, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp), shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DocumentScanner, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.qk_ocr_banner), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { dismissed = true; onOcr() }) { Text(stringResource(R.string.qk_ocr_banner_btn)) }
                IconButton(onClick = { dismissed = true }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.qk_close), Modifier.size(16.dp)) }
            }
        }
    }
}
