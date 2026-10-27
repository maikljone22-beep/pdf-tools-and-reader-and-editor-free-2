package com.smartdocs.editor.core.formsx

/**
 * Properties of several fields at once (Foxit 26.29): the dialog edits the first field; every property the user
 * changed ([orig] -> [edited]) is copied onto [target], everything else stays as the target had it. Name and
 * geometry are never shared. Type-specific options apply only to targets of the same type.
 */
object FxMerge {

    private inline fun <T> pick(o: T, e: T, t: T): T = if (o != e) e else t

    fun apply(orig: FieldSpec, edited: FieldSpec, target: FieldSpec): FieldSpec {
        var r = target.copy(
            tooltip = pick(orig.tooltip, edited.tooltip, target.tooltip),
            visibility = pick(orig.visibility, edited.visibility, target.visibility),
            readOnly = pick(orig.readOnly, edited.readOnly, target.readOnly),
            required = pick(orig.required, edited.required, target.required),
            locked = pick(orig.locked, edited.locked, target.locked),
            rotation = pick(orig.rotation, edited.rotation, target.rotation),
            font = pick(orig.font, edited.font, target.font),
            fontSize = pick(orig.fontSize, edited.fontSize, target.fontSize),
            textColor = pick(orig.textColor, edited.textColor, target.textColor),
            borderColor = pick(orig.borderColor, edited.borderColor, target.borderColor),
            fillColor = pick(orig.fillColor, edited.fillColor, target.fillColor),
            borderWidth = pick(orig.borderWidth, edited.borderWidth, target.borderWidth),
            borderStyle = pick(orig.borderStyle, edited.borderStyle, target.borderStyle),
            actions = pick(orig.actions, edited.actions, target.actions),
        )
        if (target.type != orig.type) return r
        r = r.copy(
            align = pick(orig.align, edited.align, target.align),
            multiline = pick(orig.multiline, edited.multiline, target.multiline),
            doNotScroll = pick(orig.doNotScroll, edited.doNotScroll, target.doNotScroll),
            doNotSpellCheck = pick(orig.doNotSpellCheck, edited.doNotSpellCheck, target.doNotSpellCheck),
            comb = pick(orig.comb, edited.comb, target.comb),
            maxLen = pick(orig.maxLen, edited.maxLen, target.maxLen),
            password = pick(orig.password, edited.password, target.password),
            fileSelect = pick(orig.fileSelect, edited.fileSelect, target.fileSelect),
            richText = pick(orig.richText, edited.richText, target.richText),
            defaultValue = pick(orig.defaultValue, edited.defaultValue, target.defaultValue),
            checkStyle = pick(orig.checkStyle, edited.checkStyle, target.checkStyle),
            exportValue = pick(orig.exportValue, edited.exportValue, target.exportValue),
            checkedByDefault = pick(orig.checkedByDefault, edited.checkedByDefault, target.checkedByDefault),
            radiosInUnison = pick(orig.radiosInUnison, edited.radiosInUnison, target.radiosInUnison),
            noToggleToOff = pick(orig.noToggleToOff, edited.noToggleToOff, target.noToggleToOff),
            items = pick(orig.items, edited.items, target.items),
            sortItems = pick(orig.sortItems, edited.sortItems, target.sortItems),
            editable = pick(orig.editable, edited.editable, target.editable),
            multiSelect = pick(orig.multiSelect, edited.multiSelect, target.multiSelect),
            commitOnSelChange = pick(orig.commitOnSelChange, edited.commitOnSelChange, target.commitOnSelChange),
            selectionChangeJs = pick(orig.selectionChangeJs, edited.selectionChangeJs, target.selectionChangeJs),
            caption = pick(orig.caption, edited.caption, target.caption),
            rolloverCaption = pick(orig.rolloverCaption, edited.rolloverCaption, target.rolloverCaption),
            downCaption = pick(orig.downCaption, edited.downCaption, target.downCaption),
            layout = pick(orig.layout, edited.layout, target.layout),
            highlight = pick(orig.highlight, edited.highlight, target.highlight),
            iconScale = pick(orig.iconScale, edited.iconScale, target.iconScale),
            iconProportional = pick(orig.iconProportional, edited.iconProportional, target.iconProportional),
            barcode = pick(orig.barcode, edited.barcode, target.barcode),
            format = pick(orig.format, edited.format, target.format),
            validate = pick(orig.validate, edited.validate, target.validate),
            calc = pick(orig.calc, edited.calc, target.calc),
            sigLock = pick(orig.sigLock, edited.sigLock, target.sigLock),
        )
        return r
    }

    /** Appearance gallery presets (Nitro 8.20): named looks applied to a field. */
    enum class Preset { CLASSIC, UNDERLINE, BLUE, NONE, BEVELED, YELLOW }

    fun preset(s: FieldSpec, p: Preset): FieldSpec = when (p) {
        Preset.CLASSIC -> s.copy(borderColor = 0x737373, fillColor = null, borderWidth = 1f, borderStyle = FxBorderStyle.SOLID, textColor = 0x000000)
        Preset.UNDERLINE -> s.copy(borderColor = 0x000000, fillColor = null, borderWidth = 1f, borderStyle = FxBorderStyle.UNDERLINE)
        Preset.BLUE -> s.copy(borderColor = 0x1565C0, fillColor = 0xE3F2FD, borderWidth = 1f, borderStyle = FxBorderStyle.SOLID, textColor = 0x0D47A1)
        Preset.NONE -> s.copy(borderColor = null, fillColor = null, borderWidth = 0f)
        Preset.BEVELED -> s.copy(borderColor = 0x000000, fillColor = 0xC0C0C0, borderWidth = 1f, borderStyle = FxBorderStyle.BEVELED)
        Preset.YELLOW -> s.copy(borderColor = 0xF9A825, fillColor = 0xFFF9C4, borderWidth = 1f, borderStyle = FxBorderStyle.SOLID)
    }

    /** The look of [s] only (used for "save to gallery" and "use as defaults"). */
    fun look(s: FieldSpec): FieldSpec = FieldSpec(name = "", type = s.type, widgets = emptyList(), font = s.font, fontSize = s.fontSize,
        textColor = s.textColor, borderColor = s.borderColor, fillColor = s.fillColor, borderWidth = s.borderWidth, borderStyle = s.borderStyle,
        align = s.align, checkStyle = s.checkStyle, highlight = s.highlight, layout = s.layout, visibility = s.visibility, rotation = s.rotation)

    /** Apply a saved look onto [s]. */
    fun withLook(s: FieldSpec, look: FieldSpec): FieldSpec = s.copy(font = look.font, fontSize = look.fontSize, textColor = look.textColor,
        borderColor = look.borderColor, fillColor = look.fillColor, borderWidth = look.borderWidth, borderStyle = look.borderStyle,
        align = look.align, checkStyle = look.checkStyle, highlight = look.highlight, layout = look.layout, visibility = look.visibility, rotation = look.rotation)

    // ---------------- look <-> text (preferences) ----------------

    fun encodeLook(s: FieldSpec): String = listOf(s.font.name, s.fontSize, s.textColor, s.borderColor ?: -1, s.fillColor ?: -1, s.borderWidth,
        s.borderStyle.name, s.align, s.checkStyle.name, s.highlight.name, s.layout.name, s.visibility.name, s.rotation).joinToString("|")

    fun decodeLook(type: FxType, text: String): FieldSpec? = runCatching {
        val p = text.split('|')
        FieldSpec(name = "", type = type, widgets = emptyList(), font = FxFont.valueOf(p[0]), fontSize = p[1].toFloat(), textColor = p[2].toInt(),
            borderColor = p[3].toInt().takeIf { it >= 0 }, fillColor = p[4].toInt().takeIf { it >= 0 }, borderWidth = p[5].toFloat(),
            borderStyle = FxBorderStyle.valueOf(p[6]), align = p[7].toInt(), checkStyle = FxCheckStyle.valueOf(p[8]), highlight = FxHighlight.valueOf(p[9]),
            layout = FxButtonLayout.valueOf(p[10]), visibility = FxVisibility.valueOf(p[11]), rotation = p[12].toInt())
    }.getOrNull()
}
