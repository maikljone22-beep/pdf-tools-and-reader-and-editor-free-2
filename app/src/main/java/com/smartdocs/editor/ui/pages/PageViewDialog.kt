package com.smartdocs.editor.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.ImageIo6
import java.io.File

/** Full-screen look at one page of [file] (the organizer's working copy, unsaved changes included): pinch to zoom, previous / next. */
@Composable
fun PageViewDialog(file: File, password: String?, page: Int, pageCount: Int, onDismiss: () -> Unit,
                   /** Tools for the page shown (label, icon, action): the same as its menu, so a page can be handled while looking at it. */
                   tools: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>> = emptyList(),
                   /** Changes when the document changed (an action ran): the page is drawn again. */
                   version: Int = 0,
                   /** The page's thumbnail, shown at once while the sharp picture renders. */
                   placeholder: (Int) -> Bitmap? = { null },
                   onPage: (Int) -> Unit) {
    val ctx = LocalContext.current
    // user report: viewing hung or took very long — every page opened the (large) document again and rendered at
    // 150 dpi. One session for the whole viewer, pages rendered at screen size, the thumbnail shown meanwhile.
    val session by produceState<com.smartdocs.editor.core.pdf.PdfSession?>(null, file, version) {
        val s = runCatching { com.smartdocs.editor.core.pdf.PdfSession.open(ctx, file, password) }.getOrNull()
        value = s
        try { kotlinx.coroutines.awaitCancellation() } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { s?.closeAsync() } }
    }
    val screenW = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    var bmp by remember(page, version) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(session, page) {
        val s = session ?: return@LaunchedEffect
        if (s.isClosed || page !in 0 until s.pageCount) return@LaunchedEffect
        runCatching {
            val (w, h) = s.pageSize(page)
            val pw = (screenW * 1.5f).coerceIn(600f, 2400f).toInt()
            bmp = s.renderOwned(page, pw, (pw * h / w.coerceAtLeast(1f)).toInt().coerceAtLeast(1))
        }
    }
    val tf = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202124)).systemBarsPadding()) {
            val b = bmp ?: placeholder(page)
            if (b == null || b.isRecycled) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else Image(b.asImageBitmap(), stringResource(R.string.pg_page_n, page + 1), Modifier.fillMaxSize().transformable(tf)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000))) {
            if (tools.isNotEmpty()) androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    tools.forEach { (label, icon, act) -> com.smartdocs.editor.ui.reader.ToolChip(icon, label, onClick = act) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, null, tint = Color.White) }
                Text("${page + 1} / $pageCount", color = Color.White)
                IconButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = Color.White) }
            }
            }
        }
    }
}
