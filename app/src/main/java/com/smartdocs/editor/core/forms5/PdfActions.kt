package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination

/**
 * A PDF action of a form widget or field (PDF 32000 12.6.4), already resolved against the document where that
 * is possible (destination page index, field names). Used at fill time by the in-page form layer.
 */
sealed class PdfAction {
    data class JavaScript(val code: String) : PdfAction()
    data class Submit(val submit: ButtonAction.Submit, val includeAnnotations: Boolean = false, val canonicalDates: Boolean = false) : PdfAction()
    data class Reset(val reset: ButtonAction.Reset) : PdfAction()
    data class Uri(val uri: String) : PdfAction()
    /** Go to a page of this document (zero-based), from an explicit or named destination; -1 = unresolved. */
    data class GoTo(val page: Int, val named: String? = null) : PdfAction()
    /** Go to a page of another PDF file ([page] zero-based, or -1 with a [named] destination). */
    data class GoToR(val file: String, val page: Int, val named: String?, val newWindow: Boolean) : PdfAction()
    data class Launch(val file: String) : PdfAction()
    /** Hide ([hide] = true) or show the listed fields (fully qualified names). */
    data class Hide(val fields: List<String>, val hide: Boolean) : PdfAction()
    /** Named action (NextPage, PrevPage, FirstPage, LastPage, Print, ...). */
    data class Named(val name: String) : PdfAction()
    /** Import field values from an FDF / XFDF file named by the action (relative names are looked up next to the PDF). */
    data class ImportData(val file: String) : PdfAction()
    /** SetOCGState: ordered (state, OCG names) pairs; state is ON, OFF or Toggle. */
    data class SetOcg(val ops: List<Pair<String, List<String>>>, val preserveRB: Boolean) : PdfAction()
    data class Unsupported(val type: String) : PdfAction()
}

/** Field / widget event triggers of /AA (PDF 32000 table 194) plus Mouse Up through /A. */
enum class Trigger(val key: String, val jsName: String) {
    MOUSE_DOWN("D", "Mouse Down"), MOUSE_UP("U", "Mouse Up"), ENTER("E", "Mouse Enter"), EXIT("X", "Mouse Exit"),
    FOCUS("Fo", "Focus"), BLUR("Bl", "Blur");
}

object PdfActions {

    private val NEXT = COSName.getPDFName("Next")
    private val NEW_WINDOW = COSName.getPDFName("NewWindow")
    private val STATE = COSName.getPDFName("State")
    private val PRESERVE_RB = COSName.getPDFName("PreserveRB")

    const val MAX_CHAIN = 64

    /**
     * The action [base] and every action of its /Next chain (a dictionary or an array, depth first, as a viewer
     * runs them), resolved against [doc]. Cycles and very long chains are cut at [MAX_CHAIN].
     */
    fun chain(base: COSBase?, doc: PDDocument?): List<PdfAction> {
        val out = ArrayList<PdfAction>()
        val seen = HashSet<COSDictionary>()
        fun walk(b: COSBase?) {
            if (out.size >= MAX_CHAIN) return
            when (val v = deref(b)) {
                is COSDictionary -> {
                    if (!seen.add(v)) return
                    out += parseOne(v, doc)
                    walk(v.getDictionaryObject(NEXT))
                }
                is COSArray -> for (i in 0 until v.size()) walk(v.getObject(i))
                else -> {}
            }
        }
        walk(base)
        return out
    }

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** One action dictionary (without its /Next chain). */
    fun parseOne(a: COSDictionary, doc: PDDocument?): PdfAction {
        val type = a.getCOSName(COSName.S)?.name ?: return PdfAction.Unsupported("")
        return when (type) {
            "JavaScript" -> PdfAction.JavaScript(jsText(a.getDictionaryObject(COSName.JS)).orEmpty())
            "SubmitForm" -> {
                val flags = a.getInt(COSName.FLAGS, 0)
                PdfAction.Submit(FormSubmit.fromFlags(fileName(a.getDictionaryObject(COSName.F)), flags, fieldNames(a.getDictionaryObject(COSName.FIELDS))),
                    includeAnnotations = flags and FormSubmit.FLAG_INCLUDE_ANNOTS != 0, canonicalDates = flags and FormSubmit.FLAG_CANONICAL != 0)
            }
            "ResetForm" -> PdfAction.Reset(ButtonAction.Reset(fieldNames(a.getDictionaryObject(COSName.FIELDS)), a.getInt(COSName.FLAGS, 0) and 1 != 0))
            "URI" -> PdfAction.Uri(a.getString(COSName.URI).orEmpty())
            "GoTo" -> dest(doc, a.getDictionaryObject(COSName.D)).let { (p, n) -> PdfAction.GoTo(p, n) }
            "GoToR" -> {
                val d = deref(a.getDictionaryObject(COSName.D))
                val (page, named) = when (d) {
                    is COSArray -> ((deref(d.getObject(0)) as? COSInteger)?.intValue() ?: 0) to null
                    is COSString -> -1 to d.string
                    is COSName -> -1 to d.name
                    else -> 0 to null
                }
                PdfAction.GoToR(fileName(a.getDictionaryObject(COSName.F)), page, named, a.getBoolean(NEW_WINDOW, false))
            }
            "Launch" -> PdfAction.Launch(fileName(a.getDictionaryObject(COSName.F)))
            "Hide" -> {
                val t = deref(a.getDictionaryObject(COSName.T))
                val names = when (t) {
                    is COSArray -> fieldNames(t)
                    is COSString -> listOf(t.string)
                    is COSDictionary -> listOfNotNull(fqName(t))
                    else -> emptyList()
                }
                val h = a.getDictionaryObject(COSName.H)
                PdfAction.Hide(names, if (h is COSBoolean) h.value else true)
            }
            "Named" -> PdfAction.Named(a.getNameAsString(COSName.N).orEmpty())
            "ImportData" -> PdfAction.ImportData(fileName(a.getDictionaryObject(COSName.F)))
            "SetOCGState" -> {
                val st = deref(a.getDictionaryObject(STATE)) as? COSArray
                val ops = ArrayList<Pair<String, MutableList<String>>>()
                if (st != null) for (i in 0 until st.size()) {
                    when (val o = deref(st.getObject(i))) {
                        is COSName -> ops += o.name to ArrayList()
                        is COSDictionary -> ops.lastOrNull()?.second?.add(o.getString(COSName.NAME) ?: "")
                        else -> {}
                    }
                }
                PdfAction.SetOcg(ops.map { it.first to it.second.toList() }, a.getBoolean(PRESERVE_RB, true))
            }
            "Thread" -> PdfAction.GoTo(threadPage(doc, a), null)
            else -> PdfAction.Unsupported(type)
        }
    }

    /**
     * Read-article action (/S /Thread): the page of the bead /B when given, else of the first bead of the
     * thread /D (a thread dictionary, an index into the catalog /Threads or a thread title). -1 when unresolved.
     */
    fun threadPage(doc: PDDocument?, a: COSDictionary): Int {
        fun beadPage(bead: COSDictionary?): Int {
            val pg = deref(bead?.getDictionaryObject(COSName.P)) as? COSDictionary ?: return -1
            return doc?.pages?.indexOf(PDPage(pg)) ?: -1
        }
        (deref(a.getDictionaryObject(COSName.getPDFName("B"))) as? COSDictionary)?.let { b -> beadPage(b).takeIf { it >= 0 }?.let { return it } }
        val threads = doc?.documentCatalog?.cosObject?.getDictionaryObject(COSName.getPDFName("Threads")) as? COSArray
        val thread: COSDictionary? = when (val d = deref(a.getDictionaryObject(COSName.D))) {
            is COSDictionary -> d
            is COSInteger -> threads?.getObject(d.intValue()) as? COSDictionary
            is COSString -> threads?.let { t ->
                (0 until t.size()).mapNotNull { t.getObject(it) as? COSDictionary }
                    .firstOrNull { th -> (th.getDictionaryObject(COSName.getPDFName("I")) as? COSDictionary)?.getString(COSName.getPDFName("Title")) == d.string }
            }
            else -> null
        }
        return beadPage(deref(thread?.getDictionaryObject(COSName.F)) as? COSDictionary)
    }

    /** Destination -> (zero-based page or -1, destination name). */
    fun dest(doc: PDDocument?, d: COSBase?): Pair<Int, String?> {
        val v = deref(d)
        val name = when (v) { is COSString -> v.string; is COSName -> v.name; else -> null }
        if (name != null) {
            val p = runCatching { doc?.documentCatalog?.findNamedDestinationPage(PDNamedDestination(name))?.let { pageOf(doc, it) } }.getOrNull() ?: -1
            return p to name
        }
        if (v is COSArray) {
            val pd = runCatching { PDDestination.create(v) as? PDPageDestination }.getOrNull()
            val p = pd?.let { pageOf(doc, it) } ?: ((deref(v.getObject(0)) as? COSInteger)?.intValue() ?: -1)
            return p to null
        }
        return -1 to null
    }

    private fun pageOf(doc: PDDocument?, pd: PDPageDestination): Int {
        val page: PDPage? = pd.page
        if (page != null && doc != null) {
            val i = doc.pages.indexOf(page)
            if (i >= 0) return i
        }
        return pd.pageNumber.takeIf { it >= 0 } ?: -1
    }

    fun jsText(j: COSBase?): String? = when (val v = deref(j)) {
        is COSString -> v.string
        is COSStream -> runCatching { v.toTextString() }.getOrNull()
        else -> null
    }

    fun fileName(f: COSBase?): String = when (val v = deref(f)) {
        is COSString -> v.string
        is COSDictionary -> v.getString(COSName.UF) ?: v.getString(COSName.F) ?: ""
        else -> ""
    }

    fun fieldNames(base: COSBase?): List<String> {
        val arr = deref(base) as? COSArray ?: return emptyList()
        return (0 until arr.size()).mapNotNull { i ->
            when (val o = deref(arr.getObject(i))) {
                is COSString -> o.string
                is COSDictionary -> fqName(o)
                else -> null
            }
        }
    }

    fun fqName(d: COSDictionary): String? {
        val parts = ArrayList<String>(); var cur: COSDictionary? = d; var guard = 0
        while (cur != null && guard++ < 32) { cur.getString(COSName.T)?.let { parts += it }; cur = cur.getDictionaryObject(COSName.PARENT) as? COSDictionary }
        return parts.reversed().joinToString(".").ifEmpty { null }
    }

    /**
     * Actions per trigger of one widget: the widget's /AA (falling back to the field's /AA for merged
     * field-widgets or parents), with /A added in front of Mouse Up as viewers run it.
     */
    fun triggers(widget: COSDictionary, field: COSDictionary?, doc: PDDocument?): Map<Trigger, List<PdfAction>> {
        val out = LinkedHashMap<Trigger, List<PdfAction>>()
        val waa = deref(widget.getDictionaryObject(COSName.AA)) as? COSDictionary
        val faa = field?.takeIf { it !== widget }?.let { deref(it.getDictionaryObject(COSName.AA)) as? COSDictionary }
        for (t in Trigger.values()) {
            val key = COSName.getPDFName(t.key)
            val list = ArrayList<PdfAction>()
            if (t == Trigger.MOUSE_UP) {
                val a = widget.getDictionaryObject(COSName.A) ?: field?.getDictionaryObject(COSName.A)
                list += chain(a, doc)
            }
            list += chain(waa?.getDictionaryObject(key) ?: faa?.getDictionaryObject(key), doc)
            if (list.isNotEmpty()) out[t] = list
        }
        return out
    }

    /** True when running [list] needs the user's consent first (it sends data, opens something or leaves the file). */
    fun needsConfirm(list: List<PdfAction>): Boolean = list.any {
        it is PdfAction.Submit || it is PdfAction.Uri || it is PdfAction.Reset || it is PdfAction.Launch || it is PdfAction.GoToR || it is PdfAction.ImportData
    }

    /** First action that needs consent (its text is shown in the confirmation). */
    fun firstConfirm(list: List<PdfAction>): PdfAction? = list.firstOrNull { needsConfirm(listOf(it)) }
}
