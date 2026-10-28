package com.smartdocs.editor.ui.reader.annot

import android.content.Context
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool

/**
 * Per-tool memory of the creation settings (OLD_PLAN "remember settings per tool"): colour, opacity,
 * thickness, font size plus the section-7 extras (fill, dash, no border, line endings, cloud, rich text,
 * note icon), kept in SharedPreferences "tool_settings" so a tool reopens as it was last used.
 */
object ToolMemory {
    private const val PREFS = "tool_settings"

    data class Settings(
        val color: Int, val opacity: Float, val thickness: Float, val fontSize: Float,
        val fill: Int?, val dash: Int, val noBorder: Boolean, val lineStart: String, val lineEnd: String, val cloud: Int,
        val bold: Boolean, val italic: Boolean, val underline: Boolean, val icon: String, val cloudInward: Boolean = false,
    ) {
        fun encode(): String = listOf(color, opacity, thickness, fontSize, fill ?: "", dash, noBorder, lineStart, lineEnd, cloud, bold, italic, underline, icon, cloudInward).joinToString("|")

        companion object {
            fun decode(s: String): Settings? = runCatching {
                val p = s.split('|')
                Settings(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toIntOrNull(), p[5].toInt(), p[6].toBoolean(),
                    p[7], p[8], p[9].toInt(), p[10].toBoolean(), p[11].toBoolean(), p[12].toBoolean(), p.getOrElse(13) { "" }, p.getOrElse(14) { "false" }.toBoolean())
            }.getOrNull()
        }
    }

    fun capture(s: ReaderState): Settings {
        val h = s.annot2
        return Settings(s.color, s.opacity, s.thickness, s.fontSize, h.fillColor, h.dash, h.noBorder, h.lineStart, h.lineEnd,
            if (s.tool == ReaderTool.CLOUD) h.cloudIntensity else h.rectCloud, h.bold, h.italic, h.underline, h.noteIcon, h.cloudInward)
    }

    fun apply(s: ReaderState, v: Settings) {
        val h = s.annot2
        s.color = v.color; s.opacity = v.opacity; s.thickness = v.thickness; s.fontSize = v.fontSize
        h.fillColor = v.fill; h.dash = v.dash; h.noBorder = v.noBorder; h.lineStart = v.lineStart; h.lineEnd = v.lineEnd
        if (s.tool == ReaderTool.CLOUD) h.cloudIntensity = v.cloud.coerceIn(1, 3) else h.rectCloud = v.cloud.coerceIn(0, 3)
        h.bold = v.bold; h.italic = v.italic; h.underline = v.underline; h.noteIcon = v.icon; h.cloudInward = v.cloudInward
    }

    /** "Remember tool settings" (one switch for the one memory; key kept from view5 so the old choice stays). */
    fun enabled(ctx: Context): Boolean = runCatching { ctx.getSharedPreferences("view5", Context.MODE_PRIVATE).getBoolean("tool_memory", true) }.getOrDefault(true)

    fun setEnabled(ctx: Context, on: Boolean) { runCatching { ctx.getSharedPreferences("view5", Context.MODE_PRIVATE).edit().putBoolean("tool_memory", on).apply() } }

    fun save(ctx: Context, tool: ReaderTool, v: Settings) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(tool.name, v.encode()).apply() }
    }

    fun load(ctx: Context, tool: ReaderTool): Settings? =
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(tool.name, null) }.getOrNull()?.let { Settings.decode(it) }

    /** Tools whose settings are remembered (creation tools only). */
    val REMEMBERED = setOf(ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.INK,
        ReaderTool.NOTE, ReaderTool.FREETEXT, ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL,
        ReaderTool.POLYGON, ReaderTool.POLYLINE, ReaderTool.CLOUD, ReaderTool.CARET, ReaderTool.REPLACE, ReaderTool.TEXTBOX)
}

/** Apply the creation extras of the active tool to a freshly created annotation. */
fun Annot2Host.styleNew(a: Annot): Annot = when (a.kind) {
    AnnotKind.RECT -> a.copy(fillColor = fillColor, dash = dash, noBorder = noBorder && fillColor != null, cloud = rectCloud, cloudInward = rectCloud > 0 && cloudInward)
    AnnotKind.OVAL -> a.copy(fillColor = fillColor, dash = dash, noBorder = noBorder && fillColor != null, cloud = rectCloud, cloudInward = rectCloud > 0 && cloudInward)
    AnnotKind.LINE, AnnotKind.ARROW -> a.copy(dash = dash, lineStart = lineStart, lineEnd = lineEnd, caption = caption)
    AnnotKind.INK -> a.copy(dash = dash)
    AnnotKind.POLYGON, AnnotKind.CLOUD -> a.copy(dash = dash, noBorder = noBorder && fillColor != null)
    AnnotKind.POLYLINE -> a.copy(dash = dash)
    AnnotKind.FREETEXT -> textFmt.applyTo(a.copy(bold = bold, italic = italic, underline = underline))
    AnnotKind.NOTE -> a.copy(icon = noteIcon.takeIf { it in com.smartdocs.editor.core.annot.AnnotStyle.NOTE_ICONS } ?: "")
    AnnotKind.FILE_ATTACH -> a.copy(icon = noteIcon.takeIf { it in com.smartdocs.editor.core.annot.AnnotStyle.ATTACH_ICONS } ?: "")
    else -> a
}

/** Text box formatting of section 24.99-24.107 (everything except bold / italic / underline, which have chips). */
data class TextFmt(val family: Int = 0, val strike: Boolean = false, val align: Int = -1, val lineSpacing: Float = 1.3f,
                   val charSpacing: Float = 0f, val wordSpacing: Float = 0f, val hScale: Float = 1f, val bullets: Boolean = false,
                   val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false) {
    /** Everything including bold / italic / underline (editing an existing text box). */
    fun applyAll(a: Annot): Annot = applyTo(a).copy(bold = bold, italic = italic, underline = underline)

    fun applyTo(a: Annot): Annot = a.copy(fontFamily = family, strike = strike, align = align, lineSpacing = lineSpacing,
        charSpacing = charSpacing, wordSpacing = wordSpacing, hScale = hScale, bullets = bullets)

    companion object {
        fun of(a: Annot) = TextFmt(a.fontFamily, a.strike, a.align, a.lineSpacing, a.charSpacing, a.wordSpacing, a.hScale, a.bullets, a.bold, a.italic, a.underline)
    }
}
