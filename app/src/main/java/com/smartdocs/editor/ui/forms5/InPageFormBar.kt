package com.smartdocs.editor.ui.forms5

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms.AfNumbers
import com.smartdocs.editor.core.forms5.ButtonAction
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.FieldDates
import com.smartdocs.editor.core.forms5.FieldNavigation
import com.smartdocs.editor.core.forms5.FormSubmit
import com.smartdocs.editor.core.forms5.Issue
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The field bar above the keyboard (hook: last child of ReaderScreen's root Box, aligned bottom):
 * previous / next, field name with "n of N", date picker, toggle / dropdown, clear, undo / redo, done,
 * autocomplete chips and the validation message. Also hosts the button-action confirmation, the date
 * picker and short feedback messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InPageFormBar(reader: ReaderState, modifier: Modifier = Modifier) {
    val fs = remember(reader) { FormFill.of(reader) }
    val scope = rememberCoroutineScope()
    val w = fs.active
    val enabled = fs.enabled
    // choosing a tool, a placement or a selection ends field editing
    LaunchedEffect(enabled) { if (!enabled && fs.activeId != null) fs.done() }

    Box(modifier.fillMaxWidth()) {
        if (w != null && enabled) {
            BackHandler { fs.done() }
            FieldBar(fs, w, Modifier.align(Alignment.BottomCenter))
        }
        val msg = fs.message
        if (msg != null && w == null) {
            LaunchedEffect(msg) { delay(4000); if (fs.message == msg) fs.message = null }
            Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding(), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp) {
                Text(messageText(msg), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        if (fs.busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp))
    }

    // button actions that need no confirmation run directly
    val pa = fs.pendingAction
    LaunchedEffect(pa) {
        val (bw, a) = pa ?: return@LaunchedEffect
        if (a == ButtonAction.Print || a == ButtonAction.ImportImage) { fs.pendingAction = null; FormActions.run(fs, bw, a) }
    }
    if (pa != null && pa.second != ButtonAction.Print && pa.second != ButtonAction.ImportImage) {
        val (bw, a) = pa
        AlertDialog(
            onDismissRequest = { fs.pendingAction = null },
            title = { Text(bw.label) },
            text = {
                Text(when (a) {
                    is ButtonAction.Submit -> if (FormSubmit.isMail(a.url)) stringResource(R.string.f5_submit_mail_q, a.url.removePrefix("mailto:").substringBefore('?'))
                        else stringResource(R.string.f5_submit_q, FormSubmit.host(a.url))
                    is ButtonAction.Reset -> stringResource(R.string.f5_reset_q)
                    is ButtonAction.OpenUri -> stringResource(R.string.f5_open_uri_q, a.uri)
                    else -> ""
                })
            },
            confirmButton = { TextButton(onClick = { fs.pendingAction = null; scope.launch { FormActions.run(fs, bw, a) } }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { fs.pendingAction = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    val dateFor = fs.datePickerFor
    if (dateFor != null) {
        val pattern = FieldDates.patternOf(fs.model.calc.scripts[dateFor])
        // DatePickerState requires the initial year inside its year range (1900..2100): "1/1/24" parsed as year 24 crashed it
        val initial = remember(dateFor) {
            FieldDates.parseUtc(fs.value(dateFor), pattern)?.takeIf { ms ->
                val y = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
                y in DatePickerDefaults.YearRange
            }
        }
        val st = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { fs.datePickerFor = null },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { fs.setValue(dateFor, FieldDates.formatUtc(it, pattern)) }
                    fs.datePickerFor = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { fs.datePickerFor = null }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(st) }
    }

    // app.alert of a field script, or the reason a value was rejected (the previous value is back in the field)
    val al = fs.alert
    if (al != null) {
        val issue = fs.alertField?.let { fs.errors[it] }
        AlertDialog(
            onDismissRequest = { fs.alert = null },
            title = { Text(stringResource(R.string.f5_alert_title)) },
            text = { Text(al.ifBlank { issue?.let { issueText(it) } ?: stringResource(R.string.f5_err_script) }) },
            confirmButton = { TextButton(onClick = { fs.alert = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun FieldBar(fs: FormFillState, w: FWidget, modifier: Modifier) {
    val order = fs.order
    val pos = FieldNavigation.position(order, if (w.kind == WKind.RADIO) fs.model.widgets.first { it.name == w.name }.id else w.id)
    val typed = fs.value(w.name)
    val isDate = w.kind == WKind.TEXT && FieldDates.isDateField(fs.model.calc.scripts[w.name], w.name)
    val textual = w.kind == WKind.TEXT || (w.kind == WKind.COMBO && w.editable)
    val suggestions = if (textual && !isDate) remember(w.id, typed) { fs.suggestions(w, typed) } else emptyList()
    Surface(modifier.fillMaxWidth().imePadding(), tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp)) {
            fs.errors[w.name]?.let { issue ->
                Text(issueText(issue), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                suggestions.forEach { s -> SuggestionChip(onClick = { fs.setValue(w.name, s) }, label = { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { fs.step(-1) }, enabled = order.size > 1) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.f5_prev)) }
                IconButton(onClick = { fs.step(1) }, enabled = order.size > 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.f5_next)) }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(w.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    if (pos > 0) Text(stringResource(R.string.f5_n_of, pos, order.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isDate) IconButton(onClick = { fs.datePickerFor = w.name }) { Icon(Icons.Default.CalendarMonth, stringResource(R.string.f5_pick_date)) }
                if (w.kind == WKind.CHECK || w.kind == WKind.RADIO) IconButton(onClick = { fs.toggle(w) }) { Icon(Icons.Default.CheckBox, stringResource(R.string.f5_toggle)) }
                if (w.kind == WKind.COMBO || w.kind == WKind.LIST) IconButton(onClick = { fs.menuFor = w.id }) { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.f5_choose)) }
                IconButton(onClick = { fs.setValue(w.name, if (w.kind == WKind.CHECK) "false" else "") }) { Icon(Icons.Default.Clear, stringResource(R.string.f5_clear)) }
                IconButton(onClick = { fs.undo() }, enabled = fs.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.f5_undo)) }
                IconButton(onClick = { fs.redo() }, enabled = fs.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.f5_redo)) }
                Spacer(Modifier.width(2.dp))
                TextButton(onClick = { fs.done() }) { Text(stringResource(R.string.f5_done)) }
            }
        }
    }
}

private fun num(v: Double?) = v?.let { AfNumbers.raw(it) } ?: ""

@Composable
internal fun issueText(i: Issue): String = when (i) {
    Issue.Required -> stringResource(R.string.f5_err_required)
    Issue.NotNumber -> stringResource(R.string.f5_err_number)
    is Issue.NotDate -> stringResource(R.string.f5_err_date, i.pattern)
    is Issue.OutOfRange -> when {
        i.min != null && i.max != null -> stringResource(R.string.f5_err_range, num(i.min), num(i.max))
        i.min != null -> stringResource(R.string.f5_err_min, num(i.min))
        else -> stringResource(R.string.f5_err_max, num(i.max))
    }
    is Issue.TooLong -> stringResource(R.string.f5_err_too_long, i.max)
    is Issue.BadFormat -> stringResource(R.string.f5_err_format)
    is Issue.Script -> i.message ?: stringResource(R.string.f5_err_script)
}

@Composable
private fun messageText(m: FormMessage): String = when (m) {
    is FormMessage.Submitted -> if (m.code == 0) stringResource(R.string.f5_submit_mail_ready) else stringResource(R.string.f5_submit_ok, m.code)
    is FormMessage.Failed -> stringResource(R.string.f5_submit_failed, m.reason)
    is FormMessage.MissingRequired -> stringResource(R.string.f5_missing_required, m.count)
    FormMessage.PrintHint -> stringResource(R.string.f5_print_hint)
    is FormMessage.Reset -> stringResource(R.string.f5_reset_done, m.count)
    FormMessage.RowAdded -> stringResource(R.string.f5_row_added)
}
