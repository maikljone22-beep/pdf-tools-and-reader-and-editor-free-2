package com.smartdocs.editor.ui.signature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.sign.PreSignAnalyzer

/** True when the pre-sign dialog should be shown even if the user chose "don't show again". */
fun PreSignAnalyzer.Report.mustWarn(certifyLevel: Int): Boolean = signingLocked || (certifyLevel > 0 && !canCertify)

/** True when there is anything worth showing at all. */
fun PreSignAnalyzer.Report.hasFindings(certifyLevel: Int): Boolean = items.isNotEmpty() || certifyLevel > 0

@Composable
private fun kindLabel(i: PreSignAnalyzer.Item): String = when (i.kind) {
    PreSignAnalyzer.Kind.DOC_SCRIPT -> stringResource(R.string.ps_ps_kind_doc_script)
    PreSignAnalyzer.Kind.PAGE_SCRIPT -> stringResource(R.string.ps_ps_kind_page_script)
    PreSignAnalyzer.Kind.FIELD_SCRIPT -> stringResource(R.string.ps_ps_kind_field_script)
    PreSignAnalyzer.Kind.LINK_SCRIPT -> stringResource(R.string.ps_ps_kind_link_script)
    PreSignAnalyzer.Kind.PAGE_TEMPLATE -> stringResource(R.string.ps_ps_kind_template)
    PreSignAnalyzer.Kind.XFA -> stringResource(R.string.ps_ps_kind_xfa)
    PreSignAnalyzer.Kind.LAYERS -> stringResource(R.string.ps_ps_kind_layers)
    PreSignAnalyzer.Kind.UNSIGNED_FIELD -> stringResource(R.string.ps_ps_kind_unsigned)
    PreSignAnalyzer.Kind.EMPTY_FIELDS -> stringResource(R.string.ps_ps_kind_empty, i.count)
    PreSignAnalyzer.Kind.EXISTING_SIGNATURE -> stringResource(R.string.ps_ps_kind_signed, i.count)
    PreSignAnalyzer.Kind.CERTIFIED -> stringResource(R.string.ps_ps_kind_certified, i.count)
}

@Composable
private fun eventLabel(i: PreSignAnalyzer.Item): String? {
    val res = when (i.event) {
        "OpenAction" -> R.string.ps_ev_open
        "WC" -> R.string.ps_ev_will_close
        "WS" -> R.string.ps_ev_will_save
        "DS" -> R.string.ps_ev_did_save
        "WP" -> R.string.ps_ev_will_print
        "DP" -> R.string.ps_ev_did_print
        "O" -> R.string.ps_ev_page_open
        "C" -> if (i.kind == PreSignAnalyzer.Kind.PAGE_SCRIPT) R.string.ps_ev_page_close else R.string.ps_ev_calculate
        "K" -> R.string.ps_ev_keystroke
        "F" -> R.string.ps_ev_format
        "V" -> R.string.ps_ev_validate
        "E", "X" -> R.string.ps_ev_mouse_hover
        "D", "U" -> R.string.ps_ev_click
        "Fo", "Bl" -> R.string.ps_ev_focus
        "PO", "PC", "PV", "PI" -> R.string.ps_ev_page_visibility
        "Names" -> R.string.ps_ev_doc_level
        "Templates" -> R.string.ps_ev_hidden_template
        "Pages" -> R.string.ps_ev_named_page
        else -> return null
    }
    return stringResource(res)
}

/**
 * Pre-sign warning: lists what can still change the document after signing (scripts and their trigger events,
 * page templates, XFA, unsigned fields …) and what the chosen signature type allows afterwards.
 */
@Composable
fun PreSignDialog(report: PreSignAnalyzer.Report, certifyLevel: Int, dontShow: Boolean, onDontShow: (Boolean) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val blocked = certifyLevel > 0 && !report.canCertify
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text(stringResource(R.string.ps_presign_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.items.isNotEmpty()) Text(stringResource(if (report.items.any { it.kind == PreSignAnalyzer.Kind.LAYERS }) R.string.ps_presign_intro_ocg else R.string.ps_presign_intro), style = MaterialTheme.typography.bodyMedium)
                if (report.signingLocked) Text(stringResource(R.string.ps_presign_locked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (blocked) Text(stringResource(R.string.ps_certify_not_first), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (report.items.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(report.items) { item ->
                        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Text(kindLabel(item), style = MaterialTheme.typography.labelLarge)
                                val where = buildList {
                                    if (item.target.isNotBlank() && item.kind != PreSignAnalyzer.Kind.XFA) add(item.target)
                                    eventLabel(item)?.let { add(it) }
                                    if (item.page >= 0) add(stringResource(R.string.ps_presign_page, item.page + 1))
                                }
                                if (where.isNotEmpty()) Text(where.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.snippet.isNotBlank()) Text(item.snippet, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 3)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.ps_presign_after), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(certifyDescription(certifyLevel)), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dontShow, onDontShow)
                    Text(stringResource(R.string.ps_presign_dont_show), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !blocked) { Text(stringResource(R.string.ps_presign_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
