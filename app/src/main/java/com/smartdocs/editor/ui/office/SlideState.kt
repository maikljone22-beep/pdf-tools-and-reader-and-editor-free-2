package com.smartdocs.editor.ui.office

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.smartdocs.editor.core.office5.*
import java.io.File

/** UI state of the presentation editor: current slide, selected shape, in-place text editing, slide show. */
class SlideState(val doc: PptDoc, override val file: File) : OfficeHost {
    override val ext = "pptx"
    override var tick by mutableIntStateOf(0); private set
    override val modified: Boolean get() = doc.modified
    override val canUndo: Boolean get() = doc.canUndo
    override val canRedo: Boolean get() = doc.canRedo

    var current by mutableIntStateOf(0)
    var selected by mutableStateOf<XElem?>(null)
    var editingText by mutableStateOf(false)
    var text by mutableStateOf(TextFieldValue(""))
    var showFrom by mutableStateOf<Int?>(null)
    var thumbs by mutableStateOf(true)

    private val cache = HashMap<Int, Pair<Int, PSlide>>()

    val count: Int get() { tick; return doc.slideCount }
    val size: Pair<Long, Long> get() = doc.slideSize

    fun slide(i: Int): PSlide {
        cache[i]?.let { (v, s) -> if (v == doc.version) return s }
        return PptView.build(doc, i).also { cache[i] = doc.version to it }
    }

    fun bump() { tick++ }

    fun go(i: Int) { if (editingText) commitText(); current = i.coerceIn(0, (doc.slideCount - 1).coerceAtLeast(0)); selected = null; bump() }

    fun select(e: XElem?) { if (editingText) commitText(); selected = e; bump() }

    fun selectedShape(): PShape? { tick; val e = selected ?: return null; return slide(current).shapes.firstOrNull { it.elem === e } }

    // ---------------- text ----------------

    fun beginText() {
        val sh = selectedShape() ?: return
        if (!sh.editableText) return
        val t = doc.shapeText(sh.elem!!)
        text = TextFieldValue(t, TextRange(t.length)); editingText = true; bump()
    }

    fun commitText() {
        val e = selected
        editingText = false
        if (e != null && doc.shapeText(e) != text.text) doc.changeSlide(current) { doc.setShapeText(e, text.text) }
        bump()
    }

    fun formatText(mutate: (XElem) -> Unit) {
        val e = selected ?: return
        if (editingText) commitText()
        doc.changeSlide(current) { doc.formatShapeText(e, mutate) }; bump()
    }

    /** Effective run formatting of the first run of the selection (for toggle states). */
    fun firstRun(): PRun? = selectedShape()?.paragraphs?.firstNotNullOfOrNull { p -> p.runs.firstOrNull { it.text.isNotEmpty() } }

    fun align(algn: String, rtl: Boolean? = null) { val e = selected ?: return; if (editingText) commitText(); doc.changeSlide(current) { doc.setParagraphAlign(e, algn, rtl) }; bump() }

    // ---------------- shapes ----------------

    /** Live move/resize while dragging: the first call of a drag is one undo step. */
    fun setBounds(e: XElem, x: Long, y: Long, w: Long, h: Long, first: Boolean) {
        if (first) doc.changeSlide(current) { doc.setBounds(e, x, y, w, h) } else doc.continueSlide(current) { doc.setBounds(e, x, y, w, h) }
        bump()
    }

    private fun box(): LongArray { val (w, h) = size; return longArrayOf(w / 4, h / 3, w / 2, h / 6) }

    fun addTextBox(initial: String) {
        val b = box()
        val e = doc.changeSlide(current) { doc.addTextBox(current, b[0], b[1], b[2], b[3], initial, rtl = true) }
        selected = e; bump(); beginText()
    }

    fun addShape(prst: String) {
        val (w, h) = size
        val side = minOf(w, h) / 4
        val e = doc.changeSlide(current) { doc.addShape(current, prst, (w - side) / 2, (h - side) / 2, if (prst == "line") side * 2 else side, side, "4472C4", if (prst == "line") "1F3864" else "2F528F") }
        selected = e; bump()
    }

    fun addImage(bytes: ByteArray) {
        val (w, h) = size
        val e = doc.changeSlide(current) { doc.addImage(current, bytes, w * 3 / 4, h * 3 / 4) }
        selected = e; bump()
    }

    fun deleteShape() { val e = selected ?: return; editingText = false; doc.changeSlide(current) { doc.deleteShape(current, e) }; selected = null; bump() }
    fun reorderShape(delta: Int) { val e = selected ?: return; doc.changeSlide(current) { doc.reorderShape(current, e, delta) }; bump() }
    fun fill(hex: String?) { val e = selected ?: return; doc.changeSlide(current) { doc.setFill(e, hex) }; bump() }
    fun line(hex: String?) { val e = selected ?: return; doc.changeSlide(current) { doc.setLine(e, hex) }; bump() }

    // ---------------- slides ----------------

    fun newSlide() { val i = doc.change { doc.newSlide(current) }; go(i) }
    fun duplicate() { val i = doc.change { doc.duplicateSlide(current) }; if (i >= 0) go(i) }
    fun deleteSlide() { if (doc.change { doc.deleteSlide(current) }) go(current.coerceAtMost(doc.slideCount - 1)) }
    fun moveSlide(delta: Int) { val to = current + delta; if (to in 0 until doc.slideCount) { doc.change { doc.moveSlide(current, to) }; go(to) } }
    fun transition(kind: String?, all: Boolean) { doc.change { if (all) (0 until doc.slideCount).forEach { doc.setTransition(it, kind) } else doc.setTransition(current, kind) }; bump() }
    fun background(hex: String) { doc.change { doc.setBackground(current, hex) }; bump() }

    // ---------------- host ----------------

    override fun undo() { editingText = false; if (doc.undo()) { selected = null; current = current.coerceAtMost(doc.slideCount - 1) }; bump() }
    override fun redo() { editingText = false; if (doc.redo()) { selected = null; current = current.coerceAtMost(doc.slideCount - 1) }; bump() }
    override fun commitPending() { if (editingText) commitText() }

    override fun saveTo(target: File) {
        doc.pkg.saveTo(target)
        if (target.absolutePath == file.absolutePath) { doc.modified = false; tick++ }
    }
}
