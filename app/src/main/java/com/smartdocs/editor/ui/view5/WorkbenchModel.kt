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

    fun persist() {
        v.prefs.edit().putInt("wb_color", color).putFloat("wb_width", width).putInt("wb_dash", dash.ordinal).putInt("wb_le0", startEnd.ordinal)
            .putInt("wb_le1", endEnd.ordinal).putFloat("wb_mark", markSize).putBoolean("wb_fill", fillArea).putBoolean("ar_digits", arabicDigits).apply()
        v.workbenchTool = tool
    }

    fun selectTool(t: WTool) { tool = t; pts.clear(); calibrating = false }

    val isMeasure get() = tool in setOf(WTool.DISTANCE, WTool.PERIMETER, WTool.AREA, WTool.CIRCLE, WTool.ANGLE, WTool.COUNT)

    /** Points the current tool needs before it finishes by itself (0 = finished with "Done"). */
    fun needed(): Int = if (calibrating) 2 else when (tool) {
        WTool.DISTANCE, WTool.CIRCLE, WTool.TEXTBOX, WTool.AREA_HIGHLIGHT, WTool.LINE -> 2
        WTool.ANGLE, WTool.CALLOUT -> 3
        WTool.TYPEWRITER, WTool.TICK, WTool.CROSS -> 1
        WTool.PERIMETER, WTool.AREA, WTool.COUNT -> 0
    }

    fun typeOf(t: WTool): MeasureType = when (t) {
        WTool.PERIMETER -> MeasureType.PERIMETER; WTool.AREA -> MeasureType.AREA; WTool.CIRCLE -> MeasureType.CIRCLE
        WTool.ANGLE -> MeasureType.ANGLE; WTool.COUNT -> MeasureType.COUNT; else -> MeasureType.DISTANCE
    }

    /** Real-world value of [p] for [t] with the current scale. */
    fun valueOf(t: WTool, p: List<P>): Double = when (t) {
        WTool.DISTANCE -> if (p.size >= 2) v.scale.length(Geom.dist(p[0], p[1])) else 0.0
        WTool.PERIMETER -> v.scale.length(Geom.pathLength(p))
        WTool.AREA -> v.scale.area(Geom.polygonArea(p))
        WTool.CIRCLE -> if (p.size >= 2) v.scale.area(Geom.circleArea(Geom.dist(p[0], p[1]))) else 0.0
        WTool.ANGLE -> if (p.size >= 3) Geom.angleDeg(p[0], p[1], p[2]) else 0.0
        WTool.COUNT -> p.size.toDouble()
        else -> 0.0
    }

    fun label(t: WTool, value: Double): String {
        val type = typeOf(t)
        return when (type) {
            MeasureType.COUNT -> MeasureFormat.value(value, 0, "", arabicDigits)
            MeasureType.ANGLE -> MeasureFormat.value(value, 1, "°", arabicDigits).replace(" °", "°")
            else -> MeasureFormat.value(value, v.precision, MeasureFormat.unitFor(type, v.scale), arabicDigits)
        }
    }

    /** Add a tapped point; finishes the item when the tool has all its points. */
    fun addPoint(p: P) {
        pts += p
        val n = needed()
        if (n > 0 && pts.size >= n) finish()
    }

    fun undoPoint() { if (pts.isNotEmpty()) pts.removeAt(pts.lastIndex) else if (items.isNotEmpty()) items.removeAt(items.lastIndex) }

    fun finish() {
        val p = pts.toList()
        if (calibrating) { if (p.size >= 2) calibrationPts = p; pts.clear(); calibrating = false; return }
        if (p.isEmpty()) return   // p[0] below
        when (tool) {
            WTool.DISTANCE, WTool.PERIMETER, WTool.AREA, WTool.CIRCLE, WTool.ANGLE, WTool.COUNT -> {
                val min = when (tool) { WTool.AREA, WTool.ANGLE -> 3; WTool.COUNT -> 1; else -> 2 }
                if (p.size >= min) {
                    val value = valueOf(tool, p)
                    items += WItem.Meas(Measurement(typeOf(tool), page, p, value, label(tool, value), v.scale, color, width, v.s.authorName, v.precision, dash,
                        if (fillArea && (tool == WTool.AREA || tool == WTool.CIRCLE)) color else null))
                }
            }
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

    /** Write every pending item into [out] (a copy of [src]). */
    suspend fun writeAll(context: android.content.Context, src: File, out: File, pw: String?) = withContext(Dispatchers.IO) {
        PdfOps.load(src, pw).use { doc ->
            var font: PDFont? = null
            val arabic: () -> PDFont = { font ?: PdfOps.arabicFont(context, doc).also { font = it } }
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
            doc.save(out)
        }
    }
}
