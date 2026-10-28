package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.annotAt
import kotlin.math.roundToInt

/** 24.66: what the hover tooltip shows: the comment's author and (shortened) text at the pointer [at] (page layout px). */
data class HoverTip(val at: Offset, val author: String, val text: String)

object CommentHoverLogic {
    /** Longest text shown in the tooltip; the rest is cut with an ellipsis. */
    const val MAX_TEXT = 200

    /** Tooltip for a comment by [author] with [contents]; null when both are empty (nothing to show). */
    fun tip(at: Offset, author: String, contents: String, max: Int = MAX_TEXT): HoverTip? {
        val a = author.trim(); val t = contents.trim().replace(Regex("\\s*\\n\\s*"), " ")
        if (a.isEmpty() && t.isEmpty()) return null
        return HoverTip(at, a, if (t.length > max) t.take(max).trimEnd() + "…" else t)
    }

    /** Only a hovering mouse or stylus shows the tooltip (fingers cannot hover). */
    fun hovers(type: PointerType, pressed: Boolean): Boolean = !pressed && (type == PointerType.Mouse || type == PointerType.Stylus)
}

/** Holder of the current tooltip of one page. */
class CommentHoverState {
    var tip by mutableStateOf<HoverTip?>(null)
    /** §41: the pointer over the page is a hovering stylus (the pencil then hides the cursor). */
    var stylus by mutableStateOf(false)
}

/**
 * 24.66: hover tooltip over comments. A mouse or stylus hovering over a pending or stored comment shows its author and
 * text; pressing, leaving the page or hiding the comments removes it. Events are only observed, never consumed.
 */
fun Modifier.commentHover(state: ReaderState, page: Int, g: PageGeometry, hs: CommentHoverState): Modifier = pointerInput(page, g) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull() ?: continue
            if (ev.type == PointerEventType.Exit) hs.stylus = false else hs.stylus = c.type == PointerType.Stylus
            hs.tip = when {
                ev.type == PointerEventType.Exit || ev.type == PointerEventType.Press -> null
                ev.type != PointerEventType.Move && ev.type != PointerEventType.Enter -> hs.tip
                !CommentHoverLogic.hovers(c.type, c.pressed) || state.annot2.cx.hidden || state.tool != null -> null
                else -> {
                    val s = g.layoutToScreen(c.position); val (x, y) = g.screenToPdf(s.x, s.y)
                    val tol = 6f / g.scale.coerceAtLeast(0.1f)
                    annotAt(state.annots, page, x, y, tol)?.let { CommentHoverLogic.tip(c.position, it.author, it.text) }
                        ?: state.edit.storedAt(page, x, y, tol)?.let { CommentHoverLogic.tip(c.position, it.author, it.contents) }
                }
            }
        }
    }
}

/** The tooltip box of [hs], placed below-right of the pointer and kept inside the page of [pageW] x [pageH] px. */
@Composable
fun CommentHoverTip(hs: CommentHoverState, pageW: Float, pageH: Float) {
    val tip = hs.tip ?: return
    var box by remember { mutableStateOf(IntSize.Zero) }
    Surface(
        Modifier.absoluteOffset {
            val x = (tip.at.x + 16f).coerceAtMost(pageW - box.width).coerceAtLeast(0f)
            val y = (tip.at.y + 20f).let { if (it + box.height > pageH) tip.at.y - box.height - 8f else it }.coerceAtLeast(0f)
            IntOffset(x.roundToInt(), y.roundToInt())
        }.widthIn(max = 280.dp).onSizeChanged { box = it },
        shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (tip.author.isNotEmpty()) Text(tip.author, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tip.text.isNotEmpty()) Text(tip.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}
