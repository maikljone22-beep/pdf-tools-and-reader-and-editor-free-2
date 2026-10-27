package com.smartdocs.editor.ui.office

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.core.office5.*
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Sheet geometry in pixels: default sizes plus prefix sums of the custom widths/heights, so position ↔ index
 * lookups are O(log n) even with many custom rows.
 */
class SheetGeo(s: Sheet5, val scale: Float) {
    private val sheet: Sheet5 = s
    val defW = (s.defaultColWidth * 7f + 5f) * scale
    val defH = s.defaultRowHeight * 4f / 3f * scale
    private val cIdx: IntArray; private val cPre: FloatArray
    private val rIdx: IntArray; private val rPre: FloatArray

    init {
        val cols = (s.colWidths.keys + s.colHidden).toSortedSet().toIntArray()
        cIdx = cols; cPre = FloatArray(cols.size + 1)
        cols.forEachIndexed { i, c -> cPre[i + 1] = cPre[i] + (colW0(s, c) - defW) }
        val rows = s.rowHeights.keys.sorted().toIntArray()
        rIdx = rows; rPre = FloatArray(rows.size + 1)
        rows.forEachIndexed { i, r -> rPre[i + 1] = rPre[i] + ((s.rowHeights[r] ?: 15f) * 4f / 3f * scale - defH) }
    }

    private fun colW0(s: Sheet5, c: Int) = if (c in s.colHidden) 0f else (s.colWidth(c) * 7f + 5f) * scale

    fun colW(c: Int): Float = colW0(sheet, c)
    fun rowH(r: Int): Float = (sheet.rowHeights[r]?.let { it * 4f / 3f * scale }) ?: defH

    private fun lower(a: IntArray, v: Int): Int { var lo = 0; var hi = a.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }; return lo }

    fun colX(c: Int): Float = c * defW + cPre[lower(cIdx, c)]
    fun rowY(r: Int): Float = r * defH + rPre[lower(rIdx, r)]

    fun colAt(x: Float): Int { var lo = 0; var hi = MAX_COLS - 1; while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (colX(m) <= x) lo = m else hi = m - 1 }; return lo }
    fun rowAt(y: Float): Int { var lo = 0; var hi = MAX_ROWS - 1; while (lo < hi) { val m = (lo + hi + 1) ushr 1; if (rowY(m) <= y) lo = m else hi = m - 1 }; return lo }
}

/** Header strip sizes in dp. */
const val HEADER_H = 24f
const val HEADER_W = 44f

/** Screen mapping of the grid (frozen panes, scroll, right-to-left sheets). */
class GridMap(val geo: SheetGeo, val fr: Int, val fc: Int, val sx: Float, val sy: Float, val hw: Float, val hh: Float, val width: Float, val rtl: Boolean) {
    val frozenW = geo.colX(fc); val frozenH = geo.rowY(fr)
    fun x(c: Int): Float = hw + (if (c < fc) geo.colX(c) else geo.colX(c) - sx)
    fun y(r: Int): Float = hh + (if (r < fr) geo.rowY(r) else geo.rowY(r) - sy)
    /** Left edge on screen of a span starting at [x] with width [w] (mirrored for RTL). */
    fun sx(x: Float, w: Float) = if (rtl) width - x - w else x
    fun cellRect(r: Int, c: Int, r2: Int = r, c2: Int = c): Rect {
        val x0 = x(c); val x1 = x(c2) + geo.colW(c2); val y0 = y(r); val y1 = y(r2) + geo.rowH(r2)
        return Rect(sx(x0, x1 - x0), y0, sx(x0, x1 - x0) + (x1 - x0), y1)
    }
    /** Cell under a screen point (null on headers). */
    fun hit(p: Offset): Pair<Int, Int>? {
        val px = if (rtl) width - p.x else p.x
        if (px < hw || p.y < hh) return null
        val gx = px - hw; val gy = p.y - hh
        val c = if (gx < frozenW) geo.colAt(gx) else geo.colAt(gx + sx)
        val r = if (gy < frozenH) geo.rowAt(gy) else geo.rowAt(gy + sy)
        return r to c
    }
    fun headerCol(p: Offset): Int? { val px = if (rtl) width - p.x else p.x; if (p.y >= hh || px < hw) return null; val gx = px - hw; return if (gx < frozenW) geo.colAt(gx) else geo.colAt(gx + sx) }
    fun headerRow(p: Offset): Int? { val px = if (rtl) width - p.x else p.x; if (px >= hw || p.y < hh) return null; val gy = p.y - hh; return if (gy < frozenH) geo.rowAt(gy) else geo.rowAt(gy + sy) }
}

@Composable
fun SheetGrid(s: SheetState, modifier: Modifier = Modifier) {
    val tick = s.tick
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val sheet = s.sheet
    val geo = remember(tick, s.active, s.zoom, density) { SheetGeo(sheet, density * s.zoom) }
    val geoNow by rememberUpdatedState(geo)
    var size by remember { mutableStateOf(Size.Zero) }
    val map = GridMap(geo, sheet.freezeRows, sheet.freezeCols, s.scrollX, s.scrollY, HEADER_W * density, HEADER_H * density, size.width, sheet.rtl)
    val handleR = 9f * density

    fun clampScroll() { s.scrollX = s.scrollX.coerceIn(0f, geoNow.colX(MAX_COLS - 1)); s.scrollY = s.scrollY.coerceIn(0f, geoNow.rowY(MAX_ROWS - 1)) }
    // office6: find moves the view to the found cell
    LaunchedEffect(s.reveal) {
        val (rr, cc) = s.reveal ?: return@LaunchedEffect
        s.reveal = null
        s.scrollX = (geoNow.colX(cc) - size.width / 3f).coerceAtLeast(0f)
        s.scrollY = (geoNow.rowY(rr) - size.height / 3f).coerceAtLeast(0f)
        clampScroll()
    }

    Canvas(
        modifier.fillMaxSize()
            .pointerInput(s.active, sheet.rtl) {
                detectTapGestures(
                    onTap = { p ->
                        val m = currentMap(s, geoNow, size, density)
                        m.headerCol(p)?.let { s.selectColumn(it); return@detectTapGestures }
                        m.headerRow(p)?.let { s.selectRow(it); return@detectTapGestures }
                        val (r, c) = m.hit(p) ?: run { s.select(0, 0); s.sel = CRange(0, 0, MAX_ROWS - 1, MAX_COLS - 1); return@detectTapGestures }
                        if (s.insertRef(r, c)) return@detectTapGestures
                        if (s.editing) s.commit(false)
                        if (s.sel.contains(r, c) && s.sel.size == 1L && r == s.row && c == s.col) s.menuOpen = true else s.select(r, c)
                    },
                    onDoubleTap = { p -> currentMap(s, geoNow, size, density).hit(p)?.let { (r, c) -> if (!s.insertRef(r, c)) { s.select(r, c); s.startEdit() } } },
                    onLongPress = { p -> currentMap(s, geoNow, size, density).hit(p)?.let { (r, c) -> if (!s.editing) { s.select(r, c); s.dragSelect = true } } },
                )
            }
            .pointerInput(s.active, sheet.rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val m0 = currentMap(s, geoNow, size, density)
                    val selRect = m0.cellRect(s.sel.r1, s.sel.c1, minOf(s.sel.r2, MAX_ROWS - 1), minOf(s.sel.c2, MAX_COLS - 1))
                    val handle = Offset(if (sheet.rtl) selRect.left else selRect.right, selRect.bottom)
                    val filling = !s.editing && (down.position - handle).getDistance() < handleR * 2.5f
                    val vt = VelocityTracker(); vt.addPosition(down.uptimeMillis, down.position)
                    var moved = false; var total = Offset.Zero
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size >= 2) {
                            val z = ev.calculateZoom()
                            if (z != 1f) { s.zoom = (s.zoom * z).coerceIn(0.4f, 3f); s.bump() }
                            ev.changes.forEach { it.consume() }; moved = true
                        } else {
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val d = ch.positionChange()
                            total += d
                            if (!moved && total.getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                val m = currentMap(s, geoNow, size, density)
                                when {
                                    filling -> { m.hit(ch.position)?.let { (r, c) -> s.fillTarget = CRange.of(s.sel.r1, s.sel.c1, maxOf(r, s.sel.r2), maxOf(c, s.sel.c2)) }; ch.consume() }
                                    s.dragSelect -> { m.hit(ch.position)?.let { (r, c) -> if (!s.insertRef(r, c, extend = true)) { s.sel = CRange.of(s.row, s.col, r, c); s.bump() } }; ch.consume() }
                                    else -> {
                                        s.scrollX += if (sheet.rtl) d.x else -d.x; s.scrollY -= d.y; clampScroll()
                                        vt.addPosition(ch.uptimeMillis, ch.position); ch.consume()
                                    }
                                }
                            }
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    s.dragSelect = false
                    s.fillTarget?.let { t -> s.fillTarget = null; s.fillTo(t) }
                    if (moved && !filling) {
                        val v = vt.calculateVelocity()
                        if (abs(v.x) + abs(v.y) > 400f) scope.launch {
                            var lastX = 0f; var lastY = 0f
                            val vx = if (sheet.rtl) v.x else -v.x; val vy = -v.y
                            launch { AnimationState(0f, vx).animateDecay(exponentialDecay()) { s.scrollX += value - lastX; lastX = value; clampScroll() } }
                            AnimationState(0f, vy).animateDecay(exponentialDecay()) { s.scrollY += value - lastY; lastY = value; clampScroll() }
                        }
                    }
                }
            },
    ) {
        size = this.size
        drawGrid(s, map, measurer, primary, density)
    }
}

private fun currentMap(s: SheetState, geo: SheetGeo, size: Size, density: Float) =
    GridMap(geo, s.sheet.freezeRows, s.sheet.freezeCols, s.scrollX, s.scrollY, HEADER_W * density, HEADER_H * density, size.width, s.sheet.rtl)

private val GRID = Color(0xFFDADCE0)
private val HEADER_BG = Color(0xFFF1F3F4)
private val HEADER_SEL = Color(0xFFD3E3FD)

private fun DrawScope.drawGrid(s: SheetState, m: GridMap, tm: TextMeasurer, primary: Color, density: Float) {
    val sheet = s.sheet; val geo = m.geo
    drawRect(Color.White)
    val gw = size.width - m.hw; val gh = size.height - m.hh
    // visible scrolling ranges
    val c0 = geo.colAt(m.frozenW + m.sx); val c1 = geo.colAt(m.frozenW + m.sx + gw).coerceAtMost(MAX_COLS - 1)
    val r0 = geo.rowAt(m.frozenH + m.sy); val r1 = geo.rowAt(m.frozenH + m.sy + gh).coerceAtMost(MAX_ROWS - 1)
    val scrollRows = maxOf(r0, sheet.freezeRows)..r1; val scrollCols = maxOf(c0, sheet.freezeCols)..c1
    val frozenRows = 0 until sheet.freezeRows; val frozenCols = 0 until sheet.freezeCols
    fun region(rows: IntRange, cols: IntRange, left: Float, top: Float, right: Float, bottom: Float) {
        if (rows.isEmpty() || cols.isEmpty()) return
        val l = if (m.rtl) size.width - right else left; val r = if (m.rtl) size.width - left else right
        clipRect(l, top, r, bottom) { cells(s, m, tm, rows, cols, density) }
    }
    val fx = m.hw + m.frozenW; val fy = m.hh + m.frozenH
    region(scrollRows, scrollCols, fx, fy, size.width, size.height)
    region(frozenRows, scrollCols, fx, m.hh, size.width, fy)
    region(scrollRows, frozenCols, m.hw, fy, fx, size.height)
    region(frozenRows, frozenCols, m.hw, m.hh, fx, fy)
    if (sheet.freezeRows > 0) drawLine(Color(0xFF9AA0A6), Offset(0f, fy), Offset(size.width, fy), 2f)
    if (sheet.freezeCols > 0) { val x = m.sx(fx, 0f); drawLine(Color(0xFF9AA0A6), Offset(x, 0f), Offset(x, size.height), 2f) }
    // selection
    val sel = s.sel
    val selRect = m.cellRect(sel.r1, sel.c1, minOf(sel.r2, MAX_ROWS - 1), minOf(sel.c2, MAX_COLS - 1))
    clipRect(if (m.rtl) 0f else m.hw, m.hh, if (m.rtl) size.width - m.hw else size.width, size.height) {
        if (sel.size > 1) drawRect(primary.copy(alpha = 0.12f), selRect.topLeft, selRect.size)
        drawRect(primary, selRect.topLeft, selRect.size, style = Stroke(2f * density))
        val active = m.cellRect(s.row, s.col)
        if (sel.size > 1) drawRect(primary, active.topLeft, active.size, style = Stroke(1f * density))
        if (!s.editing) drawCircle(primary, 5f * density, Offset(if (m.rtl) selRect.left else selRect.right, selRect.bottom))
        s.fillTarget?.let { t -> val fr = m.cellRect(t.r1, t.c1, t.r2, t.c2); drawRect(primary, fr.topLeft, fr.size, style = Stroke(1.5f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))) }
    }
    headers(s, m, tm, frozenCols, scrollCols, frozenRows, scrollRows, density)
}

private fun DrawScope.cells(s: SheetState, m: GridMap, tm: TextMeasurer, rows: IntRange, cols: IntRange, density: Float) {
    val sheet = s.sheet; val wb = s.wb; val geo = m.geo
    val pad = 3f * density
    // fills and grid lines
    for (r in rows) for (c in cols) {
        val f = wb.format(sheet, r, c)
        val rect = m.cellRect(r, c)
        f.fill?.let { Color.hex(it) }?.let { drawRect(it, rect.topLeft, rect.size) }
        if (s.gridlines) drawRect(GRID, rect.topLeft, rect.size, style = Stroke(1f))
    }
    // text (merged areas draw once from their top-left cell)
    for (r in rows) for (c in cols) {
        val cell = sheet.cell(r, c) ?: continue
        val merge = sheet.mergeAt(r, c)
        if (merge != null && (merge.r1 != r || merge.c1 != c)) continue
        val rect = if (merge != null) m.cellRect(merge.r1, merge.c1, merge.r2, merge.c2).also { mr -> drawRect(Color.hex(wb.format(sheet, r, c).fill) ?: Color.White, mr.topLeft, mr.size) } else m.cellRect(r, c)
        val f = wb.format(sheet, r, c)
        val v = wb.book.value(sheet, r, c)
        val shown = if (s.showFormulas && cell.formula != null) Formatted("=" + cell.formula) else wb.book.display(sheet, r, c)
        if (shown.text.isEmpty()) continue
        val w = (rect.width - 2 * pad).coerceAtLeast(1f)
        val style = TextStyle(
            color = Color.hex(shown.color) ?: Color.hex(f.fontColor) ?: (if (v is FV.Err) Color(0xFFC5221F) else Color(0xFF202124)),
            fontSize = (f.fontSize * 1.25f * s.zoom).sp,
            fontWeight = if (f.bold) FontWeight.Bold else null, fontStyle = if (f.italic) FontStyle.Italic else null,
            textDecoration = when { f.underline && f.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)); f.underline -> TextDecoration.Underline; f.strike -> TextDecoration.LineThrough; else -> null },
        )
        val layout = runCatching { tm.measure(shown.text, style, overflow = TextOverflow.Clip, softWrap = f.wrap, maxLines = if (f.wrap) 20 else 1, constraints = Constraints(maxWidth = if (f.wrap) w.toInt() else (w * 6).toInt().coerceAtLeast(1))) }.getOrNull() ?: continue
        val numeric = v is FV.Num && !s.showFormulas
        // logical alignment: "general" puts numbers at the end, text at the start of the sheet direction
        val h = f.hAlign ?: "general"
        val toEnd = when (h) { "right" -> !m.rtl; "left" -> m.rtl; "center", "centerContinuous", "fill", "justify", "distributed" -> null; else -> numeric }
        val tw = layout.size.width.toFloat()
        val x = when (toEnd) {
            null -> rect.left + (rect.width - tw) / 2
            true -> if (m.rtl) rect.left + pad else rect.right - pad - tw
            false -> if (m.rtl) rect.right - pad - tw else rect.left + pad
        }
        val th = layout.size.height.toFloat()
        val y = when (f.vAlign) { "top" -> rect.top + pad / 2; "center" -> rect.top + (rect.height - th) / 2; else -> rect.bottom - th - pad / 2 }
        clipRect(rect.left, rect.top, rect.right, rect.bottom) { drawText(layout, topLeft = Offset(x, y)) }
    }
    // borders
    for (r in rows) for (c in cols) {
        val f = wb.format(sheet, r, c)
        if (f.left == null && f.right == null && f.top == null && f.bottom == null) continue
        val rect = m.cellRect(r, c)
        val col = Color.hex(f.borderColor) ?: Color.Black
        fun wOf(st: String?) = when (st) { "medium", "mediumDashed", "mediumDashDot" -> 2f; "thick", "double" -> 3f; else -> 1f } * density
        val (ls, rs) = if (m.rtl) f.right to f.left else f.left to f.right
        ls?.let { drawLine(col, rect.topLeft, Offset(rect.left, rect.bottom), wOf(it)) }
        rs?.let { drawLine(col, Offset(rect.right, rect.top), Offset(rect.right, rect.bottom), wOf(it)) }
        f.top?.let { drawLine(col, rect.topLeft, Offset(rect.right, rect.top), wOf(it)) }
        f.bottom?.let { drawLine(col, Offset(rect.left, rect.bottom), Offset(rect.right, rect.bottom), wOf(it)) }
    }
}

private fun DrawScope.headers(s: SheetState, m: GridMap, tm: TextMeasurer, frozenCols: IntRange, scrollCols: IntRange, frozenRows: IntRange, scrollRows: IntRange, density: Float) {
    val geo = m.geo; val sel = s.sel
    val style = TextStyle(color = Color(0xFF5F6368), fontSize = (11 * minOf(s.zoom, 1.4f)).sp)
    // column header strip
    val stripL = if (m.rtl) 0f else m.hw; val stripR = if (m.rtl) size.width - m.hw else size.width
    drawRect(HEADER_BG, Offset(stripL, 0f), Size(stripR - stripL, m.hh))
    clipRect(stripL, 0f, stripR, m.hh) {
        for (c in frozenCols + scrollCols) {
            val x0 = m.x(c); val w = geo.colW(c); if (w <= 0f) continue
            val l = m.sx(x0, w)
            if (c in sel.c1..sel.c2) drawRect(HEADER_SEL, Offset(l, 0f), Size(w, m.hh))
            drawLine(GRID, Offset(l, 0f), Offset(l, m.hh), 1f)
            val t = tm.measure(A1.colName(c), style)
            drawText(t, topLeft = Offset(l + (w - t.size.width) / 2, (m.hh - t.size.height) / 2))
        }
    }
    // row header strip
    val rowL = if (m.rtl) size.width - m.hw else 0f
    drawRect(HEADER_BG, Offset(rowL, m.hh), Size(m.hw, size.height - m.hh))
    clipRect(rowL, m.hh, rowL + m.hw, size.height) {
        for (r in frozenRows + scrollRows) {
            val y = m.y(r); val h = geo.rowH(r)
            if (r in sel.r1..sel.r2) drawRect(HEADER_SEL, Offset(rowL, y), Size(m.hw, h))
            drawLine(GRID, Offset(rowL, y), Offset(rowL + m.hw, y), 1f)
            val t = tm.measure((r + 1).toString(), style)
            drawText(t, topLeft = Offset(rowL + (m.hw - t.size.width) / 2, y + (h - t.size.height) / 2))
        }
    }
    drawRect(HEADER_BG, Offset(rowL, 0f), Size(m.hw, m.hh))
    drawLine(Color(0xFFBDC1C6), Offset(0f, m.hh), Offset(size.width, m.hh), 1f)
    drawLine(Color(0xFFBDC1C6), Offset(if (m.rtl) size.width - m.hw else m.hw, 0f), Offset(if (m.rtl) size.width - m.hw else m.hw, size.height), 1f)
}
