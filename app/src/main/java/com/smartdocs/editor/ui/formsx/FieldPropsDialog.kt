package com.smartdocs.editor.ui.formsx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.formsx.*

private val PALETTE = listOf(0x000000, 0x737373, 0xFFFFFF, 0xD32F2F, 0xF57C00, 0xF9A825, 0x388E3C, 0x1565C0, 0x6A1B9A, 0xE3F2FD, 0xFFF9C4, 0xEEEEEE)

/**
 * The field Properties dialog (Nitro / Acrobat / Foxit tabs): General, Appearance, Position, Options, Actions,
 * Format, Validate, Calculate, Signed. [multi] > 1: editing several fields; only changed properties are applied
 * (FxMerge). [onIcon] picks / clears a push-button icon (handled by the screen).
 */
@Composable
fun FieldPropsDialog(
    initial: FieldSpec,
    allNames: List<String>,
    pageCount: Int,
    multi: Int,
    onDismiss: () -> Unit,
    onSave: (FieldSpec) -> Unit,
    onIcon: ((clear: Boolean) -> Unit)? = null,
) {
    var s by remember(initial) { mutableStateOf(initial) }
    val tabs = buildList {
        add(R.string.fx_tab_general); add(R.string.fx_tab_appearance)
        if (multi <= 1) add(R.string.fx_tab_position)
        if (s.type != FxType.SIGNATURE) add(R.string.fx_tab_options)
        add(R.string.fx_tab_actions)
        if (s.type == FxType.TEXT || s.type == FxType.COMBO) { add(R.string.fx_tab_format); add(R.string.fx_tab_validate); add(R.string.fx_tab_calculate) }
        if (s.type == FxType.SIGNATURE) add(R.string.fx_tab_signed)
    }
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fx_props), style = MaterialTheme.typography.titleMedium)
                        Text(if (multi > 1) stringResource(R.string.fx_n_selected, multi) else s.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onSave(s) }) { Text(stringResource(R.string.ok)) }
                }
                if (multi > 1) Text(stringResource(R.string.fx_props_multi), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                ScrollableTabRow(selectedTabIndex = tab.coerceIn(tabs.indices), edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (tabs.getOrNull(tab)) {
                        R.string.fx_tab_general -> GeneralTab(s, multi) { s = it }
                        R.string.fx_tab_appearance -> AppearanceTab(s) { s = it }
                        R.string.fx_tab_position -> PositionTab(s) { s = it }
                        R.string.fx_tab_options -> OptionsTab(s, allNames, onIcon) { s = it }
                        R.string.fx_tab_actions -> ActionsTab(s, allNames, pageCount) { s = it }
                        R.string.fx_tab_format -> FormatTab(s) { s = it }
                        R.string.fx_tab_validate -> ValidateTab(s) { s = it }
                        R.string.fx_tab_calculate -> CalculateTab(s, allNames) { s = it }
                        R.string.fx_tab_signed -> SignedTab(s, allNames) { s = it }
                        else -> {}
                    }
                }
            }
        }
    }
}

// ================= building blocks =================

@Composable
internal fun SwitchRow(label: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f)); Switch(value, onChange)
    }
}

@Composable
internal fun <T> ChoiceRow(label: Int, options: List<Pair<T, Int>>, value: T, onChange: (T) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = v == value, onClick = { onChange(v) }, label = { Text(stringResource(l)) }) }
    }
}

@Composable
internal fun ColorRow(label: Int, value: Int?, allowNone: Boolean, onChange: (Int?) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (allowNone) Box(Modifier.size(30.dp).clip(CircleShape).border(if (value == null) 3.dp else 1.dp, if (value == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { onChange(null) }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Block, stringResource(R.string.fx_none), Modifier.size(16.dp)) }
        PALETTE.forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                .border(if (value == c) 3.dp else 1.dp, if (value == c) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape).clickable { onChange(c) })
        }
        var hex by remember(value) { mutableStateOf(value?.let { "%06X".format(it) } ?: "") }
        OutlinedTextField(hex, { v -> hex = v.take(6); v.toIntOrNull(16)?.takeIf { v.length == 6 }?.let { onChange(it) } }, Modifier.width(110.dp), singleLine = true, prefix = { Text("#") })
    }
}

@Composable
internal fun NumField(label: Int, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == '-' }.take(10)) }, modifier, label = { Text(stringResource(label)) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
internal fun Script(label: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, minLines = 3,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
}

/** Multi-select of field names (calculate / submit / reset / show-hide lists). */
@Composable
internal fun FieldPicker(label: Int, names: List<String>, selected: List<String>, onChange: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label) + if (selected.isEmpty()) "" else ": " + selected.joinToString(", "), maxLines = 2)
    }
    if (open) {
        val pick = remember { mutableStateListOf<String>().apply { addAll(selected) } }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(label)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    names.forEach { n ->
                        Row(Modifier.fillMaxWidth().clickable { if (n in pick) pick.remove(n) else pick.add(n) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(n in pick, { if (it) pick.add(n) else pick.remove(n) }); Text(n)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onChange(pick.toList()); open = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

// ================= tabs =================

@Composable
private fun GeneralTab(s: FieldSpec, multi: Int, set: (FieldSpec) -> Unit) {
    if (multi <= 1) OutlinedTextField(s.name, { set(s.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_name)) }, singleLine = true)
    OutlinedTextField(s.tooltip, { set(s.copy(tooltip = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_tooltip)) })
    ChoiceRow(R.string.fx_visibility, listOf(FxVisibility.VISIBLE to R.string.fx_vis_visible, FxVisibility.HIDDEN to R.string.fx_vis_hidden,
        FxVisibility.VISIBLE_NO_PRINT to R.string.fx_vis_noprint, FxVisibility.HIDDEN_PRINTABLE to R.string.fx_vis_hiddenprint), s.visibility) { set(s.copy(visibility = it)) }
    Text(stringResource(R.string.fx_rotation), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0, 90, 180, 270).forEach { r -> FilterChip(s.rotation == r, { set(s.copy(rotation = r)) }, label = { Text("$r\u00B0") }) } }
    if (s.type != FxType.BUTTON && s.type != FxType.BARCODE) SwitchRow(R.string.fx_readonly, s.readOnly) { set(s.copy(readOnly = it)) }
    if (s.type != FxType.BUTTON && s.type != FxType.SIGNATURE && s.type != FxType.BARCODE) SwitchRow(R.string.fx_required, s.required) { set(s.copy(required = it)) }
    SwitchRow(R.string.fx_locked, s.locked) { set(s.copy(locked = it)) }
}

@Composable
private fun AppearanceTab(s: FieldSpec, set: (FieldSpec) -> Unit) {
    Text(stringResource(R.string.fx_gallery), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(FxMerge.Preset.CLASSIC to R.string.fx_gallery_classic, FxMerge.Preset.UNDERLINE to R.string.fx_gallery_underline, FxMerge.Preset.BLUE to R.string.fx_gallery_blue,
            FxMerge.Preset.NONE to R.string.fx_gallery_none, FxMerge.Preset.BEVELED to R.string.fx_gallery_beveled, FxMerge.Preset.YELLOW to R.string.fx_gallery_yellow).forEach { (p, l) ->
            AssistChip(onClick = { set(FxMerge.preset(s, p)) }, label = { Text(stringResource(l)) })
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        FxPrefs.galleryLook(ctx, s.type)?.let { look -> AssistChip(onClick = { set(FxMerge.withLook(s, look)) }, label = { Text(stringResource(R.string.fx_gallery_saved)) }) }
        AssistChip(onClick = { FxPrefs.saveGalleryLook(ctx, s) }, label = { Text(stringResource(R.string.fx_gallery_save)) }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null, Modifier.size(16.dp)) })
    }
    ColorRow(R.string.fx_border_color, s.borderColor, true) { set(s.copy(borderColor = it, borderWidth = if (it != null && s.borderWidth <= 0f) 1f else s.borderWidth)) }
    ChoiceRow(R.string.fx_thickness, listOf(1f to R.string.fx_thin, 2f to R.string.fx_medium, 3f to R.string.fx_thick), s.borderWidth) { set(s.copy(borderWidth = it)) }
    ChoiceRow(R.string.fx_style, listOf(FxBorderStyle.SOLID to R.string.fx_style_solid, FxBorderStyle.DASHED to R.string.fx_style_dashed, FxBorderStyle.BEVELED to R.string.fx_style_beveled,
        FxBorderStyle.INSET to R.string.fx_style_inset, FxBorderStyle.UNDERLINE to R.string.fx_style_underline), s.borderStyle) { set(s.copy(borderStyle = it)) }
    ColorRow(R.string.fx_fill_color, s.fillColor, true) { set(s.copy(fillColor = it)) }
    if (s.type != FxType.SIGNATURE) {
        Text(stringResource(R.string.fx_font), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FxFont.entries.filter { it != FxFont.ZAPF }.forEach { f -> FilterChip(s.font == f, { set(s.copy(font = f)) }, label = { Text(f.base) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.fontSize == 0f, { set(s.copy(fontSize = 0f)) }, label = { Text(stringResource(R.string.fx_auto)) })
            var size by remember(s.fontSize) { mutableStateOf(if (s.fontSize > 0f) s.fontSize.toString().removeSuffix(".0") else "") }
            NumField(R.string.fx_font_size, size, Modifier.width(140.dp)) { v -> size = v; v.toFloatOrNull()?.let { set(s.copy(fontSize = it.coerceIn(0f, 200f))) } }
        }
        ColorRow(R.string.fx_text_color, s.textColor, false) { set(s.copy(textColor = it ?: 0)) }
    }
}

@Composable
private fun PositionTab(s: FieldSpec, set: (FieldSpec) -> Unit) {
    val w0 = s.widgets.firstOrNull() ?: return
    var l by remember { mutableStateOf("%.1f".format(java.util.Locale.US, w0.rect.left)) }
    var b by remember { mutableStateOf("%.1f".format(java.util.Locale.US, w0.rect.bottom)) }
    var w by remember { mutableStateOf("%.1f".format(java.util.Locale.US, w0.rect.width)) }
    var h by remember { mutableStateOf("%.1f".format(java.util.Locale.US, w0.rect.height)) }
    fun push() {
        val lv = l.toFloatOrNull() ?: return; val bv = b.toFloatOrNull() ?: return; val wv = w.toFloatOrNull()?.takeIf { it > 1f } ?: return; val hv = h.toFloatOrNull()?.takeIf { it > 1f } ?: return
        set(s.copy(widgets = listOf(w0.copy(rect = FxRect(lv, bv, lv + wv, bv + hv))) + s.widgets.drop(1)))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(R.string.fx_pos_left, l, Modifier.weight(1f)) { l = it; push() }
        NumField(R.string.fx_pos_bottom, b, Modifier.weight(1f)) { b = it; push() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumField(R.string.fx_pos_width, w, Modifier.weight(1f)) { w = it; push() }
        NumField(R.string.fx_pos_height, h, Modifier.weight(1f)) { h = it; push() }
    }
}

@Composable
private fun OptionsTab(s: FieldSpec, names: List<String>, onIcon: ((Boolean) -> Unit)?, set: (FieldSpec) -> Unit) {
    when (s.type) {
        FxType.TEXT -> {
            ChoiceRow(R.string.fx_text_align, listOf(0 to R.string.fx_left, 1 to R.string.fx_center, 2 to R.string.fx_right), s.align) { set(s.copy(align = it)) }
            OutlinedTextField(s.defaultValue, { set(s.copy(defaultValue = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_default_value)) })
            SwitchRow(R.string.fx_multiline, s.multiline) { set(s.copy(multiline = it, comb = if (it) false else s.comb)) }
            SwitchRow(R.string.fx_scroll, !s.doNotScroll) { set(s.copy(doNotScroll = !it)) }
            SwitchRow(R.string.fx_spell, !s.doNotSpellCheck) { set(s.copy(doNotSpellCheck = !it)) }
            SwitchRow(R.string.fx_password, s.password) { set(s.copy(password = it)) }
            SwitchRow(R.string.fx_fileselect, s.fileSelect) { set(s.copy(fileSelect = it)) }
            SwitchRow(R.string.fx_richtext, s.richText) { set(s.copy(richText = it)) }
            var ml by remember { mutableStateOf(if (s.maxLen > 0) s.maxLen.toString() else "") }
            NumField(R.string.fx_maxlen, ml) { v -> ml = v; set(s.copy(maxLen = v.toIntOrNull()?.coerceIn(0, 9999) ?: 0)) }
            SwitchRow(R.string.fx_comb, s.comb) { set(s.copy(comb = it, multiline = if (it) false else s.multiline, maxLen = if (it && s.maxLen <= 0) 10 else s.maxLen)); if (it && s.maxLen <= 0) ml = "10" }
        }
        FxType.CHECKBOX, FxType.RADIO -> {
            ChoiceRow(R.string.fx_check_style, listOf(FxCheckStyle.CHECK to R.string.fx_cs_check, FxCheckStyle.CIRCLE to R.string.fx_cs_circle, FxCheckStyle.CROSS to R.string.fx_cs_cross,
                FxCheckStyle.DIAMOND to R.string.fx_cs_diamond, FxCheckStyle.SQUARE to R.string.fx_cs_square, FxCheckStyle.STAR to R.string.fx_cs_star), s.checkStyle) { set(s.copy(checkStyle = it)) }
            if (s.type == FxType.CHECKBOX) {
                OutlinedTextField(s.exportValue, { set(s.copy(exportValue = it.replace(' ', '_'))) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_export_value)) }, singleLine = true)
                SwitchRow(R.string.fx_checked_default, s.checkedByDefault) { set(s.copy(checkedByDefault = it)) }
            } else {
                var vals by remember { mutableStateOf(s.widgets.mapIndexed { i, w -> w.export ?: "${i + 1}" }.joinToString("\n")) }
                OutlinedTextField(vals, { v -> vals = v
                    val l = v.split('\n').map { it.trim().replace(' ', '_') }
                    set(s.copy(widgets = s.widgets.mapIndexed { i, w -> w.copy(export = l.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: w.export) }))
                }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_radio_values)) }, minLines = 2)
                OutlinedTextField(s.defaultValue, { set(s.copy(defaultValue = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_default_value)) }, singleLine = true)
                SwitchRow(R.string.fx_unison, s.radiosInUnison) { set(s.copy(radiosInUnison = it)) }
                SwitchRow(R.string.fx_no_toggle_off, s.noToggleToOff) { set(s.copy(noToggleToOff = it)) }
            }
        }
        FxType.COMBO, FxType.LIST -> ChoiceOptions(s, set)
        FxType.BUTTON -> {
            OutlinedTextField(s.caption, { set(s.copy(caption = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_caption)) }, singleLine = true)
            ChoiceRow(R.string.fx_behavior, listOf(FxHighlight.NONE to R.string.fx_hl_none, FxHighlight.PUSH to R.string.fx_hl_push, FxHighlight.OUTLINE to R.string.fx_hl_outline,
                FxHighlight.INVERT to R.string.fx_hl_invert), s.highlight) { set(s.copy(highlight = it)) }
            OutlinedTextField(s.rolloverCaption, { set(s.copy(rolloverCaption = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_rollover)) }, singleLine = true)
            OutlinedTextField(s.downCaption, { set(s.copy(downCaption = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_down_caption)) }, singleLine = true)
            ChoiceRow(R.string.fx_layout, listOf(FxButtonLayout.LABEL_ONLY to R.string.fx_layout_0, FxButtonLayout.ICON_ONLY to R.string.fx_layout_1,
                FxButtonLayout.LABEL_BELOW_ICON to R.string.fx_layout_2, FxButtonLayout.LABEL_ABOVE_ICON to R.string.fx_layout_3, FxButtonLayout.LABEL_RIGHT_OF_ICON to R.string.fx_layout_4,
                FxButtonLayout.LABEL_LEFT_OF_ICON to R.string.fx_layout_5, FxButtonLayout.LABEL_OVER_ICON to R.string.fx_layout_6), s.layout) { set(s.copy(layout = it)) }
            if (onIcon != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onIcon(false) }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fx_icon_choose)) }
                OutlinedButton(onClick = { onIcon(true) }) { Text(stringResource(R.string.fx_icon_clear)) }
            }
            ChoiceRow(R.string.fx_icon_scale, listOf(FxIconScale.ALWAYS to R.string.fx_scale_always, FxIconScale.BIGGER to R.string.fx_scale_bigger,
                FxIconScale.SMALLER to R.string.fx_scale_smaller, FxIconScale.NEVER to R.string.fx_scale_never), s.iconScale) { set(s.copy(iconScale = it)) }
            SwitchRow(R.string.fx_proportional, s.iconProportional) { set(s.copy(iconProportional = it)) }
        }
        FxType.BARCODE -> {
            val b = s.barcode ?: FxBarcode()
            fun setB(nb: FxBarcode) = set(s.copy(barcode = nb))
            Text(stringResource(R.string.fx_symbology), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FxBarcodeKind.entries.forEach { k -> FilterChip(b.kind == k, { setB(b.copy(kind = k)) }, label = { Text(k.name) }) } }
            var xd by remember { mutableStateOf(b.xDim.toString()) }
            var yx by remember { mutableStateOf(b.yxRatio.toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(R.string.fx_xdim, xd, Modifier.weight(1f)) { v -> xd = v; v.toFloatOrNull()?.let { setB(b.copy(xDim = it.coerceIn(0.5f, 20f))) } }
                if (b.kind == FxBarcodeKind.PDF417) NumField(R.string.fx_yx, yx, Modifier.weight(1f)) { v -> yx = v; v.toFloatOrNull()?.let { setB(b.copy(yxRatio = it.coerceIn(1f, 10f))) } }
            }
            Text(stringResource(R.string.fx_ecc), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..(if (b.kind == FxBarcodeKind.PDF417) 8 else if (b.kind == FxBarcodeKind.QR) 3 else 0)).forEach { e -> FilterChip(b.ecc == e, { setB(b.copy(ecc = e)) }, label = { Text(if (b.kind == FxBarcodeKind.QR) "LMQH"[e].toString() else "$e") }) }
            }
            FieldPicker(R.string.fx_bc_fields, names.filter { it != s.name }, b.fields) { setB(b.copy(fields = it)) }
            SwitchRow(R.string.fx_bc_xml, b.xml) { setB(b.copy(xml = it)) }
            if (!b.xml) SwitchRow(R.string.fx_bc_names, b.includeNames) { setB(b.copy(includeNames = it)) }
            SwitchRow(R.string.fx_bc_compress, b.compress) { setB(b.copy(compress = it)) }
        }
        FxType.SIGNATURE -> {}
    }
}

@Composable
private fun ChoiceOptions(s: FieldSpec, set: (FieldSpec) -> Unit) {
    var disp by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(disp, { disp = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.fx_item_display)) }, singleLine = true)
        OutlinedTextField(exp, { exp = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.fx_item_export)) }, singleLine = true)
        IconButton(onClick = { if (disp.isNotBlank()) { set(s.copy(items = s.items + (disp.trim() to exp.trim().ifEmpty { disp.trim() }))); disp = ""; exp = "" } }) {
            Icon(Icons.Default.Add, stringResource(R.string.fx_add_item))
        }
    }
    s.items.forEachIndexed { i, (d, e) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.defaultValue.split('\n').contains(e), onClick = {
                val cur = s.defaultValue.split('\n').filter { it.isNotEmpty() }
                set(s.copy(defaultValue = if (s.multiSelect) (if (e in cur) cur - e else cur + e).joinToString("\n") else if (e in cur) "" else e))
            })
            Text(if (d == e) d else "$d  ($e)", Modifier.weight(1f))
            IconButton(onClick = { if (i > 0) set(s.copy(items = s.items.toMutableList().apply { add(i - 1, removeAt(i)) })) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.fx_up)) }
            IconButton(onClick = { if (i < s.items.size - 1) set(s.copy(items = s.items.toMutableList().apply { add(i + 1, removeAt(i)) })) }, enabled = i < s.items.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.fx_down)) }
            IconButton(onClick = { set(s.copy(items = s.items.filterIndexed { k, _ -> k != i })) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
    SwitchRow(R.string.fx_sort, s.sortItems) { set(s.copy(sortItems = it)) }
    if (s.type == FxType.COMBO) SwitchRow(R.string.fx_editable, s.editable) { set(s.copy(editable = it)) }
    if (s.type == FxType.LIST) SwitchRow(R.string.fx_multiselect, s.multiSelect) { set(s.copy(multiSelect = it)) }
    SwitchRow(R.string.fx_spell, !s.doNotSpellCheck) { set(s.copy(doNotSpellCheck = !it)) }
    SwitchRow(R.string.fx_commit, s.commitOnSelChange) { set(s.copy(commitOnSelChange = it)) }
    Script(R.string.fx_sel_change_js, s.selectionChangeJs) { set(s.copy(selectionChangeJs = it)) }
}

@Composable
private fun FormatTab(s: FieldSpec, set: (FieldSpec) -> Unit) {
    val cat = when (s.format) { FxFormat.None -> 0; is FxFormat.Number -> 1; is FxFormat.Percent -> 2; is FxFormat.Date -> 3; is FxFormat.Time -> 4; is FxFormat.Special -> 5; is FxFormat.Mask -> 6; is FxFormat.Custom -> 7 }
    ChoiceRow(R.string.fx_fmt_cat, listOf(0 to R.string.fx_fmt_none, 1 to R.string.fx_fmt_number, 2 to R.string.fx_fmt_percent, 3 to R.string.fx_fmt_date, 4 to R.string.fx_fmt_time,
        5 to R.string.fx_fmt_special, 6 to R.string.fx_fmt_mask, 7 to R.string.fx_fmt_custom), cat) { c ->
        set(s.copy(format = when (c) { 1 -> FxFormat.Number(); 2 -> FxFormat.Percent(); 3 -> FxFormat.Date(); 4 -> FxFormat.Time(); 5 -> FxFormat.Special(); 6 -> FxFormat.Mask("999-999"); 7 -> FxFormat.Custom(); else -> FxFormat.None }))
    }
    when (val f = s.format) {
        is FxFormat.Number -> {
            Text(stringResource(R.string.fx_decimals), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0..6).forEach { d -> FilterChip(f.decimals == d, { set(s.copy(format = f.copy(decimals = d))) }, label = { Text("$d") }) } }
            Text(stringResource(R.string.fx_sep_style), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1,234.56", "1234.56", "1.234,56", "1234,56").forEachIndexed { i, l -> FilterChip(f.sep == i, { set(s.copy(format = f.copy(sep = i))) }, label = { Text(l) }) }
            }
            ChoiceRow(R.string.fx_neg_style, listOf(0 to R.string.fx_neg_minus, 1 to R.string.fx_neg_red, 2 to R.string.fx_neg_paren, 3 to R.string.fx_neg_redparen), f.neg) { set(s.copy(format = f.copy(neg = it))) }
            OutlinedTextField(f.currency, { set(s.copy(format = f.copy(currency = it))) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_currency)) }, singleLine = true)
            SwitchRow(R.string.fx_currency_before, f.currencyPrepend) { set(s.copy(format = f.copy(currencyPrepend = it))) }
        }
        is FxFormat.Percent -> {
            Text(stringResource(R.string.fx_decimals), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0..6).forEach { d -> FilterChip(f.decimals == d, { set(s.copy(format = f.copy(decimals = d))) }, label = { Text("$d") }) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1,234.56", "1234.56", "1.234,56", "1234,56").forEachIndexed { i, l -> FilterChip(f.sep == i, { set(s.copy(format = f.copy(sep = i))) }, label = { Text(l) }) }
            }
        }
        is FxFormat.Date -> {
            Text(stringResource(R.string.fx_date_pattern), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("dd/mm/yyyy", "mm/dd/yyyy", "yyyy-mm-dd", "d-mmm-yyyy", "dd.mm.yy", "mmmm d, yyyy", "dd/mm/yyyy HH:MM").forEach { p ->
                    FilterChip(f.pattern == p, { set(s.copy(format = FxFormat.Date(p))) }, label = { Text(p) })
                }
            }
            OutlinedTextField(f.pattern, { set(s.copy(format = FxFormat.Date(it))) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fx_date_pattern)) })
        }
        is FxFormat.Time -> {
            Text(stringResource(R.string.fx_time_style), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("HH:MM", "h:MM tt", "HH:MM:ss", "h:MM:ss tt").forEachIndexed { i, l -> FilterChip(f.style == i, { set(s.copy(format = FxFormat.Time(i))) }, label = { Text(l) }) } }
        }
        is FxFormat.Special -> ChoiceRow(R.string.fx_fmt_special, listOf(0 to R.string.fx_special_zip, 1 to R.string.fx_special_zip4, 2 to R.string.fx_special_phone, 3 to R.string.fx_special_ssn), f.psf) { set(s.copy(format = FxFormat.Special(it))) }
        is FxFormat.Mask -> {
            OutlinedTextField(f.mask, { set(s.copy(format = FxFormat.Mask(it))) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fx_fmt_mask)) })
            Text(stringResource(R.string.fx_mask_hint), style = MaterialTheme.typography.bodySmall)
        }
        is FxFormat.Custom -> {
            Script(R.string.fx_format_js, f.formatJs) { set(s.copy(format = f.copy(formatJs = it))) }
            Script(R.string.fx_keystroke_js, f.keystrokeJs) { set(s.copy(format = f.copy(keystrokeJs = it))) }
        }
        FxFormat.None -> {}
    }
}

@Composable
private fun ValidateTab(s: FieldSpec, set: (FieldSpec) -> Unit) {
    val v = s.validate
    ChoiceRow(R.string.fx_tab_validate, listOf(0 to R.string.fx_val_none, 1 to R.string.fx_val_range, 2 to R.string.fx_val_custom),
        when (v) { FxValidate.None -> 0; is FxValidate.Range -> 1; is FxValidate.Custom -> 2 }) { c ->
        set(s.copy(validate = when (c) { 1 -> FxValidate.Range(0.0, 100.0); 2 -> FxValidate.Custom(""); else -> FxValidate.None }))
    }
    when (v) {
        is FxValidate.Range -> {
            var lo by remember { mutableStateOf(v.min?.toString() ?: "") }
            var hi by remember { mutableStateOf(v.max?.toString() ?: "") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField(R.string.fx_min, lo, Modifier.weight(1f)) { lo = it; set(s.copy(validate = FxValidate.Range(it.toDoubleOrNull(), hi.toDoubleOrNull()))) }
                NumField(R.string.fx_max, hi, Modifier.weight(1f)) { hi = it; set(s.copy(validate = FxValidate.Range(lo.toDoubleOrNull(), it.toDoubleOrNull()))) }
            }
        }
        is FxValidate.Custom -> Script(R.string.fx_script, v.js) { set(s.copy(validate = FxValidate.Custom(it))) }
        FxValidate.None -> {}
    }
}

@Composable
private fun CalculateTab(s: FieldSpec, names: List<String>, set: (FieldSpec) -> Unit) {
    val c = s.calc
    ChoiceRow(R.string.fx_tab_calculate, listOf(0 to R.string.fx_calc_none, 1 to R.string.fx_calc_simple, 2 to R.string.fx_calc_sfn, 3 to R.string.fx_calc_custom),
        when (c) { FxCalc.None -> 0; is FxCalc.Simple -> 1; is FxCalc.Sfn -> 2; is FxCalc.Custom -> 3 }) { k ->
        set(s.copy(calc = when (k) { 1 -> FxCalc.Simple(); 2 -> FxCalc.Sfn(""); 3 -> FxCalc.Custom(""); else -> FxCalc.None }))
    }
    when (c) {
        is FxCalc.Simple -> {
            ChoiceRow(R.string.fx_calc_simple, listOf(FxCalcOp.SUM to R.string.fx_op_sum, FxCalcOp.PRODUCT to R.string.fx_op_product, FxCalcOp.AVERAGE to R.string.fx_op_avg,
                FxCalcOp.MIN to R.string.fx_op_min, FxCalcOp.MAX to R.string.fx_op_max), c.op) { set(s.copy(calc = c.copy(op = it))) }
            FieldPicker(R.string.fx_calc_fields, names.filter { it != s.name }, c.fields) { set(s.copy(calc = c.copy(fields = it))) }
        }
        is FxCalc.Sfn -> {
            OutlinedTextField(c.expr, { set(s.copy(calc = FxCalc.Sfn(it))) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fx_calc_sfn)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            FieldPicker(R.string.fx_pick_fields, names.filter { it != s.name }, emptyList()) { picked ->
                set(s.copy(calc = FxCalc.Sfn((c.expr + " " + picked.joinToString(" + ") { it.replace(" ", "\\ ") }).trim())))
            }
        }
        is FxCalc.Custom -> Script(R.string.fx_script, c.js) { set(s.copy(calc = FxCalc.Custom(it))) }
        FxCalc.None -> {}
    }
}

@Composable
private fun SignedTab(s: FieldSpec, names: List<String>, set: (FieldSpec) -> Unit) {
    val l = s.sigLock ?: FxSigLock(FxSigLock.Mode.NONE)
    ChoiceRow(R.string.fx_tab_signed, listOf(FxSigLock.Mode.NONE to R.string.fx_sig_none, FxSigLock.Mode.ALL to R.string.fx_sig_all,
        FxSigLock.Mode.INCLUDE to R.string.fx_sig_include, FxSigLock.Mode.EXCLUDE to R.string.fx_sig_exclude), l.mode) { set(s.copy(sigLock = l.copy(mode = it))) }
    if (l.mode == FxSigLock.Mode.INCLUDE || l.mode == FxSigLock.Mode.EXCLUDE) FieldPicker(R.string.fx_fields, names.filter { it != s.name }, l.fields) { set(s.copy(sigLock = l.copy(fields = it))) }
    Script(R.string.fx_sig_script, l.script) { set(s.copy(sigLock = l.copy(script = it))) }
    Text(stringResource(R.string.fx_sig_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
