package com.smartdocs.editor.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.ImageIo6
import java.io.File

/** Full-screen look at one page of [file] (the organizer's working copy, unsaved changes included): pinch to zoom, previous / next. */
@Composable
fun PageViewDialog(file: File, password: String?, page: Int, pageCount: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(page) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(file, page) {
        runCatching { ImageIo6.pageBitmaps(ctx, file, password, listOf(page), 150) { _, _, _, b, _ -> bmp = b.copy(Bitmap.Config.ARGB_8888, false) } }
    }
    DisposableEffect(page) { onDispose { bmp?.recycle() } }
    val tf = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF202124)).systemBarsPadding()) {
            val b = bmp
            if (b == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else Image(b.asImageBitmap(), stringResource(R.string.pg_page_n, page + 1), Modifier.fillMaxSize().transformable(tf)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, null, tint = Color.White) }
                Text("${page + 1} / $pageCount", color = Color.White)
                IconButton(onClick = { onPage(page + 1) }, enabled = page < pageCount - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = Color.White) }
            }
        }
    }
}
