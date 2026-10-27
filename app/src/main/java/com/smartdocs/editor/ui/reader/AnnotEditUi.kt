package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import kotlin.math.roundToInt

private enum class AeSub { NONE, COLOR, OPACITY, THICKNESS }

/**
 * Floating action bar for the selected annotation, placed above its frame (below when there is no room).
 * [frame] is the selection frame in the page's layout space. Must be called inside the page's Box.
 */
@Composable
fun AnnotActionBar(state: ReaderState, annot: Annot?, frame: Rect) {
    val ed = state.edit
    var sub by remember(ed.target) { mutableStateOf(AeSub.NONE) }
    var fmtOpen by remember(ed.target) { mutableStateOf(false) }
    if (fmtOpen && annot != null) com.smartdocs.editor.ui.reader.annot.TextFormatDialog(com.smartdocs.editor.ui.reader.annot.TextFmt.of(annot), onDismiss = { fmtOpen = false }, showBiu = true) { fm ->
        fmtOpen = false; ed.edit { a -> fm.applyAll(a) }
    }
    val loaded = annot != null
    val enabled = loaded && !ed.busy
    Surface(
        Modifier.floatNear(frame, 10f).widthIn(max = 360.dp),
        shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(4.dp)) {
            NpfNotePopup(state, annot)   // nav-prefs: comment pop-up with the note font, size and opacity
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val video = annot?.kind == AnnotKind.VIDEO
                if (annot?.kind != AnnotKind.STAMP_IMAGE && !video) AeButton(Icons.Default.Palette, R.string.ae_color, enabled, sub == AeSub.COLOR) { sub = if (sub == AeSub.COLOR) AeSub.NONE else AeSub.COLOR }
                if (!video) AeButton(Icons.Default.Opacity, R.string.ae_opacity, enabled, sub == AeSub.OPACITY) { sub = if (sub == AeSub.OPACITY) AeSub.NONE else AeSub.OPACITY }
                if (hasStroke(annot)) AeButton(Icons.Default.LineWeight, R.string.ae_thickness, enabled, sub == AeSub.THICKNESS) { sub = if (sub == AeSub.THICKNESS) AeSub.NONE else AeSub.THICKNESS }
                if (hasEditableText(annot)) AeButton(Icons.Default.Edit, R.string.ae_edit_text, enabled, false) { sub = AeSub.NONE; ed.editingText = true }
                if (annot?.kind == AnnotKind.FREETEXT) AeButton(Icons.Default.FormatSize, R.string.cx_text_format, enabled, false) { sub = AeSub.NONE; fmtOpen = true }
                AeButton(Icons.Default.ContentCopy, R.string.ae_copy, enabled, false) { sub = AeSub.NONE; ed.copy() }
                val nativeAudio = (ed.target as? AnnotTarget.Stored)?.e?.let { it.subtype == "Sound" || (it.subtype == "Screen" && it.video == null) } == true
                if (nativeAudio) AeButton(Icons.Default.PlayArrow, R.string.an_open, !ed.busy, false) { sub = AeSub.NONE; state.annot2.openSelected() }
                StampActionButtons(state, annot, enabled)      // nitro-fillsign2: cut / copy / default size / multiple pages
                if (annot?.kind == AnnotKind.FILE_ATTACH || annot?.kind == AnnotKind.SOUND)
                    AeButton(if (annot?.kind == AnnotKind.FILE_ATTACH) Icons.Default.OpenInNew else Icons.Default.PlayArrow, R.string.an_open, enabled, false) { sub = AeSub.NONE; state.annot2.openSelected() }
                if (annot?.kind == AnnotKind.VIDEO) {
                    // played inside the page; options, share and save to the device
                    AeButton(Icons.Default.PlayArrow, R.string.vid_play, enabled, false) { sub = AeSub.NONE; state.video.playSelected() }
                    AeButton(Icons.Default.Tune, R.string.vid_options, enabled, false) { sub = AeSub.NONE; state.video.optionsOpen = true }
                    AeButton(Icons.Default.Share, R.string.vid_share, !ed.busy, false) { sub = AeSub.NONE; state.video.shareSelected() }
                    AeButton(Icons.Default.SaveAlt, R.string.vid_save, !ed.busy, false) { sub = AeSub.NONE; state.video.saveSelected() }
                }
                if (annot?.kind == AnnotKind.INK) AeButton(Icons.Default.Gesture, R.string.an_recognize, enabled, false) { sub = AeSub.NONE; state.annot2.startHandwriting() }
                AeButton(Icons.Default.Delete, R.string.ae_delete, !ed.busy, false, tint = MaterialTheme.colorScheme.error) { ed.delete() }
                if (annot?.locked == true) Icon(androidx.compose.material.icons.Icons.Default.Lock, stringResource(R.string.cx_locked), Modifier.padding(horizontal = 4.dp).size(18.dp))
                com.smartdocs.editor.ui.reader.annot.SelectionMoreButton(state, multi = false)      // section 7: properties, lock, clipboard, rotate, flatten...
                if (annot != null && annot.kind in setOf(AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY)) {
                    // Foxit 24.9: adjust the markup range word by word at either end
                    val cx = state.annot2.cx
                    TextButton(onClick = { cx.adjustMarkup(true, -1) }, enabled = enabled) { Text("\u2190|") }
                    TextButton(onClick = { cx.adjustMarkup(true, 1) }, enabled = enabled) { Text("|\u2192") }
                    TextButton(onClick = { cx.adjustMarkup(false, -1) }, enabled = enabled) { Text("\u2190]") }
                    TextButton(onClick = { cx.adjustMarkup(false, 1) }, enabled = enabled) { Text("]\u2192") }
                }
                if (ed.busy || ed.storedLoading) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp)
            }
            if (annot != null) when (sub) {
                AeSub.COLOR -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PALETTE.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                                .border(if (c == annot.color) 3.dp else 1.dp, if (c == annot.color) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                                .clickable(enabled = enabled) { ed.setColor(c) },
                        )
                    }
                }
                AeSub.OPACITY -> AeSlider(stringResource(R.string.ae_opacity), annot.opacity, 0.1f..1f, enabled, { "${(it * 100).roundToInt()}%" }) { ed.setOpacity(it) }
                AeSub.THICKNESS -> AeSlider(stringResource(R.string.ae_thickness), annot.strokeWidth, 0.5f..20f, enabled, { String.format(java.util.Locale.US, "%.1f", it) }) { ed.setThickness(it) }
                AeSub.NONE -> {}
            }
        }
    }
    if (ed.editingText && annot != null) AnnotTextDialog(annot.text, state.annot2.cx.noteFontSize, state.annot2.cx.noteOpacity,
        onOk = { ed.editingText = false; ed.setText(it) }, onDismiss = { ed.editingText = false })
}

@Composable
private fun AeButton(icon: ImageVector, label: Int, enabled: Boolean, active: Boolean, tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    val text = stringResource(label)
    IconButton(onClick = onClick, enabled = enabled,
        colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else IconButtonDefaults.iconButtonColors()) {
        Icon(icon, text, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/** Slider that previews locally and commits once on release (one undo step per change). */
@Composable
private fun AeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, fmt: (Float) -> String, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Row(Modifier.width(300.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(v, { v = it }, Modifier.weight(1f).padding(horizontal = 8.dp), enabled = enabled, valueRange = range, onValueChangeFinished = { onCommit(v) })
        Text(fmt(v), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AnnotTextDialog(initial: String, fontSize: Float, opacity: Float, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        modifier = Modifier.alpha(opacity),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ae_edit_text)) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, textStyle = LocalTextStyle.current.copy(fontSize = androidx.compose.ui.unit.TextUnit(fontSize, androidx.compose.ui.unit.TextUnitType.Sp)))
                com.smartdocs.editor.ui.reader.annot.SpellCheckRow(text) { text = it }      // Foxit 24.25
            }
        },
        confirmButton = { Button(onClick = { onOk(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Takes the whole page box and places the content centred above [frame] (layout px), or below it when there is
 * no room above, clamped inside the page. Only the content itself receives touches.
 */
private fun Modifier.floatNear(frame: Rect, gapPx: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    val w = if (c.hasBoundedWidth) c.maxWidth else p.width
    val h = if (c.hasBoundedHeight) c.maxHeight else p.height
    layout(w, h) {
        val x = (frame.center.x - p.width / 2f).roundToInt().coerceIn(0, (w - p.width).coerceAtLeast(0))
        val above = frame.top - gapPx - p.height
        val y = (if (above >= 0f) above else frame.bottom + gapPx).roundToInt().coerceIn(0, (h - p.height).coerceAtLeast(0))
        p.place(x, y)
    }
}
