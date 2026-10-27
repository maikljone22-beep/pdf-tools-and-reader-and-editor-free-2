package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/**
 * The form designer's PDF side (Nitro "Form" tab / Foxit "Prepare form"): read every field of a document into
 * [FieldSpec]s, create fields, change any property of an existing field, move / resize / delete / duplicate
 * widgets, make copies, tab and calculation order. Works on an open [PDDocument]; the caller saves.
 */
object FxEditor {

    private fun n(s: String) = COSName.getPDFName(s)
    private val PMD = n("PMD")
    private val LOCK = n("Lock")
    private val CO = n("CO")
    private val TABS = n("Tabs")
    private val TU = n("TU")
    private val OPT = n("Opt")
    private val Q = n("Q")
    private val MAXLEN = n("MaxLen")
    private val SD_BARCODE = n("SDBarcode")

    // field flags (PDF 32000 tables 221, 226, 228, 230; bit n = 1 shl (n-1))
    const val FF_READONLY = 1
    const val FF_REQUIRED = 2
    const val FF_MULTILINE = 1 shl 12
    const val FF_PASSWORD = 1 shl 13
    const val FF_NO_TOGGLE_OFF = 1 shl 14
    const val FF_RADIO = 1 shl 15
    const val FF_PUSHBUTTON = 1 shl 16
    const val FF_COMBO = 1 shl 17
    const val FF_EDIT = 1 shl 18
    const val FF_SORT = 1 shl 19
    const val FF_FILESELECT = 1 shl 20
    const val FF_MULTISELECT = 1 shl 21
    const val FF_DONOTSPELLCHECK = 1 shl 22
    const val FF_DONOTSCROLL = 1 shl 23
    const val FF_COMB = 1 shl 24
    const val FF_RICHTEXT = 1 shl 25
    const val FF_RADIOS_IN_UNISON = 1 shl 25
    const val FF_COMMIT_ON_SEL = 1 shl 26

    // ================= helpers =================

    fun acroForm(doc: PDDocument): PDAcroForm {
        doc.documentCatalog.getAcroForm(null)?.let { return it }
        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        FxAppearance.ensureDr(form.cosObject, FxFont.HELVETICA)
        form.cosObject.setString(COSName.DA, "/Helv 0 Tf 0 g")
        return form
    }

    fun terminals(form: PDAcroForm): List<PDTerminalField> = runCatching { form.fieldTree.filterIsInstance<PDTerminalField>() }.getOrDefault(emptyList())

    fun field(doc: PDDocument, name: String): PDTerminalField? = doc.documentCatalog.getAcroForm(null)?.getField(name) as? PDTerminalField

    fun pageIndex(doc: PDDocument, w: PDAnnotationWidget): Int {
        w.page?.let { p -> val i = doc.pages.indexOf(p); if (i >= 0) return i }
        for (i in 0 until doc.numberOfPages) {
            val arr = doc.getPage(i).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            for (k in 0 until arr.size()) if (arr.getObject(k) === w.cosObject) return i
        }
        return -1
    }

    private fun annotIndex(page: PDPage, w: COSDictionary): Int {
        val arr = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return -1
        for (k in 0 until arr.size()) if (arr.getObject(k) === w) return k
        return -1
    }

    private fun rectOf(w: PDAnnotationWidget): FxRect = w.rectangle?.let { FxRect(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) } ?: FxRect(0f, 0f, 0f, 0f)

    private fun pdRect(r: FxRect) = PDRectangle(r.left, r.bottom, r.width, r.height)

    fun onState(w: PDAnnotationWidget): String? {
        val nAp = (w.cosObject.getDictionaryObject(COSName.AP) as? COSDictionary)?.getDictionaryObject(COSName.N) as? COSDictionary ?: return null
        return nAp.keySet().map { it.name }.firstOrNull { it != "Off" }
    }

    private fun jsOf(b: COSBase?): String? {
        val d = b as? COSDictionary ?: return null
        if (d.getCOSName(COSName.S)?.name != "JavaScript") return null
        return when (val j = d.getDictionaryObject(COSName.JS)) { is COSString -> j.string; is COSStream -> runCatching { j.toTextString() }.getOrNull(); else -> null }
    }

    private fun jsAction(code: String) = COSDictionary().apply { setItem(COSName.TYPE, n("Action")); setItem(COSName.S, n("JavaScript")); setItem(COSName.JS, COSString(code)) }

    private fun typeOf(f: PDTerminalField): FxType = when (f) {
        is PDTextField -> if (f.cosObject.getDictionaryObject(PMD) != null || f.cosObject.getDictionaryObject(SD_BARCODE) != null) FxType.BARCODE else FxType.TEXT
        is PDCheckBox -> FxType.CHECKBOX
        is PDRadioButton -> FxType.RADIO
        is PDComboBox -> FxType.COMBO
        is PDListBox -> FxType.LIST
        is PDSignatureField -> FxType.SIGNATURE
        else -> FxType.BUTTON
    }

    // ================= read =================

    fun readAll(doc: PDDocument): List<FieldSpec> {
        val form = doc.documentCatalog.getAcroForm(null) ?: return emptyList()
        return terminals(form).mapNotNull { f -> runCatching { read(doc, f) }.getOrNull() }
    }

    fun read(doc: PDDocument, f: PDTerminalField): FieldSpec {
        val cos = f.cosObject
        val type = typeOf(f)
        val ws = f.widgets
        val widgets = ws.map { w ->
            val p = pageIndex(doc, w)
            FxWidget(p, rectOf(w), if (type == FxType.CHECKBOX || type == FxType.RADIO) onState(w) else null,
                if (p >= 0) annotIndex(doc.getPage(p), w.cosObject) else -1)
        }
        val w0 = ws.firstOrNull()?.cosObject ?: cos
        val ff = f.fieldFlags
        val daStr = cos.getString(COSName.DA) ?: (cos.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.getString(COSName.DA) ?: doc.documentCatalog.getAcroForm(null)?.defaultAppearance
        val da = FxAppearance.parseDa(daStr)
        val mk = w0.getDictionaryObject(FxAppearance.MK) as? COSDictionary
        val bs = w0.getDictionaryObject(COSName.BS) as? COSDictionary
        val bw = bs?.getFloat(COSName.W, 1f) ?: ((w0.getDictionaryObject(n("Border")) as? COSArray)?.getObject(2) as? COSNumber)?.floatValue() ?: 1f
        val bc = FxAppearance.colorOf(mk?.getDictionaryObject(n("BC")) as? COSArray)
        val aa = cos.getDictionaryObject(COSName.AA) as? COSDictionary
        val fmtJs = jsOf(aa?.getDictionaryObject(COSName.F)); val keyJs = jsOf(aa?.getDictionaryObject(COSName.K))
        val choice = f as? PDChoice
        val items = choice?.let { c ->
            val disp = runCatching { c.optionsDisplayValues }.getOrDefault(emptyList())
            val exp = runCatching { c.optionsExportValues }.getOrDefault(disp)
            disp.mapIndexed { i, d -> d to (exp.getOrNull(i) ?: d) }
        } ?: emptyList()
        val dv = cos.getDictionaryObject(COSName.DV)
        val dvStr = when (dv) { is COSString -> dv.string; is COSName -> dv.name; is COSArray -> (0 until dv.size()).mapNotNull { (dv.getObject(it) as? COSString)?.string }.joinToString("\n"); else -> "" }
        val lock = cos.getDictionaryObject(LOCK) as? COSDictionary
        val barcode = (cos.getDictionaryObject(SD_BARCODE) as? COSDictionary)?.let { FxBarcodes.readSpec(it) }
            ?: (cos.getDictionaryObject(PMD) as? COSDictionary)?.let { FxBarcodes.readPmd(it) }
        return FieldSpec(
            name = f.fullyQualifiedName ?: f.partialName ?: "",
            type = type,
            widgets = widgets,
            tooltip = cos.getString(TU) ?: "",
            visibility = FxAppearance.visibilityOf(w0.getInt(COSName.F, 4)),
            readOnly = ff and FF_READONLY != 0,
            required = ff and FF_REQUIRED != 0,
            locked = w0.getInt(COSName.F, 0) and FxAppearance.F_LOCKED != 0,
            rotation = mk?.getInt(n("R"), 0) ?: 0,
            font = da.font, fontSize = da.size, textColor = da.color,
            borderColor = if (bw <= 0f) null else bc,
            fillColor = FxAppearance.colorOf(mk?.getDictionaryObject(n("BG")) as? COSArray),
            borderWidth = if (bc == null) 0f else bw,
            borderStyle = FxBorderStyle.of(bs?.getCOSName(COSName.S)?.name),
            align = cos.getInt(Q, 0),
            multiline = ff and FF_MULTILINE != 0 && f is PDTextField,
            doNotScroll = ff and FF_DONOTSCROLL != 0 && f is PDTextField,
            doNotSpellCheck = ff and FF_DONOTSPELLCHECK != 0,
            comb = ff and FF_COMB != 0 && f is PDTextField,
            maxLen = cos.getInt(MAXLEN, 0).coerceAtLeast(0),
            password = ff and FF_PASSWORD != 0 && f is PDTextField,
            fileSelect = ff and FF_FILESELECT != 0 && f is PDTextField,
            richText = ff and FF_RICHTEXT != 0 && f is PDTextField,
            defaultValue = if (type == FxType.BUTTON) "" else dvStr,
            checkStyle = FxCheckStyle.of(FxAppearance.mkString(w0, "CA").takeIf { type == FxType.CHECKBOX || type == FxType.RADIO }),
            exportValue = if (type == FxType.CHECKBOX) (ws.firstOrNull()?.let { onState(it) } ?: "Yes") else "Yes",
            checkedByDefault = type == FxType.CHECKBOX && dvStr.isNotEmpty() && dvStr != "Off",
            radiosInUnison = f is PDRadioButton && ff and FF_RADIOS_IN_UNISON != 0,
            noToggleToOff = f !is PDRadioButton || ff and FF_NO_TOGGLE_OFF != 0,
            items = items,
            sortItems = ff and FF_SORT != 0 && choice != null,
            editable = f is PDComboBox && ff and FF_EDIT != 0,
            multiSelect = choice != null && ff and FF_MULTISELECT != 0,
            commitOnSelChange = choice != null && ff and FF_COMMIT_ON_SEL != 0,
            selectionChangeJs = if (choice != null) keyJs.orEmpty() else "",
            caption = FxAppearance.mkString(w0, "CA"),
            rolloverCaption = FxAppearance.mkString(w0, "RC"),
            downCaption = FxAppearance.mkString(w0, "AC"),
            layout = FxButtonLayout.of(mk?.getInt(n("TP"), 0) ?: 0),
            highlight = FxHighlight.of(w0.getCOSName(COSName.H)?.name),
            iconScale = FxIconScale.of(((mk?.getDictionaryObject(n("IF")) as? COSDictionary)?.getCOSName(n("SW")))?.name),
            iconProportional = ((mk?.getDictionaryObject(n("IF")) as? COSDictionary)?.getCOSName(COSName.S))?.name != "A",
            barcode = barcode,
            format = if (f is PDTextField && type != FxType.BARCODE) FxScripts.parseFormat(fmtJs, keyJs) else FxFormat.None,
            validate = FxScripts.parseValidate(jsOf(aa?.getDictionaryObject(COSName.V))),
            calc = if (type == FxType.BARCODE) FxCalc.None else FxScripts.parseCalc(jsOf(aa?.getDictionaryObject(COSName.C))),
            actions = ws.firstOrNull()?.let { FxActions.readTriggers(doc, it.cosObject) } ?: FxActions.readTriggers(doc, cos),
            sigLock = lock?.let { FxSigLocks.read(it, cos) } ?: (cos.getDictionaryObject(COSName.getPDFName("SDOnSign")) as? COSDictionary)?.let { FxSigLocks.read(COSDictionary().apply { setItem(COSName.getPDFName("Action"), COSName.getPDFName("None")) }, cos).copy(mode = FxSigLock.Mode.NONE) },
        )
    }

    // ================= write =================

    private fun uniqueName(form: PDAcroForm, base: String, except: PDField? = null): String {
        val clean = base.trim().ifBlank { "field" }.replace('.', '_')
        fun taken(x: String) = form.getField(x).let { it != null && it.cosObject !== except?.cosObject }
        if (!taken(clean)) return clean
        var k = 2; while (taken("${clean}_$k")) k++
        return "${clean}_$k"
    }

    /** Ensure every widget is its own dictionary (a merged field/widget becomes field + one kid) before adding widgets. */
    private fun splitMerged(doc: PDDocument, f: PDTerminalField) {
        val cos = f.cosObject
        if (cos.getDictionaryObject(COSName.KIDS) != null) return
        if (cos.getCOSName(COSName.SUBTYPE)?.name != "Widget") return
        val w = COSDictionary()
        val widgetKeys = listOf("Type", "Subtype", "Rect", "F", "MK", "BS", "Border", "AP", "AS", "P", "H", "AA", "A", "NM", "M", "StructParent", "OC", "C")
        widgetKeys.forEach { k -> cos.getItem(n(k))?.let { v -> w.setItem(n(k), v); cos.removeItem(n(k)) } }
        // the field keeps the field-level triggers (K F V C); widget events go with the widget
        (w.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
            val fieldAa = COSDictionary(); val widgetAa = COSDictionary()
            aa.keySet().forEach { k -> if (k.name in setOf("K", "F", "V", "C")) fieldAa.setItem(k, aa.getItem(k)) else widgetAa.setItem(k, aa.getItem(k)) }
            if (fieldAa.size() > 0) cos.setItem(COSName.AA, fieldAa)
            if (widgetAa.size() > 0) w.setItem(COSName.AA, widgetAa) else w.removeItem(COSName.AA)
        }
        w.setItem(COSName.PARENT, cos)
        cos.setItem(COSName.KIDS, COSArray().apply { add(w) })
        for (i in 0 until doc.numberOfPages) {
            val arr = doc.getPage(i).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            for (k in 0 until arr.size()) if (arr.getObject(k) === cos) arr.set(k, w)
        }
    }

    private fun addWidget(doc: PDDocument, f: PDTerminalField, page: Int, r: FxRect, template: COSDictionary? = null): PDAnnotationWidget {
        splitMerged(doc, f)
        val w = COSDictionary()
        // widget keys only (a merged field/widget template also carries field keys such as /T /FT /Kids /V)
        template?.let { t -> listOf("F", "MK", "BS", "Border", "H", "AA", "A", "C", "OC", "DA", "Q").forEach { k -> t.getItem(n(k))?.let { v -> w.setItem(n(k), v) } } }
        w.setItem(COSName.TYPE, COSName.ANNOT); w.setItem(COSName.SUBTYPE, n("Widget"))
        val widget = PDAnnotationWidget(w)
        widget.rectangle = pdRect(r)
        val pg = doc.getPage(page)
        widget.page = pg
        if (w.getDictionaryObject(COSName.F) == null) w.setInt(COSName.F, FxAppearance.F_PRINT)
        w.setItem(COSName.PARENT, f.cosObject)
        val kids = (f.cosObject.getDictionaryObject(COSName.KIDS) as? COSArray) ?: COSArray().also { f.cosObject.setItem(COSName.KIDS, it) }
        kids.add(w)
        val annots = (pg.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray) ?: COSArray().also { pg.cosObject.setItem(COSName.ANNOTS, it) }
        annots.add(w)
        return widget
    }

    private fun newField(form: PDAcroForm, type: FxType): PDTerminalField = when (type) {
        FxType.TEXT, FxType.BARCODE -> PDTextField(form)
        FxType.CHECKBOX -> PDCheckBox(form)
        FxType.RADIO -> PDRadioButton(form)
        FxType.COMBO -> PDComboBox(form)
        FxType.LIST -> PDListBox(form)
        FxType.SIGNATURE -> PDSignatureField(form)
        FxType.BUTTON -> PDPushButton(form)
    }

    /** Create a field from [spec] (one widget per [FieldSpec.widgets] entry). Returns its final (unique) name. */
    fun create(doc: PDDocument, spec: FieldSpec): String {
        val form = acroForm(doc)
        val f = newField(form, spec.type)
        // "a.b.c": a hierarchy of non-terminal parents (shared by fields with the same prefix), the field is "c"
        val parts = spec.name.trim().split('.').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("field") }
        var siblings = (form.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray) ?: COSArray().also { form.cosObject.setItem(COSName.FIELDS, it) }
        var parent: COSDictionary? = null
        for (p in parts.dropLast(1)) {
            var node: COSDictionary? = null
            for (i in 0 until siblings.size()) {
                val d = siblings.getObject(i) as? COSDictionary ?: continue
                if (d.getString(COSName.T) == p && d.getDictionaryObject(COSName.FT) == null && d.getDictionaryObject(COSName.KIDS) is COSArray) { node = d; break }
            }
            if (node == null) {
                node = COSDictionary().apply { setString(COSName.T, p); setItem(COSName.KIDS, COSArray()) }
                parent?.let { node.setItem(COSName.PARENT, it) }
                siblings.add(node)
            }
            parent = node
            siblings = node.getDictionaryObject(COSName.KIDS) as COSArray
        }
        val prefix = parts.dropLast(1).joinToString(".")
        fun taken(x: String) = form.getField(if (prefix.isEmpty()) x else "$prefix.$x") != null
        var leaf = parts.last()
        if (taken(leaf)) { var k = 2; while (taken("${leaf}_$k")) k++; leaf = "${leaf}_$k" }
        f.partialName = leaf
        if (parent != null) f.cosObject.setItem(COSName.PARENT, parent)
        // no merged widget: always a field with kids, so later duplicates are simple
        f.cosObject.setItem(COSName.KIDS, COSArray())
        spec.widgets.forEach { addWidget(doc, f, it.page, it.rect) }
        siblings.add(f.cosObject)
        val name = f.fullyQualifiedName
        writeProps(doc, f, spec.copy(name = name), created = true)
        return name
    }

    /**
     * Apply [spec] to the existing field [name]: rename, flags, appearance, options, scripts, actions, look of every
     * widget (geometry from [FieldSpec.widgets] when the counts match). Changing the type replaces the field.
     * Returns the field's name afterwards.
     */
    fun update(doc: PDDocument, name: String, spec: FieldSpec): String {
        val form = acroForm(doc)
        val f = form.getField(name) as? PDTerminalField ?: error("no field $name")
        val oldType = typeOf(f)
        val compatible = oldType == spec.type || setOf(oldType, spec.type) == setOf(FxType.TEXT, FxType.BARCODE)
        if (!compatible) {
            delete(doc, listOf(name))
            return create(doc, spec.copy(name = spec.name.ifBlank { name }))
        }
        // rename (only the partial name; a hierarchy keeps its parents)
        val wanted = spec.name.substringAfterLast('.').trim()
        if (wanted.isNotEmpty() && wanted != f.partialName) {
            val prefix = f.fullyQualifiedName.substringBeforeLast('.', "")
            val full = if (prefix.isEmpty()) wanted else "$prefix.$wanted"
            f.partialName = if (form.getField(full) == null) wanted.replace('.', '_') else uniqueName(form, wanted, f).substringAfterLast('.')
        }
        val ws = f.widgets
        if (spec.widgets.size == ws.size) ws.forEachIndexed { i, w -> if (w.rectangle == null || rectOf(w) != spec.widgets[i].rect) w.rectangle = pdRect(spec.widgets[i].rect) }
        writeProps(doc, f, spec.copy(name = f.fullyQualifiedName), created = false)
        return f.fullyQualifiedName
    }

    private fun setFlag(cur: Int, bit: Int, on: Boolean) = if (on) cur or bit else cur and bit.inv()

    private fun writeProps(doc: PDDocument, f: PDTerminalField, s: FieldSpec, created: Boolean) {
        val form = acroForm(doc)
        val cos = f.cosObject
        FxAppearance.ensureDr(form.cosObject, s.font)
        if (s.tooltip.isNotBlank()) cos.setString(TU, s.tooltip) else cos.removeItem(TU)
        var ff = cos.getInt(COSName.FF, 0)
        ff = setFlag(ff, FF_READONLY, s.readOnly || s.type == FxType.BARCODE)
        ff = setFlag(ff, FF_REQUIRED, s.required && s.type != FxType.BUTTON)
        if (s.type != FxType.SIGNATURE) cos.setString(COSName.DA, FxAppearance.da(s.font, s.fontSize, s.textColor))
        when (f) {
            is PDTextField -> {
                ff = setFlag(ff, FF_MULTILINE, s.multiline && !s.comb)
                ff = setFlag(ff, FF_PASSWORD, s.password)
                ff = setFlag(ff, FF_FILESELECT, s.fileSelect)
                ff = setFlag(ff, FF_DONOTSPELLCHECK, s.doNotSpellCheck)
                ff = setFlag(ff, FF_DONOTSCROLL, s.doNotScroll)
                ff = setFlag(ff, FF_COMB, s.comb && s.maxLen > 0)
                ff = setFlag(ff, FF_RICHTEXT, s.richText)
                if (s.maxLen > 0) cos.setInt(MAXLEN, s.maxLen) else cos.removeItem(MAXLEN)
                cos.setInt(Q, s.align.coerceIn(0, 2))
                if (s.defaultValue.isNotEmpty() && s.type == FxType.TEXT) cos.setString(COSName.DV, s.defaultValue) else if (s.type == FxType.TEXT) cos.removeItem(COSName.DV)
                if (created && s.defaultValue.isNotEmpty() && s.type == FxType.TEXT) cos.setString(COSName.V, s.defaultValue)
            }
            is PDChoice -> {
                ff = setFlag(ff, FF_SORT, s.sortItems)
                ff = setFlag(ff, FF_MULTISELECT, s.multiSelect && f is PDListBox)
                ff = setFlag(ff, FF_EDIT, s.editable && f is PDComboBox)
                ff = setFlag(ff, FF_DONOTSPELLCHECK, s.doNotSpellCheck)
                ff = setFlag(ff, FF_COMMIT_ON_SEL, s.commitOnSelChange)
                cos.setInt(Q, s.align.coerceIn(0, 2))
                val items = (if (s.sortItems) s.items.sortedBy { it.first.lowercase() } else s.items).ifEmpty { listOf("1" to "1", "2" to "2") }
                val opt = COSArray()
                items.forEach { (d, e) -> if (d == e) opt.add(COSString(d)) else opt.add(COSArray().apply { add(COSString(e)); add(COSString(d)) }) }
                cos.setItem(OPT, opt)
                val dvs = s.defaultValue.split('\n').filter { it.isNotEmpty() }
                if (dvs.isNotEmpty()) {
                    val dv: COSBase = if (dvs.size == 1) COSString(dvs[0]) else COSArray().apply { dvs.forEach { add(COSString(it)) } }
                    cos.setItem(COSName.DV, dv); if (created) cos.setItem(COSName.V, dv)
                } else cos.removeItem(COSName.DV)
            }
            is PDRadioButton -> {
                ff = ff or FF_RADIO
                ff = setFlag(ff, FF_NO_TOGGLE_OFF, s.noToggleToOff)
                ff = setFlag(ff, FF_RADIOS_IN_UNISON, s.radiosInUnison)
            }
            is PDPushButton -> ff = ff or FF_PUSHBUTTON
            else -> {}
        }
        cos.setInt(COSName.FF, ff)
        // ---- scripts (field level /AA K F V C)
        val aa = (cos.getDictionaryObject(COSName.AA) as? COSDictionary) ?: COSDictionary()
        fun setJs(key: COSName, js: String?) { if (js.isNullOrBlank()) aa.removeItem(key) else aa.setItem(key, jsAction(js)) }
        if (f is PDTextField && s.type == FxType.TEXT) {
            val fk = FxScripts.format(s.format)
            setJs(COSName.F, fk?.first); setJs(COSName.K, fk?.second)
        }
        if (f is PDChoice) setJs(COSName.K, s.selectionChangeJs)
        if (f is PDTextField || f is PDChoice) setJs(COSName.V, FxScripts.validate(s.validate))
        val calcJs = if (s.type == FxType.BARCODE) s.barcode?.let { FxBarcodes.calcJs(it) } else FxScripts.calc(s.calc)
        if (f is PDTextField || f is PDChoice) setJs(COSName.C, calcJs)
        if (aa.size() > 0) cos.setItem(COSName.AA, aa) else cos.removeItem(COSName.AA)
        updateCalcOrder(form, cos, calcJs != null)
        // ---- barcode settings
        if (s.type == FxType.BARCODE && s.barcode != null) { cos.setItem(SD_BARCODE, FxBarcodes.writeSpec(s.barcode)); cos.setItem(PMD, FxBarcodes.pmd(s.barcode)) }
        else { cos.removeItem(SD_BARCODE); cos.removeItem(PMD) }
        // ---- signature lock
        if (f is PDSignatureField) FxSigLocks.write(cos, s.sigLock)
        // ---- widgets: look, triggers, check / radio states, appearances
        val ws = f.widgets
        ws.forEachIndexed { i, w ->
            FxAppearance.writeLook(w.cosObject, s)
            FxActions.writeTriggers(doc, w.cosObject, s.actions)
            val r = rectOf(w)
            when (f) {
                is PDCheckBox -> {
                    val old = onState(w)
                    val on = s.exportValue.ifBlank { "Yes" }.replace(' ', '_')
                    w.cosObject.setItem(COSName.AP, FxAppearance.checkAp(doc, s, r, on))
                    val wasOn = old != null && (cos.getCOSName(COSName.V)?.name == old)
                    val checked = if (created) s.checkedByDefault else wasOn
                    w.cosObject.setItem(COSName.AS, if (checked) n(on) else COSName.Off)
                    if (i == 0) {
                        cos.setItem(COSName.V, if (checked) n(on) else COSName.Off)
                        if (s.checkedByDefault) cos.setItem(COSName.DV, n(on)) else cos.removeItem(COSName.DV)
                    }
                }
                is PDRadioButton -> {
                    val on = (s.widgets.getOrNull(i)?.export ?: onState(w) ?: "${i + 1}").replace(' ', '_')
                    w.cosObject.setItem(COSName.AP, FxAppearance.checkAp(doc, s, r, on))
                    val cur = cos.getCOSName(COSName.V)?.name
                    val dvName = s.defaultValue.takeIf { it.isNotEmpty() }
                    val sel = if (created) dvName == on else cur == on
                    w.cosObject.setItem(COSName.AS, if (sel) n(on) else COSName.Off)
                }
                is PDPushButton -> w.cosObject.setItem(COSName.AP, FxAppearance.buttonAp(doc, s, r, iconOf(w.cosObject)))
                is PDTextField -> if (s.type == FxType.BARCODE) {
                    FxBarcodes.render(doc, f, s)
                } else if (created || w.cosObject.getDictionaryObject(COSName.AP) == null) {
                    w.cosObject.setItem(COSName.AP, FxAppearance.textAp(doc, s, r, cos.getString(COSName.V) ?: ""))
                } else {}
                else -> {}
            }
        }
        if (f is PDRadioButton) {
            if (created) { val dv = s.defaultValue.takeIf { it.isNotEmpty() }?.replace(' ', '_'); cos.setItem(COSName.V, dv?.let { n(it) } ?: COSName.Off); if (dv != null) cos.setItem(COSName.DV, n(dv)) else cos.removeItem(COSName.DV) }
        }
        // text and choice: PdfBox regenerates the look from /DA /MK /Q (needs font metrics: device only)
        if (f is PDTextField && s.type == FxType.TEXT || f is PDChoice) {
            try { f.constructAppearancesPublic() } catch (_: Throwable) { form.needAppearances = true }
        }
    }

    /** PdfBox's appearance builder for one field (package-private there), reached through refreshAppearances. */
    private fun PDTerminalField.constructAppearancesPublic() {
        val form = acroForm
        form.refreshAppearances(listOf<PDField>(this))
    }

    private fun updateCalcOrder(form: PDAcroForm, field: COSDictionary, hasCalc: Boolean) {
        val co = (form.cosObject.getDictionaryObject(CO) as? COSArray) ?: COSArray()
        var idx = -1
        for (i in 0 until co.size()) if (co.getObject(i) === field) idx = i
        if (hasCalc && idx < 0) co.add(field)
        if (!hasCalc && idx >= 0) co.remove(idx)
        if (co.size() > 0) form.cosObject.setItem(CO, co) else form.cosObject.removeItem(CO)
    }

    /**
     * Add one more button to the radio group [name] (Foxit "place radio buttons one by one"): same look, its own
     * export value. Returns false when [name] is not a radio group.
     */
    fun addRadioWidget(doc: PDDocument, name: String, page: Int, r: FxRect, export: String): Boolean {
        val f = field(doc, name) as? PDRadioButton ?: return false
        splitMerged(doc, f)
        val spec = read(doc, f)
        val template = f.widgets.firstOrNull()?.cosObject
        val w = addWidget(doc, f, page, r, template)
        w.cosObject.removeItem(COSName.AP)
        val on = export.ifBlank { "Choice${f.widgets.size}" }.replace(' ', '_')
        w.cosObject.setItem(COSName.AP, FxAppearance.checkAp(doc, spec, r.norm(), on))
        w.cosObject.setItem(COSName.AS, COSName.Off)
        return true
    }

    // ================= push button icons =================

    fun iconOf(widget: COSDictionary): COSStream? = (widget.getDictionaryObject(FxAppearance.MK) as? COSDictionary)?.getDictionaryObject(n("I")) as? COSStream

    /** A form XObject that draws [image] at its pixel size (the /MK /I icon of a button). */
    fun iconFromImage(doc: PDDocument, image: com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject): COSStream {
        val w = image.width.toFloat().coerceAtLeast(1f); val h = image.height.toFloat().coerceAtLeast(1f)
        val st = FxAppearance.stream(doc, w, h, "q ${FxAppearance.f(w)} 0 0 ${FxAppearance.f(h)} 0 0 cm /Im0 Do Q\n")
        (st.getDictionaryObject(COSName.RESOURCES) as COSDictionary).setItem(COSName.XOBJECT, COSDictionary().apply { setItem(n("Im0"), image.cosObject) })
        return st
    }

    /** Set (or with null clear) the icon of every widget of push button [name] and redraw it. */
    fun setIcon(doc: PDDocument, name: String, icon: COSStream?): Boolean {
        val f = field(doc, name) as? PDPushButton ?: return false
        val spec = read(doc, f)
        f.widgets.forEach { w ->
            val mk = (w.cosObject.getDictionaryObject(FxAppearance.MK) as? COSDictionary) ?: COSDictionary().also { w.cosObject.setItem(FxAppearance.MK, it) }
            if (icon != null) mk.setItem(n("I"), icon) else mk.removeItem(n("I"))
            w.cosObject.setItem(COSName.AP, FxAppearance.buttonAp(doc, spec, rectOf(w), icon))
        }
        return true
    }

    // ================= geometry =================

    /** Move / resize widget [ref]. Returns false when the field is locked or missing. */
    fun setRect(doc: PDDocument, ref: FxRef, r: FxRect, force: Boolean = false): Boolean {
        val f = field(doc, ref.name) ?: return false
        val w = f.widgets.getOrNull(ref.widget) ?: return false
        if (!force && w.cosObject.getInt(COSName.F, 0) and FxAppearance.F_LOCKED != 0) return false
        w.rectangle = pdRect(r.norm())
        // hand-made appearances keep their BBox and scale into the new rect; regenerate the ones we own
        runCatching {
            val spec = read(doc, f)
            when (f) {
                is PDCheckBox, is PDRadioButton -> {
                    val on = onState(w) ?: "Yes"
                    w.cosObject.setItem(COSName.AP, FxAppearance.checkAp(doc, spec, rectOf(w), on))
                }
                is PDPushButton -> w.cosObject.setItem(COSName.AP, FxAppearance.buttonAp(doc, spec, rectOf(w), iconOf(w.cosObject)))
                is PDTextField -> if (spec.type == FxType.BARCODE) FxBarcodes.render(doc, f, spec)
                    else try { f.constructAppearancesPublic() } catch (_: Throwable) { acroForm(doc).needAppearances = true }
                is PDChoice -> try { f.constructAppearancesPublic() } catch (_: Throwable) { acroForm(doc).needAppearances = true }
                else -> {}
            }
            Unit
        }
        return true
    }

    /** Remove fields (and all their widgets) by name. Returns how many were removed. */
    fun delete(doc: PDDocument, names: Collection<String>): Int {
        val form = doc.documentCatalog.getAcroForm(null) ?: return 0
        var count = 0
        names.distinct().forEach { name ->
            val f = form.getField(name) as? PDTerminalField ?: return@forEach
            val widgetCos = f.widgets.map { it.cosObject }.toSet() + f.cosObject
            for (i in 0 until doc.numberOfPages) {
                val arr = doc.getPage(i).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                var k = arr.size() - 1
                while (k >= 0) { if (arr.getObject(k) in widgetCos) arr.remove(k); k-- }
            }
            val parent = f.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary
            if (parent != null) (parent.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids -> removeIdentity(kids, f.cosObject) }
            else (form.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray)?.let { removeIdentity(it, f.cosObject) }
            (form.cosObject.getDictionaryObject(CO) as? COSArray)?.let { removeIdentity(it, f.cosObject) }
            count++
        }
        return count
    }

    /** Remove one widget of a multi-widget field (the last widget removes the whole field). */
    fun deleteWidget(doc: PDDocument, ref: FxRef): Boolean {
        val f = field(doc, ref.name) ?: return false
        val ws = f.widgets
        if (ws.size <= 1) return delete(doc, listOf(ref.name)) > 0
        val w = ws.getOrNull(ref.widget) ?: return false
        for (i in 0 until doc.numberOfPages) (doc.getPage(i).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { removeIdentity(it, w.cosObject) }
        (f.cosObject.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { removeIdentity(it, w.cosObject) }
        return true
    }

    private fun removeIdentity(arr: COSArray, o: COSBase) {
        var k = arr.size() - 1
        while (k >= 0) { if (arr.getObject(k) === o) arr.remove(k); k-- }
    }

    /**
     * Another widget of the same field (same name = same value) on [pages] at the same position: Foxit's
     * "duplicate across pages". Returns the number of widgets added.
     */
    fun duplicateToPages(doc: PDDocument, ref: FxRef, pages: Collection<Int>): Int {
        val f = field(doc, ref.name) ?: return 0
        if (f is PDRadioButton) return 0
        splitMerged(doc, f)
        val w = f.widgets.getOrNull(ref.widget) ?: return 0
        val src = pageIndex(doc, w)
        val r = rectOf(w)
        val template = w.cosObject
        var count = 0
        pages.distinct().filter { it in 0 until doc.numberOfPages && it != src }.forEach { p ->
            val nw = addWidget(doc, f, p, r, template)
            (template.getDictionaryObject(COSName.AP))?.let { nw.cosObject.setItem(COSName.AP, it) }
            count++
        }
        return count
    }

    /**
     * Nitro "Make copies" / Foxit "Create multiple copies": [rows] x [cols] copies of [name]'s first widget, each a
     * new field ("name_r_c", or widgets of the same field when [sameName]), spaced by [dx] / [dy] points (0 = the
     * field size). Returns the names of the new fields.
     */
    fun makeCopies(doc: PDDocument, name: String, rows: Int, cols: Int, dx: Float = 0f, dy: Float = 0f, sameName: Boolean = false): List<String> {
        val f = field(doc, name) ?: return emptyList()
        if (sameName) splitMerged(doc, f)
        val spec = read(doc, f)
        val base = spec.widgets.firstOrNull() ?: return emptyList()
        val sx = if (dx > 0f) dx else base.rect.width + 4f
        val sy = if (dy > 0f) dy else base.rect.height + 4f
        val out = ArrayList<String>()
        for (r in 0 until rows.coerceIn(1, 40)) for (c in 0 until cols.coerceIn(1, 40)) {
            if (r == 0 && c == 0) continue
            val rect = base.rect.offset(c * sx, -r * sy)
            if (sameName && f !is PDRadioButton) {
                val nw = addWidget(doc, f, base.page, rect, f.widgets.first().cosObject)
                runCatching { setRect(doc, FxRef(f.fullyQualifiedName, f.widgets.size - 1), rect, force = true) }
                if (nw.cosObject.getDictionaryObject(COSName.AP) == null) f.widgets.first().cosObject.getDictionaryObject(COSName.AP)?.let { nw.cosObject.setItem(COSName.AP, it) }
            } else {
                out += create(doc, spec.copy(name = "${spec.name.replace('.', '_')}_${r}_$c", widgets = listOf(FxWidget(base.page, rect)) +
                    if (spec.type == FxType.RADIO) spec.widgets.drop(1).map { it.copy(rect = it.rect.offset(c * sx, -r * sy)) } else emptyList()))
            }
        }
        if (sameName) out += f.fullyQualifiedName
        return out
    }

    /** Copy of field [name] moved by (dx, dy) on [page] (null = same page) as a new field; returns its name. */
    fun duplicate(doc: PDDocument, name: String, dx: Float, dy: Float, page: Int? = null): String? {
        val f = field(doc, name) ?: return null
        val spec = read(doc, f)
        val ws = spec.widgets.map { it.copy(page = page ?: it.page, rect = it.rect.offset(dx, dy)) }
        return create(doc, spec.copy(name = spec.name.replace('.', '_') + "_copy", widgets = ws))
    }

    // ================= tab order / calculation order =================

    /** Automatic tab order of [page]: "R" rows, "C" columns, "S" structure; null removes /Tabs (unspecified). */
    fun setTabs(doc: PDDocument, page: Int, mode: String?) {
        val p = doc.getPage(page).cosObject
        if (mode == null) p.removeItem(TABS) else p.setItem(TABS, n(mode))
    }

    fun tabsOf(doc: PDDocument, page: Int): String? = doc.getPage(page).cosObject.getCOSName(TABS)?.name

    /**
     * Manual tab order: the widgets [order] come first in the page's /Annots array in that order (other annotations
     * keep their order after them) and /Tabs is removed, so viewers follow the array (Acrobat "Order Tabs Manually").
     */
    fun setManualTabOrder(doc: PDDocument, page: Int, order: List<FxRef>) {
        val p = doc.getPage(page)
        val arr = p.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return
        val wanted = order.mapNotNull { ref -> field(doc, ref.name)?.widgets?.getOrNull(ref.widget)?.cosObject }
        val all = (0 until arr.size()).map { arr.getObject(it) }
        val rest = all.filter { o -> wanted.none { it === o } }
        val fresh = COSArray()
        wanted.forEach { w -> if (all.any { it === w }) fresh.add(w) }
        rest.forEach { fresh.add(it) }
        p.cosObject.setItem(COSName.ANNOTS, fresh)
        p.cosObject.removeItem(TABS)
    }

    /** Widgets of [page] in tab order: /Tabs R (rows), C (columns), else the /Annots order. */
    fun tabOrder(doc: PDDocument, page: Int, specs: List<FieldSpec> = readAll(doc)): List<FxRef> {
        val refs = specs.flatMap { s -> s.widgets.mapIndexedNotNull { i, w -> if (w.page == page) Triple(FxRef(s.name, i), w.rect, w.annotIndex) else null } }
        return when (tabsOf(doc, page)) {
            "R" -> refs.sortedWith(compareBy({ -Math.round(it.second.top / 4f) }, { it.second.left })).map { it.first }
            "C" -> refs.sortedWith(compareBy({ Math.round(it.second.left / 4f) }, { -it.second.top })).map { it.first }
            else -> refs.sortedBy { it.third }.map { it.first }
        }
    }

    /** Fields in calculation order (/CO). */
    fun calcOrder(doc: PDDocument): List<String> {
        val form = doc.documentCatalog.getAcroForm(null) ?: return emptyList()
        val co = form.cosObject.getDictionaryObject(CO) as? COSArray ?: return emptyList()
        val all = terminals(form)
        return (0 until co.size()).mapNotNull { i -> val o = co.getObject(i); all.firstOrNull { it.cosObject === o }?.fullyQualifiedName }
    }

    fun setCalcOrder(doc: PDDocument, names: List<String>) {
        val form = acroForm(doc)
        val arr = COSArray()
        names.distinct().forEach { nm -> form.getField(nm)?.let { arr.add(it.cosObject) } }
        if (arr.size() > 0) form.cosObject.setItem(CO, arr) else form.cosObject.removeItem(CO)
    }

    /** Lock or unlock fields in the designer (widget /F Locked). */
    fun setLocked(doc: PDDocument, names: Collection<String>, locked: Boolean) {
        names.forEach { nm -> field(doc, nm)?.widgets?.forEach { w ->
            val fl = w.cosObject.getInt(COSName.F, 0)
            w.cosObject.setInt(COSName.F, if (locked) fl or FxAppearance.F_LOCKED else fl and FxAppearance.F_LOCKED.inv())
        } }
    }

    // ================= property helpers (pure) =================

    /** Nitro "Inherit properties": [target] takes [source]'s look and options but keeps its name, type, place and value list. */
    fun inherit(source: FieldSpec, target: FieldSpec): FieldSpec {
        val look = target.copy(
            tooltip = target.tooltip, visibility = source.visibility, readOnly = source.readOnly, required = source.required, rotation = source.rotation,
            font = source.font, fontSize = source.fontSize, textColor = source.textColor, borderColor = source.borderColor, fillColor = source.fillColor,
            borderWidth = source.borderWidth, borderStyle = source.borderStyle,
        )
        if (source.type != target.type) return look
        return look.copy(
            align = source.align, multiline = source.multiline, doNotScroll = source.doNotScroll, doNotSpellCheck = source.doNotSpellCheck,
            comb = source.comb, maxLen = source.maxLen, password = source.password, fileSelect = source.fileSelect, richText = source.richText,
            checkStyle = source.checkStyle, radiosInUnison = source.radiosInUnison, sortItems = source.sortItems, editable = source.editable,
            multiSelect = source.multiSelect, commitOnSelChange = source.commitOnSelChange, layout = source.layout, highlight = source.highlight,
            format = source.format, validate = source.validate,
        )
    }
}
