package com.smartdocs.editor.ui.view5

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.view5.Box
import com.smartdocs.editor.core.view5.CommentWriter5
import com.smartdocs.editor.core.view5.DashStyle
import com.smartdocs.editor.core.view5.Geom
import com.smartdocs.editor.core.view5.LineEnd
import com.smartdocs.editor.core.view5.MarkKind
import com.smartdocs.editor.core.view5.MeasureFormat
import com.smartdocs.editor.core.view5.MeasureType
import com.smartdocs.editor.core.view5.MeasureWriter
import com.smartdocs.editor.core.view5.Measurement
import com.smartdocs.editor.core.view5.P
import com.smartdocs.editor.core.view5.TextBoxSpec
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Something placed in the workbench and not yet written to the file. */
sealed interface WItem {
    val page: Int
    data class Meas(val m: Measurement) : WItem { override val page: Int get() = m.page }
    data class Text(val spec: TextBoxSpec) : WItem { override val page: Int get() = spec.page }
    data class Mark(override val page: Int, val kind: MarkKind, val at: P, val size: Float, val color: Int) : WItem
    data class Area(override val page: Int, val box: Box, val color: Int, val opacity: Float) : WItem
    data class Line(override val page: Int, val a: P, val b: P, val color: Int, val width: Float, val dash: DashStyle, val start: LineEnd, val end: LineEnd) : WItem
}

/** Text box waiting for its text (callout / text box / typewriter). */
data class TextRequest(val tool: WTool, val page: Int, val box: Box, val tip: P? = null)

/** Editing state of the page workbench. */
class WorkbenchModel(val v: V5State) {
    var page by mutableIntStateOf(v.s.currentPage)
    var tool by mutableStateOf(v.workbenchTool)
    val pts = mutableStateListOf<P>()
    val items = mutableStateListOf<WItem>()
    var calibrating by mutableStateOf(false)
    var calibrationPts by mutableStateOf<List<P>?>(null)
    var textRequest by mutableStateOf<TextRequest?>(null)
    var color by mutableIntStateOf(v.prefs.getInt("wb_color", 0xE53935))
    var width by mutableFloatStateOf(v.prefs.getFloat("wb_width", 1.5f))
    var dash by mutableStateOf(DashStyle.entries.getOrElse(v.prefs.getInt("wb_dash", 0)) { DashStyle.SOLID })
    var startEnd by mutableStateOf(LineEnd.entries.getOrElse(v.prefs.getInt("wb_le0", 0)) { LineEnd.NONE })
    var endEnd by mutableStateOf(LineEnd.entries.getOrElse(v.prefs.getInt("wb_le1", 1)) { LineEnd.OPEN_ARROW })
    var markSize by mutableFloatStateOf(v.prefs.getFloat("wb_mark", 16f))
    var fillArea by mutableStateOf(v.prefs.getBoolean("wb_fill", true))
    var arabicDigits by mutableStateOf(v.prefs.getBoolean("ar_digits", false))
    // distance leader lines (7.30) and the label of the next measurement (25.14)
    var leader by mutableFloatStateOf(v.prefs.getFloat("wb_ll", 0f))
    var leaderExt by mutableFloatStateOf(v.prefs.getFloat("wb_lle", 0f))
    var leaderOffset by mutableFloatStateOf(v.prefs.getFloat("wb_llo", 0f))
    var nextLabel by mutableStateOf("")
    /** §41: depth of a volume / height of a polylength wall, in real units (0 = none). */
    var depth by mutableFloatStateOf(v.prefs.getFloat("wb_depth", 0f))
    /** 24.27 / 25.19 / 25.18: stored annotations of the page with their changed handles ((page, index) -> change). */
    val storedEdits = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, Pair<com.smartdocs.editor.core.view5.StoredEdit.Editable, com.smartdocs.editor.core.view5.StoredEdit.Change>>()

    fun persist() {
        v.prefs.edit().putInt("wb_color", color).putFloat("wb_width", width).putInt("wb_dash", dash.ordinal).putInt("wb_le0", startEnd.ordinal)
            .putInt("wb_le1", endEnd.ordinal).putFloat("wb_mark", markSize).putBoolean("wb_fill", fillArea).putBoolean("ar_digits", arabicDigits)
            .putFloat("wb_ll", leader).putFloat("wb_lle", leaderExt).putFloat("wb_llo", leaderOffset).putFloat("wb_depth", depth).apply()
        v.workbenchTool = tool
    }

    fun selectTool(t: WTool) { tool = t; pts.clear(); calibrating = false }

    val isMeasure get() = tool in MEASURE_TOOLS

    /** Something to save: pending items or changed stored annotations. */
    val hasChanges get() = items.isNotEmpty() || storedEdits.isNotEmpty()

    /** Points the current tool needs before it finishes by itself (0 = finished with "Done"). */
    fun needed(): Int = if (calibrating) 2 else when (tool) {
        WTool.DISTANCE, WTool.CIRCLE, WTool.TEXTBOX, WTool.AREA_HIGHLIGHT, WTool.LINE, WTool.DIAMETER, WTool.RADIUS -> 2
        WTool.ANGLE, WTool.CALLOUT -> 3
        WTool.TYPEWRITER, WTool.TICK, WTool.CROSS -> 1
        WTool.PERIMETER, WTool.AREA, WTool.COUNT, WTool.VOLUME, WTool.POLYLENGTH, WTool.ADJUST -> 0
    }

    fun typeOf(t: WTool): MeasureType = when (t) {
        WTool.PERIMETER -> MeasureType.PERIMETER; WTool.AREA -> MeasureType.AREA; WTool.CIRCLE -> MeasureType.CIRCLE
        WTool.ANGLE -> MeasureType.ANGLE; WTool.COUNT -> MeasureType.COUNT
        WTool.VOLUME -> MeasureType.VOLUME; WTool.POLYLENGTH -> MeasureType.POLYLENGTH; WTool.DIAMETER -> MeasureType.DIAMETER; WTool.RADIUS -> MeasureType.RADIUS
        else -> MeasureType.DISTANCE
    }

    fun toolOf(t: MeasureType): WTool = when (t) {
        MeasureType.DISTANCE -> WTool.DISTANCE; MeasureType.PERIMETER -> WTool.PERIMETER; MeasureType.AREA -> WTool.AREA
        MeasureType.CIRCLE -> WTool.CIRCLE; MeasureType.ANGLE -> WTool.ANGLE; MeasureType.COUNT -> WTool.COUNT
        MeasureType.VOLUME -> WTool.VOLUME; MeasureType.POLYLENGTH -> WTool.POLYLENGTH; MeasureType.DIAMETER -> WTool.DIAMETER; MeasureType.RADIUS -> WTool.RADIUS
    }

    /** Real-world value of [p] for [t] with the current scale (and depth for a volume). */
    fun valueOf(t: WTool, p: List<P>): Double = if (t in MEASURE_TOOLS) com.smartdocs.editor.core.view5.MeasureCalc.value(typeOf(t), p, v.scale, depth.toDouble()) else 0.0

    fun label(t: WTool, value: Double): String = com.smartdocs.editor.core.view5.MeasureCalc.label(typeOf(t), value, v.scale, v.precision, arabicDigits)

    /** §41: the wall area line of a polylength with a height. */
    fun extraLabel(t: WTool, p: List<P>): String =
        if (t == WTool.POLYLENGTH && depth > 0f) v.s.context.getString(com.smartdocs.editor.R.string.cmr_wall_area,
            MeasureFormat.value(com.smartdocs.editor.core.view5.MeasureCalc.wallArea(p, v.scale, depth.toDouble()), v.precision, v.scale.realUnit.code + "²", arabicDigits)) else ""

    /** Add a tapped point; finishes the item when the tool has all its points. */
    fun addPoint(p: P) {
        pts += p
        val n = needed()
        if (n > 0 && pts.size >= n) finish()
    }

    /** Edit the last pending measurement (25.14 label, 25.19 end points, 25.20 flip, 7.30 leader lines). */
    fun lastMeasure(): Measurement? = (items.lastOrNull { it is WItem.Meas && it.page == page } as? WItem.Meas)?.m

    fun replaceLastMeasure(m: Measurement) {
        val i = items.indexOfLast { it is WItem.Meas && it.page == page }
        if (i < 0) return
        // the value follows the (possibly moved) end points
        val t = toolOf(m.type)
        val value = com.smartdocs.editor.core.view5.MeasureCalc.value(m.type, m.pts, m.scale, m.depth)
        items[i] = WItem.Meas(m.copy(value = value, label = label(t, value), extraLabel = if (m.type == MeasureType.POLYLENGTH && m.depth > 0) extraLabel(t, m.pts) else m.extraLabel))
    }

    /** 25.18 / 25.19: a pending measurement whose points or label were dragged (the value follows). */
    fun replaceMeasure(index: Int, m: Measurement) {
        if (index !in items.indices) return
        val value = com.smartdocs.editor.core.view5.MeasureCalc.value(m.type, m.pts, m.scale, m.depth)
        items[index] = WItem.Meas(m.copy(value = value, label = label(toolOf(m.type), value)))
    }

    fun undoPoint() { if (pts.isNotEmpty()) pts.removeAt(pts.lastIndex) else if (items.isNotEmpty()) items.removeAt(items.lastIndex) }

    fun finish() {
        val p = pts.toList()
        if (calibrating) { if (p.size >= 2) calibrationPts = p; pts.clear(); calibrating = false; return }
        if (p.isEmpty()) return   // p[0] below
        when (tool) {
            WTool.DISTANCE, WTool.PERIMETER, WTool.AREA, WTool.CIRCLE, WTool.ANGLE, WTool.COUNT, WTool.VOLUME, WTool.POLYLENGTH, WTool.DIAMETER, WTool.RADIUS -> {
                if (p.size >= com.smartdocs.editor.core.view5.MeasureCalc.minPoints(typeOf(tool))) {
                    val value = valueOf(tool, p)
                    val dist = tool == WTool.DISTANCE
                    val deep = tool == WTool.VOLUME || tool == WTool.POLYLENGTH
                    items += WItem.Meas(Measurement(typeOf(tool), page, p, value, label(tool, value), v.scale, color, width, v.s.authorName, v.precision, dash,
                        if (fillArea && (tool == WTool.AREA || tool == WTool.CIRCLE || tool == WTool.VOLUME || tool == WTool.DIAMETER || tool == WTool.RADIUS)) color else null,
                        leader = if (dist) leader else 0f, leaderExt = if (dist) leaderExt else 0f, leaderOffset = if (dist) leaderOffset else 0f,
                        customLabel = nextLabel.takeIf { it.isNotBlank() }, depth = if (deep) depth.toDouble() else 0.0, extraLabel = extraLabel(tool, p)))
                }
            }
            WTool.ADJUST -> {}
            WTool.TICK -> items += WItem.Mark(page, MarkKind.TICK, p[0], markSize, color)
            WTool.CROSS -> items += WItem.Mark(page, MarkKind.CROSS, p[0], markSize, color)
            WTool.AREA_HIGHLIGHT -> if (p.size >= 2) items += WItem.Area(page, Box.of(p[0], p[1]), if (color == 0xE53935) 0xFFEB3B else color, 0.5f)
            WTool.LINE -> if (p.size >= 2) items += WItem.Line(page, p[0], p[1], color, width, dash, startEnd, endEnd)
            WTool.TEXTBOX -> if (p.size >= 2) textRequest = TextRequest(WTool.TEXTBOX, page, Box.of(p[0], p[1]))
            WTool.CALLOUT -> if (p.size >= 3) textRequest = TextRequest(WTool.CALLOUT, page, Box.of(p[1], p[2]), tip = p[0])
            WTool.TYPEWRITER -> textRequest = TextRequest(WTool.TYPEWRITER, page, Box(p[0].x.toFloat(), p[0].y.toFloat() - 20f, p[0].x.toFloat() + 160f, p[0].y.toFloat()))
        }
        pts.clear()
    }

    /** Write every pending item into [out] (a copy of [src]); signed files get an incremental update (audit CM-01). */
    suspend fun writeAll(context: android.content.Context, src: File, out: File, pw: String?) = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.pdf.SignedSave.editFile(src, out, pw) { doc ->
            var font: PDFont? = null
            val arabic: () -> PDFont = { font ?: PdfOps.arabicFont(context, doc).also { font = it } }
            // stored annotations first: their indices are those of the file before anything is added
            val edited = storedEdits.values.toList()
            edited.forEach { (e, c) ->
                if (e.page in 0 until doc.numberOfPages) runCatching { com.smartdocs.editor.core.view5.StoredEdit.apply(doc, doc.getPage(e.page), e, c, arabic) }
            }
            items.toList().forEach { it2 ->
                if (it2.page !in 0 until doc.numberOfPages) return@forEach
                val pg = doc.getPage(it2.page)
                runCatching {
                    when (it2) {
                        is WItem.Meas -> MeasureWriter.write(doc, pg, it2.m, arabic)
                        is WItem.Text -> CommentWriter5.writeTextBox(doc, pg, it2.spec, arabic)
                        is WItem.Mark -> CommentWriter5.writeMark(doc, pg, it2.kind, it2.at, it2.size, it2.color, v.s.authorName)
                        is WItem.Area -> CommentWriter5.writeAreaHighlight(doc, pg, it2.box, it2.color, it2.opacity, v.s.authorName)
                        is WItem.Line -> CommentWriter5.writeLine(doc, pg, it2.a, it2.b, it2.color, it2.width, 1f, it2.dash, it2.start, it2.end, v.s.authorName)
                    }
                }
            }
            (items.map { it.page } + edited.map { it.first.page }).distinct()
        }
    }

    companion object {
        val MEASURE_TOOLS = setOf(WTool.DISTANCE, WTool.PERIMETER, WTool.AREA, WTool.CIRCLE, WTool.ANGLE, WTool.COUNT,
            WTool.VOLUME, WTool.POLYLENGTH, WTool.DIAMETER, WTool.RADIUS)
    }
}
