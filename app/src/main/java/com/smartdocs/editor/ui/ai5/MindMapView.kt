package com.smartdocs.editor.ui.ai5

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.MindMapLayout
import com.smartdocs.editor.core.ai5.MindNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Zoom / pan mind map; tap a node to collapse or expand its branch; export as PNG or PDF (shared right away). */
@Composable
fun MindMapView(root: MindNode, title: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var collapsed by remember(root) { mutableStateOf(setOf<String>()) }
    val nodes = remember(root, collapsed, rtl) { MindMapLayout.layout(root, MindMapLayout.measureWith(), rtl, collapsed) }
    val bounds = remember(nodes) { MindMapLayout.bounds(nodes) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var fitted by remember(root) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun fit() {
        if (size.width == 0) return
        val z = minOf(size.width / (bounds.width() + 64f), size.height / (bounds.height() + 64f)).coerceIn(0.2f, 2.5f)
        zoom = z
        pan = Offset(size.width / 2f - (bounds.centerX()) * z, size.height / 2f - (bounds.centerY()) * z)
    }
    LaunchedEffect(size, root) { if (!fitted && size.width > 0) { fit(); fitted = true } }

    fun export(pdf: Boolean) {
        busy = true
        scope.launch {
            runCatching {
                val out = withContext(Dispatchers.IO) {
                    val base = title.substringBeforeLast('.').ifBlank { "mindmap" }.take(40) + "_mindmap"
                    if (pdf) MindMapLayout.toPdf(root, rtl, app.documents.uniqueFile(app.documents.docsDir, base, "pdf"))
                    else MindMapLayout.toPng(root, rtl, app.documents.uniqueFile(app.documents.docsDir, base, "png"))
                }
                ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(out), if (pdf) "application/pdf" else "image/png"), null))
            }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTransformGestures { centroid, p, z, _ ->
                val nz = (zoom * z).coerceIn(0.15f, 5f)
                pan = (pan - centroid) * (nz / zoom) + centroid + p
                zoom = nz
            } }
            .pointerInput(nodes) { detectTapGestures { tap ->
                val x = (tap.x - pan.x) / zoom; val y = (tap.y - pan.y) / zoom
                nodes.firstOrNull { x in it.x..(it.x + it.w) && y in it.y..(it.y + it.h) }?.let { n ->
                    if (n.node.children.isNotEmpty()) collapsed = if (n.path in collapsed) collapsed - n.path else collapsed + n.path
                }
            } }) {
            withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) {
                MindMapLayout.draw(drawContext.canvas.nativeCanvas, nodes, rtl, 0f, 0f)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { fit() }) { Icon(Icons.Default.CenterFocusStrong, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.a5_fit)) }
            FilledTonalButton(onClick = { export(false) }, enabled = !busy) { Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("PNG") }
            FilledTonalButton(onClick = { export(true) }, enabled = !busy) { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("PDF") }
        }
        Text(stringResource(R.string.a5_mindmap_hint), Modifier.align(Alignment.TopCenter).padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
