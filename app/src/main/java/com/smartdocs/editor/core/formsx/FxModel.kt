package com.smartdocs.editor.core.formsx

/**
 * Design-time model of an AcroForm field (Nitro / Acrobat "Properties" dialog): everything the form designer can
 * read from a PDF and write back. Pure Kotlin (no Android types) so the writers are JVM unit tested.
 *
 * Geometry is in PDF user space of the widget's page (points, origin bottom-left, unrotated page).
 */
data class FxRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val left get() = minOf(x0, x1)
    val right get() = maxOf(x0, x1)
    val bottom get() = minOf(y0, y1)
    val top get() = maxOf(y0, y1)
    val width get() = right - left
    val height get() = top - bottom
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    fun norm() = FxRect(left, bottom, right, top)
    fun offset(dx: Float, dy: Float) = FxRect(left + dx, bottom + dy, right + dx, top + dy)
    fun withSize(w: Float, h: Float) = FxRect(left, top - h, left + w, top)
    fun contains(x: Float, y: Float) = x in left..right && y in bottom..top
}

enum class FxType { TEXT, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE, BUTTON, BARCODE }

/** Nitro "General > Visibility". */
enum class FxVisibility { VISIBLE, HIDDEN, VISIBLE_NO_PRINT, HIDDEN_PRINTABLE }

/** /BS /S values: Solid, Dashed, Beveled, Inset, Underline. */
enum class FxBorderStyle(val pdf: String) { SOLID("S"), DASHED("D"), BEVELED("B"), INSET("I"), UNDERLINE("U");
    companion object { fun of(s: String?) = entries.firstOrNull { it.pdf == s } ?: SOLID }
}

/** Check box / radio button glyph (/MK /CA, ZapfDingbats character). */
enum class FxCheckStyle(val zapf: String) { CHECK("4"), CIRCLE("l"), CROSS("8"), DIAMOND("u"), SQUARE("n"), STAR("H");
    companion object { fun of(s: String?) = entries.firstOrNull { it.zapf == s } ?: CHECK }
}

/** Push button layout (/MK /TP 0..6). */
enum class FxButtonLayout(val tp: Int) { LABEL_ONLY(0), ICON_ONLY(1), LABEL_BELOW_ICON(2), LABEL_ABOVE_ICON(3), LABEL_RIGHT_OF_ICON(4), LABEL_LEFT_OF_ICON(5), LABEL_OVER_ICON(6);
    companion object { fun of(tp: Int) = entries.firstOrNull { it.tp == tp } ?: LABEL_ONLY }
}

/** Push button highlight (/H): None, Invert, Outline, Push. */
enum class FxHighlight(val pdf: String) { NONE("N"), INVERT("I"), OUTLINE("O"), PUSH("P");
    companion object { fun of(s: String?) = entries.firstOrNull { it.pdf == s } ?: INVERT }
}

/** Icon scaling (/MK /IF /SW): Always, Bigger, Smaller, Never. */
enum class FxIconScale(val pdf: String) { ALWAYS("A"), BIGGER("B"), SMALLER("S"), NEVER("N");
    companion object { fun of(s: String?) = entries.firstOrNull { it.pdf == s } ?: ALWAYS }
}

/** Standard 14 fonts offered for field text (the /DA font resource names Acrobat uses). */
enum class FxFont(val res: String, val base: String) {
    HELVETICA("Helv", "Helvetica"), HELVETICA_BOLD("HeBo", "Helvetica-Bold"), TIMES("TiRo", "Times-Roman"), TIMES_BOLD("TiBo", "Times-Bold"),
    COURIER("Cour", "Courier"), COURIER_BOLD("CoBo", "Courier-Bold"), ZAPF("ZaDb", "ZapfDingbats");
    companion object { fun of(res: String?) = entries.firstOrNull { it.res == res } ?: HELVETICA }
}

enum class FxBarcodeKind { QR, PDF417, DATAMATRIX }

/** Barcode field (Nitro / Acrobat): symbology, module size, encoded fields, payload layout. */
data class FxBarcode(
    val kind: FxBarcodeKind = FxBarcodeKind.QR,
    val xDim: Float = 2f,                 // module width in points
    val yxRatio: Float = 1f,              // PDF417 row height / module width
    val ecc: Int = 1,                     // QR 0..3 (L,M,Q,H); PDF417 0..8
    val fields: List<String> = emptyList(),   // fields whose values are encoded (empty = all)
    val xml: Boolean = false,             // payload as XML instead of tab-delimited
    val includeNames: Boolean = true,     // tab payload: first line holds the field names
    val compress: Boolean = false,        // Acrobat "compress data before encoding" (Flate + base64 here)
)

/** Format tab. */
sealed class FxFormat {
    data object None : FxFormat()
    /** AFNumber: [sep] 0 "1,234.56", 1 "1234.56", 2 "1.234,56", 3 "1234,56"; [neg] 0 minus, 1 red, 2 parens, 3 red+parens. */
    data class Number(val decimals: Int = 2, val sep: Int = 0, val neg: Int = 0, val currency: String = "", val currencyPrepend: Boolean = true) : FxFormat()
    data class Percent(val decimals: Int = 2, val sep: Int = 0) : FxFormat()
    data class Date(val pattern: String = "dd/mm/yyyy") : FxFormat()
    /** AFTime style 0 "HH:MM", 1 "h:MM tt", 2 "HH:MM:ss", 3 "h:MM:ss tt". */
    data class Time(val style: Int = 0) : FxFormat()
    /** AFSpecial 0 Zip, 1 Zip+4, 2 Phone, 3 SSN. */
    data class Special(val psf: Int = 0) : FxFormat()
    /** Arbitrary mask (AFSpecial_KeystrokeEx): 9 digit, A letter, O letter/digit, X any, others literal. */
    data class Mask(val mask: String = "") : FxFormat()
    data class Custom(val formatJs: String = "", val keystrokeJs: String = "") : FxFormat()
}

/** Validate tab. */
sealed class FxValidate {
    data object None : FxValidate()
    data class Range(val min: Double? = null, val max: Double? = null) : FxValidate()
    data class Custom(val js: String = "") : FxValidate()
}

enum class FxCalcOp(val af: String) { SUM("SUM"), PRODUCT("PRD"), AVERAGE("AVG"), MIN("MIN"), MAX("MAX");
    companion object { fun of(s: String?) = entries.firstOrNull { it.af.equals(s, true) } ?: SUM }
}

/** Calculate tab. */
sealed class FxCalc {
    data object None : FxCalc()
    data class Simple(val op: FxCalcOp = FxCalcOp.SUM, val fields: List<String> = emptyList()) : FxCalc()
    /** Simplified field notation, e.g. "(price * qty) - discount". */
    data class Sfn(val expr: String = "") : FxCalc()
    data class Custom(val js: String = "") : FxCalc()
}

/** Action triggers: widget-level mouse / focus events and the field-level keystroke (choice selection change). */
enum class FxTrigger(val key: String) { MOUSE_UP("U"), MOUSE_DOWN("D"), ENTER("E"), EXIT("X"), FOCUS("Fo"), BLUR("Bl") }

enum class FxSubmitFormat { FDF, XFDF, HTML, PDF }

/** One action of a trigger's list (executed in order through /Next). */
sealed class FxAction {
    data class GoToPage(val page: Int, val zoom: Float? = null) : FxAction()
    data class GoToNamed(val dest: String) : FxAction()
    data class GoToRemote(val file: String, val page: Int = 0) : FxAction()
    data class OpenUrl(val url: String) : FxAction()
    data class Submit(val url: String, val format: FxSubmitFormat = FxSubmitFormat.FDF, val fields: List<String> = emptyList(), val exclude: Boolean = false,
                      val includeEmpty: Boolean = false, val canonicalDates: Boolean = false, val includeAnnots: Boolean = false, val get: Boolean = false) : FxAction()
    data class Reset(val fields: List<String> = emptyList(), val exclude: Boolean = false) : FxAction()
    data class ShowHide(val fields: List<String>, val hide: Boolean) : FxAction()
    data class JavaScript(val code: String) : FxAction()
    data class Named(val name: String) : FxAction()
    data class ImportData(val file: String) : FxAction()
    /** Anything the designer cannot edit (kept as is when the list is written back). */
    data class Raw(val subtype: String, val cos: Any) : FxAction()
}

/** Signature field "when signed" behaviour (/Lock, SigFieldLock). */
data class FxSigLock(val mode: Mode = Mode.ALL, val fields: List<String> = emptyList(), val script: String = "") {
    enum class Mode { NONE, ALL, INCLUDE, EXCLUDE }
}

/** One widget of a field. */
data class FxWidget(val page: Int, val rect: FxRect, val export: String? = null, val annotIndex: Int = -1)

/** The whole Properties dialog of one field. */
data class FieldSpec(
    val name: String,
    val type: FxType,
    val widgets: List<FxWidget>,
    // ---- General
    val tooltip: String = "",
    val visibility: FxVisibility = FxVisibility.VISIBLE,
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val locked: Boolean = false,              // widget /F Locked: the designer cannot move or change it
    val rotation: Int = 0,                    // /MK /R 0, 90, 180, 270
    // ---- Appearance
    val font: FxFont = FxFont.HELVETICA,
    val fontSize: Float = 0f,                 // 0 = auto
    val textColor: Int = 0x000000,
    val borderColor: Int? = 0x737373,
    val fillColor: Int? = null,
    val borderWidth: Float = 1f,              // 0 none, 1 thin, 2 medium, 3 thick
    val borderStyle: FxBorderStyle = FxBorderStyle.SOLID,
    // ---- Text
    val align: Int = 0,                       // /Q 0 left 1 centre 2 right
    val multiline: Boolean = false,
    val doNotScroll: Boolean = false,
    val doNotSpellCheck: Boolean = false,
    val comb: Boolean = false,
    val maxLen: Int = 0,
    val password: Boolean = false,
    val fileSelect: Boolean = false,
    val richText: Boolean = false,
    val defaultValue: String = "",
    // ---- Check box / radio
    val checkStyle: FxCheckStyle = FxCheckStyle.CHECK,
    val exportValue: String = "Yes",          // check box on-state
    val checkedByDefault: Boolean = false,
    val radiosInUnison: Boolean = false,
    val noToggleToOff: Boolean = true,
    // ---- Combo / list (display text, export value)
    val items: List<Pair<String, String>> = emptyList(),
    val sortItems: Boolean = false,
    val editable: Boolean = false,
    val multiSelect: Boolean = false,
    val commitOnSelChange: Boolean = false,
    val selectionChangeJs: String = "",
    // ---- Push button
    val caption: String = "",
    val rolloverCaption: String = "",
    val downCaption: String = "",
    val layout: FxButtonLayout = FxButtonLayout.LABEL_ONLY,
    val highlight: FxHighlight = FxHighlight.INVERT,
    val iconScale: FxIconScale = FxIconScale.ALWAYS,
    val iconProportional: Boolean = true,
    // ---- Barcode
    val barcode: FxBarcode? = null,
    // ---- Format / validate / calculate
    val format: FxFormat = FxFormat.None,
    val validate: FxValidate = FxValidate.None,
    val calc: FxCalc = FxCalc.None,
    // ---- Actions
    val actions: Map<FxTrigger, List<FxAction>> = emptyMap(),
    // ---- Signature
    val sigLock: FxSigLock? = null,
) {
    val page: Int get() = widgets.firstOrNull()?.page ?: 0
    val rect: FxRect get() = widgets.firstOrNull()?.rect ?: FxRect(0f, 0f, 0f, 0f)
}

/** Reference to one widget on a page (the designer selects widgets). */
data class FxRef(val name: String, val widget: Int = 0)
