package com.smartdocs.editor.ui.office

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.core.office5.*

/** sp per typographic point at zoom 1 (11 pt body text shows at about 14 sp). */
private const val SP_PER_PT = 1.25f
val PAPER_TEXT = Color(0xFF111111)

fun spanStyle(p: RunProps, zoom: Float): SpanStyle {
    val deco = listOfNotNull(TextDecoration.Underline.takeIf { p.underline == true }, TextDecoration.LineThrough.takeIf { p.strike == true })
    val size = (p.sizePt ?: WordView.DEFAULT_SIZE) * SP_PER_PT * zoom * (if (p.vertAlign != null) 0.7f else 1f)
    return SpanStyle(
        color = Color.hex(p.color) ?: Color.Unspecified,
        fontSize = size.sp,
        fontWeight = if (p.bold == true) FontWeight.Bold else null,
        fontStyle = if (p.italic == true) FontStyle.Italic else null,
        fontFamily = p.font?.lowercase()?.let { f -> when { f.contains("courier") || f.contains("mono") || f.contains("consol") -> FontFamily.Monospace; f.contains("times") || f.contains("serif") && !f.contains("sans") -> FontFamily.Serif; else -> null } },
        background = Color.hex(p.highlight) ?: Color.Unspecified,
        textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
        baselineShift = when (p.vertAlign) { "superscript" -> BaselineShift.Superscript; "subscript" -> BaselineShift.Subscript; else -> null },
    )
}

/** Paragraph text with its run styles; pictures become inline content ids "img0", "img1"… when [inlineImages]. */
fun annotated(v: WPara, zoom: Float, inlineImages: Boolean): AnnotatedString = buildAnnotatedString {
    var img = 0
    for (sp in v.spans) {
        val st = spanStyle(sp.props, zoom)
        var i = sp.start
        while (i < sp.end) {
            val j = if (inlineImages) v.text.indexOf(WordRuns.OBJ, i).let { if (it < 0 || it >= sp.end) sp.end else it } else sp.end
            if (j > i) withStyle(st) { append(v.text.substring(i, j)) }
            if (j < sp.end && inlineImages) { appendInlineContent("img${img++}", "\u25A1"); i = j + 1 } else i = j
        }
    }
}

private class SpanTransform(val v: WPara, val zoom: Float) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text != v.text) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(annotated(v, zoom, false), OffsetMapping.Identity)
    }
    override fun equals(other: Any?) = other is SpanTransform && other.v === v && other.zoom == zoom
    override fun hashCode() = System.identityHashCode(v)
}

fun paraStyle(v: WPara, zoom: Float): TextStyle = TextStyle(
    color = PAPER_TEXT,
    fontSize = (v.baseSizePt * SP_PER_PT * zoom).sp,
    lineHeight = 1.45.em,
    textAlign = when (v.align) { PAlign.CENTER -> TextAlign.Center; PAlign.END -> TextAlign.End; PAlign.JUSTIFY -> TextAlign.Justify; else -> TextAlign.Start },
    textDirection = if (v.rtl) TextDirection.Rtl else TextDirection.Ltr,
)

/** Decoded picture (downsampled), cached per byte array. */
private val imageCache = java.util.WeakHashMap<ByteArray, ImageBitmap?>()

fun decodeImage(bytes: ByteArray?, maxPx: Int = 1600): ImageBitmap? {
    bytes ?: return null
    synchronized(imageCache) { if (imageCache.containsKey(bytes)) return imageCache[bytes] }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (o.outWidth / sample > maxPx || o.outHeight / sample > maxPx) sample *= 2
    val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
    synchronized(imageCache) { imageCache[bytes] = bmp }
    return bmp
}

@Composable
fun PictureBox(img: WImage, widthFraction: Float, selected: Boolean, modifier: Modifier = Modifier) {
    val bmp = remember(img.bytes) { decodeImage(img.bytes) }
    val ratio = if (img.cx > 0 && img.cy > 0) img.cx.toFloat() / img.cy else 4f / 3f
    val m = modifier.fillMaxWidth(widthFraction.coerceIn(0.05f, 1f)).aspectRatio(ratio)
        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
    if (bmp != null) Image(bmp, null, m, contentScale = ContentScale.FillBounds)
    else Box(m.background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, null, tint = Color.Gray) }
}

/** One body paragraph: shown as formatted text, or edited in place when focused. */
@Composable
fun ParagraphBlock(s: WordState, index: Int, v: WPara, contentWidthEmu: Long) {
    val editing = !s.reading && s.focus == index
    val zoom = s.zoom
    val onlyPictures = v.images.isNotEmpty() && v.text.all { it == WordRuns.OBJ || it.isWhitespace() }
    CompositionLocalProvider(LocalLayoutDirection provides if (v.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        val indent = ((v.indentTwips / 15f) * 0.75f * zoom).coerceIn(0f, 220f).dp + if (v.listLevel >= 0 && v.indentTwips == 0) (18 * (v.listLevel + 1)).dp else 0.dp
        val vpad = if (v.heading != 0) 8.dp else 2.dp
        if (onlyPictures) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !s.reading) { s.selectImage(index) },
                contentAlignment = when (v.align) { PAlign.CENTER -> Alignment.Center; PAlign.END -> Alignment.CenterEnd; else -> Alignment.CenterStart }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    v.images.forEach { PictureBox(it, it.cx.toFloat() / contentWidthEmu, s.image == index) }
                }
            }
            return@CompositionLocalProvider
        }
        Row(Modifier.fillMaxWidth().padding(start = indent, top = vpad, bottom = vpad)) {
            v.marker?.takeIf { it.isNotEmpty() }?.let { Text(it, style = paraStyle(v, zoom).copy(textAlign = TextAlign.Start), modifier = Modifier.padding(end = 6.dp)) }
            if (editing) {
                val fr = remember { FocusRequester() }
                LaunchedEffect(index) { runCatching { fr.requestFocus() } }
                BasicTextField(
                    s.field, { s.onField(it) },
                    Modifier.weight(1f).focusRequester(fr).onPreviewKeyEvent { e -> wordKeys(s, e) },
                    textStyle = paraStyle(v, zoom),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = SpanTransform(v, zoom),
                )
            } else {
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val text = remember(v, zoom) { annotated(v, zoom, true) }
                val inline = remember(v, contentWidthEmu) {
                    v.images.withIndex().associate { (k, im) ->
                        val wEm = (im.cx / 9525f / 11f).coerceIn(1f, 30f); val hEm = (im.cy / 9525f / 11f).coerceIn(1f, 30f)
                        "img$k" to InlineTextContent(Placeholder(wEm.em, hEm.em, PlaceholderVerticalAlign.TextBottom)) { PictureBox(im, 1f, false) }
                    }
                }
                Text(
                    text, Modifier.weight(1f).pointerInput(index, s.reading) {
                        if (!s.reading) detectTapGestures { pos -> s.focusParagraph(index, layout?.getOffsetForPosition(pos)) }
                    },
                    style = paraStyle(v, zoom), inlineContent = inline, onTextLayout = { layout = it },
                )
            }
        }
    }
}

/** Hardware keys inside a paragraph: Backspace at the start joins, Ctrl+B/I/U/Z/Y. */
private fun wordKeys(s: WordState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.key == Key.Backspace && s.field.selection.collapsed && s.field.selection.start == 0) return s.joinWithPrevious()
    if (!e.isCtrlPressed) return false
    when (e.key) {
        Key.B -> s.toggle({ it.bold }) { r, on -> WordFormat.setBold(r, on) }
        Key.I -> s.toggle({ it.italic }) { r, on -> WordFormat.setItalic(r, on) }
        Key.U -> s.toggle({ it.underline }) { r, on -> WordFormat.setUnderline(r, on) }
        Key.Z -> s.undo()
        Key.Y -> s.redo()
        else -> return false
    }
    return true
}

/** A table: tap a cell to edit it; lines of a cell are its paragraphs. */
@Composable
fun TableBlock(s: WordState, index: Int, t: WBlock.Table) {
    val border = Color(0xFF8A8F96)
    CompositionLocalProvider(LocalLayoutDirection provides if (t.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, border)) {
            t.rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { c, cell ->
                        val editing = !s.reading && s.cell == CellPos(index, r, c)
                        val first = cell.paragraphs.firstOrNull()
                        Box(
                            Modifier.weight(cell.span.toFloat()).fillMaxHeight().border(0.5.dp, border)
                                .background(Color.hex(cell.shading) ?: Color.White)
                                .then(if (editing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                                .clickable(enabled = !s.reading && !editing) { s.focusCell(CellPos(index, r, c)) }.padding(5.dp),
                        ) {
                            if (editing) {
                                val fr = remember { FocusRequester() }
                                LaunchedEffect(r, c) { runCatching { fr.requestFocus() } }
                                BasicTextField(s.field, { s.onField(it) }, Modifier.fillMaxWidth().focusRequester(fr),
                                    textStyle = first?.let { paraStyle(it, s.zoom) } ?: TextStyle(color = PAPER_TEXT, fontSize = (14 * s.zoom).sp, textDirection = TextDirection.Content),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                            } else if (!cell.mergedAway) Column {
                                cell.paragraphs.forEach { p -> Text(annotated(p, s.zoom, false), style = paraStyle(p, s.zoom)) }
                            }
                        }
                    }
                    val missing = t.gridCols - row.sumOf { it.span }
                    if (missing > 0) Spacer(Modifier.weight(missing.toFloat()))
                }
            }
        }
    }
}

/** Content controls and other markup the editor keeps but does not edit. */
@Composable
fun OtherBlock(text: String, zoom: Float) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Color(0xFFF4F5F7)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = Color.Gray)
        Spacer(Modifier.width(6.dp))
        Text(text.ifBlank { "\u2026" }, color = Color(0xFF555555), fontSize = (13 * zoom).sp, style = TextStyle(textDirection = TextDirection.Content))
    }
}
