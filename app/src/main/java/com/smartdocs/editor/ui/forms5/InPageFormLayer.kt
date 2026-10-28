package com.smartdocs.editor.ui.forms5

import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset      // tab audit D11: layout px from the left edge, also in RTL
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms.KeystrokeKind
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.FieldRotation
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// field highlight and required-field colours come from Settings -> Forms (FormPrefs)

/**
 * The form layer of one reader page (hook: one call inside PageCanvas's page Box, after the Canvas).
 * Draws field highlights and pending values over the rendered bitmap and handles taps on field boxes only;
 * the rest of the page keeps its own gestures. [g] is the page's PageGeometry (PDF space -> layout px).
 */
@Composable
fun InPageFormLayer(reader: ReaderState, pageIndex: Int, g: PageGeometry) {
    val fs = remember(reader) { FormFill.of(reader) }
    LaunchedEffect(reader.file, reader.session, reader.renderVersion) { fs.ensureLoaded() }
    val model = fs.model
    val widgets = remember(model, pageIndex) { model.onPage(pageIndex) }.filter { !fs.isHidden(it) }
    val repeats = remember(model, pageIndex) { model.xfaRepeats.filter { it.page == pageIndex && (it.canAdd || it.count > 1) } }
    if (widgets.isEmpty() && repeats.isEmpty()) return
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val highlight = LocalSettings.current.highlightFields
    LaunchedEffect(Unit) { com.smartdocs.editor.core.forms5.FormPrefs.load(reader.context) }
    val HIGHLIGHT = Color(com.smartdocs.editor.core.forms5.FormPrefs.highlightColor)
    val REQUIRED = Color(com.smartdocs.editor.core.forms5.FormPrefs.requiredColor)
    val night = reader.nightMode
    val enabled = fs.enabled
    val rects = remember(widgets, g) { widgets.associate { w -> w.id to g.pdfRectToLayout(RectF(w.box.left, w.box.top, w.box.right, w.box.bottom)) } }
    val active = fs.active?.takeIf { it.page == pageIndex }
    val editing = active?.takeIf { enabled && (it.kind == WKind.TEXT || (it.kind == WKind.COMBO && it.editable)) }
    val tm = rememberTextMeasurer()

    // decoded thumbnails of pictures picked for image fields
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    val pickedImages = fs.images.toMap()
    LaunchedEffect(pickedImages) {
        pickedImages.forEach { (n, f) ->
            if (widgets.any { it.name == n } && thumbs[n + "|" + f.path] == null) withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull()
            }?.let { thumbs[n + "|" + f.path] = it }
        }
    }

    // image picker for image fields of this page
    var pickFor by remember { mutableStateOf<String?>(null) }
    val pick = rememberImagePicker(multiple = false) { files -> val n = pickFor; val f = files.firstOrNull(); if (n != null && f != null) fs.setImage(n, f); pickFor = null }
    val target = fs.imageTarget
    LaunchedEffect(target) {
        if (target != null && model.byName[target]?.firstOrNull()?.page == pageIndex) { pickFor = target; fs.imageTarget = null; pick() }
    }

    val sigIcons = remember { com.smartdocs.editor.core.sign.SignPrefs.showSignatureIcons(reader.context) }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val bg = if (night) Color(0xFF202020) else Color.White
            val fg = if (night) Color(0xFFE6E6E6) else Color.Black
            widgets.forEach { w ->
                val r = rects[w.id] ?: return@forEach
                if (w.kind == WKind.SIGNATURE && sigIcons) fs.sigStatus[w.name]?.let { ok -> sigBadge(r.topLeft, ok) }
                val interactive = !fs.isReadOnly(w) && w.kind != WKind.BUTTON && !fs.isCalculated(w.name)
                if (highlight && interactive && w.id != editing?.id) drawRect(HIGHLIGHT, r.topLeft, r.size)
                if (highlight && w.required && interactive) drawRect(REQUIRED, r.topLeft, r.size, style = Stroke(1.5f))
                if (w.id == editing?.id) return@forEach
                if (fs.isPendingValue(w.name)) {
                    val p = fs.props(w.name)
                    val fill = p?.fillColor?.takeIf { it ushr 24 != 0 }?.let { Color(it) } ?: w.background?.let { Color(0xFF000000 or it.toLong()) }
                    val ink = p?.textColor?.takeIf { it ushr 24 != 0 }?.let { Color(it) } ?: Color(0xFF000000 or w.textColor.toLong())
                    val angle = FieldRotation.textAngle(w.pageRotation, w.mkRotation, g.rotation)
                    val f = FieldRotation.frame(r.left, r.top, r.right, r.bottom, angle)
                    val frame = Rect(f[0], f[1], f[2], f[3])
                    // drawn upright in the frame, then turned like the saved appearance (/Rotate, /MK /R, view rotation)
                    rotate(angle.toFloat(), pivot = r.center) {
                        drawPending(fs, w, frame, if (fill != null && !night) fill else bg, if (night) fg else ink, g.scale, tm, thumbs[w.name + "|" + fs.images[w.name]?.path])
                    }
                }
                if (w.name in fs.errors) drawRect(REQUIRED.copy(alpha = 1f), r.topLeft, r.size, style = Stroke(3f))
                if (w.id == active?.id || active?.let { a -> a.kind == WKind.RADIO && w.name == a.name } == true) drawRect(accent, r.topLeft, r.size, style = Stroke(2.5f))
            }
        }
        if (enabled) widgets.forEach { w ->
            if (fs.isReadOnly(w) && w.kind != WKind.BUTTON && w.kind != WKind.SIGNATURE) return@forEach
            if ((w.kind == WKind.BUTTON && w.action == null && w.triggers.isEmpty()) || fs.isCalculated(w.name) || w.id == editing?.id) return@forEach
            val r = rects[w.id] ?: return@forEach
            val minPx = if (w.kind == WKind.CHECK || w.kind == WKind.RADIO) with(density) { 36.dp.toPx() } else 0f
            val t = expand(r, minPx)
            Box(
                Modifier
                    .absoluteOffset { IntOffset(t.left.roundToInt(), t.top.roundToInt()) }
                    .size(with(density) { t.width.toDp() }, with(density) { t.height.toDp() })
                    // selmenu-rest: a long-press opens the field menu (copy value, clear, reset, properties, designer...);
                    // a tap while that menu is open only closes it
                    .pointerInput(w) { detectTapGestures(onTap = { if (!com.smartdocs.editor.ui.selmenu.SelHooks.onPageTap(reader)) fs.tap(w) },
                        onLongPress = { com.smartdocs.editor.ui.selmenu.SelHooks.longPressField(reader, w) }) },
            )
        }
        if (editing != null) rects[editing.id]?.let { ActiveTextEditor(fs, editing, it, g.scale, accent, FieldRotation.textAngle(editing.pageRotation, editing.mkRotation, g.rotation)) }
        // generated XFA forms: "add row" under the last instance of each repeating subform
        if (enabled) repeats.forEach { rp ->
            val r = g.pdfRectToLayout(RectF(rp.box.left, rp.box.top, rp.box.right, rp.box.bottom))
            Box(
                Modifier.absoluteOffset { IntOffset(r.left.roundToInt(), (r.bottom - with(density) { 14.dp.toPx() }).roundToInt()) }
                    .size(with(density) { r.width.toDp() }, 28.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    if (rp.count > 1) androidx.compose.material3.OutlinedButton(onClick = { fs.removeRow(rp) }, enabled = !fs.busy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                        Icon(Icons.Default.Remove, null, Modifier.size(16.dp)); Text(stringResource(R.string.f8_remove_row), style = MaterialTheme.typography.labelMedium)
                    }
                    if (rp.canAdd) FilledTonalButton(onClick = { fs.addRow(rp) }, enabled = !fs.busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text(stringResource(R.string.f5_add_row), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        val menu = fs.menuFor?.let { id -> widgets.firstOrNull { it.id == id } }
        if (menu != null && enabled) rects[menu.id]?.let { ChoiceMenu(fs, menu, it) }
    }
}

private fun expand(r: Rect, minPx: Float): Rect {
    if (r.width >= minPx && r.height >= minPx) return r
    val c = r.center; val w = max(r.width, minPx) / 2f; val h = max(r.height, minPx) / 2f
    return Rect(c.x - w, c.y - h, c.x + w, c.y + h)
}

private fun DrawScope.drawPending(fs: FormFillState, w: FWidget, r: Rect, bg: Color, fg: Color, scale: Float,
                                  tm: androidx.compose.ui.text.TextMeasurer, thumb: ImageBitmap?) {
    when (w.kind) {
        WKind.CHECK, WKind.RADIO -> {
            val inset = minOf(r.width, r.height) * 0.18f
            val inner = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            if (w.kind == WKind.RADIO) drawCircle(bg, inner.minDimension / 2f, inner.center) else drawRect(bg, inner.topLeft, inner.size)
            if (fs.isOn(w)) {
                if (w.kind == WKind.RADIO) drawCircle(fg, inner.minDimension * 0.32f, inner.center)
                else {
                    val p = Path().apply {
                        moveTo(inner.left + inner.width * 0.15f, inner.top + inner.height * 0.55f)
                        lineTo(inner.left + inner.width * 0.42f, inner.top + inner.height * 0.82f)
                        lineTo(inner.left + inner.width * 0.88f, inner.top + inner.height * 0.2f)
                    }
                    drawPath(p, fg, style = Stroke(max(2f, inner.minDimension * 0.14f), cap = StrokeCap.Round))
                }
            }
        }
        WKind.IMAGE -> {
            drawRect(bg, r.topLeft, r.size)
            if (thumb != null) {
                val k = minOf(r.width / thumb.width, r.height / thumb.height)
                val dw = thumb.width * k; val dh = thumb.height * k
                drawImage(thumb, dstOffset = IntOffset((r.left + (r.width - dw) / 2).roundToInt(), (r.top + (r.height - dh) / 2).roundToInt()),
                    dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)))
            }
        }
        WKind.TEXT, WKind.COMBO, WKind.LIST -> {
            drawRect(bg, r.topLeft, r.size)
            val d = fs.display(w)
            val text = if (w.password) "\u2022".repeat(d.text.length) else d.text
            if (text.isEmpty()) return
            val color = if (d.red) Color(0xFFD32F2F) else fg
            val px = if (w.fontSize > 0f) w.fontSize * scale else if (w.multiline) 11f * scale else (r.height * 0.6f).coerceAtMost(14f * scale)
            var style = TextStyle(fontSize = px.toSp(), color = color, textDirection = TextDirection.Content,
                textAlign = when (w.quadding) { 1 -> TextAlign.Center; 2 -> TextAlign.Right; else -> if (rtlText(text)) TextAlign.Right else TextAlign.Left })
            val pad = 2f * scale.coerceAtLeast(0.5f)
            val maxW = (r.width - 2 * pad).roundToInt().coerceAtLeast(1)
            if (w.comb && w.maxLen > 0 && !w.multiline) {
                val cell = r.width / w.maxLen
                text.take(w.maxLen).forEachIndexed { i, ch ->
                    val l = tm.measure(AnnotatedString(ch.toString()), style.copy(textAlign = TextAlign.Center), maxLines = 1, constraints = Constraints(maxWidth = cell.roundToInt().coerceAtLeast(1)))
                    drawText(l, topLeft = Offset(r.left + i * cell, r.top + (r.height - l.size.height) / 2f))
                }
                return
            }
            if (w.fontSize <= 0f && !w.multiline) {
                // /DA size 0 (auto): shrink until the text fits the width, as viewers do for auto-sized fields
                var fit = style
                var guard = 0
                while (guard++ < 12) {
                    val probe = tm.measure(AnnotatedString(text), fit, maxLines = 1, softWrap = false)
                    if (probe.size.width <= maxW || fit.fontSize.value <= 4f) break
                    fit = fit.copy(fontSize = (fit.fontSize.value * maxOf(0.5f, maxW.toFloat() / probe.size.width) * 0.98f).coerceAtLeast(4f).sp)
                }
                style = fit
            }
            val layout = tm.measure(AnnotatedString(text), style, overflow = TextOverflow.Clip, softWrap = w.multiline,
                maxLines = if (w.multiline) Int.MAX_VALUE else 1,
                constraints = Constraints(maxWidth = maxW, maxHeight = r.height.roundToInt().coerceAtLeast(1)))
            val top = if (w.multiline) r.top + pad else r.top + (r.height - layout.size.height) / 2f
            drawText(layout, topLeft = Offset(r.left + pad, top))
        }
        else -> {}
    }
}

private fun rtlText(s: String) = s.any { it.code in 0x0590..0x08FF }

@Composable
private fun ActiveTextEditor(fs: FormFillState, w: FWidget, screen: Rect, scale: Float, accent: Color, angle: Int) {
    // the editor is laid out upright in the widget's frame and turned with graphicsLayer, so the typed text
    // runs in the same direction as the saved appearance (taps are mapped back by Compose)
    val fr = FieldRotation.frame(screen.left, screen.top, screen.right, screen.bottom, angle)
    val r = Rect(fr[0], fr[1], fr[2], fr[3])
    val density = LocalDensity.current
    val focus = remember(w.id) { FocusRequester() }
    val external = fs.value(w.name)
    var tfv by remember(w.id) { mutableStateOf(TextFieldValue(external, TextRange(external.length))) }
    LaunchedEffect(external) { if (external != tfv.text) tfv = TextFieldValue(external, TextRange(external.length)) }
    LaunchedEffect(w.id) { runCatching { focus.requestFocus() } }
    val px = if (w.fontSize > 0f) w.fontSize * scale else if (w.multiline) 11f * scale else (r.height * 0.6f).coerceAtMost(14f * scale)
    val minPx = with(density) { minEditorTextPx() }
    val fontSp = with(density) { max(px, minPx).toSp() }
    val ks = fs.model.calc.scripts[w.name]?.keystroke ?: KeystrokeKind.NONE
    val keyboard = when (ks) {
        KeystrokeKind.NUMBER, KeystrokeKind.PERCENT -> KeyboardType.Decimal
        KeystrokeKind.SPECIAL -> KeyboardType.Phone
        else -> if (w.password) KeyboardType.Password else KeyboardType.Text
    }
    val align = when (w.quadding) { 1 -> TextAlign.Center; 2 -> TextAlign.Right; else -> if (rtlText(tfv.text)) TextAlign.Right else TextAlign.Left }
    // the editor is at least tall enough for its text when the field is tiny
    val h = max(r.height, if (w.multiline) r.height else max(px, minPx) * 1.35f)
    BasicTextField(
        value = tfv,
        onValueChange = onChange@{ nv ->
            if (w.maxLen > 0 && nv.text.length > w.maxLen) return@onChange
            if (nv.text == tfv.text) { tfv = nv; return@onChange }        // selection / composition only
            // keystroke script (K): may reject the change or rewrite it
            val accepted = fs.keystroke(w, tfv.text, nv.text) ?: return@onChange
            tfv = if (accepted == nv.text) nv else TextFieldValue(accepted, TextRange(accepted.length))
            if (accepted != fs.value(w.name)) fs.setValue(w.name, accepted, typing = true)
        },
        modifier = Modifier
            .absoluteOffset { IntOffset(r.left.roundToInt(), (r.top - (h - r.height) / 2f * (if (angle == 0) 0f else 1f)).roundToInt()) }
            .size(with(density) { r.width.toDp() }, with(density) { h.toDp() })
            .graphicsLayer { rotationZ = angle.toFloat() }
            .background(Color.White)
            .border(2.dp, accent)
            .padding(horizontal = 2.dp)
            .focusRequester(focus),
        textStyle = TextStyle(fontSize = fontSp, color = Color.Black, textAlign = align, textDirection = TextDirection.Content),
        singleLine = !w.multiline,
        visualTransformation = if (w.password) PasswordVisualTransformation() else if (ks == KeystrokeKind.NONE) com.smartdocs.editor.ui.nve.spellTransformation(com.smartdocs.editor.ui.nve.rememberSpellIssues(tfv.text)) else VisualTransformation.None,   // NVE: live spell check
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (w.multiline) ImeAction.Default else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { fs.step(1) }, onDone = { fs.done() }),
        cursorBrush = SolidColor(accent),
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = if (w.multiline) Alignment.TopStart else Alignment.CenterStart) { inner() } },
    )
}

/** 12 sp in px at the current density: the editor text never gets smaller than this, even on tiny fields. */
private fun androidx.compose.ui.unit.Density.minEditorTextPx(): Float = 12f * fontScale * density

@Composable
private fun ChoiceMenu(fs: FormFillState, w: FWidget, r: Rect) {
    val density = LocalDensity.current
    val current = fs.value(w.name).split('\n').filter { it.isNotEmpty() }
    Box(Modifier.absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }.size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })) {
        DropdownMenu(expanded = true, onDismissRequest = { fs.menuFor = null }) {
            if (!w.multiSelect) DropdownMenuItem(text = { Text(stringResource(R.string.f5_none)) }, onClick = { fs.setValue(w.name, ""); fs.menuFor = null })
            w.options.forEachIndexed { i, label ->
                val export = w.exports.getOrNull(i) ?: label
                val selected = export in current
                if (w.multiSelect) DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
                    onClick = {
                        val next = if (selected) current - export else current + export
                        // keep the option order of the field
                        fs.setValue(w.name, w.options.indices.map { w.exports.getOrNull(it) ?: w.options[it] }.filter { it in next }.joinToString("\n"))
                    },
                )
                else DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = if (selected) ({ Icon(Icons.Default.Check, null) }) else null,
                    onClick = { fs.setValue(w.name, export); fs.menuFor = null },
                )
            }
        }
    }
}

/** Signature status badge at a signed field's corner (Nitro signature icons; Settings can hide them). */
private fun DrawScope.sigBadge(at: Offset, valid: Boolean) {
    val r = 9.dp.toPx()
    val c = Offset(at.x + r, at.y + r)
    drawCircle(if (valid) Color(0xFF2E7D32) else Color(0xFFC62828), r, c)
    val s = r * 0.5f
    if (valid) {
        drawLine(Color.White, Offset(c.x - s, c.y), Offset(c.x - s * 0.2f, c.y + s * 0.7f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(c.x - s * 0.2f, c.y + s * 0.7f), Offset(c.x + s, c.y - s * 0.6f), 2.dp.toPx(), StrokeCap.Round)
    } else {
        drawLine(Color.White, Offset(c.x - s * 0.7f, c.y - s * 0.7f), Offset(c.x + s * 0.7f, c.y + s * 0.7f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(c.x + s * 0.7f, c.y - s * 0.7f), Offset(c.x - s * 0.7f, c.y + s * 0.7f), 2.dp.toPx(), StrokeCap.Round)
    }
}
