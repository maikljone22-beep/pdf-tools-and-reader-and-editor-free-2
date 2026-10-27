package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField

/**
 * Every JavaScript of a document and how to change it (Acrobat's "Document JavaScripts", "Document Actions",
 * "Edit All JavaScripts", page / bookmark / link actions). A script is addressed by a [locator] string:
 *
 *  - `named:<name>`               document-level script (/Names /JavaScript)
 *  - `doc:<WC|WS|DS|WP|DP>`       document action (catalog /AA)
 *  - `open`                       catalog /OpenAction
 *  - `page:<i>:<O|C>`             page open / close action (page /AA)
 *  - `field:<name>:<K|F|V|C>`     field keystroke / format / validate / calculate
 *  - `widget:<page>:<annot>:<A|D|U|E|X|Fo|Bl>`  widget mouse-up (/A) or trigger (/AA)
 *  - `outline:<i>`                bookmark (depth-first index) action
 *  - `link:<page>:<annot>`        link annotation action
 *
 * [setCode] writes a JavaScript action there (a blank code removes it). Pure PdfBox, no Android types.
 */
object DocScripts {

    data class Script(val locator: String, val kind: String, val label: String, val page: Int, val code: String)

    private val JAVASCRIPT = COSName.getPDFName("JavaScript")
    private val OUTLINES = COSName.getPDFName("Outlines")
    private val FIRST = COSName.getPDFName("First")
    private val NEXT = COSName.getPDFName("Next")
    private val OPEN_ACTION = COSName.getPDFName("OpenAction")
    private val TITLE = COSName.getPDFName("Title")

    val DOC_ACTIONS = listOf("WC", "WS", "DS", "WP", "DP")
    val FIELD_EVENTS = listOf("K", "F", "V", "C")
    val WIDGET_EVENTS = listOf("A", "D", "U", "E", "X", "Fo", "Bl")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    fun jsAction(code: String): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("Action"))
        setItem(COSName.S, JAVASCRIPT)
        setItem(COSName.JS, COSString(code))
    }

    /** JavaScript of an action (and its /Next chain), joined; null when it has none. */
    private fun code(a: COSBase?): String? =
        PdfActions.chain(a, null).filterIsInstance<PdfAction.JavaScript>().map { it.code }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("\n")

    // ------------------------------------------------------------------ document-level (name tree)

    /** (name, code) of the /Names /JavaScript tree in name order. */
    fun named(doc: PDDocument): List<Pair<String, String>> {
        val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        fun walk(node: COSDictionary?, depth: Int) {
            if (node == null || depth > 12) return
            (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
                var i = 0
                while (i + 1 < arr.size()) {
                    val n = (deref(arr.getObject(i)) as? COSString)?.string ?: ""
                    out += n to (code(arr.getObject(i + 1)) ?: "")
                    i += 2
                }
            }
            (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) walk(kids.getObject(k) as? COSDictionary, depth + 1) }
        }
        walk(names.getDictionaryObject(JAVASCRIPT) as? COSDictionary, 0)
        return out
    }

    /** Rewrite the document-level scripts as one sorted name-tree leaf (empty list removes the tree). */
    fun setNamed(doc: PDDocument, scripts: List<Pair<String, String>>) {
        val cat = doc.documentCatalog.cosObject
        val names = (cat.getDictionaryObject(COSName.NAMES) as? COSDictionary) ?: COSDictionary().also { cat.setItem(COSName.NAMES, it) }
        val clean = scripts.filter { it.first.isNotBlank() }.distinctBy { it.first }.sortedBy { it.first }
        if (clean.isEmpty()) { names.removeItem(JAVASCRIPT); return }
        val arr = COSArray()
        clean.forEach { (n, c) -> arr.add(COSString(n)); arr.add(jsAction(c)) }
        names.setItem(JAVASCRIPT, COSDictionary().apply { setItem(COSName.NAMES, arr) })
    }

    /** Add or replace one document-level script. */
    fun putNamed(doc: PDDocument, name: String, code: String) {
        setNamed(doc, named(doc).filter { it.first != name } + (name to code))
    }

    fun removeNamed(doc: PDDocument, name: String) { setNamed(doc, named(doc).filter { it.first != name }) }

    // ------------------------------------------------------------------ list

    fun listAll(doc: PDDocument): List<Script> {
        val out = ArrayList<Script>()
        named(doc).forEach { (n, c) -> out += Script("named:$n", "named", n, -1, c) }
        val cat = doc.documentCatalog.cosObject
        (deref(cat.getDictionaryObject(COSName.AA)) as? COSDictionary)?.let { aa ->
            DOC_ACTIONS.forEach { k -> code(aa.getDictionaryObject(COSName.getPDFName(k)))?.let { out += Script("doc:$k", "doc", k, -1, it) } }
        }
        code(cat.getDictionaryObject(OPEN_ACTION))?.let { out += Script("open", "open", "OpenAction", -1, it) }
        doc.pages.forEachIndexed { pi, page ->
            (deref(page.cosObject.getDictionaryObject(COSName.AA)) as? COSDictionary)?.let { aa ->
                listOf("O", "C").forEach { k -> code(aa.getDictionaryObject(COSName.getPDFName(k)))?.let { out += Script("page:$pi:$k", "page", k, pi, it) } }
            }
            val annots = deref(page.cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray ?: return@forEachIndexed
            for (ai in 0 until annots.size()) {
                val an = deref(annots.getObject(ai)) as? COSDictionary ?: continue
                when (an.getNameAsString(COSName.SUBTYPE)) {
                    "Link" -> code(an.getDictionaryObject(COSName.A))?.let { out += Script("link:$pi:$ai", "link", "Link ${ai + 1}", pi, it) }
                    "Widget" -> {
                        val label = fieldName(an) ?: "Widget ${ai + 1}"
                        code(an.getDictionaryObject(COSName.A))?.let { out += Script("widget:$pi:$ai:A", "widget", "$label (A)", pi, it) }
                        (deref(an.getDictionaryObject(COSName.AA)) as? COSDictionary)?.let { aa ->
                            WIDGET_EVENTS.drop(1).forEach { k -> code(aa.getDictionaryObject(COSName.getPDFName(k)))?.let { out += Script("widget:$pi:$ai:$k", "widget", "$label ($k)", pi, it) } }
                        }
                    }
                }
            }
        }
        doc.documentCatalog.acroForm?.fieldTree?.forEach { f ->
            if (f !is PDTerminalField) return@forEach
            val name = f.fullyQualifiedName ?: return@forEach
            val aa = deref(f.cosObject.getDictionaryObject(COSName.AA)) as? COSDictionary ?: return@forEach
            FIELD_EVENTS.forEach { k -> code(aa.getDictionaryObject(COSName.getPDFName(k)))?.let { out += Script("field:$name:$k", "field", "$name ($k)", -1, it) } }
        }
        outlines(doc).forEachIndexed { i, o -> code(o.getDictionaryObject(COSName.A))?.let { out += Script("outline:$i", "outline", o.getString(TITLE) ?: "#$i", -1, it) } }
        return out
    }

    private fun fieldName(w: COSDictionary): String? = PdfActions.fqName(w)

    /** Bookmark dictionaries depth first. */
    fun outlines(doc: PDDocument): List<COSDictionary> {
        val root = deref(doc.documentCatalog.cosObject.getDictionaryObject(OUTLINES)) as? COSDictionary ?: return emptyList()
        val out = ArrayList<COSDictionary>()
        val seen = HashSet<COSDictionary>()
        fun walk(first: COSDictionary?, depth: Int) {
            var cur = first
            while (cur != null && seen.add(cur) && out.size < 5000 && depth < 40) {
                out += cur
                walk(deref(cur.getDictionaryObject(FIRST)) as? COSDictionary, depth + 1)
                cur = deref(cur.getDictionaryObject(NEXT)) as? COSDictionary
            }
        }
        walk(deref(root.getDictionaryObject(FIRST)) as? COSDictionary, 0)
        return out
    }

    // ------------------------------------------------------------------ write

    private fun setIn(holder: COSDictionary, key: COSName, code: String) {
        if (code.isBlank()) holder.removeItem(key) else holder.setItem(key, jsAction(code))
    }

    private fun aaOf(d: COSDictionary, create: Boolean): COSDictionary? =
        (deref(d.getDictionaryObject(COSName.AA)) as? COSDictionary) ?: if (create) COSDictionary().also { d.setItem(COSName.AA, it) } else null

    /** Write [code] as the JavaScript at [locator] (blank removes it). Returns false when the target does not exist. */
    fun setCode(doc: PDDocument, locator: String, code: String): Boolean {
        val parts = locator.split(':')
        val cat = doc.documentCatalog.cosObject
        when (parts[0]) {
            "named" -> { val n = locator.substringAfter(':'); if (code.isBlank()) removeNamed(doc, n) else putNamed(doc, n, code) }
            "doc" -> { val k = parts.getOrNull(1)?.takeIf { it in DOC_ACTIONS } ?: return false; setIn(aaOf(cat, true)!!, COSName.getPDFName(k), code) }
            "open" -> setIn(cat, OPEN_ACTION, code)
            "page" -> {
                val pi = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until doc.numberOfPages } ?: return false
                val k = parts.getOrNull(2)?.takeIf { it == "O" || it == "C" } ?: return false
                setIn(aaOf(doc.getPage(pi).cosObject, true)!!, COSName.getPDFName(k), code)
            }
            "field" -> {
                val k = parts.last().takeIf { it in FIELD_EVENTS } ?: return false
                val name = locator.removePrefix("field:").removeSuffix(":$k")
                val f = doc.documentCatalog.acroForm?.getField(name) ?: return false
                setIn(aaOf(f.cosObject, true)!!, COSName.getPDFName(k), code)
            }
            "widget", "link" -> {
                val pi = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until doc.numberOfPages } ?: return false
                val ai = parts.getOrNull(2)?.toIntOrNull() ?: return false
                val annots = deref(doc.getPage(pi).cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray ?: return false
                val an = deref(annots.getObject(ai)) as? COSDictionary ?: return false
                val k = if (parts[0] == "link") "A" else parts.getOrNull(3)?.takeIf { it in WIDGET_EVENTS } ?: return false
                if (k == "A") setIn(an, COSName.A, code) else setIn(aaOf(an, true)!!, COSName.getPDFName(k), code)
            }
            "outline" -> {
                val i = parts.getOrNull(1)?.toIntOrNull() ?: return false
                val o = outlines(doc).getOrNull(i) ?: return false
                if (code.isBlank()) o.removeItem(COSName.A) else { o.removeItem(COSName.DEST); o.setItem(COSName.A, jsAction(code)) }
            }
            else -> return false
        }
        return true
    }

    /** Current code at [locator] ("" when none). */
    fun codeAt(doc: PDDocument, locator: String): String = listAll(doc).firstOrNull { it.locator == locator }?.code.orEmpty()
}
