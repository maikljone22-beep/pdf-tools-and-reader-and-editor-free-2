package com.smartdocs.editor.ui.selmenu

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.core.formsx.FxEditor
import com.smartdocs.editor.core.formsx.FxRef
import com.smartdocs.editor.core.selmenu.FieldFacts
import com.smartdocs.editor.core.selmenu.FieldKind
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.ui.forms5.FormFill
import com.smartdocs.editor.ui.forms5.FormFillState
import com.smartdocs.editor.ui.forms5.FormMessage
import com.smartdocs.editor.ui.formsx.DesignMode
import com.smartdocs.editor.ui.formsx.DesignerState
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderIntents
import com.smartdocs.editor.ui.reader.ReaderState

/**
 * fix/selmenu-rest: the form-field menu of the registry (long-press on a field of the in-page form layer).
 * Value actions run on [FormFillState] (one undo step each, like typing); structural ones open the form designer
 * on that field through [SelFieldHandoff].
 */
object SelFieldMenu {

    fun kindOf(k: WKind): FieldKind = when (k) {
        WKind.TEXT -> FieldKind.TEXT; WKind.CHECK -> FieldKind.CHECK; WKind.RADIO -> FieldKind.RADIO; WKind.COMBO -> FieldKind.COMBO
        WKind.LIST -> FieldKind.LIST; WKind.IMAGE -> FieldKind.IMAGE; WKind.SIGNATURE -> FieldKind.SIGNATURE; WKind.BUTTON -> FieldKind.BUTTON
    }

    /** Registry facts of [w] with its current (pending or computed) value. */
    fun facts(fs: FormFillState, w: FWidget): FieldFacts {
        val v = fs.value(w.name)
        val empty = when (w.kind) {
            WKind.CHECK -> !v.equals("true", true)
            WKind.RADIO -> v.isBlank() || v == "Off"
            WKind.IMAGE -> w.name !in fs.images
            else -> v.isEmpty()
        }
        return FieldFacts(
            kind = kindOf(w.kind), readOnly = fs.isReadOnly(w), calculated = fs.isCalculated(w.name), hasValue = !empty,
            changed = w.name in fs.edits || w.name in fs.images,
            hasCalculations = !com.smartdocs.editor.core.forms5.FormPrefs.autoCalculate && (fs.model.calc.hasCalculations || fs.scripts?.hasCalculations == true),
        )
    }

    /** The cleared value of [w] (the same as the form's Reset action). */
    private fun cleared(w: FWidget) = if (w.kind == WKind.CHECK) "false" else ""

    /** Runs a field action; true closes the menu. */
    fun run(ctx: Context, s: ReaderState, actions: ReaderActions?, item: SelItem, obj: SelObject, toast: (String) -> Unit): Boolean {
        val w = obj.widget ?: return true
        val fs = FormFill.of(s)
        val m = SelMenuState.of(s)
        when (item.action) {
            SelAction.F_COPY_VALUE -> {
                val text = when (w.kind) { WKind.TEXT, WKind.COMBO, WKind.LIST -> fs.display(w).text; else -> fs.value(w.name) }
                ReaderIntents.copy(ctx, text, "field"); toast(ctx.getString(R.string.sm_copied))
            }
            SelAction.F_PASTE -> {
                val clip = runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
                if (clip.isNullOrEmpty()) { toast(ctx.getString(R.string.nv_clipboard_empty)); return true }
                var t = if (w.multiline) clip else clip.replace('\n', ' ')
                if (w.maxLen > 0) t = t.take(w.maxLen)
                // the field's keystroke script may rewrite or refuse the paste, as for typing
                val kept = fs.keystroke(w, fs.value(w.name), t) ?: return true
                fs.setValue(w.name, kept)
            }
            SelAction.F_CLEAR -> if (w.kind == WKind.IMAGE) fs.setValues(mapOf(w.name to fs.model.values[w.name].orEmpty())) else fs.setValues(mapOf(w.name to cleared(w)))
            // back to the value saved in the file (pending typing and a picked picture dropped)
            SelAction.F_RESET -> fs.setValues(mapOf(w.name to fs.model.values[w.name].orEmpty()))
            SelAction.F_PICK_IMAGE -> fs.imageTarget = w.name
            SelAction.F_SIGN -> fs.sigTapped = w
            SelAction.F_SPELL, SelAction.F_PROPS -> m.fieldDialog = item.action to w
            SelAction.F_CALCULATE -> { fs.calculateNow(); fs.message = FormMessage.Calculated }
            SelAction.F_DESIGNER, SelAction.F_DUPLICATE, SelAction.F_DELETE, SelAction.F_TAB_ORDER -> {
                val a = actions ?: return true
                SelFieldHandoff.pending = SelFieldHandoff.Request(w.name, w.page, w.annotIndex, item.action)
                a.navigateTool("form_builder")
            }
            else -> {}
        }
        return true
    }
}

/**
 * Hand-off from the reader's field menu to the form designer: the field to select and what to do with it.
 * The designer takes it once its copy of the document is loaded ([apply], hook in FormDesignerScreen).
 */
object SelFieldHandoff {
    data class Request(val name: String, val page: Int, val annotIndex: Int, val op: SelAction)

    @Volatile var pending: Request? = null

    /** Selects the requested field in [st] and runs the operation; no-op until the designer has loaded fields. */
    fun apply(ctx: Context, st: DesignerState) {
        val r = pending ?: return
        val spec = st.specs.firstOrNull { it.name == r.name } ?: return
        pending = null
        val wi = spec.widgets.indexOfFirst { it.page == r.page && it.annotIndex == r.annotIndex }.takeIf { it >= 0 }
            ?: spec.widgets.indexOfFirst { it.page == r.page }.coerceAtLeast(0)
        val ref = FxRef(spec.name, wi)
        st.page = spec.widgets.getOrNull(wi)?.page ?: r.page
        st.mode = DesignMode.SELECT
        st.selection.clear(); st.selection.add(ref)
        when (r.op) {
            SelAction.F_DUPLICATE -> st.edit(ctx.getString(R.string.fx_duplicate)) { doc -> FxEditor.duplicate(doc, spec.name, 12f, -12f) != null }
            SelAction.F_DELETE -> {
                if (spec.locked) { st.message = ctx.getString(R.string.fx_locked_msg); return }
                st.edit(ctx.getString(R.string.delete), after = { st.selection.clear() }) { doc ->
                    if (spec.widgets.size > 1) FxEditor.deleteWidget(doc, ref) else FxEditor.delete(doc, listOf(spec.name)) > 0
                }
            }
            SelAction.F_TAB_ORDER -> { st.mode = DesignMode.TAB_ORDER; st.selection.clear(); st.tabPicks.clear() }
            else -> {}
        }
    }
}

/** Field properties (read only) and spell check of a field value, opened from the field menu. */
@Composable
internal fun SelFieldDialogs(s: ReaderState) {
    val m = SelMenuState.of(s)
    val (action, w) = m.fieldDialog ?: return
    val fs = FormFill.of(s)
    val close = { m.fieldDialog = null }
    when (action) {
        SelAction.F_SPELL -> {
            var text by remember(w) { mutableStateOf(fs.value(w.name)) }
            AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.smr_f_spell)) },
                text = {
                    Column {
                        Text(w.label.ifBlank { w.name }, style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = if (w.multiline) 3 else 1)
                        com.smartdocs.editor.ui.reader.annot.SpellCheckRow(text) { text = it }
                    }
                },
                confirmButton = { Button(onClick = {
                    close()
                    val kept = fs.keystroke(w, fs.value(w.name), if (w.maxLen > 0) text.take(w.maxLen) else text)
                    if (kept != null) fs.setValue(w.name, kept)
                }) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
        }
        SelAction.F_PROPS -> {
            val yes = stringResource(R.string.yes); val no = stringResource(R.string.no)
            val typeLabel = stringResource(kindLabel(w.kind))
            val rows = buildList {
                add(R.string.smr_fp_name to w.name)
                if (w.label.isNotBlank() && w.label != w.name) add(R.string.smr_fp_tooltip to w.label)
                add(R.string.smr_fp_type to typeLabel)
                add(R.string.smr_fp_page to "${w.page + 1}")
                add(R.string.smr_fp_value to (if (w.kind == WKind.TEXT || w.kind == WKind.COMBO || w.kind == WKind.LIST) fs.display(w).text else fs.value(w.name)))
                add(R.string.smr_fp_required to if (w.required) yes else no)
                add(R.string.smr_fp_readonly to if (fs.isReadOnly(w)) yes else no)
                add(R.string.smr_fp_calculated to if (fs.isCalculated(w.name)) yes else no)
                if (w.maxLen > 0) add(R.string.smr_fp_maxlen to "${w.maxLen}")
                if (w.options.isNotEmpty()) add(R.string.smr_fp_options to w.options.joinToString(", ").take(300))
            }
            AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.smr_f_props)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rows.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.ok)) } })
        }
        else -> close()
    }
}

private fun kindLabel(k: WKind): Int = when (k) {
    WKind.TEXT -> R.string.smr_fk_text; WKind.CHECK -> R.string.smr_fk_check; WKind.RADIO -> R.string.smr_fk_radio
    WKind.COMBO -> R.string.smr_fk_combo; WKind.LIST -> R.string.smr_fk_list; WKind.IMAGE -> R.string.smr_fk_image
    WKind.SIGNATURE -> R.string.smr_fk_signature; WKind.BUTTON -> R.string.smr_fk_button
}
