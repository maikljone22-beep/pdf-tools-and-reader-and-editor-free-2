package com.smartdocs.editor.core.security

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.IdentityHashMap

/** Kinds of actions the inspector reports (internal GoTo jumps and named page navigation are harmless and skipped). */
enum class ActionKind { JAVASCRIPT, LAUNCH, URI, SUBMIT_FORM, IMPORT_DATA, REMOTE_GOTO, MEDIA }

/** Where an action sits. Automatic places run without a click: on open, document events, page open / close. */
enum class ActionPlace(val automatic: Boolean) {
    OPEN_ACTION(true), DOCUMENT_EVENTS(true), DOCUMENT_SCRIPTS(true), PAGE_EVENTS(true), LINKS(false), FIELDS(false)
}

data class ActionFinding(val kind: ActionKind, val place: ActionPlace)

/** What a document can do on its own (the action-inspector banner). Counts are per (kind, place). */
data class ActionFindings(val counts: Map<ActionFinding, Int>) {
    val total: Int get() = counts.values.sum()
    fun count(kind: ActionKind): Int = counts.filterKeys { it.kind == kind }.values.sum()
    fun count(place: ActionPlace): Int = counts.filterKeys { it.place == place }.values.sum()
    val hasJavaScript: Boolean get() = count(ActionKind.JAVASCRIPT) > 0
    val hasOpenAction: Boolean get() = count(ActionPlace.OPEN_ACTION) > 0

    /**
     * Worth a banner: any script, any file launch, or anything that fires on its own (open action, document or page
     * events). Plain web links that only work when tapped do not raise it (they go through Internet access).
     */
    val needsBanner: Boolean get() = counts.keys.any { it.kind == ActionKind.JAVASCRIPT || it.kind == ActionKind.LAUNCH || it.place.automatic }

    companion object { val EMPTY = ActionFindings(emptyMap()) }
}

/**
 * Scans a document for JavaScript and actions that leave it: catalog /OpenAction and /AA, the /Names /JavaScript
 * tree, page /AA, annotation /A and /AA (links, widgets) and AcroForm field /AA, following /Next chains.
 */
object ActionInspector {
    private val NEXT = COSName.getPDFName("Next")
    private val JAVASCRIPT = COSName.getPDFName("JavaScript")

    fun scan(doc: PDDocument): ActionFindings {
        val counts = LinkedHashMap<ActionFinding, Int>()
        val seen: MutableSet<COSBase> = java.util.Collections.newSetFromMap(IdentityHashMap())
        fun add(k: ActionKind, p: ActionPlace) { val f = ActionFinding(k, p); counts[f] = (counts[f] ?: 0) + 1 }
        fun action(base: COSBase?, place: ActionPlace, depth: Int = 0) {
            val d = deref(base) as? COSDictionary ?: return
            if (depth > 32 || !seen.add(d)) return
            kindOf(d.getNameAsString(COSName.S))?.let { add(it, place) }
            when (val n = deref(d.getDictionaryObject(NEXT))) {
                is COSArray -> for (i in 0 until n.size()) action(n.getObject(i), place, depth + 1)
                is COSDictionary -> action(n, place, depth + 1)
                else -> {}
            }
        }
        fun events(aa: COSBase?, place: ActionPlace) {
            val d = deref(aa) as? COSDictionary ?: return
            for (k in d.keySet()) action(d.getDictionaryObject(k), place)
        }
        val cat = doc.documentCatalog.cosObject
        // /OpenAction is either an action dictionary or a destination array (a plain jump: harmless)
        (deref(cat.getDictionaryObject(COSName.OPEN_ACTION)) as? COSDictionary)?.let { action(it, ActionPlace.OPEN_ACTION) }
        events(cat.getDictionaryObject(COSName.AA), ActionPlace.DOCUMENT_EVENTS)
        (deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary)?.let { names ->
            (deref(names.getDictionaryObject(JAVASCRIPT)) as? COSDictionary)?.let { tree -> nameTreeValues(tree) { action(it, ActionPlace.DOCUMENT_SCRIPTS) } }
        }
        for (page in doc.pages) {
            events(page.cosObject.getDictionaryObject(COSName.AA), ActionPlace.PAGE_EVENTS)
            val annots = deref(page.cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray ?: continue
            for (i in 0 until annots.size()) {
                val a = deref(annots.getObject(i)) as? COSDictionary ?: continue
                val widget = a.getNameAsString(COSName.SUBTYPE) == "Widget"
                action(a.getDictionaryObject(COSName.A), if (widget) ActionPlace.FIELDS else ActionPlace.LINKS)
                events(a.getDictionaryObject(COSName.AA), ActionPlace.FIELDS)
            }
        }
        // field-level /AA (calculate, format, keystroke, validate) of non-merged fields
        val form = runCatching { doc.documentCatalog.getAcroForm(null) }.getOrNull()
        if (form != null) runCatching { form.fieldTree.forEach { f -> events(f.cosObject.getDictionaryObject(COSName.AA), ActionPlace.FIELDS) } }
        return ActionFindings(counts)
    }

    fun kindOf(s: String?): ActionKind? = when (s) {
        "JavaScript" -> ActionKind.JAVASCRIPT
        "Launch" -> ActionKind.LAUNCH
        "URI" -> ActionKind.URI
        "SubmitForm" -> ActionKind.SUBMIT_FORM
        "ImportData" -> ActionKind.IMPORT_DATA
        "GoToR", "GoToE" -> ActionKind.REMOTE_GOTO
        "Rendition", "Movie", "Sound", "RichMediaExecute" -> ActionKind.MEDIA
        else -> null
    }

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    private fun nameTreeValues(node: COSDictionary, depth: Int = 0, onValue: (COSBase?) -> Unit) {
        if (depth > 32) return
        (deref(node.getDictionaryObject(COSName.NAMES)) as? COSArray)?.let { arr -> var i = 1; while (i < arr.size()) { onValue(arr.getObject(i)); i += 2 } }
        (deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray)?.let { kids ->
            for (i in 0 until kids.size()) (deref(kids.getObject(i)) as? COSDictionary)?.let { nameTreeValues(it, depth + 1, onValue) }
        }
    }
}
