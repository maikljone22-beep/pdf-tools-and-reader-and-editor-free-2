package com.smartdocs.editor.ui.view5

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.smartdocs.editor.core.view5.MeasureType
import com.smartdocs.editor.core.view5.MeasureWriter
import com.smartdocs.editor.core.view5.P
import com.smartdocs.editor.core.view5.StoredEdit
import com.smartdocs.editor.core.view5.TextBoxSpec

/** A drag handle of the ADJUST tool: point [point] (-1 = the measurement label) of a pending item or of a saved annotation. */
sealed interface AdjustHandle {
    data class Item(val index: Int, val point: Int) : AdjustHandle
    data class Stored(val page: Int, val annot: Int, val point: Int) : AdjustHandle
}

/**
 * 24.27 / 25.18 / 25.19: the ADJUST tool of the workbench. Every placed measurement, line and callout of the page,
 * pending or saved, shows handles: end points / vertices, the measurement label (moved on its own) and the callout
 * tip and knee. Pending items change at once; saved ones are collected in [WorkbenchModel.storedEdits] and written
 * on Save ([StoredEdit.apply]).
 */
internal object Adjust {
    /** Largest distance (screen px) between a touch and the handle it grabs. */
    private const val GRAB_PX = 48f

    /** The knee of a callout: its own, else half way between the tip and the box centre (as the writer draws it). */
    fun kneeOf(s: TextBoxSpec): P? {
        val t = s.tip ?: return null
        return s.knee ?: P((t.x + (s.box.x0 + s.box.x1) / 2) / 2, (t.y + (s.box.y0 + s.box.y1) / 2) / 2)
    }

    private fun storedLabel(e: StoredEdit.Editable, c: StoredEdit.Change?): P? =
        c?.label ?: c?.let { StoredEdit.changedMeasurement(e, it) }?.let { MeasureWriter.labelAt(it) } ?: e.label

    /** Every handle of the current page with its position (page points). */
    fun handles(m: WorkbenchModel, stored: List<StoredEdit.Editable>): List<Pair<AdjustHandle, P>> {
        val out = ArrayList<Pair<AdjustHandle, P>>()
        m.items.forEachIndexed { i, it ->
            if (it.page != m.page) return@forEachIndexed
            when (it) {
                is WItem.Meas -> {
                    it.m.pts.forEachIndexed { k, p -> out += AdjustHandle.Item(i, k) to p }
                    if (it.m.type != MeasureType.COUNT) out += AdjustHandle.Item(i, -1) to MeasureWriter.labelAt(it.m)
                }
                is WItem.Line -> { out += AdjustHandle.Item(i, 0) to it.a; out += AdjustHandle.Item(i, 1) to it.b }
                is WItem.Text -> it.spec.tip?.let { t -> out += AdjustHandle.Item(i, 0) to t; kneeOf(it.spec)?.let { k -> out += AdjustHandle.Item(i, 1) to k } }
                else -> {}
            }
        }
        stored.filter { it.page == m.page }.forEach { e ->
            val c = m.storedEdits[e.page to e.index]?.second
            (c?.pts ?: e.pts).forEachIndexed { k, p -> out += AdjustHandle.Stored(e.page, e.index, k) to p }
            if (e.kind == StoredEdit.Kind.MEASURE) storedLabel(e, c)?.let { out += AdjustHandle.Stored(e.page, e.index, -1) to it }
        }
        return out
    }

    /** The handle nearest to the touch [at] (screen), if close enough. */
    fun hit(m: WorkbenchModel, stored: List<StoredEdit.Editable>, view: WbView, at: Offset): AdjustHandle? =
        handles(m, stored).map { (h, p) -> h to (view.toScreen(p) - at).getDistance() }.filter { it.second <= GRAB_PX }.minByOrNull { it.second }?.first

    private fun <T> List<T>.with(i: Int, v: T): List<T> = toMutableList().also { if (i in it.indices) it[i] = v }

    /** Drop handle [h] at [to] (page points). */
    fun commit(m: WorkbenchModel, stored: List<StoredEdit.Editable>, h: AdjustHandle, to: P) {
        when (h) {
            is AdjustHandle.Item -> when (val it = m.items.getOrNull(h.index)) {
                is WItem.Meas -> if (h.point < 0) {
                    val a = MeasureWriter.labelAnchor(it.m)
                    m.items[h.index] = WItem.Meas(it.m.copy(labelOffset = P(to.x - a.x, to.y - a.y)))
                } else m.replaceMeasure(h.index, it.m.copy(pts = it.m.pts.with(h.point, to)))
                is WItem.Line -> m.items[h.index] = if (h.point == 0) it.copy(a = to) else it.copy(b = to)
                is WItem.Text -> m.items[h.index] = WItem.Text(if (h.point == 0) it.spec.copy(tip = to, knee = kneeOf(it.spec)) else it.spec.copy(knee = to))
                else -> {}
            }
            is AdjustHandle.Stored -> {
                val e = stored.firstOrNull { it.page == h.page && it.index == h.annot } ?: return
                val key = e.page to e.index
                val cur = m.storedEdits[key]?.second ?: StoredEdit.Change(e.pts, null)
                m.storedEdits[key] = e to (if (h.point < 0) cur.copy(label = to) else cur.copy(pts = cur.pts.with(h.point, to)))
            }
        }
    }

    /** Saved shapes as they will be after Save (dashed) and every handle; the grabbed one is drawn at [grabAt]. */
    fun draw(scope: DrawScope, m: WorkbenchModel, stored: List<StoredEdit.Editable>, view: WbView, grab: AdjustHandle?, grabAt: P?) = with(scope) {
        val accent = Color(0xFF1E88E5)
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
        stored.filter { it.page == m.page }.forEach { e ->
            val c = m.storedEdits[e.page to e.index]?.second ?: return@forEach
            val sp = c.pts.map { view.toScreen(it) }
            if (sp.size < 2) return@forEach
            val circle = e.measurement?.type.let { it == MeasureType.CIRCLE || it == MeasureType.DIAMETER || it == MeasureType.RADIUS }
            if (circle) drawCircle(accent, (sp[1] - sp[0]).getDistance(), sp[0], style = Stroke(3f, pathEffect = dash))
            else {
                val closed = e.measurement?.type == MeasureType.AREA || e.measurement?.type == MeasureType.VOLUME
                val path = Path().apply { sp.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; if (closed) close() }
                drawPath(path, accent, style = Stroke(3f, pathEffect = dash))
            }
        }
        handles(m, stored).forEach { (h, p) ->
            val o = view.toScreen(if (h == grab && grabAt != null) grabAt else p)
            val label = (h is AdjustHandle.Item && h.point < 0) || (h is AdjustHandle.Stored && h.point < 0)
            if (label) {
                drawRect(Color.White, Offset(o.x - 13f, o.y - 13f), Size(26f, 26f))
                drawRect(accent, Offset(o.x - 9f, o.y - 9f), Size(18f, 18f))
            } else {
                drawCircle(Color.White, 13f, o)
                drawCircle(if (h is AdjustHandle.Stored) Color(0xFF8E24AA) else accent, 9f, o)
            }
        }
    }
}
