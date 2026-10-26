package com.smartdocs.editor.ui.signature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.sign.RevisionDiff
import com.smartdocs.editor.core.sign.RevisionSnapshot

@Composable
private fun changeLabel(c: RevisionDiff.Change): String {
    val p = (c.page ?: 0) + 1
    return when (c.kind) {
        RevisionDiff.Kind.PAGE_ADDED -> stringResource(R.string.g6_ch_page_added, p)
        RevisionDiff.Kind.PAGE_REMOVED -> stringResource(R.string.g6_ch_page_removed, p)
        RevisionDiff.Kind.PAGE_ROTATED -> stringResource(R.string.g6_ch_page_rotated, p, c.detail)
        RevisionDiff.Kind.PAGE_RESIZED -> stringResource(R.string.g6_ch_page_resized, p)
        RevisionDiff.Kind.FIELD_ADDED -> stringResource(R.string.g6_ch_field_added, c.detail)
        RevisionDiff.Kind.FIELD_REMOVED -> stringResource(R.string.g6_ch_field_removed, c.detail)
        RevisionDiff.Kind.FIELD_CHANGED -> stringResource(R.string.g6_ch_field_changed, c.detail)
        RevisionDiff.Kind.ANNOT_ADDED -> stringResource(R.string.g6_ch_annot_added, c.detail, p)
        RevisionDiff.Kind.ANNOT_REMOVED -> stringResource(R.string.g6_ch_annot_removed, c.detail, p)
        RevisionDiff.Kind.LINK_ADDED -> stringResource(R.string.g6_ch_link_added, p)
        RevisionDiff.Kind.LINK_REMOVED -> stringResource(R.string.g6_ch_link_removed, p)
    }
}

/** One-line summary card in the sign tab ("3 changes since the last signature" → list). */
@Composable
fun SignChangesCard(report: RevisionSnapshot.Report?, onShow: () -> Unit) {
    val r = report ?: return
    if (r.base == RevisionSnapshot.Base.NONE) return
    OutlinedCard(onClick = onShow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = if (r.changes.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.g6_ch_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (r.changes.isEmpty()) stringResource(R.string.g6_ch_none)
                    else stringResource(if (r.base == RevisionSnapshot.Base.LAST_SIGNATURE) R.string.g6_ch_since_sig else R.string.g6_ch_since_rev, r.changes.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full list; [onConfirm] non-null = shown before signing ("sign anyway"). */
@Composable
fun SignChangesDialog(report: RevisionSnapshot.Report, onDismiss: () -> Unit, onConfirm: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.History, null) },
        title = { Text(stringResource(R.string.g6_ch_title)) },
        text = {
            Column {
                Text(stringResource(if (report.base == RevisionSnapshot.Base.LAST_SIGNATURE) R.string.g6_ch_base_sig else R.string.g6_ch_base_rev, report.revisions),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (report.changes.isEmpty()) Text(stringResource(R.string.g6_ch_none))
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(report.changes) { c -> Text("• " + changeLabel(c), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            }
        },
        confirmButton = {
            if (onConfirm != null) TextButton(onClick = onConfirm) { Text(stringResource(R.string.g6_ch_sign_anyway)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { if (onConfirm != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
