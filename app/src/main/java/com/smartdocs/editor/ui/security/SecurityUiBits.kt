package com.smartdocs.editor.ui.security

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.redact.SanitizeSkip
import com.smartdocs.editor.core.redact.SkipReason

/** audit 5 P-L: a checkbox with its label as ONE toggleable row (TalkBack reads the label; the whole row is the target). */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** audit 5 P-L: a radio button with its label (and optional [content]) as one selectable row. */
@Composable
internal fun RadioRow(selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Row(Modifier.padding(start = 8.dp)) { content() }
    }
}

/** audit 5 R-8: the lines of the report for hidden content that could not be removed (null when nothing was skipped). */
@Composable
internal fun rememberSkipText(): (List<SanitizeSkip>) -> String? {
    val title = stringResource(R.string.ttrd_skipped_title)
    val unreadable = stringResource(R.string.ttrd_skip_unreadable)
    val nested = stringResource(R.string.ttrd_skip_nested)
    val vertical = stringResource(R.string.ttrd_skip_vertical)
    val size = stringResource(R.string.ttrd_skip_size)
    val rewrite = stringResource(R.string.ttrd_skip_rewrite)
    val hint = stringResource(R.string.ttrd_skip_hint)
    return { list ->
        if (list.isEmpty()) null
        else (listOf(title) + list.take(30).map { s ->
            val p = s.page + 1
            when (s.reason) {
                SkipReason.UNREADABLE -> unreadable.format(p)
                SkipReason.NESTED -> nested.format(p, s.count)
                SkipReason.VERTICAL -> vertical.format(p, s.count)
                SkipReason.ODD_SIZE -> size.format(p, s.count)
                SkipReason.REWRITE_FAILED -> rewrite.format(p, s.count)
            }
        } + listOf(hint)).joinToString("\n")
    }
}
