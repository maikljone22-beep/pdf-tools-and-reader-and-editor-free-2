package com.smartdocs.editor.ui.share5

import android.net.Uri
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
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.CommentExchange
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.share5.FdfRule
import com.smartdocs.editor.core.share5.RecipientBook
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Route "share5/send_back?uri=" (fc-sync, Foxit §37): a reviewer sends an e-mail review back. Large files go as an FDF
 * with only the comments ([FdfRule], default above 5 MB); To / Cc offer addresses used before ([RecipientBook]).
 * The sender's "Returned comments" screen merges either form into the master copy.
 */
@Composable
fun SendBackScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(uri?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.isFile }) }
    var to by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf(file?.let { ctx.getString(R.string.fcy_back_subject, it.nameWithoutExtension) }.orEmpty()) }
    var message by remember { mutableStateOf("") }
    var ruleOn by remember { mutableStateOf(FdfRule.enabled(ctx)) }
    var limit by remember { mutableIntStateOf(FdfRule.limitMb(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberPdfPicker { l -> file = l.firstOrNull(); if (subject.isBlank()) subject = file?.let { ctx.getString(R.string.fcy_back_subject, it.nameWithoutExtension) }.orEmpty() }
    val noComments = stringResource(R.string.fcy_back_no_comments)

    Scaffold(topBar = { AppTopBar(stringResource(R.string.fcy_back_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.fcy_back_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilePickRow(file, stringResource(R.string.sh5_pick_pdf), pick)
            RecipientField(to, { to = it }, stringResource(R.string.sh5_recipients))
            RecipientField(cc, { cc = it }, stringResource(R.string.fcy_cc))
            OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_subject)) }, singleLine = true)
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_message)) }, minLines = 2)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.fcy_fdf_rule, limit), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(ruleOn, { ruleOn = it; FdfRule.set(ctx, it, limit) })
                    }
                    if (ruleOn) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FdfRule.LIMITS_MB.forEach { mb -> FilterChip(selected = mb == limit, onClick = { limit = mb; FdfRule.set(ctx, ruleOn, mb) }, label = { Text("$mb") }) }
                    }
                    file?.let { f ->
                        val fdf = FdfRule.useFdf(f.length(), ruleOn, limit)
                        Text(stringResource(if (fdf) R.string.fcy_will_send_fdf else R.string.fcy_will_send_pdf, DocumentRepository.formatSize(f.length())),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = file != null && !busy, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                val f = file ?: return@Button
                scope.launch {
                    busy = true; error = null
                    runCatching {
                        if (FdfRule.useFdf(f.length(), ruleOn, limit)) {
                            val out = File(File(ctx.cacheDir, "send_back").apply { mkdirs() }, f.nameWithoutExtension + ".fdf")
                            val n = withContext(Dispatchers.IO) { CommentExchange.exportFdf(f, null, out) }
                            if (n == 0) throw IllegalStateException(noComments)
                            out to "application/vnd.fdf"
                        } else f to "application/pdf"
                    }.onSuccess { (attach, mime) ->
                        RecipientBook.remember(ctx, S5Share.emails(to) + S5Share.emails(cc))
                        S5Share.email(ctx, listOf(attach), mime, S5Share.emails(to), subject.trim().ifBlank { f.nameWithoutExtension }, message.trim(), cc = S5Share.emails(cc))
                        nav.popBackStack()
                    }.onFailure { error = it.message ?: it.toString() }
                    busy = false
                }
            }) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_send)) }
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.sh5_preparing))
}
