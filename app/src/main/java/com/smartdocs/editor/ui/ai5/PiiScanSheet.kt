package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.PiiDetector
import com.smartdocs.editor.core.ai5.PiiHit
import com.smartdocs.editor.core.ai5.PiiKind
import com.smartdocs.editor.core.ai5.PiiScanner
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.SearchHit
import java.io.File

@Composable
fun piiLabel(k: PiiKind): String = stringResource(when (k) {
    PiiKind.EMAIL -> R.string.a5_pii_email; PiiKind.PHONE -> R.string.a5_pii_phone; PiiKind.CARD -> R.string.a5_pii_card; PiiKind.IBAN -> R.string.a5_pii_iban
    PiiKind.NATIONAL_ID -> R.string.a5_pii_id; PiiKind.PASSPORT -> R.string.a5_pii_passport; PiiKind.DATE -> R.string.a5_pii_date
    PiiKind.URL -> R.string.a5_pii_url; PiiKind.IP -> R.string.a5_pii_ip
    PiiKind.PERSON -> R.string.nt_pii_person; PiiKind.ORG -> R.string.nt_pii_org; PiiKind.ROLE -> R.string.nt_pii_role
})

/**
 * Smart PII scan (on device). Lists findings by kind with checkboxes; [onApply] receives the selected hits in the
 * same convention as [PdfSession.search] (used by the redaction screen to mark them).
 * Pass an open [session] when there is one, otherwise [file] (+ [password]) is opened briefly.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PiiScanSheet(file: File?, password: String?, session: PdfSession? = null, onApply: (List<SearchHit>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var kinds by remember { mutableStateOf(PiiKind.entries.toSet() - PiiKind.DATE - com.smartdocs.editor.core.ai5.PiiEntities.KINDS) }
    val settings = com.smartdocs.editor.LocalSettings.current
    val aiReady = com.smartdocs.editor.core.ai.AiClient.available(settings)
    var useAi by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<PiiHit>?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var progress by remember { mutableStateOf(0f) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runKey by remember { mutableStateOf(0) }
    LaunchedEffect(runKey) {
        scanning = true; error = null
        runCatching {
            val s = session ?: PdfSession.open(ctx, file ?: return@runCatching emptyList<PiiHit>(), password)
            val ai: (suspend (String) -> List<com.smartdocs.editor.core.ai5.PiiMatch>)? =
                if (useAi && aiReady && kinds.any { it in com.smartdocs.editor.core.ai5.PiiEntities.KINDS }) {
                    com.smartdocs.editor.core.ai5.PiiEntities.finder(ctx, com.smartdocs.editor.core.ai.AiClient(settings), kinds)
                } else null
            try { PiiScanner.scan(s, kinds, progress = { p, n -> progress = (p + 1f) / n.coerceAtLeast(1) }, ai = ai) } finally { if (session == null) s.closeAsync() }
        }.onSuccess { list -> hits = list; selected = list.indices.toSet() }.onFailure { error = it.message }
        scanning = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.a5_pii_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.a5_pii_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PiiKind.entries.forEach { k -> FilterChip(selected = k in kinds, onClick = { kinds = if (k in kinds) kinds - k else kinds + k }, label = { Text(piiLabel(k)) }) }
            }
            if (kinds.any { it in com.smartdocs.editor.core.ai5.PiiEntities.KINDS }) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(useAi && aiReady, { useAi = it }, enabled = aiReady)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (aiReady) R.string.nt_pii_use_ai else R.string.nt_pii_ai_off), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { runKey++ }, enabled = !scanning) { Text(stringResource(R.string.a5_scan_again)) }
            if (scanning) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = hits
            if (list != null && !scanning) {
                Text(stringResource(R.string.a5_pii_found, list.size), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list.indices.toList()) { i ->
                        val h = list[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i in selected, { selected = if (it) selected + i else selected - i })
                            Column(Modifier.weight(1f)) {
                                Text(piiLabel(h.match.kind) + " · " + stringResource(R.string.a5_page_n, h.hit.page + 1), style = MaterialTheme.typography.labelMedium)
                                Text(PiiDetector.mask(h.match.value), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selected = if (selected.size == list.size) emptySet() else list.indices.toSet() }) { Text(stringResource(R.string.a5_select_all)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onApply(selected.sorted().map { list[it].hit }) }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.a5_mark_for_redaction)) }
                }
            }
        }
    }
}
