package com.smartdocs.editor.ui.nve.panes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.panes.DocNotice
import com.smartdocs.editor.core.nve.panes.DocNotices
import com.smartdocs.editor.core.nve.panes.NoticeHit
import com.smartdocs.editor.ui.nve.NvePane
import com.smartdocs.editor.ui.nve.NveState
import com.smartdocs.editor.ui.reader.ReaderState

/**
 * Document notifications bar (Nitro / Foxit): dismissible banners for form fields, JavaScript, font licence
 * restrictions, PDF/A and PDF/UA claims, unapplied redaction marks, 3D content (only its poster image is shown) and
 * PDF portfolios. Detection runs once per file version ([DocNotices]); dismissals live in NveState.dismissedBanners.
 * [onOpenTool] opens a tool route id on this document ("forms", "redact").
 */
@Composable
fun NveDocBanners(state: ReaderState, onOpenTool: (String) -> Unit, modifier: Modifier = Modifier) {
    val n = NveState.of(state)
    val f = state.file
    val hits by produceState<List<NoticeHit>>(emptyList(), f, state.password, state.renderVersion, state.session != null) {
        value = if (f == null || state.session == null) emptyList() else runCatching { readDoc(f, state.password) { DocNotices.detect(it) } }.getOrDefault(emptyList())
    }
    val shown = hits.filter { it.kind.name !in n.dismissedBanners }
    AnimatedVisibility(shown.isNotEmpty(), modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { h ->
                val (icon, text) = when (h.kind) {
                    DocNotice.FORMS -> Icons.Default.Checklist to stringResource(R.string.np_bn_forms, h.count)
                    DocNotice.JAVASCRIPT -> Icons.Default.Code to stringResource(R.string.np_bn_js, h.count)
                    DocNotice.FONT_RESTRICTED -> Icons.Default.FontDownloadOff to stringResource(R.string.np_bn_fonts, h.detail)
                    DocNotice.PDFA -> Icons.Default.Inventory to stringResource(R.string.np_bn_pdfa, h.detail)
                    DocNotice.PDFUA -> Icons.Default.Accessibility to stringResource(R.string.np_bn_pdfua, h.detail)
                    DocNotice.PENDING_REDACTIONS -> Icons.Default.Block to stringResource(R.string.np_bn_redact, h.count)
                    DocNotice.THREE_D -> Icons.Default.ViewInAr to stringResource(R.string.np_bn_3d, h.count)
                    DocNotice.PORTFOLIO -> Icons.Default.FolderZip to stringResource(R.string.np_bn_portfolio, h.count)
                }
                val action: Pair<String, () -> Unit>? = when (h.kind) {
                    DocNotice.FORMS -> stringResource(R.string.np_bn_forms_act) to { onOpenTool("forms") }
                    DocNotice.PENDING_REDACTIONS -> stringResource(R.string.np_bn_redact_act) to { onOpenTool("redact") }
                    DocNotice.PORTFOLIO -> stringResource(R.string.np_bn_portfolio_act) to { n.pane = NvePane.ATTACHMENTS }
                    else -> null
                }
                Banner(icon, text, action) { n.dismissedBanners += h.kind.name }
            }
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, action: Pair<String, () -> Unit>?, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
            IconButton(onClick = onDismiss, Modifier.size(32.dp)) { Icon(Icons.Default.Close, stringResource(R.string.np_dismiss), Modifier.size(18.dp)) }
        }
    }
}
