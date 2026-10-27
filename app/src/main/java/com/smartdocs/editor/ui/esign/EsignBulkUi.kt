package com.smartdocs.editor.ui.esign

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.BulkItem
import com.smartdocs.editor.core.esign.BulkSender
import com.smartdocs.editor.core.esign.BulkState
import com.smartdocs.editor.core.esign.BulkTemplate
import com.smartdocs.editor.core.esign.CsvRecipients
import com.smartdocs.editor.core.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recipient list of a bulk send: paste (one per line) or import a CSV file; shows what was understood and what was skipped. */
@Composable
fun BulkRecipientsEditor(text: String, onText: (String) -> Unit, parsed: CsvRecipients.Result) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty")
                    require(bytes.size <= 2 * 1024 * 1024) { "> 2 MB" }
                    CsvRecipients.decode(bytes)
                }
            }.onSuccess { importError = null; onText(it) }
                .onFailure { importError = ctx.getString(R.string.ne2_bulk_import_failed, it.message ?: it.toString()) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NoteCard(stringResource(R.string.ne2_bulk_mode_desc), Icons.Default.Groups)
        OutlinedTextField(text, onText, label = { Text(stringResource(R.string.ne2_bulk_paste_label)) }, minLines = 5, maxLines = 12,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { importer.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/tab-separated-values", "application/vnd.ms-excel", "*/*")) },
                shape = PillShape) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ne2_bulk_import)) }
            Spacer(Modifier.weight(1f))
            if (text.isNotEmpty()) TextButton(onClick = { onText("") }) { Text(stringResource(R.string.ne2_bulk_clear)) }
        }
        importError?.let { NoteCard(it, Icons.Default.ErrorOutline, error = true) }
        if (parsed.rows.isNotEmpty()) {
            Text(stringResource(R.string.ne2_bulk_ready, parsed.rows.size), style = MaterialTheme.typography.titleSmall)
            parsed.rows.take(5).forEach { r ->
                Text(listOf(r.name, r.email).filter { it.isNotBlank() }.joinToString(" · ") +
                    (if (r.extra.isNotEmpty()) " · " + r.extra.values.filter { it.isNotBlank() }.joinToString(", ") else ""),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (parsed.rows.size > 5) Text(stringResource(R.string.ne2_bulk_more, parsed.rows.size - 5), style = MaterialTheme.typography.bodySmall)
            NoteCard(stringResource(R.string.ne2_bulk_placeholders, BulkTemplate.available(parsed.headers, parsed.rows.firstOrNull()).joinToString("  ")), Icons.Default.DataObject)
        }
        if (parsed.issues.isNotEmpty()) {
            Text(stringResource(R.string.ne2_bulk_issues, parsed.issues.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            parsed.issues.take(10).forEach { Text(issueText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (parsed.issues.size > 10) Text(stringResource(R.string.ne2_bulk_more, parsed.issues.size - 10), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun issueText(i: CsvRecipients.RowIssue): String = when (i.issue) {
    CsvRecipients.Issue.MISSING_NAME -> stringResource(R.string.ne2_issue_missing_name, i.line)
    CsvRecipients.Issue.BAD_EMAIL -> stringResource(R.string.ne2_issue_bad_email, i.line, i.text)
    CsvRecipients.Issue.DUPLICATE -> stringResource(R.string.ne2_issue_duplicate, i.line, i.text)
    CsvRecipients.Issue.TOO_MANY -> stringResource(R.string.ne2_issue_too_many, i.line, CsvRecipients.MAX_ROWS)
}

/** Outcome of a bulk send: counts, the failed rows with their error, retry of the failed ones, and all links to share. */
@Composable
fun BulkResultDialog(items: List<BulkItem>, onRetry: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val sent = BulkSender.sent(items)
    val failed = items.filter { it.state == BulkState.FAILED }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.ne2_bulk_result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ne2_bulk_result_body, sent, failed.size), style = MaterialTheme.typography.titleSmall)
                failed.take(30).forEach { Text(stringResource(R.string.ne2_bulk_failed_row, it.row.name, it.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (failed.size > 30) Text(stringResource(R.string.ne2_bulk_more, failed.size - 30), style = MaterialTheme.typography.bodySmall)
                if (sent > 0) {
                    Text(stringResource(R.string.ne2_bulk_links_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        ShareHelpers.copy(ctx, items.filter { it.state == BulkState.SENT }.joinToString("\n") { "${it.row.name}: ${it.link}" })
                    }, shape = PillShape) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ne2_bulk_copy_links)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cn_done)) } },
        dismissButton = { if (failed.isNotEmpty()) TextButton(onClick = onRetry) { Text(stringResource(R.string.ne2_bulk_retry, failed.size)) } },
    )
}
