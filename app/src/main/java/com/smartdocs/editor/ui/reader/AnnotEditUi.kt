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
import androidx.compose.material.icons.filled.MoreVert
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
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.ui.selmenu.SelBindings
import kotlin.math.roundToInt

private enum class AeSub { NONE, COLOR, OPACITY, THICKNESS }

/**
 * Floating action bar for the selected annotation, placed above its frame (below when there is no room).
 * [frame] is the selection frame in the page's layout space. Must be called inside the page's Box.
 * fix/selmenu-rest: its items come from the selection-menu registry (core/selmenu, ANNOT context; facts and
 * simple handlers in ui/selmenu/SelAnnotMenu): the row, then "More" with the former SelectionMoreButton items
 * plus Reply. Items the document refuses stay greyed and say why when tapped.
 */
@Composable
fun AnnotActionBar(state: ReaderState, annot: Annot?, frame: Rect) {
    val ed = state.edit
    val ctx = state.context
    var sub by remember(ed.target) { mutableStateOf(AeSub.NONE) }
    var fmtOpen by remember(ed.target) { mutableStateOf(false) }
    if (fmtOpen && annot != null) com.smartdocs.editor.ui.reader.annot.TextFormatDialog(com.smartdocs.editor.ui.reader.annot.TextFmt.of(annot), onDismiss = { fmtOpen = false }, showBiu = true) { fm ->
        fmtOpen = false; ed.edit { a -> fm.applyAll(a) }
    }
    val loaded = annot != null
    val enabled = loaded && !ed.busy
    // nitro-fillsign2 stamp items (cut / copy / default size / multiple pages) and the NVE image-annotation properties
    val sigPath = rememberStampSigPath(annot)
    val gate = com.smartdocs.editor.ui.signature.rememberSignatureGate()
    var multi by remember { mutableStateOf(false) }
    var nveProps by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val nveStored = (ed.target as? AnnotTarget.Stored)?.e
    val ai = com.smartdocs.editor.LocalSettings.current.aiEnabled
    val menu = com.smartdocs.editor.ui.selmenu.SelAnnotMenu.model(state, annot, sigPath, ai)
    val copied = stringResource(R.string.nf2_copied)
    val sizeSaved = stringResource(R.string.nf2_size_saved)
    fun why(item: SelItem) = android.widget.Toast.makeText(ctx,
        item.why?.let { ctx.getString(SelBindings.why(it)) } ?: ctx.getString(R.string.sm_unavailable), android.widget.Toast.LENGTH_SHORT).show()
    fun run(item: SelItem) {
        if (!item.enabled) { why(item); return }
        val a = item.action
        sub = when (a) {
            SA.A_COLOR -> if (sub == AeSub.COLOR) AeSub.NONE else AeSub.COLOR
            SA.A_OPACITY -> if (sub == AeSub.OPACITY) AeSub.NONE else AeSub.OPACITY
            SA.A_THICKNESS -> if (sub == AeSub.THICKNESS) AeSub.NONE else AeSub.THICKNESS
            else -> AeSub.NONE
        }
        when (a) {
            SA.A_COLOR, SA.A_OPACITY, SA.A_THICKNESS -> {}
            SA.A_EDIT_TEXT -> ed.editingText = true
            SA.A_TEXT_FORMAT -> fmtOpen = true
            // 7.10: the note in a floating window that stays open
            SA.A_FLOAT_NOTE -> ed.target?.let { t -> if (annot != null) state.annot2.cx.floating = com.smartdocs.editor.ui.reader.annot.FloatingNote(t, annot.author, annot.text) }
            SA.A_STAMP_CUT -> annot?.let { state.cutStamp(it) }
            SA.A_STAMP_COPY -> annot?.let { state.copyStamp(it); android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show() }
            SA.A_STAMP_DEFAULT_SIZE -> if (annot != null && sigPath != null) {
                val r = PageGeometry.normPdf(annot.rects.first())
                com.smartdocs.editor.core.data.SignatureMetaStore.setWidth(ctx, sigPath, r.right - r.left)
                android.widget.Toast.makeText(ctx, sizeSaved, android.widget.Toast.LENGTH_SHORT).show()
            }
            // tab audit D15: copying a locked signature to other pages asks for its PIN
            SA.A_STAMP_PAGES -> gate.requestFor(sigPath) { multi = true }
            SA.A_IMAGE_PROPS -> nveProps = true
            else -> com.smartdocs.editor.ui.selmenu.SelAnnotMenu.run(state, item)
        }
    }
    // busy gating as before: delete, play and video share / save only wait for a running edit, the rest for the load too
    fun gated(a: SA) = if (a == SA.A_DELETE || a == SA.A_PLAY || a == SA.A_VIDEO_SHARE || a == SA.A_VIDEO_SAVE) !ed.busy else enabled
    Surface(
        Modifier.floatNear(frame, 10f).widthIn(max = 360.dp),
        shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(4.dp)) {
            NpfNotePopup(state, annot)   // nav-prefs: comment pop-up with the note font, size and opacity
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                menu.inline.forEach { item ->
                    val a = item.action
                    val glyph = RANGE_GLYPH[a]
                    if (glyph != null) TextButton(onClick = { run(item) }, enabled = gated(a), modifier = Modifier.alpha(if (item.enabled) 1f else 0.45f)) { Text(glyph) }
                    else AeButton(SelBindings.icon(a), SelBindings.label(a), gated(a),
                        active = (a == SA.A_COLOR && sub == AeSub.COLOR) || (a == SA.A_OPACITY && sub == AeSub.OPACITY) || (a == SA.A_THICKNESS && sub == AeSub.THICKNESS),
                        tint = if (a == SA.A_DELETE) MaterialTheme.colorScheme.error else LocalContentColor.current, dim = !item.enabled) { run(item) }
                    if (a == SA.A_DELETE && annot?.locked == true) Icon(Icons.Default.Lock, stringResource(R.string.cx_locked), Modifier.padding(horizontal = 4.dp).size(18.dp))
                }
                // section 7: properties, lock, clipboard, rotate, place on pages, flatten, export (+ Reply)
                Box {
                    IconButton(onClick = { moreOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.cx_more)) }
                    DropdownMenu(moreOpen, { moreOpen = false }) {
                        menu.more.forEach { item ->
                            val reason = item.why
                            DropdownMenuItem(
                                text = { Text(stringResource(SelBindings.label(item.action)), Modifier.alpha(if (item.enabled) 1f else 0.45f)) },
                                leadingIcon = { Icon(SelBindings.icon(item.action), null, Modifier.alpha(if (item.enabled) 1f else 0.45f)) },
                                trailingIcon = if (reason == null) null else ({ Text(stringResource(SelBindings.why(reason)), style = MaterialTheme.typography.labelSmall, maxLines = 1) }),
                                onClick = { moreOpen = false; run(item) },
                            )
                        }
                    }
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
    if (nveProps && nveStored != null) com.smartdocs.editor.ui.reader.annot.ImageAnnotPropsDialog5(state, nveStored) { nveProps = false }
    if (multi && annot != null) MultiPageDialog(state.pageCount, onDismiss = { multi = false }) { set, range ->
        multi = false
        state.placeOnPages(annot, set, range) { n -> android.widget.Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.nf2_placed_on_pages, n, n), android.widget.Toast.LENGTH_SHORT).show() }
    }
    if (ed.editingText && annot != null) AnnotTextDialog(annot.text, state.annot2.cx.noteFontSize, state.annot2.cx.noteOpacity,
        onOk = { ed.editingText = false; ed.setText(it) }, onDismiss = { ed.editingText = false })
}

private typealias SA = com.smartdocs.editor.core.selmenu.SelAction

/** The markup range buttons keep their compact glyphs (start / end, one word back / on). */
private val RANGE_GLYPH = mapOf(SA.A_RANGE_START_LESS to "←|", SA.A_RANGE_START_MORE to "|→", SA.A_RANGE_END_LESS to "←]", SA.A_RANGE_END_MORE to "]→")

@Composable
private fun AeButton(icon: ImageVector, label: Int, enabled: Boolean, active: Boolean, tint: Color = LocalContentColor.current, dim: Boolean = false, onClick: () -> Unit) {
    val text = stringResource(label)
    IconButton(onClick = onClick, enabled = enabled,
        colors = if (active) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else IconButtonDefaults.iconButtonColors()) {
        Icon(icon, text, tint = if (enabled && !dim) tint else tint.copy(alpha = 0.38f))
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
