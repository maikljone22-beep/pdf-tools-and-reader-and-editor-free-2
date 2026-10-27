package com.smartdocs.editor.ui.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.redact.RedactMark
import java.text.DateFormat
import java.util.Date

/** Review states of the Review state model (Foxit / Acrobat "Set status"). */
val REVIEW_STATES = listOf("Accepted", "Rejected", "Cancelled", "Completed", "None")

@Composable
fun reviewLabel(state: String) = stringResource(when (state) {
    "Accepted" -> R.string.nt_rv_accepted; "Rejected" -> R.string.nt_rv_rejected; "Cancelled" -> R.string.nt_rv_cancelled
    "Completed" -> R.string.nt_rv_completed; else -> R.string.nt_rv_none
})

/**
 * Mark properties beyond the look (Foxit Properties): General (author, subject), Locked, the Review History with
 * "set status", the appearance dialog, and "apply this mark only" (the others stay pending).
 */
@Composable
fun MarkPropertiesDialog(
    m: RedactMark, me: String, onDismiss: () -> Unit, onAppearance: () -> Unit,
    onSave: (author: String, subject: String, locked: Boolean) -> Unit, onReview: (String) -> Unit, onApplyOne: () -> Unit,
) {
    var author by remember { mutableStateOf(m.author.ifBlank { me }) }
    var subject by remember { mutableStateOf(m.subject.ifBlank { "Redact" }) }
    var locked by remember { mutableStateOf(m.locked) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.s5_properties)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nt_rd_general), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nt_rd_author)) })
                OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nt_rd_subject)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nt_rd_locked), Modifier.weight(1f)); Switch(locked, { locked = it })
                }
                OutlinedButton(onClick = onAppearance, Modifier.fillMaxWidth()) { Icon(Icons.Default.Palette, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_rd_appearance)) }
                HorizontalDivider()
                Text(stringResource(R.string.nt_rd_review_history), style = MaterialTheme.typography.labelLarge)
                if (m.reviews.isEmpty()) Text(stringResource(R.string.nt_rd_no_reviews), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                m.reviews.asReversed().forEach { r ->
                    Text("${reviewLabel(r.state)} — ${r.author}" + if (r.time > 0) " · " + fmt.format(Date(r.time)) else "", style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.nt_rd_set_status), style = MaterialTheme.typography.labelMedium)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    REVIEW_STATES.forEach { s -> AssistChip(onClick = { onReview(s) }, label = { Text(reviewLabel(s)) }) }
                }
                HorizontalDivider()
                OutlinedButton(onClick = onApplyOne, Modifier.fillMaxWidth()) { Icon(Icons.Default.Gavel, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_rd_apply_one)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(author.trim(), subject.trim(), locked) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Opens a text file and hands its contents to [onText] (word lists, code sets, profiles). */
@Composable
fun rememberTextImport(mimes: Array<String> = arrayOf("text/plain", "application/json", "application/octet-stream"), onText: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()?.let(onText)
    }
    return { l.launch(mimes) }
}

/** Saves the text [text]() returns as a file the user names (word lists, code sets, profiles). */
@Composable
fun rememberTextExport(mime: String, text: () -> String, onDone: (Boolean) -> Unit = {}): (String) -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) onDone(runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text().toByteArray()) } != null }.getOrDefault(false))
    }
    return { name -> l.launch(name) }
}
