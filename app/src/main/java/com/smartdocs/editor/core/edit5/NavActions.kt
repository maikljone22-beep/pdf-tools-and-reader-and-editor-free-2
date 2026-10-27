package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * How a destination shows its page (ISO 32000 §12.3.2.2): [fit] with the parameters it uses. [rect] is the FitR
 * rectangle (left, bottom, right, top); FitR without one falls back to Fit. Null numbers = "keep the current value".
 */
data class DestView(val fit: DestFit = DestFit.XYZ, val left: Float? = null, val top: Float? = null, val zoom: Float = 0f, val rect: List<Float>? = null)

/** Helpers for explicit destination arrays `[page /XYZ l t z]`, `[page /FitH t]`, `[page /FitR l b r t]` … */
object Dests {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun num(v: Float?): COSBase = v?.let { COSFloat(it) } ?: COSNull.NULL

    /** Append the view part of a destination (after the page) to [a]. */
    fun appendView(a: COSArray, v: DestView) {
        when (v.fit) {
            DestFit.FIT -> a.add(COSName.getPDFName("Fit"))
            DestFit.FITB -> a.add(COSName.getPDFName("FitB"))
            DestFit.FITH -> { a.add(COSName.getPDFName("FitH")); a.add(num(v.top)) }
            DestFit.FITBH -> { a.add(COSName.getPDFName("FitBH")); a.add(num(v.top)) }
            DestFit.FITV -> { a.add(COSName.getPDFName("FitV")); a.add(num(v.left)) }
            DestFit.FITR -> {
                val r = v.rect?.takeIf { it.size == 4 }
                if (r == null) a.add(COSName.getPDFName("Fit"))
                else { a.add(COSName.getPDFName("FitR")); r.forEach { a.add(COSFloat(it)) } }
            }
            DestFit.XYZ -> { a.add(COSName.getPDFName("XYZ")); a.add(num(v.left)); a.add(num(v.top)); a.add(if (v.zoom > 0f) COSFloat(v.zoom) else COSNull.NULL) }
        }
    }

    fun array(pageRef: COSBase, v: DestView): COSArray = COSArray().apply { add(pageRef); appendView(this, v) }

    /** The view of an explicit destination array (element 0 is the page). */
    fun view(a: COSArray): DestView {
        fun n(i: Int) = (deref(a.getObject(i)) as? COSNumber)?.floatValue()
        return when (a.getName(1)) {
            "XYZ" -> DestView(DestFit.XYZ, n(2), n(3), n(4) ?: 0f)
            "FitH" -> DestView(DestFit.FITH, top = n(2))
            "FitBH" -> DestView(DestFit.FITBH, top = n(2))
            "FitV" -> DestView(DestFit.FITV, left = n(2))
            "FitBV" -> DestView(DestFit.FITV, left = n(2))
            "FitB" -> DestView(DestFit.FITB)
            "FitR" -> {
                val r = listOf(n(2), n(3), n(4), n(5))
                if (r.all { it != null }) DestView(DestFit.FITR, rect = r.map { it!! }) else DestView(DestFit.FIT)
            }
            else -> DestView(DestFit.FIT)
        }
    }
}

/**
 * One action of an action list (ISO 32000 §12.6). [Kept] is an action type we do not edit (form submit, media, …):
 * it is copied unchanged from position [Kept.index] of the original list when written.
 */
sealed class NavAction {
    data class GoTo(val page: Int, val view: DestView = DestView(), val named: String = "") : NavAction()
    data class Uri(val uri: String) : NavAction()
    data class GoToR(val file: String, val page: Int = 0, val named: String = "", val view: DestView = DestView(DestFit.FIT), val newWindow: Boolean = true) : NavAction()
    data class Launch(val file: String, val newWindow: Boolean = true) : NavAction()
    data class Named(val name: String) : NavAction()
    data class Js(val script: String) : NavAction()
    data class Kept(val type: String, val index: Int) : NavAction()
}

/** Reading and writing action lists (`/A` with `/Next` chains) of links, bookmarks and document triggers. */
object NavActions {
    /** Standard named actions (§12.6.4.11) plus the common Acrobat menu items viewers accept. */
    val NAMED = listOf("NextPage", "PrevPage", "FirstPage", "LastPage", "GoBack", "GoForward", "Print", "Find", "FullScreen", "SaveAs", "Close", "ZoomViewIn", "ZoomViewOut")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** The action dictionaries of the list starting at [head], in execution order (each followed by its /Next). */
    fun flatten(head: COSBase?): List<COSDictionary> {
        val out = ArrayList<COSDictionary>()
        val seen = HashSet<COSDictionary>()
        fun walk(b: COSBase?, depth: Int) {
            if (depth > 16 || out.size > 64) return
            when (val v = deref(b)) {
                is COSDictionary -> if (seen.add(v)) { out += v; walk(v.getDictionaryObject(COSName.NEXT), depth + 1) }
                is COSArray -> for (i in 0 until v.size()) walk(v.getObject(i), depth + 1)
                else -> Unit
            }
        }
        walk(head, 0)
        return out
    }

    fun jsText(b: COSBase?): String? = when (val j = deref(b)) {
        is COSString -> j.string
        is COSStream -> runCatching { j.toTextString() }.getOrNull()
        else -> null
    }

    private fun fileName(f: COSBase?): String = when (val v = deref(f)) {
        is COSString -> v.string
        is COSDictionary -> v.getString(COSName.UF) ?: v.getString(COSName.F) ?: ""
        else -> ""
    }

    /** Page index of a page reference (dictionary) or a plain number (remote destinations). */
    private fun pageOf(doc: PDDocument?, b: COSBase?): Int {
        val v = deref(b)
        if (v is COSNumber) return v.intValue()
        if (v is COSDictionary && doc != null) return runCatching { doc.pages.indexOf(com.tom_roush.pdfbox.pdmodel.PDPage(v)) }.getOrDefault(-1)
        return -1
    }

    /** Decode one action dictionary. */
    fun decode(doc: PDDocument?, a: COSDictionary, index: Int): NavAction {
        val s = a.getNameAsString(COSName.S) ?: ""
        return when (s) {
            "URI" -> NavAction.Uri(a.getString(COSName.URI) ?: "")
            "GoTo" -> when (val d = deref(a.getDictionaryObject(COSName.D))) {
                is COSArray -> NavAction.GoTo(pageOf(doc, d.getObject(0)), Dests.view(d))
                is COSString -> NavAction.GoTo(-1, named = d.string)
                is COSName -> NavAction.GoTo(-1, named = d.name)
                else -> NavAction.Kept(s, index)
            }
            "GoToR" -> {
                val d = deref(a.getDictionaryObject(COSName.D))
                NavAction.GoToR(fileName(a.getDictionaryObject(COSName.F)),
                    page = (d as? COSArray)?.let { pageOf(null, it.getObject(0)) }?.coerceAtLeast(0) ?: 0,
                    named = (d as? COSString)?.string ?: (d as? COSName)?.name ?: "",
                    view = (d as? COSArray)?.let { Dests.view(it) } ?: DestView(DestFit.FIT),
                    newWindow = a.getBoolean(COSName.getPDFName("NewWindow"), true))
            }
            "Launch" -> NavAction.Launch(fileName(a.getDictionaryObject(COSName.F)), a.getBoolean(COSName.getPDFName("NewWindow"), true))
            "Named" -> NavAction.Named(a.getNameAsString(COSName.N) ?: "")
            "JavaScript" -> jsText(a.getDictionaryObject(COSName.JS))?.let { NavAction.Js(it) } ?: NavAction.Kept(s, index)
            else -> NavAction.Kept(s.ifBlank { "?" }, index)
        }
    }

    /** The whole list starting at [head] (an /A value). */
    fun read(doc: PDDocument?, head: COSBase?): List<NavAction> = flatten(head).mapIndexed { i, d -> decode(doc, d, i) }

    private fun fileSpec(name: String): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FILESPEC); setString(COSName.F, name); setString(COSName.UF, name)
    }

    private fun relative(target: String, docFile: File?): String {
        if (docFile == null) return target
        val t = File(target)
        return if (t.parentFile?.absolutePath == docFile.absoluteFile.parentFile?.absolutePath) t.name else target
    }

    /** Build one action dictionary ([original] = the flattened original list, for [NavAction.Kept]). */
    fun build(doc: PDDocument, a: NavAction, original: List<COSDictionary>, docFile: File?): COSDictionary? {
        fun act(s: String) = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("Action")); setName(COSName.S, s) }
        return when (a) {
            is NavAction.Uri -> act("URI").apply { setString(COSName.URI, a.uri) }
            is NavAction.GoTo -> act("GoTo").apply {
                if (a.named.isNotBlank()) setItem(COSName.D, COSString(a.named))
                else { if (doc.numberOfPages == 0) return null; setItem(COSName.D, Dests.array(doc.getPage(a.page.coerceIn(0, doc.numberOfPages - 1)).cosObject, a.view)) }
            }
            is NavAction.GoToR -> act("GoToR").apply {
                setItem(COSName.F, fileSpec(relative(a.file, docFile)))
                setItem(COSName.D, if (a.named.isNotBlank()) COSString(a.named) else Dests.array(COSInteger.get(a.page.coerceAtLeast(0).toLong()), a.view))
                setBoolean(COSName.getPDFName("NewWindow"), a.newWindow)
            }
            is NavAction.Launch -> act("Launch").apply { setItem(COSName.F, fileSpec(relative(a.file, docFile))); setBoolean(COSName.getPDFName("NewWindow"), a.newWindow) }
            is NavAction.Named -> act("Named").apply { setName(COSName.N, a.name) }
            is NavAction.Js -> act("JavaScript").apply { setString(COSName.JS, a.script) }
            is NavAction.Kept -> original.getOrNull(a.index)?.let { o -> COSDictionary(o).apply { removeItem(COSName.NEXT) } }
        }
    }

    /**
     * Write [list] as one action list: the first action, the others in its /Next array. Null when nothing
     * could be built. [originalHead] is the /A the list was read from (for kept actions).
     */
    fun write(doc: PDDocument, list: List<NavAction>, originalHead: COSBase?, docFile: File? = null): COSDictionary? {
        val orig = flatten(originalHead)
        val built = list.mapNotNull { build(doc, it, orig, docFile) }
        if (built.isEmpty()) return null
        val head = built.first()
        if (built.size > 1) head.setItem(COSName.NEXT, COSArray().apply { built.drop(1).forEach { add(it) } })
        else head.removeItem(COSName.NEXT)
        return head
    }

    // ---------------- print / extract page actions (Nitro "Print pages" / "Extract pages") ----------------

    /** JavaScript that prints pages [from]..[to] (1-based) with the print dialog. */
    fun printScript(from: Int, to: Int) = "this.print({bUI: true, nStart: ${from - 1}, nEnd: ${to - 1}});"

    /** JavaScript that extracts pages [from]..[to] (1-based) into a new document. */
    fun extractScript(from: Int, to: Int) = "this.extractPages({nStart: ${from - 1}, nEnd: ${to - 1}});"

    private val PAGE_SCRIPT = Regex("""^\s*this\.(print|extractPages)\(\{\s*(?:bUI:\s*true,\s*)?nStart:\s*(\d+),\s*nEnd:\s*(\d+)\s*\}\);\s*$""")

    /** ("print" | "extract", from, to) for a script made by [printScript] / [extractScript]. */
    fun pageScript(script: String): Triple<String, Int, Int>? {
        val m = PAGE_SCRIPT.find(script) ?: return null
        val kind = if (m.groupValues[1] == "print") "print" else "extract"
        return Triple(kind, m.groupValues[2].toInt() + 1, m.groupValues[3].toInt() + 1)
    }
}
