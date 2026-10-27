package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.FormCalc
import com.smartdocs.editor.core.forms.FormCalcModel
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.smartdocs.editor.core.security.JsPolicy
import java.io.File

enum class WKind { TEXT, CHECK, RADIO, COMBO, LIST, SIGNATURE, BUTTON, IMAGE }

/**
 * One widget of an AcroForm field, ready for the in-page layer. [box] is in the reader's displayed page
 * space (crop origin at 0,0, page /Rotate applied; see [PageSpace]).
 */
data class FWidget(
    val id: String,                 // "<name>#<widget index>"
    val name: String,               // fully qualified field name (the value key)
    val label: String,              // tooltip (/TU) or partial name
    val kind: WKind,
    val page: Int,
    val annotIndex: Int,
    val box: Box5,
    val onValue: String? = null,    // check box / radio: the value this widget stands for
    val options: List<String> = emptyList(),   // choice display texts
    val exports: List<String> = emptyList(),   // choice export values (same size as options)
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val password: Boolean = false,
    val comb: Boolean = false,
    val multiSelect: Boolean = false,
    val editable: Boolean = false,  // editable combo box
    val maxLen: Int = 0,
    val quadding: Int = 0,          // 0 left, 1 centre, 2 right
    val fontSize: Float = 0f,       // 0 = auto
    val textColor: Int = 0x000000,
    val background: Int? = null,
    val action: ButtonAction? = null,
    val validate: ValidateSpec? = null,
    val hidden: Boolean = false,
    val mkRotation: Int = 0,        // /MK /R of the widget (counter-clockwise, 0/90/180/270)
    val pageRotation: Int = 0,      // /Rotate of its page (clockwise)
)

data class FormModel(
    val widgets: List<FWidget>,
    val values: Map<String, String>,       // FormOps conventions: "true"/"false", radio export value, choice value(s) joined by \n
    val calc: FormCalcModel,
    val xfa: XfaSupport.Kind,
    val tabs: Map<Int, String>,             // page -> /Tabs (R, C, S)
    val generatedFromXfa: Boolean = false,
    val js: FormJs = FormJs.EMPTY,                 // raw field / document JavaScript for the Rhino runtime
    val xfaRepeats: List<XfaRepeat> = emptyList(),   // generated XFA forms: subforms that can take another row
) {
    val byName: Map<String, List<FWidget>> by lazy { widgets.groupBy { it.name } }
    fun onPage(page: Int) = widgets.filter { it.page == page && !it.hidden }
    val isEmpty: Boolean get() = widgets.isEmpty()

    companion object { val EMPTY = FormModel(emptyList(), emptyMap(), FormCalcModel.EMPTY, XfaSupport.Kind.NONE, emptyMap()) }
}

object FormModelLoader {

    private val TU = COSName.getPDFName("TU")
    private val TABS = COSName.getPDFName("Tabs")
    private val MK = COSName.getPDFName("MK")
    private val BG = COSName.getPDFName("BG")
    private val R = COSName.getPDFName("R")
    private val JAVASCRIPT = COSName.getPDFName("JavaScript")

    /** Document-level scripts (/Names /JavaScript name tree), in tree order. */
    internal fun docScripts(doc: PDDocument): List<String> {
        val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary ?: return emptyList()
        val out = ArrayList<String>()
        fun walk(node: COSDictionary?, depth: Int) {
            if (node == null || depth > 12 || out.size > 200) return
            (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
                var i = 1
                while (i < arr.size()) { js(arr.getObject(i))?.takeIf { it.isNotBlank() }?.let { out += it }; i += 2 }
            }
            (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) walk(kids.getObject(k) as? COSDictionary, depth + 1) }
        }
        walk(names.getDictionaryObject(JAVASCRIPT) as? COSDictionary, 0)
        return out
    }

    private fun jsType(f: PDField) = when (f) {
        is PDTextField -> "text"; is PDCheckBox -> "checkbox"; is PDRadioButton -> "radiobutton"; is PDComboBox -> "combobox"
        is PDListBox -> "listbox"; is PDSignatureField -> "signature"; else -> "button"
    }

    internal fun js(action: COSBase?): String? {
        val d = action as? COSDictionary ?: return null
        if (d.getCOSName(COSName.S)?.name != "JavaScript") return null
        return when (val j = d.getDictionaryObject(COSName.JS)) {
            is COSString -> j.string
            is COSStream -> runCatching { j.toTextString() }.getOrNull()
            else -> null
        }
    }

    private fun color(arr: COSBase?): Int? {
        val a = arr as? COSArray ?: return null
        val c = (0 until a.size()).mapNotNull { (a.getObject(it) as? COSNumber)?.floatValue() }
        fun b(f: Float) = (f.coerceIn(0f, 1f) * 255f).toInt()
        return when (c.size) {
            1 -> b(c[0]).let { (it shl 16) or (it shl 8) or it }
            3 -> (b(c[0]) shl 16) or (b(c[1]) shl 8) or b(c[2])
            4 -> { val k = 1f - c[3]; (b((1 - c[0]) * k) shl 16) or (b((1 - c[1]) * k) shl 8) or b((1 - c[2]) * k) }
            else -> null
        }
    }

    /** Font size and colour from a /DA string ("/Helv 10 Tf 0 0 1 rg"). */
    internal fun parseDa(da: String?): Pair<Float, Int> {
        if (da.isNullOrBlank()) return 0f to 0
        val size = Regex("""([\d.]+)\s+Tf""").find(da)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val rg = Regex("""([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+rg""").find(da)
        val g = Regex("""(?<![\d.])([\d.]+)\s+g\b""").find(da)
        fun b(s: String) = ((s.toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * 255f).toInt()
        val col = when {
            rg != null -> (b(rg.groupValues[1]) shl 16) or (b(rg.groupValues[2]) shl 8) or b(rg.groupValues[3])
            g != null -> b(g.groupValues[1]).let { (it shl 16) or (it shl 8) or it }
            else -> 0
        }
        return size to col
    }

    /** Appearance state names of a check box / radio widget other than Off. */
    private fun onStates(w: COSDictionary): List<String> {
        val ap = w.getDictionaryObject(COSName.AP) as? COSDictionary ?: return emptyList()
        val n = ap.getDictionaryObject(COSName.N) as? COSDictionary ?: ap.getDictionaryObject(COSName.D) as? COSDictionary ?: return emptyList()
        return n.keySet().map { it.name }.filter { it != "Off" }
    }

    private fun fieldValue(f: PDField, exports: List<String>, states: List<String>): String = runCatching {
        when (f) {
            is PDCheckBox -> f.isChecked.toString()
            is PDRadioButton -> {
                val v = f.value ?: ""
                // with /Opt the state names are indexes or private names: report the export value
                val i = states.indexOf(v)
                if (exports.isNotEmpty() && i >= 0 && i < exports.size) exports[i] else if (v == "Off") "" else v
            }
            is PDListBox -> f.value.joinToString("\n")
            is PDComboBox -> f.value.firstOrNull() ?: ""
            is PDTextField -> f.value ?: ""
            else -> ""
        }
    }.getOrDefault("")

    private fun buttonAction(field: PDField, w: COSDictionary): ButtonAction? {
        val a = (w.getDictionaryObject(COSName.A) ?: field.cosObject.getDictionaryObject(COSName.A)) as? COSDictionary
        val aaU = ((w.getDictionaryObject(COSName.AA) ?: field.cosObject.getDictionaryObject(COSName.AA)) as? COSDictionary)?.getDictionaryObject(COSName.getPDFName("U"))
        if (a != null) {
            when (a.getCOSName(COSName.S)?.name) {
                "SubmitForm" -> {
                    val fBase = a.getDictionaryObject(COSName.F)
                    val url = when (fBase) {
                        is COSString -> fBase.string
                        is COSDictionary -> fBase.getString(COSName.UF) ?: fBase.getString(COSName.F) ?: ""
                        else -> ""
                    }
                    return FormSubmit.fromFlags(url, a.getInt(COSName.FLAGS, 0), names(a.getDictionaryObject(COSName.FIELDS)))
                }
                "ResetForm" -> return ButtonAction.Reset(names(a.getDictionaryObject(COSName.FIELDS)), a.getInt(COSName.FLAGS, 0) and 1 != 0)
                "URI" -> return a.getString(COSName.URI)?.let { ButtonAction.OpenUri(it) }
                "JavaScript" -> if (JsPolicy.enabled) FormSubmit.parseJs(js(a))?.let { return it }
                "Named" -> if (a.getNameAsString(COSName.N) == "Print") return ButtonAction.Print
            }
        }
        return if (JsPolicy.enabled) FormSubmit.parseJs(js(aaU)) else null
    }

    private fun names(base: COSBase?): List<String> {
        val arr = base as? COSArray ?: return emptyList()
        return (0 until arr.size()).mapNotNull { i ->
            when (val o = arr.getObject(i)) {
                is COSString -> o.string
                is COSDictionary -> fqName(o)
                else -> null
            }
        }
    }

    private fun fqName(d: COSDictionary): String? {
        val parts = ArrayList<String>(); var cur: COSDictionary? = d; var guard = 0
        while (cur != null && guard++ < 32) { cur.getString(COSName.T)?.let { parts += it }; cur = cur.getDictionaryObject(COSName.PARENT) as? COSDictionary }
        return parts.reversed().joinToString(".").ifEmpty { null }
    }

    fun load(doc: PDDocument): FormModel {
        val xfa = runCatching { XfaSupport.detect(doc).kind }.getOrDefault(XfaSupport.Kind.NONE)
        val acro = doc.documentCatalog.acroForm ?: return FormModel.EMPTY.copy(xfa = xfa)
        // where every annotation lives: page index and position in /Annots (one pass over the pages)
        val where = HashMap<COSDictionary, Pair<Int, Int>>()
        val geom = ArrayList<FloatArray>()
        val tabs = HashMap<Int, String>()
        doc.pages.forEachIndexed { pi, page ->
            val crop = page.cropBox
            geom += floatArrayOf(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation.toFloat())
            page.cosObject.getNameAsString(TABS)?.let { tabs[pi] = it }
            (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { arr ->
                for (i in 0 until arr.size()) (arr.getObject(i) as? COSDictionary)?.let { where.putIfAbsent(it, pi to i) }
            }
        }
        // "Run JavaScript in PDF documents" off: no field script, AF emulation, validation or JavaScript button action
        val jsOn = JsPolicy.enabled
        val calc = runCatching { FormCalc.load(doc) }.getOrDefault(FormCalcModel.EMPTY)
        val widgets = ArrayList<FWidget>(); val values = LinkedHashMap<String, String>()
        val jsFields = ArrayList<JsField>(); val jsScripts = LinkedHashMap<String, FieldJs>()
        val dictName = HashMap<COSDictionary, String>()
        for (f in acro.fieldTree) {
            if (f !is PDTerminalField) continue
            val name = f.fullyQualifiedName ?: continue
            val ws = runCatching { f.widgets }.getOrDefault(emptyList())
            val label = (f.cosObject.getString(TU) ?: f.partialName ?: name).ifBlank { name }
            val aa = f.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary
            val aaV = aa?.getDictionaryObject(COSName.V)
            val validate = if (jsOn) FieldValidation.parseValidate(js(aaV)) else null
            dictName[f.cosObject] = name
            FieldJs(js(aa?.getDictionaryObject(COSName.K)), js(aa?.getDictionaryObject(COSName.F)), js(aaV), js(aa?.getDictionaryObject(COSName.C)))
                .takeIf { jsOn && !it.isEmpty }?.let { jsScripts[name] = it }
            val (options, exports) = when (f) {
                is PDChoice -> runCatching { f.optionsDisplayValues to f.optionsExportValues }.getOrDefault(emptyList<String>() to emptyList())
                is PDButton -> runCatching { emptyList<String>() to f.exportValues }.getOrDefault(emptyList<String>() to emptyList())
                else -> emptyList<String>() to emptyList()
            }
            val states = ws.map { onStates(it.cosObject).firstOrNull() ?: "" }
            values[name] = fieldValue(f, if (f is PDButton) exports else emptyList(), states)
            val da = (f as? PDTextField)?.defaultAppearance ?: (f as? PDChoice)?.defaultAppearance
            val (fs, tc) = parseDa(runCatching { da }.getOrNull())
            val w0 = ws.firstOrNull()
            val r0 = runCatching { w0?.rectangle }.getOrNull()
            val mk0 = w0?.cosObject?.getDictionaryObject(MK) as? COSDictionary
            jsFields += JsField(
                name = name, type = jsType(f), value = values[name].orEmpty(),
                onValue = if (f is PDCheckBox) states.firstOrNull { it.isNotEmpty() } ?: "Yes" else null,
                exports = exports, items = if (f is PDChoice) options.indices.map { options[it] to (exports.getOrNull(it) ?: options[it]) } else emptyList(),
                readOnly = f.isReadOnly, required = f.isRequired,
                hidden = runCatching { w0 != null && (w0.isHidden || w0.isNoView) }.getOrDefault(false),
                multiline = (f as? PDTextField)?.isMultiline ?: false, password = (f as? PDTextField)?.isPassword ?: false,
                maxLen = (f as? PDTextField)?.maxLen?.coerceAtLeast(0) ?: 0, textColor = (0xFF shl 24) or tc,
                fillColor = color(mk0?.getDictionaryObject(BG))?.let { (0xFF shl 24) or it },
                page = w0?.let { where[it.cosObject]?.first } ?: 0,
                rect = r0?.let { listOf(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) }.orEmpty(),
                tooltip = f.cosObject.getString(TU).orEmpty(),
                defaultValue = (f.cosObject.getDictionaryObject(COSName.DV) as? COSString)?.string.orEmpty(),
            )
            ws.forEachIndexed { wi, w ->
                val (page, ai) = where[w.cosObject] ?: (runCatching { w.page?.let { p -> doc.pages.indexOf(p) } }.getOrNull()?.takeIf { it >= 0 }?.let { it to 999 } ?: return@forEachIndexed)
                val r = runCatching { w.rectangle }.getOrNull() ?: return@forEachIndexed
                val g = geom.getOrNull(page) ?: return@forEachIndexed
                val box = PageSpace.rectToDisplay(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY, g[0], g[1], g[2], g[3], g[4].toInt())
                if (box.width < 1f || box.height < 1f) return@forEachIndexed
                val action = if (f is PDPushButton) buttonAction(f, w.cosObject) else null
                val kind = when (f) {
                    is PDTextField -> WKind.TEXT
                    is PDCheckBox -> WKind.CHECK
                    is PDRadioButton -> WKind.RADIO
                    is PDComboBox -> WKind.COMBO
                    is PDListBox -> WKind.LIST
                    is PDSignatureField -> WKind.SIGNATURE
                    is PDPushButton -> if (action == ButtonAction.ImportImage) WKind.IMAGE else WKind.BUTTON
                    else -> WKind.BUTTON
                }
                val onValue = when (f) {
                    is PDRadioButton -> exports.getOrNull(wi)?.takeIf { exports.size == ws.size } ?: states[wi]
                    is PDCheckBox -> states[wi].ifEmpty { "Yes" }
                    else -> null
                }
                val mk = w.cosObject.getDictionaryObject(MK) as? COSDictionary
                widgets += FWidget(
                    id = "$name#$wi", name = name, label = label, kind = kind, page = page, annotIndex = ai, box = box,
                    onValue = onValue, options = options, exports = exports.takeIf { f is PDChoice }.orEmpty(),
                    readOnly = f.isReadOnly, required = f.isRequired,
                    multiline = (f as? PDTextField)?.isMultiline ?: false,
                    password = (f as? PDTextField)?.isPassword ?: false,
                    comb = (f as? PDTextField)?.isComb ?: false,
                    multiSelect = (f as? PDListBox)?.isMultiSelect ?: false,
                    editable = (f as? PDComboBox)?.isEdit ?: false,
                    maxLen = (f as? PDTextField)?.maxLen?.coerceAtLeast(0) ?: 0,
                    quadding = runCatching { (f as? PDTextField)?.q ?: (f as? PDChoice)?.q ?: 0 }.getOrDefault(0),
                    fontSize = fs, textColor = tc, background = color(mk?.getDictionaryObject(BG)),
                    action = action, validate = validate,
                    hidden = runCatching { w.isHidden || w.isNoView }.getOrDefault(false),
                    mkRotation = runCatching { AppearanceRotation.rotationOf(w.cosObject) }.getOrDefault(0), pageRotation = FieldRotation.norm(g[4].toInt()),
                )
            }
        }
        val generated = doc.documentInformation?.getCustomMetadataValue(XfaGenerated.KEY_SOURCE) != null
        // calculation order: /CO first, then the other fields with a calculate script
        val co = ArrayList<String>()
        (acro.cosObject.getDictionaryObject(COSName.CO) as? COSArray)?.let { arr ->
            for (i in 0 until arr.size()) (arr.getObject(i) as? COSDictionary)?.let { d -> dictName[d]?.let { n -> if (n !in co && jsScripts[n]?.calculate != null) co += n } }
        }
        jsScripts.forEach { (n, s) -> if (s.calculate != null && n !in co) co += n }
        val coIndex = co.withIndex().associate { it.value to it.index }
        val formJs = if (jsScripts.isEmpty()) FormJs.EMPTY else FormJs(jsFields.map { it.copy(calcIndex = coIndex[it.name] ?: -1) }, jsScripts, co,
            if (jsOn) runCatching { docScripts(doc) }.getOrDefault(emptyList()) else emptyList(), doc.numberOfPages)
        val repeats = if (generated) runCatching { XfaRepeat.parse(doc.documentInformation?.getCustomMetadataValue(XfaGenerated.KEY_REPEAT)) }.getOrDefault(emptyList()) else emptyList()
        return FormModel(widgets, values, calc, xfa, tabs, generated, formJs, repeats)
    }

    suspend fun load(file: File, password: String?): FormModel = withContext(Dispatchers.IO) {
        runCatching { PdfOps.load(file, password).use { load(it) } }.getOrDefault(FormModel.EMPTY)
    }
}
