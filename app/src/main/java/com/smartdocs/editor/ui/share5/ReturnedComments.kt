package com.smartdocs.editor.ui.share5

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.CommentExchange
import com.smartdocs.editor.core.share5.EmailReview
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reader hook (SHR-3): when the opened PDF is a reviewer's annotated copy of a document this device sent for
 * e-mail review, offer to merge its comments into the master copy. Invisible otherwise.
 */
@Composable
fun ReturnedCommentsHost(file: File, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember(file.absolutePath) { mutableStateOf<EmailReview.Found?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath) { found = runCatching { EmailReview.match(ctx, file) }.getOrNull() }
    val f = found ?: return
    AlertDialog(
        onDismissRequest = { EmailReview.dismiss(ctx, f); found = null },
        icon = { Icon(Icons.Default.MergeType, null) },
        title = { Text(stringResource(R.string.sh5_merge_q_title)) },
        text = { Text(stringResource(R.string.sh5_merge_q, f.authors.joinToString("، ").ifBlank { ctx.getString(R.string.sh5_reviewer) }, f.record.subject.ifBlank { f.record.fileName })) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                runCatching { EmailReview.merge(ctx, f, file) }
                    .onSuccess { (n, master) -> Toast.makeText(ctx, ctx.getString(R.string.sh5_merged_n, n), Toast.LENGTH_LONG).show(); found = null
                        nav.navigate(Routes.reader(Uri.fromFile(master).toString())) }
                    .onFailure { Toast.makeText(ctx, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }
                busy = false
            }
        }) { Text(stringResource(R.string.sh5_merge)) } },
        dismissButton = { TextButton(onClick = { EmailReview.dismiss(ctx, f); found = null }) { Text(stringResource(R.string.sh5_not_now)) } },
    )
}

/** Route "share5/returned?uri=": an XFDF / FDF / annotated PDF opened from a mail app. */
@Composable
fun ReturnedScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var incoming by remember { mutableStateOf<File?>(null) }
    var found by remember { mutableStateOf<EmailReview.Found?>(null) }
    var checked by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        val u = uri?.let(Uri::parse) ?: return@LaunchedEffect
        incoming = runCatching { if (u.scheme == "file") File(u.path!!) else app.documents.localFile(u) }.getOrNull()
        incoming?.let { found = runCatching { EmailReview.match(ctx, it) }.getOrNull() }
        // Foxit 24.90: an FDF / XFDF names its PDF (/F, <f href>); when that PDF is on the device it is preselected
        if (found == null) incoming?.let { inc ->
            target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.smartdocs.editor.core.annot.CommentOps7.fdfTargetName(inc)?.let { n ->
                    com.smartdocs.editor.core.annot.CommentOps7.findPdf(n, listOfNotNull(inc.parentFile, app.documents.docsDir, ctx.getExternalFilesDir(null)))
                }
            }
        }
        checked = true
    }
    val pickTarget = rememberPdfPicker { target = it.firstOrNull() }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_returned_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val inc = incoming
            if (!checked) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (inc == null) Text(stringResource(R.string.sh5_returned_unreadable), color = MaterialTheme.colorScheme.error)
            else {
                Text(inc.name, style = MaterialTheme.typography.titleMedium)
                val f = found
                if (f != null) {
                    Text(stringResource(R.string.sh5_merge_q, f.authors.joinToString("، ").ifBlank { ctx.getString(R.string.sh5_reviewer) }, f.record.subject.ifBlank { f.record.fileName }))
                    Button(enabled = !busy, shape = PillShape, modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            busy = true
                            runCatching { EmailReview.merge(ctx, f, inc) }
                                .onSuccess { (n, master) -> Toast.makeText(ctx, ctx.getString(R.string.sh5_merged_n, n), Toast.LENGTH_LONG).show()
                                    nav.popBackStack(); nav.navigate(Routes.reader(Uri.fromFile(master).toString())) }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }) { Icon(Icons.Default.MergeType, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_merge)) }
                } else {
                    Text(stringResource(R.string.sh5_returned_nomatch), style = MaterialTheme.typography.bodyMedium)
                    FilePickRow(target, stringResource(R.string.sh5_pick_target), pickTarget)
                    Button(enabled = target != null && !busy, shape = PillShape, modifier = Modifier.fillMaxWidth(), onClick = {
                        val t = target ?: return@Button
                        scope.launch {
                            busy = true; error = null
                            runCatching {
                                val out = app.documents.uniqueFile(app.documents.docsDir, t.nameWithoutExtension + " - " + ctx.getString(R.string.sh5_with_comments))
                                val kind = EmailReview.kindOf(inc)
                                if (kind == EmailReview.Kind.PDF) CommentExchange.merge(ctx, t, out, null, inc) else CommentExchange.importFile(ctx, t, out, null, inc)
                                out
                            }.onSuccess { result = it }.onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.sh5_import_into)) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.sh5_merging))
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
}

/** Route "share5/email_new?uri=": send a copy for review by e-mail; returned comments are merged later. */
@Composable
fun EmailReviewScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(uri?.let { Uri.parse(it).path }?.let(::File)) }
    var subject by remember { mutableStateOf(file?.nameWithoutExtension.orEmpty()) }
    var to by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberPdfPicker { f -> file = f.firstOrNull(); if (subject.isBlank()) subject = file?.nameWithoutExtension.orEmpty() }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_email_review), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sh5_email_review_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilePickRow(file, stringResource(R.string.sh5_pick_pdf), pick)
            OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_subject)) }, singleLine = true)
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_recipients)) })
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_message)) }, minLines = 2)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = file != null && !busy, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                val f = file ?: return@Button
                scope.launch {
                    busy = true; error = null
                    runCatching { EmailReview.prepare(ctx, f, null, subject.trim(), to.trim()) }
                        .onSuccess { rec ->
                            val body = (message.trim().takeIf { it.isNotEmpty() }?.let { it + "\n\n" } ?: "") + ctx.getString(R.string.sh5_email_review_body)
                            S5Share.email(ctx, listOf(File(rec.masterPath)), "application/pdf", S5Share.emails(to), ctx.getString(R.string.sh5_email_review_subject, subject.trim().ifBlank { f.nameWithoutExtension }), body)
                            nav.popBackStack()
                        }
                        .onFailure { error = it.message ?: it.toString() }
                    busy = false
                }
            }) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_send)) }
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.sh5_preparing))
}
