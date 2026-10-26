package com.smartdocs.editor.ui.share5

import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabClient
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.collab.CollabFlows
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.share5.InvitedReviewer
import com.smartdocs.editor.core.share5.ReviewExtra
import com.smartdocs.editor.core.share5.ReviewExtras
import com.smartdocs.editor.core.share5.S5Client
import com.smartdocs.editor.core.share5.S5Config
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Invitation text for a review (the app link page of the collab worker, the optional browser link and the codes). */
internal fun inviteText(ctx: android.content.Context, title: String, id: String, code: String, webUrl: String?, deadline: Long, message: String): String = buildString {
    append(ctx.getString(R.string.sh5_invite_line, title)).append('\n')
    if (message.isNotBlank()) append(message.trim()).append('\n')
    if (deadline > 0) append(ctx.getString(R.string.sh5_invite_due, S5Share.date(deadline))).append('\n')
    append(ctx.getString(R.string.sh5_invite_app)).append(' ').append(CollabClient.shareUrl(id, code)).append('\n')
    if (webUrl != null) append(ctx.getString(R.string.sh5_invite_web)).append(' ').append(webUrl).append('\n')
    append(ctx.getString(R.string.sh5_invite_codes, id, code))
}

/** Route "share5/review_new?uri=": managed shared review (SHR-1, O1). */
@Composable
fun ReviewCreateScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(uri?.let { Uri.parse(it).path }?.let(::File)) }
    var title by remember { mutableStateOf(file?.nameWithoutExtension.orEmpty()) }
    var myName by remember { mutableStateOf(CollabStore.authorName(ctx).ifBlank { settings.authorName }) }
    var myEmail by remember { mutableStateOf("") }
    var deadline by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf("") }
    val reviewers = remember { mutableStateListOf<InvitedReviewer>() }
    var rName by remember { mutableStateOf("") }
    var rEmail by remember { mutableStateOf("") }
    var allowWeb by remember { mutableStateOf(S5Config.enabled) }
    var allowDownload by remember { mutableStateOf(true) }
    var reminders by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Pair<String, String>?>(null) } // id, invitation text
    var doneLink by remember { mutableStateOf("") }
    var mailed by remember { mutableIntStateOf(-1) }
    val pick = rememberPdfPicker { f -> file = f.firstOrNull(); if (title.isBlank()) title = file?.nameWithoutExtension.orEmpty() }
    val tooLarge = stringResource(R.string.sh5_too_large)
    val encrypted = stringResource(R.string.sh5_encrypted)

    if (!CollabConfig.enabled) {
        Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_review_new), onBack = { nav.popBackStack() }) }) { pad ->
            EmptyState(Icons.Default.CloudOff, stringResource(R.string.sh5_collab_off), Modifier.padding(pad))
        }
        return
    }

    fun create() {
        val f = file ?: return
        if (rName.isNotBlank() || rEmail.isNotBlank()) { reviewers += InvitedReviewer(rName.trim(), rEmail.trim()); rName = ""; rEmail = "" }
        scope.launch {
            busy = true; error = null
            try {
                CollabStore.setAuthorName(ctx, myName.trim())
                val (ref, created) = CollabFlows.create(ctx, f, title.trim().ifBlank { f.nameWithoutExtension }, deadline)
                val extra = ReviewExtra(title.trim(), message.trim(), myName.trim(), myEmail.trim(), reviewers.toList(), allowWeb, allowDownload, reminders)
                ReviewExtras.put(ctx, ref.id, extra)
                var web: String? = null
                if (S5Config.enabled) withContext(Dispatchers.IO) {
                    web = runCatching { S5Client.putExtra(ref.id, created.ownerKey, extra) }.getOrNull()
                    ReviewExtras.setWebUrl(ctx, ref.id, web)
                    if (reviewers.any { it.email.contains('@') }) mailed = runCatching { S5Client.remind(ref.id, created.ownerKey, first = true) }.getOrDefault(-1)
                }
                doneLink = web ?: created.shareUrl
                done = ref.id to inviteText(ctx, ref.title, ref.id, created.code, web, deadline, message)
            } catch (e: Exception) {
                error = when (e) { is CollabFlows.TooLarge -> tooLarge; is CollabFlows.Encrypted -> encrypted; else -> e.message ?: e.toString() }
            }
            busy = false
        }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_review_new), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilePickRow(file, stringResource(R.string.sh5_pick_pdf), pick)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_subject)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(myName, { myName = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.sh5_my_name)) }, singleLine = true)
                OutlinedTextField(myEmail, { myEmail = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.sh5_my_email)) }, singleLine = true)
            }
            Text(stringResource(R.string.sh5_deadline), style = MaterialTheme.typography.titleSmall)
            DateChooser(deadline, { deadline = it }, listOf(1, 3, 7, 14), stringResource(R.string.sh5_no_deadline))
            Text(stringResource(R.string.sh5_reviewers), style = MaterialTheme.typography.titleSmall)
            reviewers.forEachIndexed { i, r ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(r.name.ifBlank { r.email }); if (r.name.isNotBlank() && r.email.isNotBlank()) Text(r.email, style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { reviewers.removeAt(i) }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(rName, { rName = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.sh5_r_name)) }, singleLine = true)
                OutlinedTextField(rEmail, { rEmail = it }, Modifier.weight(1.2f), label = { Text(stringResource(R.string.sh5_r_email)) }, singleLine = true)
                FilledTonalIconButton(enabled = rName.isNotBlank() || rEmail.isNotBlank(), onClick = { reviewers += InvitedReviewer(rName.trim(), rEmail.trim()); rName = ""; rEmail = "" }) {
                    Icon(Icons.Default.PersonAdd, stringResource(R.string.sh5_add))
                }
            }
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sh5_message)) }, minLines = 2)
            Text(stringResource(R.string.sh5_permissions), style = MaterialTheme.typography.titleSmall)
            SwitchRow(stringResource(R.string.sh5_allow_web), stringResource(if (S5Config.enabled) R.string.sh5_allow_web_sub else R.string.sh5_server_off_short), allowWeb, S5Config.enabled) { allowWeb = it }
            SwitchRow(stringResource(R.string.sh5_allow_download), null, allowDownload, true) { allowDownload = it }
            SwitchRow(stringResource(R.string.sh5_auto_remind), stringResource(R.string.sh5_auto_remind_sub), reminders, S5Config.enabled) { reminders = it }
            Text(stringResource(R.string.sh5_review_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { create() }, enabled = file != null && !busy && myName.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = PillShape) {
                Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sh5_create_send))
            }
        }
    }
    if (busy) ProgressDialog(stringResource(R.string.sh5_uploading))
    done?.let { (id, text) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sh5_review_ready)) },
            text = { Column {
                if (mailed > 0) Text(stringResource(R.string.sh5_mailed_n, mailed), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.sh5_invite_how), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                InviteActions(text, doneLink, title, reviewers.map { it.email }.filter { it.contains('@') })
            } },
            confirmButton = { TextButton(onClick = { done = null; nav.popBackStack(); nav.navigate(Share5Routes.review(id)) }) { Text(stringResource(R.string.sh5_manage)) } },
        )
    }
}

@Composable
internal fun SwitchRow(title: String, sub: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked && enabled, onChange, enabled = enabled)
    }
}
