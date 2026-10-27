package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * Action dictionaries (PDF 32000 12.6) for the designer's Actions tab: build a trigger's list as one action with a
 * /Next chain, and read an existing chain back into [FxAction]s. Unknown actions survive as [FxAction.Raw].
 */
object FxActions {

    const val SUBMIT_EXCLUDE = 1
    const val SUBMIT_INCLUDE_NO_VALUE = 2
    const val SUBMIT_HTML = 4
    const val SUBMIT_GET = 8
    const val SUBMIT_XFDF = 32
    const val SUBMIT_INCLUDE_ANNOTS = 128
    const val SUBMIT_PDF = 256
    const val SUBMIT_CANONICAL = 512

    private fun n(s: String) = COSName.getPDFName(s)
    private val NEXT = n("Next")
    private val FIELDS = n("Fields")
    private val FLAGS = n("Flags")
    private val URI = n("URI")
    private val H = n("H")

    private fun base(subtype: String) = COSDictionary().apply { setItem(COSName.TYPE, n("Action")); setItem(COSName.S, n(subtype)) }

    private fun names(list: List<String>) = COSArray().apply { list.forEach { add(COSString(it)) } }

    private fun fileSpec(url: String) = COSDictionary().apply { setItem(n("FS"), n("URL")); setString(COSName.F, url) }

    /** Optional content groups of [doc]: (dictionary, name). */
    fun ocgs(doc: PDDocument?): List<Pair<COSDictionary, String>> {
        val props = doc?.documentCatalog?.cosObject?.getDictionaryObject(n("OCProperties")) as? COSDictionary ?: return emptyList()
        val arr = props.getDictionaryObject(n("OCGs")) as? COSArray ?: return emptyList()
        return (0 until arr.size()).mapNotNull { i -> (arr.getObject(i) as? COSDictionary)?.let { it to (it.getString(COSName.NAME) ?: "Layer ${i + 1}") } }
    }

    /** One action dictionary (without /Next). */
    fun build(doc: PDDocument?, a: FxAction): COSDictionary = when (a) {
        is FxAction.GoToPage -> base("GoTo").apply {
            val dest = COSArray()
            val page = doc?.takeIf { a.page in 0 until it.numberOfPages }?.getPage(a.page)
            if (page != null) dest.add(page.cosObject) else dest.add(COSInteger.get(a.page.toLong()))
            if (a.zoom != null) { dest.add(n("XYZ")); dest.add(COSNull.NULL); dest.add(COSNull.NULL); dest.add(COSFloat(a.zoom)) } else dest.add(n("Fit"))
            setItem(COSName.D, dest)
        }
        is FxAction.GoToNamed -> base("GoTo").apply { setString(COSName.D, a.dest) }
        is FxAction.GoToRemote -> base("GoToR").apply {
            setString(COSName.F, a.file)
            setItem(COSName.D, COSArray().apply { add(COSInteger.get(a.page.toLong())); add(n("Fit")) })
        }
        is FxAction.OpenUrl -> base("URI").apply { setString(URI, a.url) }
        is FxAction.Submit -> base("SubmitForm").apply {
            setItem(COSName.F, fileSpec(a.url))
            if (a.fields.isNotEmpty()) setItem(FIELDS, names(a.fields))
            var f = 0
            if (a.exclude && a.fields.isNotEmpty()) f = f or SUBMIT_EXCLUDE
            if (a.includeEmpty) f = f or SUBMIT_INCLUDE_NO_VALUE
            when (a.format) {
                FxSubmitFormat.HTML -> { f = f or SUBMIT_HTML; if (a.get) f = f or SUBMIT_GET }
                FxSubmitFormat.XFDF -> f = f or SUBMIT_XFDF
                FxSubmitFormat.PDF -> f = f or SUBMIT_PDF
                FxSubmitFormat.FDF -> if (a.includeAnnots) f = f or SUBMIT_INCLUDE_ANNOTS
            }
            if (a.canonicalDates) f = f or SUBMIT_CANONICAL
            setInt(FLAGS, f)
        }
        is FxAction.Reset -> base("ResetForm").apply {
            if (a.fields.isNotEmpty()) { setItem(FIELDS, names(a.fields)); if (a.exclude) setInt(FLAGS, 1) }
        }
        is FxAction.ShowHide -> base("Hide").apply {
            setItem(COSName.T, if (a.fields.size == 1) COSString(a.fields[0]) else names(a.fields))
            setBoolean(H, a.hide)
        }
        is FxAction.JavaScript -> base("JavaScript").apply { setItem(COSName.JS, COSString(a.code)) }
        is FxAction.Named -> base("Named").apply { setItem(COSName.N, n(a.name)) }
        is FxAction.ImportData -> base("ImportData").apply { setString(COSName.F, a.file) }
        is FxAction.SetLayers -> base("SetOCGState").apply {
            val groups = ocgs(doc)
            val arr = COSArray()
            a.states.groupBy { it.second }.forEach { (state, l) ->
                arr.add(n(if (state.equals("toggle", true)) "Toggle" else state.uppercase()))
                l.forEach { (layer, _) -> groups.firstOrNull { it.second == layer }?.let { arr.add(it.first) } }
            }
            setItem(n("State"), arr)
        }
        is FxAction.ReadArticle -> base("Thread").apply {
            val threads = doc?.documentCatalog?.cosObject?.getDictionaryObject(n("Threads")) as? COSArray
            val t = threads?.getObject(a.thread) as? COSDictionary
            if (t != null) setItem(COSName.D, t) else setInt(COSName.D, a.thread)
        }
        is FxAction.Raw -> ((a.cos as? COSDictionary)?.let { d -> COSDictionary().also { c -> c.addAll(d); c.removeItem(NEXT) } }) ?: base(a.subtype)
    }

    /** The list as one dictionary whose /Next chain holds the rest; null for an empty list. */
    fun chain(doc: PDDocument?, list: List<FxAction>): COSDictionary? {
        if (list.isEmpty()) return null
        val dicts = list.map { build(doc, it) }
        for (i in 0 until dicts.size - 1) dicts[i].setItem(NEXT, dicts[i + 1])
        return dicts.first()
    }

    private fun str(b: COSBase?): String? = when (b) {
        is COSString -> b.string
        is COSName -> b.name
        is COSStream -> runCatching { b.toTextString() }.getOrNull()
        else -> null
    }

    private fun strList(b: COSBase?): List<String> = when (b) {
        is COSArray -> (0 until b.size()).mapNotNull { i ->
            when (val o = b.getObject(i)) {
                is COSString -> o.string
                is COSDictionary -> o.getString(COSName.T)   // field dictionary reference
                else -> null
            }
        }
        is COSString -> listOf(b.string)
        else -> emptyList()
    }

    private fun fileOf(b: COSBase?): String = when (b) {
        is COSString -> b.string
        is COSDictionary -> b.getString(COSName.UF) ?: b.getString(COSName.F) ?: ""
        else -> ""
    }

    /** Read one action (without following /Next). */
    fun read(doc: PDDocument?, d: COSDictionary): FxAction {
        val s = d.getCOSName(COSName.S)?.name ?: ""
        return when (s) {
            "GoTo" -> when (val dest = d.getDictionaryObject(COSName.D)) {
                is COSString -> FxAction.GoToNamed(dest.string)
                is COSName -> FxAction.GoToNamed(dest.name)
                is COSArray -> {
                    val p = dest.getObject(0)
                    val page = when (p) {
                        is COSDictionary -> doc?.pages?.indexOf(com.tom_roush.pdfbox.pdmodel.PDPage(p)) ?: -1
                        is COSNumber -> p.intValue()
                        else -> -1
                    }
                    val zoom = if ((dest.getObject(1) as? COSName)?.name == "XYZ") (dest.getObject(4) as? COSNumber)?.floatValue() else null
                    if (page >= 0) FxAction.GoToPage(page, zoom) else FxAction.Raw(s, d)
                }
                else -> FxAction.Raw(s, d)
            }
            "GoToR" -> FxAction.GoToRemote(fileOf(d.getDictionaryObject(COSName.F)),
                ((d.getDictionaryObject(COSName.D) as? COSArray)?.getObject(0) as? COSNumber)?.intValue() ?: 0)
            "URI" -> FxAction.OpenUrl(d.getString(URI) ?: "")
            "SubmitForm" -> {
                val f = d.getInt(FLAGS, 0)
                val fmt = when {
                    f and SUBMIT_PDF != 0 -> FxSubmitFormat.PDF
                    f and SUBMIT_XFDF != 0 -> FxSubmitFormat.XFDF
                    f and SUBMIT_HTML != 0 -> FxSubmitFormat.HTML
                    else -> FxSubmitFormat.FDF
                }
                FxAction.Submit(fileOf(d.getDictionaryObject(COSName.F)), fmt, strList(d.getDictionaryObject(FIELDS)), f and SUBMIT_EXCLUDE != 0,
                    f and SUBMIT_INCLUDE_NO_VALUE != 0, f and SUBMIT_CANONICAL != 0, f and SUBMIT_INCLUDE_ANNOTS != 0, f and SUBMIT_GET != 0)
            }
            "ResetForm" -> FxAction.Reset(strList(d.getDictionaryObject(FIELDS)), d.getInt(FLAGS, 0) and 1 != 0)
            "Hide" -> FxAction.ShowHide(strList(d.getDictionaryObject(COSName.T)), (d.getDictionaryObject(H) as? COSBoolean)?.value ?: true)
            "JavaScript" -> FxAction.JavaScript(str(d.getDictionaryObject(COSName.JS)) ?: "")
            "Named" -> FxAction.Named(d.getCOSName(COSName.N)?.name ?: "")
            "ImportData" -> FxAction.ImportData(fileOf(d.getDictionaryObject(COSName.F)))
            "SetOCGState" -> {
                val groups = ocgs(doc)
                val arr = d.getDictionaryObject(n("State")) as? COSArray ?: return FxAction.Raw(s, d)
                var state = "ON"; val out = ArrayList<Pair<String, String>>()
                for (i in 0 until arr.size()) {
                    when (val o = arr.getObject(i)) {
                        is COSName -> state = o.name
                        is COSDictionary -> groups.firstOrNull { it.first === o }?.let { out += it.second to state } ?: out.add((o.getString(COSName.NAME) ?: "?") to state)
                        else -> {}
                    }
                }
                FxAction.SetLayers(out)
            }
            "Thread" -> {
                val threads = doc?.documentCatalog?.cosObject?.getDictionaryObject(n("Threads")) as? COSArray
                when (val dd = d.getDictionaryObject(COSName.D)) {
                    is COSNumber -> FxAction.ReadArticle(dd.intValue())
                    is COSDictionary -> FxAction.ReadArticle((0 until (threads?.size() ?: 0)).firstOrNull { threads!!.getObject(it) === dd } ?: 0)
                    else -> FxAction.Raw(s, d)
                }
            }
            else -> FxAction.Raw(s, d)
        }
    }

    /** Read the whole /Next chain (a dictionary or an array of dictionaries at each step), loop safe. */
    fun readChain(doc: PDDocument?, first: COSBase?): List<FxAction> {
        val out = ArrayList<FxAction>()
        val seen = HashSet<COSDictionary>()
        fun walk(b: COSBase?, depth: Int) {
            if (depth > 32 || out.size > 64) return
            when (b) {
                is COSDictionary -> {
                    if (!seen.add(b)) return
                    out += read(doc, b)
                    walk(b.getDictionaryObject(NEXT), depth + 1)
                }
                is COSArray -> for (i in 0 until b.size()) walk(b.getObject(i), depth + 1)
                else -> {}
            }
        }
        walk(first, 0)
        return out
    }

    /**
     * Write [actions] onto a widget / field dictionary: Mouse Up goes into /A (Acrobat's place for it), the other
     * triggers into /AA. Triggers missing from the map are cleared.
     */
    fun writeTriggers(doc: PDDocument?, widget: COSDictionary, actions: Map<FxTrigger, List<FxAction>>) {
        val aa = (widget.getDictionaryObject(COSName.AA) as? COSDictionary) ?: COSDictionary()
        widget.removeItem(COSName.A)
        aa.removeItem(n("U"))
        for (t in FxTrigger.entries) {
            val c = chain(doc, actions[t].orEmpty())
            if (t == FxTrigger.MOUSE_UP) { if (c != null) widget.setItem(COSName.A, c) }
            else if (c != null) aa.setItem(n(t.key), c) else aa.removeItem(n(t.key))
        }
        if (aa.size() > 0) widget.setItem(COSName.AA, aa) else widget.removeItem(COSName.AA)
    }

    /** Triggers of a widget: /A (and /AA /U) are Mouse Up. */
    fun readTriggers(doc: PDDocument?, widget: COSDictionary): Map<FxTrigger, List<FxAction>> {
        val out = LinkedHashMap<FxTrigger, List<FxAction>>()
        val aa = widget.getDictionaryObject(COSName.AA) as? COSDictionary
        val up = readChain(doc, widget.getDictionaryObject(COSName.A)) + readChain(doc, aa?.getDictionaryObject(n("U")))
        if (up.isNotEmpty()) out[FxTrigger.MOUSE_UP] = up
        for (t in FxTrigger.entries) if (t != FxTrigger.MOUSE_UP) {
            val l = readChain(doc, aa?.getDictionaryObject(n(t.key)))
            if (l.isNotEmpty()) out[t] = l
        }
        return out
    }
}
