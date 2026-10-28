package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.formsx.FieldSpec
import com.smartdocs.editor.core.formsx.FxEditor
import com.smartdocs.editor.core.formsx.FxRect
import com.smartdocs.editor.core.formsx.FxSigLock
import com.smartdocs.editor.core.formsx.FxSigLocks
import com.smartdocs.editor.core.formsx.FxType
import com.smartdocs.editor.core.formsx.FxWidget
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedGuard
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.cos.COSUpdateInfo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 8.84 / 27.10: document changes a form's JavaScript asks for while filling, written when the form is saved:
 * `this.addScript` (a document-level script), `field.setLock` (the SigFieldLock of a signature field, which the
 * signing code turns into FieldMDP), `this.addField` (a new field through the form designer's writer),
 * `this.removeField` (the field and its widgets are deleted), `field.setAction` (the field's / widgets' trigger
 * scripts) and `field.buttonSetIcon` with an icon that is not a picture (copied from another button / a named icon).
 *
 * On a signed file each change is judged by [SignedGuard] ([split]): the allowed ones are appended as an
 * incremental update ([appendTo]), the others go into a copy the reader offers to save.
 */
data class ScriptDocChanges(
    val scripts: Map<String, String> = emptyMap(),
    val locks: Map<String, FxSigLock> = emptyMap(),
    val fields: List<NewField> = emptyList(),
    val removed: List<String> = emptyList(),
    /** Field name -> PDF trigger key ([triggerKey]) -> script ("" removes the action). */
    val actions: Map<String, Map<String, String>> = emptyMap(),
    /** Target push button -> where its new icon comes from. */
    val icons: Map<String, IconSrc> = emptyMap(),
) {
    data class NewField(val name: String, val type: FxType, val page: Int, val rect: FxRect)

    /** An icon of `buttonGetIcon` (another button [field]) or `getIcon` (a named icon [named] of /Names /AP). */
    data class IconSrc(val field: String = "", val named: String = "")

    val isEmpty: Boolean get() = scripts.isEmpty() && locks.isEmpty() && fields.isEmpty() && removed.isEmpty() && actions.isEmpty() && icons.isEmpty()

    /**
     * Splits into (changes [allowed] on a signed file, the rest). Each change is judged as the signed gate sees it:
     * scripts and actions change the document's behaviour (MODIFY), a lock prepares a signature field (FORM_FILL),
     * new / removed fields and button icons are annotation-level (ANNOTATE). Locking or removing a field that
     * already holds a signature ([signedFields]) is never allowed in place.
     */
    fun split(signedFields: Set<String>, allowed: (SignedGuard.Change) -> Boolean): Pair<ScriptDocChanges, ScriptDocChanges> {
        fun signed(n: String) = signedFields.any { it == n || it.startsWith("$n.") }
        val annotOk = allowed(SignedGuard.Change.ANNOTATE)
        val modifyOk = allowed(SignedGuard.Change.MODIFY)
        val lockOk = locks.filterKeys { !signed(it) && allowed(SignedGuard.Change.FORM_FILL) }
        val fieldOk = if (annotOk) fields else emptyList()
        val removedOk = removed.filter { !signed(it) && annotOk }
        val iconOk = if (annotOk) icons else emptyMap()
        val ok = ScriptDocChanges(if (modifyOk) scripts else emptyMap(), lockOk, fieldOk, removedOk, if (modifyOk) actions else emptyMap(), iconOk)
        val rest = ScriptDocChanges(scripts - ok.scripts.keys, locks - lockOk.keys, fields - fieldOk.toSet(), removed - removedOk.toSet(),
            actions - ok.actions.keys, icons - iconOk.keys)
        return ok to rest
    }

    /** Apply to an open document; returns how many changes were made. */
    fun apply(doc: PDDocument): Int = applyTracked(doc, null)

    /**
     * Apply and, with [touched], collect what an incremental update has to rewrite: field names whose dictionaries /
     * widgets changed, the pages whose /Annots changed and other dictionaries / arrays that changed.
     */
    private fun applyTracked(doc: PDDocument, touched: Touched?): Int {
        var n = 0
        scripts.forEach { (name, code) -> DocScripts.putNamed(doc, name.ifBlank { "script" }, code); n++ }
        val acro = doc.documentCatalog.getAcroForm(null)
        locks.forEach { (name, lock) ->
            val f = acro?.getField(name) as? PDSignatureField ?: return@forEach
            FxSigLocks.write(f.cosObject, lock); touched?.names?.add(name); n++
        }
        fields.forEach { nf ->
            if (nf.page !in 0 until doc.numberOfPages) return@forEach
            if (doc.documentCatalog.getAcroForm(null)?.getField(nf.name) != null) return@forEach      // name taken
            runCatching { FxEditor.create(doc, FieldSpec(nf.name, nf.type, listOf(FxWidget(nf.page, nf.rect)))) }.onSuccess { touched?.names?.add(it); n++ }
        }
        icons.forEach { (target, src) ->
            val icon = ScriptIcons.find(doc, src) ?: return@forEach
            if (runCatching { FxEditor.setIcon(doc, target, icon) }.getOrDefault(false)) { touched?.names?.add(target); n++ }
        }
        actions.forEach { (name, byKey) -> byKey.forEach { (key, code) -> if (putAction(doc, name, key, code, touched)) n++ } }
        removed.forEach { name -> if (removeField(doc, name, touched)) n++ }
        return n
    }

    /** Apply to [file] in place (loaded with [password], saved back only when something changed). */
    suspend fun applyTo(file: File, password: String?) = withContext(Dispatchers.IO) {
        if (isEmpty) return@withContext 0
        val tmp = File(file.parentFile, ".${file.name}.js.pdf")
        val n = PdfOps.load(file, password).use { d -> apply(d).also { if (it > 0) d.save(tmp) } }
        if (n > 0 && !tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        n
    }

    /**
     * Signed [file]: the changes are appended to it as an incremental update (the signed bytes stay a prefix); the
     * file is replaced only when something changed. Throws [com.smartdocs.editor.core.pdf.SignedDocumentException]
     * when the update cannot be written.
     */
    suspend fun appendTo(file: File, password: String?) = withContext(Dispatchers.IO) {
        if (isEmpty) return@withContext 0
        val tmp = File(file.parentFile, ".${file.name}.jsi.pdf")
        val n = PdfOps.loadRaw(file, password).use { d -> appendInto(d, tmp) }
        if (n > 0 && !tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        n
    }

    /** Apply to [doc] (loaded raw) and append the result to [out] as an incremental update; returns the change count. */
    fun appendInto(doc: PDDocument, out: File): Int {
        val t = Touched()
        return applyTracked(doc, t).also { if (it > 0) SignedSave.saveIncremental(doc, t.flag(doc), out) }
    }

    /** Objects an incremental update must rewrite (PdfBox 2.0 writes flagged objects and new ones only). */
    private class Touched {
        val names = LinkedHashSet<String>()
        val pages = LinkedHashSet<Int>()
        val dicts = ArrayList<COSBase>()

        /** Flags everything and returns the pages for [SignedSave.saveIncremental]. */
        fun flag(doc: PDDocument): List<PDPage> {
            dicts.forEach { (it as? COSUpdateInfo)?.isNeedToBeUpdated = true }
            val acro = doc.documentCatalog.getAcroForm(null)
            val out = LinkedHashSet<PDPage>()
            if (acro != null) {
                out += SignedFormMarks.mark(doc, acro, names)
                // a field created under an existing parent: the parent's /Kids changed too
                names.forEach { n ->
                    var d = acro.getField(n)?.cosObject?.getDictionaryObject(COSName.PARENT) as? COSDictionary
                    while (d != null) { (d.getDictionaryObject(COSName.KIDS) as? COSArray)?.isNeedToBeUpdated = true; d = d.getDictionaryObject(COSName.PARENT) as? COSDictionary }
                }
            }
            pages.filter { it in 0 until doc.numberOfPages }.forEach { out += doc.getPage(it) }
            return out.toList()
        }
    }

    private fun aaOf(d: COSDictionary): COSDictionary =
        (d.getDictionaryObject(COSName.AA) as? COSDictionary) ?: COSDictionary().also { d.setItem(COSName.AA, it) }

    private fun jsAction(code: String) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("Action")); setItem(COSName.S, COSName.getPDFName("JavaScript")); setItem(COSName.JS, COSString(code))
    }

    /** setAction: [key] of every terminal field named [name] (or under it); a blank [code] removes the action. */
    private fun putAction(doc: PDDocument, name: String, key: String, code: String, touched: Touched?): Boolean {
        val acro = doc.documentCatalog.getAcroForm(null) ?: return false
        val terms = terminals(acro.getField(name) ?: return false)
        if (terms.isEmpty()) return false
        val k = COSName.getPDFName(key)
        for (t in terms) {
            if (key in WIDGET_KEYS) t.widgets.forEach { w ->
                val wd = w.cosObject
                if (key == "U") {
                    // Mouse Up replaces the widget's activation action (/A) as well
                    if (code.isBlank()) wd.removeItem(COSName.A) else wd.setItem(COSName.A, jsAction(code))
                    (wd.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { it.removeItem(k); touched?.dicts?.add(it) }
                } else {
                    val aa = aaOf(wd)
                    if (code.isBlank()) aa.removeItem(k) else aa.setItem(k, jsAction(code))
                    touched?.dicts?.add(aa)
                }
            } else {
                val aa = aaOf(t.cosObject)
                if (code.isBlank()) aa.removeItem(k) else aa.setItem(k, jsAction(code))
                touched?.dicts?.add(aa)
                if (key == "C") {
                    // the calculation order lists every field with a Calculate script
                    val co = (acro.cosObject.getDictionaryObject(CO) as? COSArray) ?: COSArray().also { acro.cosObject.setItem(CO, it) }
                    val at = (0 until co.size()).firstOrNull { co.getObject(it) === t.cosObject }
                    if (code.isBlank()) { if (at != null) co.remove(at) } else if (at == null) co.add(t.cosObject)
                    touched?.dicts?.add(co)
                }
            }
            touched?.names?.add(t.fullyQualifiedName)
        }
        return true
    }

    /** removeField: the field [name] (or a parent with all its kids) leaves the form and its widgets the pages. */
    private fun removeField(doc: PDDocument, name: String, touched: Touched?): Boolean {
        val acro = doc.documentCatalog.getAcroForm(null) ?: return false
        val f = acro.getField(name) ?: return false
        val terms = terminals(f)
        val gone = HashSet<COSBase>()
        terms.forEach { t -> gone += t.cosObject; t.widgets.forEach { gone += it.cosObject } }
        gone += f.cosObject
        for (i in 0 until doc.numberOfPages) {
            val arr = doc.getPage(i).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            var hit = false
            var k = arr.size() - 1
            while (k >= 0) { if (arr.getObject(k) in gone) { arr.remove(k); hit = true }; k-- }
            if (hit) { touched?.pages?.add(i); touched?.dicts?.add(arr) }
        }
        val parent = f.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary
        val list = if (parent != null) parent.getDictionaryObject(COSName.KIDS) as? COSArray else acro.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray
        list?.let { removeIdentity(it, f.cosObject); touched?.dicts?.add(it) }
        parent?.let { touched?.dicts?.add(it) }
        (acro.cosObject.getDictionaryObject(CO) as? COSArray)?.let { co -> terms.forEach { removeIdentity(co, it.cosObject) }; touched?.dicts?.add(co) }
        touched?.dicts?.add(acro.cosObject)
        (acro.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray)?.let { touched?.dicts?.add(it) }
        return true
    }

    private fun removeIdentity(arr: COSArray, o: COSBase) {
        var k = arr.size() - 1
        while (k >= 0) { if (arr.getObject(k) === o) arr.remove(k); k-- }
    }

    private fun terminals(f: PDField): List<PDTerminalField> = when (f) {
        is PDTerminalField -> listOf(f)
        is PDNonTerminalField -> f.children.flatMap { terminals(it) }
        else -> emptyList()
    }

    companion object {
        private val CO = COSName.getPDFName("CO")
        /** Trigger keys stored on the widget (the rest, K F V C, are field keys). */
        val WIDGET_KEYS = setOf("U", "D", "E", "X", "Fo", "Bl")

        /** setAction's cTrigger ("MouseUp", "OnFocus", "Calculate"...) as its /AA key; null when unknown. */
        fun triggerKey(t: String): String? = when (t.trim().lowercase().replace(" ", "")) {
            "mouseup" -> "U"; "mousedown" -> "D"; "mouseenter" -> "E"; "mouseexit" -> "X"; "onfocus", "focus" -> "Fo"; "onblur", "blur" -> "Bl"
            "keystroke" -> "K"; "validate" -> "V"; "calculate" -> "C"; "format" -> "F"
            else -> null
        }

        /** Acrobat field type names of addField. */
        fun typeOf(t: String): FxType? = when (t.trim().lowercase()) {
            "text" -> FxType.TEXT; "checkbox" -> FxType.CHECKBOX; "radiobutton" -> FxType.RADIO; "combobox" -> FxType.COMBO
            "listbox" -> FxType.LIST; "signature" -> FxType.SIGNATURE; "button" -> FxType.BUTTON
            else -> null
        }

        /** The lock of `field.setLock({ action, fields })`. */
        fun lockOf(action: String, fields: List<String>): FxSigLock = when (action.trim().lowercase()) {
            "include" -> FxSigLock(FxSigLock.Mode.INCLUDE, fields)
            "exclude" -> FxSigLock(FxSigLock.Mode.EXCLUDE, fields)
            else -> FxSigLock(FxSigLock.Mode.ALL)
        }

        /** addField's oCoords "x1,y1,x2,y2" (page user space) as a rect; null when not four numbers. */
        fun rectOf(coords: String): FxRect? {
            val v = coords.split(',').mapNotNull { it.trim().toFloatOrNull() }
            return if (v.size >= 4 && v[0] != v[2] && v[1] != v[3]) FxRect(v[0], v[1], v[2], v[3]) else null
        }
    }
}
