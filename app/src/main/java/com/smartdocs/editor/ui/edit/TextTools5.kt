package com.smartdocs.editor.ui.edit

import android.content.Context
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.EditFonts
import com.smartdocs.editor.core.edit.EditTextStyle
import com.smartdocs.editor.core.edit.FontEmbed
import com.smartdocs.editor.core.edit.FontFamily
import com.smartdocs.editor.core.edit.LinkTarget
import com.smartdocs.editor.core.edit.ListKind
import com.smartdocs.editor.core.edit.NumberFormat
import com.smartdocs.editor.core.edit.PageModel
import com.smartdocs.editor.core.edit.PageTextModel
import com.smartdocs.editor.core.edit.RichText
import com.smartdocs.editor.core.edit.RunStyle
import com.smartdocs.editor.core.edit.Script
import com.smartdocs.editor.core.edit.TextBlock
import com.smartdocs.editor.core.edit.TextCase
import com.smartdocs.editor.core.edit.TextEdit5
import com.smartdocs.editor.ui.nve.NveDigits
import com.smartdocs.editor.ui.nve.SpellIssue
import com.smartdocs.editor.ui.reader.ReaderState
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

// NVE-text: edit-mode text tools (rich text, fonts, spacing, lists, rotation, join / split / linked boxes, outlines,
// reflow, OCR, preferences). State is a side-car of the EditController; every change goes through
// EditController.runNve -> applyEdit -> ReaderState.applyInPlace (one file step of the unified history).

/** Edit preferences (SharedPreferences "nve_edit"). */
internal object EditPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences(NveDigits.PREFS, Context.MODE_PRIVATE)
    fun lineMode(ctx: Context) = p(ctx).getBoolean("line_mode", false)
    fun wordGap(ctx: Context) = p(ctx).getFloat("word_gap", 0.22f)
    fun warnFonts(ctx: Context) = p(ctx).getBoolean("warn_fonts", true)
    fun systemFonts(ctx: Context) = p(ctx).getBoolean("system_fonts", true)
    fun alertNoText(ctx: Context) = p(ctx).getBoolean("alert_no_text", true)
    fun liveSpell(ctx: Context) = p(ctx).getBoolean("live_spell", true)
    fun set(ctx: Context, key: String, v: Boolean) { p(ctx).edit().putBoolean(key, v).apply() }
    fun setFloat(ctx: Context, key: String, v: Float) { p(ctx).edit().putFloat(key, v).apply() }

    /** Grouping options of the text model from the preferences (called before every page load). */
    fun apply(ctx: Context) { PageTextModel.options = PageTextModel.Options(lineMode(ctx), wordGap(ctx)) }
}



/** Linked text boxes (prefs "nve_links"): source box (file, page, rounded rect) → target box. */
internal object TextLinks {
    private fun p(ctx: Context) = ctx.getSharedPreferences("nve_links", Context.MODE_PRIVATE)
    private fun key(file: String, page: Int, r: RectF) = "$file|$page|${r.left.roundToInt()},${r.top.roundToInt()},${r.right.roundToInt()},${r.bottom.roundToInt()}"

    fun set(ctx: Context, file: String, page: Int, r: RectF, t: LinkTarget?) {
        val k = key(file, page, r)
        val e = p(ctx).edit()
        all(ctx, file, page).filter { (src, _) -> near(src, r) }.forEach { (src, _) -> e.remove(key(file, page, src)) }
        if (t != null) e.putString(k, "${t.page};${t.left};${t.top};${t.right};${t.bottom}")
        e.apply()
    }

    private fun all(ctx: Context, file: String, page: Int): List<Pair<RectF, LinkTarget>> = p(ctx).all.mapNotNull { (k, v) ->
        val parts = k.split('|'); if (parts.size != 3 || parts[0] != file || parts[1] != page.toString()) return@mapNotNull null
        val r = parts[2].split(',').mapNotNull { it.toFloatOrNull() }; if (r.size != 4) return@mapNotNull null
        val t = (v as? String)?.split(';')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 5 } ?: return@mapNotNull null
        RectF(r[0], r[1], r[2], r[3]) to LinkTarget(t[0].toInt(), t[1], t[2], t[3], t[4])
    }

    /** Same box (tab-view-core E-07: start corner = left edge for LTR boxes, right edge for RTL ones; see [LinkBoxMatch]). */
    private fun near(a: RectF, b: RectF) = LinkBoxMatch.same(a.left, a.top, a.right, b.left, b.top, b.right)

    fun target(ctx: Context, file: String, page: Int, r: RectF): LinkTarget? = all(ctx, file, page).firstOrNull { near(it.first, r) }?.second

    /**
     * tab-view-core E-07: links of [page] whose source box no longer exists (after an undo / redo or another edit)
     * are dropped, so a stale link never flows text into a box it was not made for.
     */
    fun prune(ctx: Context, file: String, page: Int, boxes: List<RectF>) {
        val stale = all(ctx, file, page).filter { (src, _) -> boxes.none { near(src, it) } }
        if (stale.isEmpty()) return
        val e = p(ctx).edit(); stale.forEach { (src, _) -> e.remove(key(file, page, src)) }; e.apply()
    }

    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * tab-view-core E-07: links belong to the document (its permanent /ID), not to whatever file is later saved at the
     * same path. [prime] reads the ID off the main thread; [docKey] falls back to the path until then.
     */
    fun docKey(f: java.io.File): String = ids[f.absolutePath] ?: f.absolutePath

    fun prime(f: java.io.File, password: String?) {
        if (ids.containsKey(f.absolutePath)) return
        val id = runCatching {
            com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, password).use { d ->
                (d.document.documentID?.getObject(0) as? com.tom_roush.pdfbox.cos.COSString)?.toHexString()
            }
        }.getOrNull()
        if (!id.isNullOrBlank()) ids[f.absolutePath] = "id:$id"
    }
}

/** tab-view-core E-07: pure "same linked box" test (unit-tested). */
object LinkBoxMatch {
    /** Same top edge and the same left (LTR start) or right (RTL start) edge within [tol] points. */
    fun same(l1: Float, t1: Float, r1: Float, l2: Float, t2: Float, r2: Float, tol: Float = 6f): Boolean =
        abs(t1 - t2) < tol && (abs(l1 - l2) < tol || abs(r1 - r2) < tol)
}

internal enum class TSheet { MORE, FONTS, COLOR, STROKE_COLOR, PREFS, REFLOW }
internal enum class Pick { NONE, JOIN_SAME, JOIN_LINE, LINK }
internal enum class RunAttr { BOLD, ITALIC, UNDERLINE, STRIKE, SUPER, SUB }

internal class TextTools5(val c: EditController) {
    private val ctx: Context get() = c.state.context
    /** Selection of the inline text field (per-run styles apply to it). */
    var sel by mutableStateOf(TextRange.Zero)
    // pending move / width change of the selected block (display points)
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    var dw by mutableStateOf<Float?>(null)
    var guides by mutableStateOf<List<Pair<Boolean, Float>>>(emptyList())      // (vertical, position)
    var sheet by mutableStateOf<TSheet?>(null)
    var pick by mutableStateOf(Pick.NONE)
    /** "Edit text" request from the reader selection waiting for its page to load. */
    var request: com.smartdocs.editor.ui.nve.NveEditRequest.Request? = null
    private var pickSource: Pair<Int, TextBlock>? = null
    val movePending: Boolean get() = dx != 0f || dy != 0f || dw != null

    fun reset() { dx = 0f; dy = 0f; dw = null; guides = emptyList(); sel = TextRange.Zero }

    // ---------------- per-run styles ----------------

    private val hasSel: Boolean get() = !sel.collapsed && sel.min < c.text.length

    /** Toggles [a] on the selected characters (or on the whole box when nothing is selected). */
    fun toggle(a: RunAttr) {
        val st = c.style
        if (!hasSel) {
            c.style = when (a) {
                RunAttr.BOLD -> st.copy(bold = !st.bold)
                RunAttr.ITALIC -> st.copy(italic = !st.italic)
                RunAttr.UNDERLINE -> st.copy(underline = !st.underline)
                RunAttr.STRIKE -> st.copy(strike = !st.strike)
                RunAttr.SUPER -> st.copy(script = if (st.script == Script.SUPER) Script.NONE else Script.SUPER)
                RunAttr.SUB -> st.copy(script = if (st.script == Script.SUB) Script.NONE else Script.SUB)
            }
            return
        }
        val s = sel.min; val e = sel.max.coerceAtMost(c.text.length)
        val on = RichText.all(st, c.text, s, e) { cs ->
            when (a) { RunAttr.BOLD -> cs.bold; RunAttr.ITALIC -> cs.italic; RunAttr.UNDERLINE -> cs.underline; RunAttr.STRIKE -> cs.strike
                RunAttr.SUPER -> cs.script == Script.SUPER; RunAttr.SUB -> cs.script == Script.SUB }
        }
        val r = when (a) {
            RunAttr.BOLD -> RunStyle(s, e, bold = !on)
            RunAttr.ITALIC -> RunStyle(s, e, italic = !on)
            RunAttr.UNDERLINE -> RunStyle(s, e, underline = !on)
            RunAttr.STRIKE -> RunStyle(s, e, strike = !on)
            RunAttr.SUPER -> RunStyle(s, e, script = if (on) Script.NONE else Script.SUPER)
            RunAttr.SUB -> RunStyle(s, e, script = if (on) Script.NONE else Script.SUB)
        }
        c.style = st.copy(runs = st.runs + r)
    }

    fun isOn(a: RunAttr): Boolean {
        val st = c.style
        if (!hasSel) return when (a) { RunAttr.BOLD -> st.bold; RunAttr.ITALIC -> st.italic; RunAttr.UNDERLINE -> st.underline; RunAttr.STRIKE -> st.strike
            RunAttr.SUPER -> st.script == Script.SUPER; RunAttr.SUB -> st.script == Script.SUB }
        return RichText.all(st, c.text, sel.min, sel.max) { cs ->
            when (a) { RunAttr.BOLD -> cs.bold; RunAttr.ITALIC -> cs.italic; RunAttr.UNDERLINE -> cs.underline; RunAttr.STRIKE -> cs.strike
                RunAttr.SUPER -> cs.script == Script.SUPER; RunAttr.SUB -> cs.script == Script.SUB }
        }
    }

    /** Text colour of the selection (or of the box). */
    fun color(rgb: Int) {
        val st = c.style
        c.style = if (hasSel) st.copy(runs = st.runs + RunStyle(sel.min, sel.max.coerceAtMost(c.text.length), color = rgb)) else st.copy(color = rgb)
    }

    /** Size of the selection changed by [delta] points. */
    fun selectionSize(delta: Float) {
        if (!hasSel) { c.style = c.style.copy(size = (c.style.size + delta).coerceIn(4f, 200f)); return }
        val cur = RichText.styles(c.style, c.text).getOrNull(sel.min)?.size ?: c.style.size
        c.style = c.style.copy(runs = c.style.runs + RunStyle(sel.min, sel.max.coerceAtMost(c.text.length), size = (cur + delta).coerceIn(4f, 200f)))
    }

    fun changeCase(mode: TextCase) {
        val t = c.text
        c.text = if (hasSel) RichText.changeCase(t, mode, sel.min, sel.max) else RichText.changeCase(t, mode)
        if (c.text.length != t.length) c.style = c.style.copy(runs = RichText.shiftRuns(c.style.runs, t, c.text))
    }

    fun clearRuns() { c.style = c.style.copy(runs = emptyList()) }

    // ---------------- move / resize (smart alignment) ----------------

    fun drag(move: Boolean, ddx: Float, ddy: Float) {
        val b = c.block ?: return
        val m = c.model ?: return
        if (move) { dx += ddx; dy += ddy } else dw = (dw ?: 0f) + ddx
        // snapping guides: page centre, margins, other blocks' edges
        val tol = 4f
        val left = b.rect.left + dx; val right = b.rect.right + dx + (dw ?: 0f); val top = b.rect.top + dy
        val xs = ArrayList<Float>().apply {
            add(36f); add(m.width - 36f); add(m.width / 2f)
            m.blocks.filter { it.id != b.id }.forEach { add(it.rect.left); add(it.rect.right) }
        }
        val ys = ArrayList<Float>().apply { add(m.height - 36f); add(m.height / 2f); m.blocks.filter { it.id != b.id }.forEach { add(it.rect.top) } }
        val g = ArrayList<Pair<Boolean, Float>>()
        if (move) {
            xs.firstOrNull { abs(it - left) < tol }?.let { dx += it - left; g += true to it }
                ?: xs.firstOrNull { abs(it - (left + right) / 2f) < tol }?.let { dx += it - (left + right) / 2f; g += true to it }
                ?: xs.firstOrNull { abs(it - right) < tol }?.let { dx += it - right; g += true to it }
            ys.firstOrNull { abs(it - top) < tol }?.let { dy += it - top; g += false to it }
        } else xs.firstOrNull { abs(it - right) < tol }?.let { dw = (dw ?: 0f) + it - right; g += true to it }
        guides = g
    }

    fun dragEnd() { guides = emptyList() }

    // ---------------- commits ----------------

    /**
     * Commit of an existing block that needs [TextEdit5] (rotated or form text, a moved / resized box, a linked box).
     * Returns false when the plain [com.smartdocs.editor.core.edit.ContentEdit] path should be used.
     */
    fun commitBlock(b: TextBlock, text: String, st: EditTextStyle, done: Int): Boolean {
        val f = c.state.file ?: return false
        val p = c.page; val pw = c.state.password
        val link = TextLinks.target(ctx, TextLinks.docKey(f), p, b.rect)
        val mx = dx; val my = dy; val mw = dw
        when {
            link != null && text.isNotBlank() && st.list == ListKind.NONE -> c.runNve(done) { src, out ->
                TextEdit5.flowLinked(ctx, src, out, pw, p, b.ops, text, st, b.rect.top - b.rect.bottom + 2f, link)
            }
            b.angle != 0f || b.inForm || movePending -> c.runNve(done) { src, out ->
                TextEdit5.replace(ctx, src, out, pw, p, b.ops, text, st, mx, my, mw)
            }
            else -> return false
        }
        return true
    }

    fun delete(b: TextBlock): Boolean {
        if (!b.inForm) return false
        val p = c.page; val pw = c.state.password
        c.runNve(R.string.ed_deleted) { src, out -> TextEdit5.delete(src, out, pw, p, b.ops) }
        return true
    }

    /** A tap while a pick mode is on (join with / link to). True when consumed. */
    fun onTap(m: PageModel, x: Float, y: Float): Boolean {
        if (pick == Pick.NONE) return false
        val src = pickSource
        val mode = pick
        pick = Pick.NONE; pickSource = null
        if (src == null) return true
        val hit = m.blocks.filter { x in it.rect.left..it.rect.right && y in it.rect.bottom..it.rect.top }.minByOrNull { it.rect.width() * (it.rect.top - it.rect.bottom) }
        val pw = c.state.password
        when (mode) {
            Pick.JOIN_SAME, Pick.JOIN_LINE -> {
                val (sp, a) = src
                if (hit == null || sp != c.page || hit.ops.contentEquals(a.ops)) { c.message = ctx.getString(R.string.nt_pick_none); return true }
                val st = EditTextStyle(size = a.size, color = a.color, bold = a.bold, italic = a.italic, align = a.align, family = a.family)
                c.runNve(R.string.nt_joined) { s, o -> TextEdit5.join(ctx, s, o, pw, sp, a.ops, hit.ops, if (mode == Pick.JOIN_SAME) " " else "\n", st) }
            }
            Pick.LINK -> {
                val f = c.state.file ?: return true
                val (sp, a) = src
                val r = hit?.rect ?: RectF(x, y, x + a.rect.width(), y - (a.rect.top - a.rect.bottom))
                TextLinks.set(ctx, TextLinks.docKey(f), sp, a.rect, LinkTarget(c.page, r.left, r.top, r.right, r.bottom))
                c.message = ctx.getString(R.string.nt_linked, c.page + 1)
            }
            Pick.NONE -> {}
        }
        return true
    }

    fun startPick(mode: Pick) {
        val b = c.block ?: return
        pickSource = c.page to b
        pick = mode
        sheet = null
        c.clearSelection()
        c.message = ctx.getString(if (mode == Pick.LINK) R.string.nt_pick_link else R.string.nt_pick_join)
    }

    fun unlink() { val b = c.block ?: return; val f = c.state.file ?: return; TextLinks.set(ctx, TextLinks.docKey(f), c.page, b.rect, null); c.message = ctx.getString(R.string.nt_unlinked) }
    fun linked(): LinkTarget? { val b = c.block ?: return null; val f = c.state.file ?: return null; return TextLinks.target(ctx, TextLinks.docKey(f), c.page, b.rect) }

    fun split() {
        val b = c.block ?: return
        val at = sel.min
        if (at <= 0 || at >= c.text.length) { c.message = ctx.getString(R.string.nt_split_hint); return }
        val p = c.page; val pw = c.state.password; val t = c.text; val st = c.style
        sheet = null
        c.runNve(R.string.nt_split_done) { s, o -> TextEdit5.split(ctx, s, o, pw, p, b.ops, t, at, st) }
    }

    fun removeKerning() {
        val b = c.block ?: return
        val p = c.page; val pw = c.state.password
        sheet = null
        c.runNve(R.string.nt_kerning_done) { s, o -> TextEdit5.removeKerning(s, o, pw, p, b.ops) }
    }

    fun toOutlines() {
        val b = c.block ?: return
        val p = c.page; val pw = c.state.password
        sheet = null
        c.runNve(R.string.nt_outlines_done) { s, o -> TextEdit5.toOutlines(s, o, pw, p, b.ops) }
    }

    /** Rewrite the block with a standard (system) font, nothing embedded for Latin text. */
    fun toSystemFont() {
        val b = c.block ?: return
        val p = c.page; val pw = c.state.password; val t = c.text; val st = c.style.copy(embed = FontEmbed.SYSTEM, fontPath = null)
        sheet = null
        c.runNve(R.string.ed_saved) { s, o -> TextEdit5.replace(ctx, s, o, pw, p, b.ops, t, st) }
    }

    fun reflow(text: String, st: EditTextStyle) {
        val p = c.page; val pw = c.state.password
        sheet = null
        c.runNve(R.string.ed_saved) { s, o -> TextEdit5.reflowPage(ctx, s, o, pw, p, text, st) }
    }

    /** Recognise the text of this (scanned) page as editable text (the app's OCR, editable mode), then reload. */
    fun ocrPage() {
        val p = c.page; val pw = c.state.password
        c.runNve(R.string.nt_ocr_done) { s, o ->
            val engine = com.smartdocs.editor.core.ocr.OcrEngine(ctx.applicationContext)
            try {
                com.smartdocs.editor.ui.ocr.runOcrToFile(ctx, engine, s, o, pw,
                    com.smartdocs.editor.ui.ocr.OcrOptions(listOf(p), com.smartdocs.editor.ui.ocr.OcrPrefs.languages(ctx), editable = true)) { _, _ -> }
            } finally { runCatching { engine.close() } }
        }
    }

    companion object {
        private val map = WeakHashMap<EditController, TextTools5>()
        fun of(c: EditController): TextTools5 = synchronized(map) { map.getOrPut(c) { TextTools5(c) } }
        fun peek(c: EditController): TextTools5? = synchronized(map) { map[c] }
    }
}

/** Rich preview of the inline field: per-run styles plus spelling underlines. [pxToSp]: point size → text unit. */
internal fun richTransformation(st: EditTextStyle, issues: List<SpellIssue>, pxToSp: (Float) -> TextUnit): VisualTransformation = VisualTransformation { s ->
    val b = AnnotatedString.Builder(s)
    val n = s.length
    if (st.runs.isNotEmpty() || st.underline || st.strike || st.script != Script.NONE) {
        val cs = RichText.styles(st, s.text)
        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n && cs[j] == cs[i]) j++
            val c = cs[i]
            val deco = listOfNotNull(TextDecoration.Underline.takeIf { c.underline }, TextDecoration.LineThrough.takeIf { c.strike })
            b.addStyle(SpanStyle(
                color = Color(0xFF000000 or c.color.toLong()),
                fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (c.italic) FontStyle.Italic else FontStyle.Normal,
                fontSize = pxToSp(c.drawSize),
                textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
                baselineShift = when (c.script) { Script.SUPER -> BaselineShift.Superscript; Script.SUB -> BaselineShift.Subscript; Script.NONE -> null },
            ), i, j)
            i = j
        }
    }
    issues.forEach { iss -> if (iss.end <= n) b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, background = Color(0x33D32F2F)), iss.start, iss.end) }
    TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
}

// ============================================================ UI

/** Extra buttons of the format bar (rich text toggles, font, spell check, more). */
@Composable
internal fun TextBarExtras5(c: EditController) {
    val t = TextTools5.of(c)
    val ctx = LocalContext.current
    @Composable fun Tog(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, a: RunAttr) {
        val on = t.isOn(a)
        if (on) FilledTonalIconButton(onClick = { t.toggle(a) }) { Icon(icon, stringResource(label)) }
        else IconButton(onClick = { t.toggle(a) }) { Icon(icon, stringResource(label)) }
    }
    Tog(Icons.Default.FormatUnderlined, R.string.nt_underline, RunAttr.UNDERLINE)
    Tog(Icons.Default.FormatStrikethrough, R.string.nt_strike, RunAttr.STRIKE)
    Tog(Icons.Default.Superscript, R.string.nt_superscript, RunAttr.SUPER)
    Tog(Icons.Default.Subscript, R.string.nt_subscript, RunAttr.SUB)
    TextButton(onClick = { t.sheet = TSheet.FONTS }) {
        Text(EditFonts.nameOf(ctx, c.style.fontPath) ?: stringResource(when (c.style.family) {
            FontFamily.SANS -> R.string.ed_family_sans; FontFamily.SERIF -> R.string.ed_family_serif; FontFamily.MONO -> R.string.ed_family_mono }), maxLines = 1)
    }
    com.smartdocs.editor.ui.nve.SpellCheckButton(c.text, onFixed = { fixed ->
        c.style = c.style.copy(runs = RichText.shiftRuns(c.style.runs, c.text, fixed)); c.text = fixed
    })
    IconButton(onClick = { t.sheet = TSheet.COLOR }) { Icon(Icons.Default.Palette, stringResource(R.string.nt_color_custom)) }
    IconButton(onClick = { t.sheet = TSheet.MORE }) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.nt_more)) }
}

/** Justify button (after the other alignment buttons). */
@Composable
internal fun JustifyButton5(c: EditController) {
    val on = c.style.align == com.smartdocs.editor.core.edit.TextAlign.JUSTIFY
    val act = { c.style = c.style.copy(align = com.smartdocs.editor.core.edit.TextAlign.JUSTIFY); c.alignTouched = true }
    if (on) FilledTonalIconButton(onClick = act) { Icon(Icons.Default.FormatAlignJustify, stringResource(R.string.nt_justify)) }
    else IconButton(onClick = act) { Icon(Icons.Default.FormatAlignJustify, stringResource(R.string.nt_justify)) }
}

/** Top bar actions of edit mode: reflow the page, OCR, preferences. */
@Composable
internal fun TextTopActions5(c: EditController) {
    val t = TextTools5.of(c)
    if (c.mode != EditMode.TEXT) return
    IconButton(onClick = { t.sheet = TSheet.REFLOW }, enabled = !c.busy && (c.model?.blocks?.isNotEmpty() == true)) { Icon(Icons.Default.Notes, stringResource(R.string.nt_reflow)) }
    IconButton(onClick = { t.sheet = TSheet.PREFS }) { Icon(Icons.Default.Tune, stringResource(R.string.nt_prefs)) }
}

/** "No editable text" bar with the OCR button (scanned pages). */
@Composable
internal fun NoTextBar5(c: EditController) {
    val m = c.model ?: return
    if (c.mode != EditMode.TEXT || c.editingText || m.blocks.isNotEmpty()) return
    val t = TextTools5.of(c)
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nt_no_text_ocr), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { t.ocrPage() }, enabled = !c.busy) { Icon(Icons.Default.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_ocr)) }
        }
    }
}

/** Sheets of the text tools, the "open on a point" request and the no-text alert. */
@Composable
internal fun TextTools5Host(c: EditController) {
    val t = TextTools5.of(c)
    val ctx = LocalContext.current
    // reader selection → "edit text" (NveEditRequest, set by the reader): select the block under the selection
    LaunchedEffect(c.model) {
        val m = c.model ?: return@LaunchedEffect
        val r = t.request ?: com.smartdocs.editor.ui.nve.NveEditRequest.take(c.state) ?: return@LaunchedEffect
        if (r.page != c.page) { t.request = r; c.goToPage(r.page); return@LaunchedEffect }
        t.request = null
        val rect = r.rect ?: return@LaunchedEffect
        val (x, y) = com.smartdocs.editor.core.edit.Affine.apply(com.smartdocs.editor.core.edit.Affine.fromValues(m.toDisplay), rect.centerX(), (rect.top + rect.bottom) / 2f)
        c.mode = EditMode.TEXT
        if (!c.editingText && m.blocks.any { x in it.rect.left - 2f..it.rect.right + 2f && y in it.rect.bottom - 2f..it.rect.top + 2f }) c.onTap(x, y)
    }
    LaunchedEffect(c.model) {
        val m = c.model ?: return@LaunchedEffect
        if (m.blocks.isEmpty() && c.mode == EditMode.TEXT && EditPrefs.alertNoText(ctx)) c.message = ctx.getString(R.string.nt_no_text_alert)
    }
    LaunchedEffect(c.block, c.newAt) { if (!c.editingText) t.reset() }
    when (t.sheet) {
        TSheet.MORE -> MoreSheet(c, t)
        TSheet.FONTS -> FontSheet(c, t)
        TSheet.COLOR -> ColorPickerDialog(c.style.color, onPick = { t.color(it); t.sheet = null }, onDismiss = { t.sheet = null })
        TSheet.STROKE_COLOR -> ColorPickerDialog(c.style.strokeColor, onPick = { c.style = c.style.copy(strokeColor = it, stroke = true); t.sheet = TSheet.MORE }, onDismiss = { t.sheet = TSheet.MORE })
        TSheet.PREFS -> PrefsSheet(c, t)
        TSheet.REFLOW -> ReflowSheet(c, t)
        null -> {}
    }
}

@Composable
private fun Label(id: Int) = Text(stringResource(id), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${fmt(value)}", style = MaterialTheme.typography.labelMedium)
        Slider(value, onChange, valueRange = range, modifier = Modifier.height(30.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoreSheet(c: EditController, t: TextTools5) {
    val st = c.style
    fun set(s: EditTextStyle) { c.style = s }
    ModalBottomSheet(onDismissRequest = { t.sheet = null }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.nt_more), style = MaterialTheme.typography.titleLarge)
            if (!t.sel.collapsed) Text(stringResource(R.string.nt_sel_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            // case
            Label(R.string.nt_case)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(TextCase.UPPER to R.string.nt_case_upper, TextCase.LOWER to R.string.nt_case_lower, TextCase.TITLE to R.string.nt_case_title, TextCase.SENTENCE to R.string.nt_case_sentence)
                    .forEach { (m, l) -> AssistChip(onClick = { t.changeCase(m) }, label = { Text(stringResource(l)) }) }
            }
            Label(R.string.nt_selection_size)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { t.selectionSize(-1f) }) { Icon(Icons.Default.TextDecrease, stringResource(R.string.pc_cd_smaller_text)) }
                IconButton(onClick = { t.selectionSize(1f) }) { Icon(Icons.Default.TextIncrease, stringResource(R.string.pc_cd_larger_text)) }
                if (st.runs.isNotEmpty()) TextButton(onClick = { t.clearRuns() }) { Text(stringResource(R.string.nt_clear_runs)) }
            }
            // spacing
            Label(R.string.nt_spacing)
            SliderRow(stringResource(R.string.nt_char_spacing), st.charSpacing, -2f..10f, { "%.1f".format(it) }) { set(st.copy(charSpacing = (it * 10).roundToInt() / 10f)) }
            SliderRow(stringResource(R.string.nt_word_spacing), st.wordSpacing, 0f..20f, { "%.1f".format(it) }) { set(st.copy(wordSpacing = (it * 10).roundToInt() / 10f)) }
            SliderRow(stringResource(R.string.nt_line_spacing), st.lineSpacing, 0.7f..3f, { "%.2f".format(it) }) { set(st.copy(lineSpacing = (it * 20).roundToInt() / 20f)) }
            SliderRow(stringResource(R.string.nt_para_spacing), st.paraSpacing, 0f..36f, { "%.0f".format(it) }) { set(st.copy(paraSpacing = it.roundToInt().toFloat())) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nt_indent, st.indent.roundToInt()), Modifier.weight(1f))
                IconButton(onClick = { set(st.copy(indent = (st.indent - 12f).coerceAtLeast(0f))) }) { Icon(Icons.Default.FormatIndentDecrease, stringResource(R.string.nt_indent_less)) }
                IconButton(onClick = { set(st.copy(indent = (st.indent + 12f).coerceAtMost(200f))) }) { Icon(Icons.Default.FormatIndentIncrease, stringResource(R.string.nt_indent_more)) }
            }
            SliderRow(stringResource(R.string.nt_hscale), st.hScale, 50f..200f, { "${it.roundToInt()}%" }) { set(st.copy(hScale = it.roundToInt().toFloat())) }
            SliderRow(stringResource(R.string.nt_rise), st.rise, -20f..20f, { "%.1f".format(it) }) { set(st.copy(rise = (it * 2).roundToInt() / 2f)) }
            // text mode
            Label(R.string.nt_text_mode)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!st.stroke && !st.invisible && st.fillText, { set(st.copy(stroke = false, invisible = false, fillText = true)) }, label = { Text(stringResource(R.string.nt_mode_fill)) })
                FilterChip(st.stroke && !st.fillText && !st.invisible, { set(st.copy(stroke = true, fillText = false, invisible = false)) }, label = { Text(stringResource(R.string.nt_mode_stroke)) })
                FilterChip(st.stroke && st.fillText && !st.invisible, { set(st.copy(stroke = true, fillText = true, invisible = false)) }, label = { Text(stringResource(R.string.nt_mode_fill_stroke)) })
                FilterChip(st.invisible, { set(st.copy(invisible = !st.invisible)) }, label = { Text(stringResource(R.string.nt_mode_invisible)) })
            }
            if (st.stroke) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nt_stroke_color), Modifier.weight(1f))
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000 or st.strokeColor.toLong())).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { t.sheet = TSheet.STROKE_COLOR })
                }
                SliderRow(stringResource(R.string.nt_stroke_width), st.strokeWidth, 0f..4f, { if (it == 0f) "auto" else "%.1f".format(it) }) { set(st.copy(strokeWidth = (it * 10).roundToInt() / 10f)) }
            }
            // direction
            Label(R.string.nt_direction)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(st.rtlOverride == null, { set(st.copy(rtlOverride = null)) }, label = { Text(stringResource(R.string.nt_dir_auto)) })
                FilterChip(st.rtlOverride == false, { set(st.copy(rtlOverride = false)) }, label = { Text(stringResource(R.string.nt_dir_ltr)) })
                FilterChip(st.rtlOverride == true, { set(st.copy(rtlOverride = true)) }, label = { Text(stringResource(R.string.nt_dir_rtl)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nt_vertical), Modifier.weight(1f))
                Switch(st.vertical, { set(st.copy(vertical = it)) })
            }
            // lists
            Label(R.string.nt_lists)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(st.list == ListKind.NONE, { set(st.copy(list = ListKind.NONE)) }, label = { Text(stringResource(R.string.nt_list_none)) })
                RichText.BULLETS.forEach { bch -> FilterChip(st.list == ListKind.BULLET && st.bullet == bch, { set(st.copy(list = ListKind.BULLET, bullet = bch)) }, label = { Text(bch) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberFormat.entries.forEach { nf -> FilterChip(st.list == ListKind.NUMBER && st.numbering == nf, { set(st.copy(list = ListKind.NUMBER, numbering = nf)) },
                    label = { Text(RichText.marker(ListKind.NUMBER, 1, format = nf).trim() + " " + RichText.marker(ListKind.NUMBER, 2, format = nf).trim()) }) }
            }
            // rotation
            Label(R.string.nt_rotation)
            var angle by remember(st.rotation) { mutableStateOf(st.rotation.roundToInt().toString()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(st.rotation, { set(st.copy(rotation = it.roundToInt().toFloat())) }, valueRange = -180f..180f, modifier = Modifier.weight(1f))
                OutlinedTextField(angle, { v -> angle = v.take(5); v.toFloatOrNull()?.let { set(st.copy(rotation = it.coerceIn(-360f, 360f))) } }, singleLine = true, modifier = Modifier.width(88.dp), suffix = { Text("\u00B0") })
            }
            // font embedding
            Label(R.string.nt_embedding)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(st.embed == FontEmbed.SUBSET, { set(st.copy(embed = FontEmbed.SUBSET)) }, label = { Text(stringResource(R.string.nt_embed_subset)) })
                FilterChip(st.embed == FontEmbed.FULL, { set(st.copy(embed = FontEmbed.FULL)) }, label = { Text(stringResource(R.string.nt_embed_full)) })
                FilterChip(st.embed == FontEmbed.SYSTEM, { set(st.copy(embed = FontEmbed.SYSTEM)) }, label = { Text(stringResource(R.string.nt_embed_system)) })
            }
            // object operations on an existing block
            if (c.block != null) {
                Label(R.string.nt_object)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { t.startPick(Pick.JOIN_SAME) }, label = { Text(stringResource(R.string.nt_join_same)) }, leadingIcon = { Icon(Icons.Default.MergeType, null) })
                    AssistChip(onClick = { t.startPick(Pick.JOIN_LINE) }, label = { Text(stringResource(R.string.nt_join_line)) })
                    AssistChip(onClick = { t.split() }, label = { Text(stringResource(R.string.nt_split)) }, leadingIcon = { Icon(Icons.Default.CallSplit, null) })
                    AssistChip(onClick = { t.removeKerning() }, label = { Text(stringResource(R.string.nt_kerning)) })
                    AssistChip(onClick = { t.toOutlines() }, label = { Text(stringResource(R.string.nt_outlines)) }, leadingIcon = { Icon(Icons.Default.Gesture, null) })
                    AssistChip(onClick = { t.toSystemFont() }, label = { Text(stringResource(R.string.nt_to_system_font)) })
                    if (t.linked() == null) AssistChip(onClick = { t.startPick(Pick.LINK) }, label = { Text(stringResource(R.string.nt_link)) }, leadingIcon = { Icon(Icons.Default.Link, null) })
                    else AssistChip(onClick = { t.unlink() }, label = { Text(stringResource(R.string.nt_unlink)) }, leadingIcon = { Icon(Icons.Default.LinkOff, null) })
                }
                Text(stringResource(R.string.nt_move_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSheet(c: EditController, t: TextTools5) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val faces = remember { EditFonts.list(ctx, EditPrefs.systemFonts(ctx)) }
    val shown = remember(q) { faces.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = { t.sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.nt_fonts), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.nt_font_search)) })
            LazyColumn(Modifier.weight(1f)) {
                item {
                    val b = c.block
                    if (b != null && b.fontReusable) ListItem(headlineContent = { Text(stringResource(R.string.nt_font_original, b.fontName)) },
                        modifier = Modifier.clickable { c.style = c.style.copy(fontPath = null, family = b.family, bold = b.bold, italic = b.italic); t.sheet = null })
                    listOf(FontFamily.SANS to R.string.ed_family_sans, FontFamily.SERIF to R.string.ed_family_serif, FontFamily.MONO to R.string.ed_family_mono).forEach { (f, l) ->
                        ListItem(headlineContent = { Text(stringResource(l), fontFamily = when (f) {
                            FontFamily.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif; FontFamily.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
                            FontFamily.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace }) },
                            supportingContent = { Text(stringResource(R.string.nt_font_standard)) },
                            trailingContent = { if (c.style.fontPath == null && c.style.family == f) Icon(Icons.Default.Check, null) },
                            modifier = Modifier.clickable { c.style = c.style.copy(fontPath = null, family = f); t.sheet = null })
                    }
                    HorizontalDivider()
                }
                items(shown, key = { it.id }) { face ->
                    val tf = remember(face.id) { EditFonts.typeface(ctx, face.id) }
                    ListItem(
                        headlineContent = { Text(face.name, fontFamily = tf?.let { androidx.compose.ui.text.font.FontFamily(it) }) },
                        supportingContent = { Text(stringResource(if (face.bundled) R.string.nt_font_bundled else R.string.nt_font_system) + if (face.arabic) " \u00B7 " + stringResource(R.string.nt_font_arabic) else "") },
                        trailingContent = { if (c.style.fontPath == face.id) Icon(Icons.Default.Check, null) },
                        modifier = Modifier.clickable { c.style = c.style.copy(fontPath = face.id); t.sheet = null },
                    )
                }
            }
        }
    }
}

/** Colour picker: hex, HSV sliders and CMYK input (converted to RGB). */
@Composable
internal fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) } }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF
    var hex by remember(rgb) { mutableStateOf("#%06X".format(rgb)) }
    var cmyk by remember { mutableStateOf("") }
    fun setRgb(c: Int) { val f = FloatArray(3); android.graphics.Color.colorToHSV(c or 0xFF000000.toInt(), f); h = f[0]; s = f[1]; v = f[2] }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nt_color_custom)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(36.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF000000 or rgb.toLong())))
                Text(stringResource(R.string.nt_hue, h.roundToInt()), style = MaterialTheme.typography.labelMedium); Slider(h, { h = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.nt_saturation, (s * 100).roundToInt()), style = MaterialTheme.typography.labelMedium); Slider(s, { s = it })
                Text(stringResource(R.string.nt_value, (v * 100).roundToInt()), style = MaterialTheme.typography.labelMedium); Slider(v, { v = it })
                OutlinedTextField(hex, { x -> hex = x.take(7); x.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { setRgb(it) } }, singleLine = true, label = { Text("HEX") })
                OutlinedTextField(cmyk, { x ->
                    cmyk = x
                    val p = x.split(',', ' ', ';').mapNotNull { it.trim().toFloatOrNull() }
                    if (p.size == 4) {
                        val (cc, mm, yy, kk) = p.map { (if (it > 1f) it / 100f else it).coerceIn(0f, 1f) }
                        val r = (255 * (1 - cc) * (1 - kk)).roundToInt(); val g = (255 * (1 - mm) * (1 - kk)).roundToInt(); val b = (255 * (1 - yy) * (1 - kk)).roundToInt()
                        setRgb((r shl 16) or (g shl 8) or b)
                    }
                }, singleLine = true, label = { Text(stringResource(R.string.nt_cmyk)) }, placeholder = { Text("0, 100, 100, 0") })
            }
        },
        confirmButton = { Button(onClick = { onPick(rgb) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrefsSheet(c: EditController, t: TextTools5) {
    val ctx = LocalContext.current
    var line by remember { mutableStateOf(EditPrefs.lineMode(ctx)) }
    var gap by remember { mutableFloatStateOf(EditPrefs.wordGap(ctx)) }
    var warn by remember { mutableStateOf(EditPrefs.warnFonts(ctx)) }
    var sys by remember { mutableStateOf(EditPrefs.systemFonts(ctx)) }
    var alert by remember { mutableStateOf(EditPrefs.alertNoText(ctx)) }
    var live by remember { mutableStateOf(EditPrefs.liveSpell(ctx)) }
    var digits by remember { mutableStateOf(NveDigits.mode(ctx)) }
    ModalBottomSheet(onDismissRequest = { t.sheet = null; c.reload() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.nt_prefs), style = MaterialTheme.typography.titleLarge)
            Label(R.string.nt_select_mode)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!line, { line = false; EditPrefs.set(ctx, "line_mode", false) }, label = { Text(stringResource(R.string.nt_select_paragraph)) })
                FilterChip(line, { line = true; EditPrefs.set(ctx, "line_mode", true) }, label = { Text(stringResource(R.string.nt_select_line)) })
            }
            SliderRow(stringResource(R.string.nt_word_distance), gap, 0.08f..0.6f, { "%.2f".format(it) }) { gap = it; EditPrefs.setFloat(ctx, "word_gap", it) }
            @Composable fun Sw(label: Int, on: Boolean, key: String, set: (Boolean) -> Unit) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.weight(1f)); Switch(on, { set(it); EditPrefs.set(ctx, key, it) })
                }
            }
            Sw(R.string.nt_pref_warn_fonts, warn, "warn_fonts") { warn = it }
            Sw(R.string.nt_pref_system_fonts, sys, "system_fonts") { sys = it }
            Sw(R.string.nt_pref_alert_no_text, alert, "alert_no_text") { alert = it }
            Sw(R.string.nt_pref_live_spell, live, "live_spell") { live = it }
            Label(R.string.nt_digits)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(NveDigits.Mode.AUTO to R.string.nt_digits_auto, NveDigits.Mode.LATIN to R.string.nt_digits_latin,
                    NveDigits.Mode.ARABIC_INDIC to R.string.nt_digits_arabic, NveDigits.Mode.EASTERN_ARABIC_INDIC to R.string.nt_digits_eastern).forEach { (m, l) ->
                    FilterChip(digits == m, { digits = m; NveDigits.setMode(ctx, m) }, label = { Text(stringResource(l)) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Reflow editing: the page's text in reading order as one editable text, written back as flowing text. */
@Composable
private fun ReflowSheet(c: EditController, t: TextTools5) {
    val m = c.model ?: run { t.sheet = null; return }
    val blocks = m.blocks.filter { it.angle == 0f && !it.unreadable }
    var text by remember { mutableStateOf(blocks.joinToString("\n") { it.text }) }
    val first = blocks.firstOrNull()
    AlertDialog(
        onDismissRequest = { t.sheet = null },
        title = { Text(stringResource(R.string.nt_reflow)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nt_reflow_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp))
                com.smartdocs.editor.ui.nve.SpellCheckButton(text, onFixed = { text = it })
            }
        },
        confirmButton = {
            Button(onClick = {
                val st = first?.let { EditTextStyle(size = it.size, color = it.color, bold = it.bold, italic = it.italic, align = it.align, family = it.family) } ?: EditTextStyle()
                t.reflow(NveDigits.apply(c.state.context, text), st)
            }, enabled = first != null && !c.busy) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = { t.sheet = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
