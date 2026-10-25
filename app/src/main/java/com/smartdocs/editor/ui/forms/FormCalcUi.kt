package com.smartdocs.editor.ui.forms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms.AfFormat
import com.smartdocs.editor.core.forms.Display
import com.smartdocs.editor.core.forms.FieldScripts
import com.smartdocs.editor.core.forms.KeystrokeKind

/** Read-only presentation of a calculated field: formatted result + "calculated" chip. */
@Composable
internal fun CalculatedValue(display: Display, unsupported: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Functions, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            display.text.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            color = if (display.red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(if (unsupported) R.string.ps_calc_unsupported else R.string.ps_calc_auto), style = MaterialTheme.typography.labelSmall) })
    }
}

/**
 * Text input that honours the field's keystroke/format actions: decimal keyboard for AFNumber/AFPercent,
 * a date picker for AFDate, and a "shown as" hint with the formatted value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptedTextField(value: String, onChange: (String) -> Unit, scripts: FieldScripts?, display: Display?, readOnly: Boolean, multiline: Boolean) {
    var pickDate by remember { mutableStateOf(false) }
    val kind = scripts?.keystroke ?: KeystrokeKind.NONE
    val dt = scripts?.format as? com.smartdocs.editor.core.forms.FormatSpec.DateTime
    val isDate = kind == KeystrokeKind.DATE || (dt != null && !dt.timeOnly && kind != KeystrokeKind.TIME)
    val keyboard = when (kind) {
        KeystrokeKind.NUMBER, KeystrokeKind.PERCENT -> KeyboardType.Decimal
        KeystrokeKind.SPECIAL -> KeyboardType.Phone
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), readOnly = readOnly, singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = if (isDate && !readOnly) ({ IconButton(onClick = { pickDate = true }) { Icon(Icons.Default.CalendarMonth, stringResource(R.string.ps_pick_date)) } }) else null,
        supportingText = if (display != null && display.text.isNotBlank() && display.text != value) ({
            Text(stringResource(R.string.ps_shown_as, display.text), color = if (display.red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }) else null,
    )
    if (pickDate) {
        val initial = remember(value) { AfFormat.parseDate(value, (scripts?.format as? com.smartdocs.editor.core.forms.FormatSpec.DateTime)?.pattern)?.time }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(AfFormat.formatPickedDate(it, scripts?.format)) }
                    pickDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}
