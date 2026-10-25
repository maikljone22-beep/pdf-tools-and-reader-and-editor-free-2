package com.smartdocs.editor.ui.forms

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms.XfaSupport

/** Banner explaining how an XFA document is handled. */
@Composable
internal fun XfaBanner(info: XfaSupport.Info, onFlattenInfo: () -> Unit) {
    if (info.kind == XfaSupport.Kind.NONE) return
    val dynamic = info.kind == XfaSupport.Kind.DYNAMIC
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = if (dynamic) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (dynamic) Icons.Default.Warning else Icons.Default.Info, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (dynamic) R.string.ps_xfa_dynamic_title else R.string.ps_xfa_static_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (dynamic) R.string.ps_xfa_dynamic_body else R.string.ps_xfa_static_body), style = MaterialTheme.typography.bodySmall)
                if (dynamic) TextButton(onClick = onFlattenInfo, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.ps_xfa_flatten_q)) }
            }
        }
    }
}

@Composable
internal fun XfaFlattenDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ps_xfa_flatten_q)) },
        text = { Text(stringResource(R.string.ps_xfa_flatten_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun xTypeLabel(t: XfaSupport.XType): String = stringResource(when (t) {
    XfaSupport.XType.TEXT -> R.string.pg_form_type_text
    XfaSupport.XType.NUMBER -> R.string.ps_xfa_type_number
    XfaSupport.XType.DATE -> R.string.ps_xfa_type_date
    XfaSupport.XType.CHECK -> R.string.pg_form_type_check
    XfaSupport.XType.CHOICE -> R.string.pg_form_type_combo
    XfaSupport.XType.RADIO -> R.string.pg_form_type_radio
    XfaSupport.XType.SIGNATURE -> R.string.pg_form_type_signature
    XfaSupport.XType.BUTTON -> R.string.pg_form_type_button
    XfaSupport.XType.PASSWORD -> R.string.password
    XfaSupport.XType.IMAGE -> R.string.ps_xfa_type_image
    XfaSupport.XType.OTHER -> R.string.form_fields
})

/** Editor for one XFA field (dynamic XFA fill-in list). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XfaFieldEditor(f: XfaSupport.XField, value: String, edited: Boolean, onChange: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (edited) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.caption.ifBlank { f.name }, style = MaterialTheme.typography.titleSmall)
                    Text(f.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                }
                AssistChip(onClick = {}, enabled = false, label = { Text(xTypeLabel(f.type), style = MaterialTheme.typography.labelSmall) })
            }
            Spacer(Modifier.height(6.dp))
            val disabled = !f.bound || f.type == XfaSupport.XType.SIGNATURE || f.type == XfaSupport.XType.IMAGE
            when (f.type) {
                XfaSupport.XType.CHECK -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val checked = XfaSupport.isChecked(f.copy(value = value))
                    Switch(checked, { onChange(XfaSupport.checkValue(f, it)) }, enabled = !disabled)
                }
                XfaSupport.XType.RADIO, XfaSupport.XType.CHOICE -> Column {
                    val opts = f.options.ifEmpty { f.exportValues }
                    opts.forEachIndexed { i, label ->
                        val export = f.exportValues.getOrNull(i) ?: label
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = value == export, onClick = { onChange(export) }, enabled = !disabled)
                            Text(label)
                        }
                    }
                    if (opts.isEmpty()) OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, enabled = !disabled)
                }
                XfaSupport.XType.SIGNATURE, XfaSupport.XType.IMAGE ->
                    Text(stringResource(R.string.ps_xfa_not_editable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> OutlinedTextField(
                    value, onChange, Modifier.fillMaxWidth(), enabled = !disabled, singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = when (f.type) {
                            XfaSupport.XType.NUMBER -> androidx.compose.ui.text.input.KeyboardType.Decimal
                            XfaSupport.XType.PASSWORD -> androidx.compose.ui.text.input.KeyboardType.Password
                            else -> androidx.compose.ui.text.input.KeyboardType.Text
                        }
                    ),
                    placeholder = if (f.type == XfaSupport.XType.DATE) ({ Text("YYYY-MM-DD") }) else null,
                )
            }
            if (!f.bound) Text(stringResource(R.string.ps_xfa_unbound), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
