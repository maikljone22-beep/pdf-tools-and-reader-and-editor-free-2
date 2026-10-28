package com.smartdocs.editor.ui.forms5

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ToolChip

/**
 * Tab audit D18: AI smart fill of the open form.
 *  (a) Suggestions start from the values the user sees (pending edits included), and fields with a pending value are
 *      never proposed again, so a typed but unsaved value is not overwritten.
 *  (b) The document's text and the profile go to the AI endpoint only when the document allows copying.
 *  (c) Reachable from the Fill & Sign toolbar ([FillSignSmartFillChip]) as well as from the field bar.
 */
object SmartFill {
    /** True when smart fill may send the document out (tells the user why not otherwise). */
    fun allowed(fs: FormFillState): Boolean = fs.reader.guard.check(DocAction.COPY)

    /** Field names whose value is pending (typed / computed / image): smart fill leaves them alone. */
    fun pendingNames(fs: FormFillState): Set<String> = fs.model.widgets.map { it.name }.filter { fs.isPendingValue(it) }.toSet()
}

/** The smart-fill sheet for [fs] (values go through setValues: one undo step). */
@Composable
fun SmartFillSheet(fs: FormFillState, onDismiss: () -> Unit) {
    val f = fs.reader.file ?: run { onDismiss(); return }
    val current = remember(fs) { fs.currentValues() }
    val skip = remember(fs) { SmartFill.pendingNames(fs) }
    com.smartdocs.editor.ui.ai5.FormFillSheet(f, fs.reader.password, onValues = { v -> if (v.isNotEmpty()) fs.setValues(v) },
        current = current, skip = skip, onDismiss = onDismiss)
}

/** Fill & Sign toolbar chip: shown when the AI switch is on and the document has fillable fields. */
@Composable
fun FillSignSmartFillChip(state: ReaderState) {
    val fs = remember(state) { FormFill.of(state) }
    val aiOn = com.smartdocs.editor.LocalSettings.current.aiEnabled
    if (!aiOn || fs.model.isEmpty) return
    var open by rememberSaveable { mutableStateOf(false) }
    ToolChip(Icons.Default.AutoAwesome, stringResource(R.string.ai6r_smart_fill)) { if (SmartFill.allowed(fs)) open = true }
    if (open) SmartFillSheet(fs) { open = false }
}
