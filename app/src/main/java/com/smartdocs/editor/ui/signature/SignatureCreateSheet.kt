package com.smartdocs.editor.ui.signature

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SavedSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pen colours offered in the Draw / Type tabs. */
internal val penColors = listOf(0xFF000000.toInt(), 0xFF1A237E.toInt(), 0xFF0D47A1.toInt(), 0xFFB71C1C.toInt(), 0xFF1B5E20.toInt())

/** Bottom sheet with Draw / Type / Image / Camera tabs; the result is saved as a transparent PNG + DB row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureCreateSheet(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var initials by rememberSaveable { mutableStateOf(false) }
    var blackWhite by rememberSaveable { mutableStateOf(false) }   // nitro-quicksign: save as black and white
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nothingMsg = stringResource(R.string.sa_nothing_to_save)

    // Each tab exposes a producer for its current bitmap.
    val drawState = remember { DrawState() }
    var typedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cameraBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun save() {
        if (saving) return
        scope.launch {
            saving = true; error = null
            val bmp = withContext(Dispatchers.Default) {
                when (tab) {
                    0 -> InkRenderer.toBitmap(drawState.strokes.toList())
                    1 -> typedBitmap
                    2 -> imageBitmap
                    else -> cameraBitmap
                }
            }
            if (bmp == null) { error = nothingMsg; saving = false; return@launch }
            runCatching {
                withContext(Dispatchers.IO) {
                    val f = app.documents.uniqueFile(app.documents.signaturesDir, "sig_${System.currentTimeMillis()}", "png")
                    SignatureImage.savePng(if (blackWhite) SignatureImage.toBlackAndWhite(bmp) else bmp, f)
                    app.db.signatures().insert(SavedSignature(path = f.absolutePath, isInitials = initials))
                }
            }.onSuccess { onSaved() }.onFailure { error = it.message ?: it.toString() }
            saving = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.sig_create), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            TabRow(tab) {
                listOf(R.string.sig_draw, R.string.sig_type, R.string.sig_image, R.string.sig_camera).forEachIndexed { i, s ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(s)) })
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 480.dp)) {
                when (tab) {
                    0 -> DrawTab(drawState)
                    1 -> TypeTab(onBitmap = { typedBitmap = it })
                    2 -> ImageTab(camera = false, onBitmap = { imageBitmap = it }, blackWhite = blackWhite)
                    else -> ImageTab(camera = true, onBitmap = { cameraBitmap = it }, blackWhite = blackWhite)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(blackWhite, { blackWhite = it }); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nq_bw_save), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(initials, { initials = it }); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sa_save_as_initials), Modifier.weight(1f))
                Button(onClick = { save() }, enabled = !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save))
                }
            }
        }
    }
}

/** Strokes drawn so far + the in-progress one. Kept outside composition so tab switches keep the drawing. */
class DrawState {
    val strokes = mutableStateListOf<InkStroke>()
    val current = mutableStateListOf<InkPoint>()
    var color by mutableIntStateOf(penColors[0])
    var width by mutableFloatStateOf(6f)
    /** Size (px) of the inline canvas: stroke coordinates are in its units, the enlarged canvas scales them. */
    var canvasW by mutableIntStateOf(0)
    var canvasH by mutableIntStateOf(0)
    var enlarged by mutableStateOf(false)
}

/**
 * Ink surface. [scale] maps the inline-canvas units of [state] to this surface (1 inline, > 1 enlarged), so strokes
 * drawn in either view stay in the same coordinates and the saved PNG is identical.
 */
@Composable
private fun InkCanvas(state: DrawState, pressure: Boolean, scale: Float, modifier: Modifier) {
    Canvas(
        modifier.pointerInput(pressure, state.color, state.width, scale) {
            awaitEachGesture {
                val down = awaitFirstDown()
                state.current.clear()
                state.current += InkPoint(down.position.x / scale, down.position.y / scale, if (pressure) down.pressure.coerceIn(0.05f, 1f) else 1f)
                down.consume()
                drag(down.id) { ch ->
                    state.current += InkPoint(ch.position.x / scale, ch.position.y / scale, if (pressure) ch.pressure.coerceIn(0.05f, 1f) else 1f)
                    ch.consume()
                }
                if (state.current.isNotEmpty()) state.strokes += InkStroke(state.current.toList(), state.color, state.width, pressure)
                state.current.clear()
            }
        }
    ) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            state.strokes.forEach { InkRenderer.draw(nc, it, scale) }
            if (state.current.isNotEmpty()) InkRenderer.draw(nc, InkStroke(state.current.toList(), state.color, state.width, pressure), scale)
        }
    }
}

/** "Enlarge canvas": the same drawing magnified to (nearly) full screen, with the same pen tools. */
@Composable
private fun EnlargedDrawDialog(state: DrawState, pressure: Boolean) {
    Dialog(onDismissRequest = { state.enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sig_draw), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (state.strokes.isNotEmpty()) state.strokes.removeAt(state.strokes.size - 1) }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = { state.strokes.clear() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sa_clear)) }
                    FilledTonalButton(onClick = { state.enlarged = false }) { Icon(Icons.Default.FullscreenExit, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.done)) }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val cw = state.canvasW.coerceAtLeast(1).toFloat(); val ch = state.canvasH.coerceAtLeast(1).toFloat()
                    val availW = with(density) { maxWidth.toPx() }; val availH = with(density) { maxHeight.toPx() }
                    val scale = minOf(availW / cw, availH / ch).coerceAtLeast(1f)
                    Box(
                        Modifier.size(with(density) { (cw * scale).toDp() }, with(density) { (ch * scale).toDp() })
                            .background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    ) {
                        InkCanvas(state, pressure, scale, Modifier.fillMaxSize())
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    penColors.forEach { c -> ColorDot(c, state.color == c) { state.color = c } }
                    Slider(state.width, { state.width = it }, valueRange = 2f..16f, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun DrawTab(state: DrawState) {
    val settings = LocalSettings.current
    val pressure = settings.pressureSensitivity
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(
            Modifier.fillMaxWidth().height(220.dp).background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .onSizeChanged { state.canvasW = it.width; state.canvasH = it.height }
        ) {
            if (state.strokes.isEmpty() && state.current.isEmpty())
                Text(stringResource(R.string.sa_draw_hint), Modifier.align(Alignment.Center), color = Color.LightGray)
            InkCanvas(state, pressure, 1f, Modifier.fillMaxSize())
            IconButton(onClick = { state.enlarged = true }, Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Fullscreen, stringResource(R.string.nq_enlarge_canvas), tint = Color.Gray)
            }
        }
        if (state.enlarged && state.canvasW > 0) EnlargedDrawDialog(state, pressure)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sa_pen_color), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            penColors.forEach { c -> ColorDot(c, state.color == c) { state.color = c } }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (state.strokes.isNotEmpty()) state.strokes.removeAt(state.strokes.size - 1) }) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
            IconButton(onClick = { state.strokes.clear() }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.sa_clear)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sa_thickness), style = MaterialTheme.typography.labelMedium)
            Slider(state.width, { state.width = it }, valueRange = 2f..16f, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
}

@Composable
internal fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(3.dp).size(28.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun TypeTab(onBitmap: (Bitmap?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var style by rememberSaveable { mutableIntStateOf(0) }
    var color by rememberSaveable { mutableIntStateOf(penColors[0]) }
    val bmp by produceState<Bitmap?>(null, name, style, color) {
        value = withContext(Dispatchers.Default) { SignatureImage.renderText(name.trim(), SignatureImage.typefaces[style], color) }
        onBitmap(value)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sa_type_name)) }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            penColors.forEach { c -> ColorDot(c, color == c) { color = c } }
        }
        Spacer(Modifier.height(8.dp))
        SignatureImage.typefaces.forEachIndexed { i, tf ->
            val preview = remember(name, i, color) { SignatureImage.renderText(name.ifBlank { "Signature" }, tf, color, 72f) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .border(if (style == i) 2.dp else 1.dp, if (style == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .background(Color.White, RoundedCornerShape(10.dp)).clickable { style = i }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(style == i, { style = i })
                preview?.let { Image(it.asImageBitmap(), null, Modifier.height(40.dp), contentScale = ContentScale.Fit) }
            }
        }
        bmp?.let { Spacer(Modifier.height(4.dp)); Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(80.dp).background(Color.White, RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit) }
    }
}
