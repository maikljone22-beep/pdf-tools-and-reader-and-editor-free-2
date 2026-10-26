package com.smartdocs.editor.ui.share5

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabClient
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.collab.ReviewMeta
import com.smartdocs.editor.core.share5.InvitedReviewer
import com.smartdocs.editor.core.share5.ReviewExtra
import com.smartdocs.editor.core.share5.ReviewExtras
import com.smartdocs.editor.core.share5.ReviewStatus
import com.smartdocs.editor.core.share5.S5Client
import com.smartdocs.editor.core.share5.S5Config
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Route "share5/review/{id}": reviewer status, reminders, deadline, end / reopen, revoke link, activity (owner); privacy (reviewer). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewManageScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reviews by CollabStore.reviews.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { CollabStore.load(ctx) }
    val ref = reviews.firstOrNull { it.id == id }
    var status by remember { mutableStateOf<ReviewStatus?>(null) }
    var meta by remember { mutableStateOf<ReviewMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var deadlineDialog by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var inviteDialog by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }

    LaunchedEffect(ref?.id, ref?.code, reload) {
        val r = ref ?: return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching { CollabClient.meta(r.id, r.code) }.onSuccess { meta = it; error = null }
                    .onFailure { error = if (CollabClient.isGone(it)) ctx.getString(R.string.sh5_review_gone) else it.message }
                if (r.isOwner && S5Config.enabled) runCatching { S5Client.status(r.id, r.ownerKey!!) }.onSuccess { status = it }
            }
            delay(30_000)
        }
    }

    if (ref == null) {
        Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_manage), onBack = { nav.popBackStack() }) }) { pad -> EmptyState(Icons.Default.SearchOff, stringResource(R.string.sh5_review_gone), Modifier.padding(pad)) }
        return
    }
    val extra = status?.extra ?: ReviewExtras.get(ctx, ref.id) ?: ReviewExtra(subject = ref.title)
    val deadline = status?.deadline ?: meta?.deadline ?: ref.deadline
    val closed = status?.closed ?: meta?.closed ?: ref.closed
    val ended = closed || (deadline > 0 && System.currentTimeMillis() > deadline)
    val webUrl = status?.webUrl ?: ReviewExtras.webUrl(ctx, ref.id)

    fun act(okMsg: Int?, block: suspend () -> Unit) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }
            .onSuccess { okMsg?.let { Toast.makeText(ctx, ctx.getString(it), Toast.LENGTH_SHORT).show() }; reload++ }
            .onFailure { e -> Toast.makeText(ctx, if (S5Client.isNoMail(e)) ctx.getString(R.string.sh5_no_mail) else (e.message ?: e.toString()), Toast.LENGTH_LONG).show() }
    }
    fun remind(to: InvitedReviewer?) {
        if (S5Config.enabled && status?.mail == true) act(R.string.sh5_reminded) { S5Client.remind(ref.id, ref.ownerKey!!, to?.email) }
        else S5Share.mailText(ctx, listOfNotNull(to?.email) .ifEmpty { extra.reviewers.map { it.email }.filter { it.contains('@') } },
            ctx.getString(R.string.sh5_remind_subject, extra.subject.ifBlank { ref.title }), inviteText(ctx, ref.title, ref.id, ref.code, webUrl, deadline, extra.message))
    }

    Scaffold(topBar = {
        AppTopBar(ref.title, onBack = { nav.popBackStack() }) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.sh5_refresh)) }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = if (ended) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(if (ended) stringResource(R.string.sh5_review_ended) else stringResource(R.string.sh5_review_open), style = MaterialTheme.typography.titleMedium)
                        Text(if (deadline > 0) stringResource(R.string.sh5_due, S5Share.date(deadline)) else stringResource(R.string.sh5_no_deadline), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (ref.isOwner) R.string.sh5_role_owner else R.string.sh5_role_reviewer), style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { nav.navigate(Routes.reader(Uri.fromFile(File(ref.localPath)).toString())) }) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_open_doc)) }
                            if (ref.isOwner) OutlinedButton(onClick = { inviteDialog = true }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_invite)) }
                            else OutlinedButton(onClick = { privacy = true }) { Icon(Icons.Default.PrivacyTip, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_my_privacy)) }
                        }
                    }
                }
            }
            if (ref.isOwner) item {
                if (!S5Config.enabled) NotConfiguredNote()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip(onClick = { deadlineDialog = true }, enabled = S5Config.enabled, label = { Text(stringResource(R.string.sh5_change_deadline)) }, leadingIcon = { Icon(Icons.Default.Event, null) })
                    AssistChip(onClick = { remind(null) }, enabled = !ended, label = { Text(stringResource(R.string.sh5_remind_all)) }, leadingIcon = { Icon(Icons.Default.NotificationsActive, null) })
                    AssistChip(onClick = { addDialog = true }, enabled = S5Config.enabled, label = { Text(stringResource(R.string.sh5_add_reviewers)) }, leadingIcon = { Icon(Icons.Default.PersonAdd, null) })
                    if (ended) AssistChip(onClick = { act(R.string.sh5_reopened) { if (deadline > 0 && deadline < System.currentTimeMillis()) S5Client.setDeadline(ref.id, ref.ownerKey!!, 0); S5Client.setClosed(ref.id, ref.ownerKey!!, false); CollabStore.upsert(ctx, ref.copy(closed = false)) } },
                        enabled = S5Config.enabled, label = { Text(stringResource(R.string.sh5_reopen)) }, leadingIcon = { Icon(Icons.Default.LockOpen, null) })
                    else AssistChip(onClick = { act(R.string.sh5_ended) { if (S5Config.enabled) S5Client.setClosed(ref.id, ref.ownerKey!!, true) else CollabClient.close(ref.id, ref.ownerKey!!); CollabStore.upsert(ctx, ref.copy(closed = true)) } },
                        label = { Text(stringResource(R.string.sh5_end)) }, leadingIcon = { Icon(Icons.Default.Lock, null) })
                    AssistChip(onClick = { confirmRevoke = true }, enabled = S5Config.enabled, label = { Text(stringResource(R.string.sh5_revoke)) }, leadingIcon = { Icon(Icons.Default.LinkOff, null) })
                }
            }
            item { Text(stringResource(R.string.sh5_reviewers), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            val people = status?.reviewers
            if (people != null) {
                if (people.isEmpty()) item { Text(stringResource(R.string.sh5_no_reviewers), style = MaterialTheme.typography.bodySmall) }
                items(people, key = { "p_" + it.name + it.email }) { p ->
                    ListItem(
                        leadingContent = { Icon(if (p.web) Icons.Default.Language else Icons.Default.Person, null) },
                        headlineContent = { Text(p.name.ifBlank { p.email.ifBlank { "?" } }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOfNotNull(
                            stringResource(when (p.status) { "commented" -> R.string.sh5_st_commented; "opened" -> R.string.sh5_st_opened; else -> R.string.sh5_st_invited }),
                            p.count.takeIf { it > 0 }?.let { ctx.getString(R.string.sh5_n_comments, it) },
                            p.last.takeIf { it > 0 }?.let { S5Share.date(it) },
                            if (p.anonymous) ctx.getString(R.string.sh5_anonymous) else null,
                        ).joinToString(" · ")) },
                        trailingContent = { if (ref.isOwner && !ended && p.email.contains('@')) IconButton(onClick = { remind(InvitedReviewer(p.name, p.email)) }) { Icon(Icons.Default.NotificationsActive, stringResource(R.string.sh5_remind)) } },
                    )
                }
            } else {
                val rv = meta?.reviewers.orEmpty()
                if (rv.isEmpty() && extra.reviewers.isEmpty()) item { Text(stringResource(R.string.sh5_no_reviewers), style = MaterialTheme.typography.bodySmall) }
                items(extra.reviewers, key = { "i_" + it.name + it.email }) { r -> ListItem(leadingContent = { Icon(Icons.Default.PersonOutline, null) }, headlineContent = { Text(r.name.ifBlank { r.email }) }, supportingContent = { Text(stringResource(R.string.sh5_st_invited)) }) }
                items(rv, key = { "m_" + it.name + it.lastSeen }) { r -> ListItem(leadingContent = { Icon(Icons.Default.Person, null) }, headlineContent = { Text(r.name.ifBlank { "?" }) },
                    supportingContent = { Text(ctx.getString(R.string.sh5_n_comments, r.count) + " · " + S5Share.date(r.lastSeen)) }) }
            }
            status?.activity?.takeIf { it.isNotEmpty() }?.let { list ->
                item { Text(stringResource(R.string.sh5_activity), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(list.take(50), key = { "a_" + it.time + it.kind + it.name }) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(activityText(ctx, a.kind, a.name, a.count, a.web), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(S5Share.date(a.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (deadlineDialog) {
        var d by remember { mutableLongStateOf(deadline) }
        AlertDialog(onDismissRequest = { deadlineDialog = false }, title = { Text(stringResource(R.string.sh5_change_deadline)) },
            text = { DateChooser(d, { d = it }, listOf(1, 3, 7), stringResource(R.string.sh5_no_deadline)) },
            confirmButton = { TextButton(onClick = { deadlineDialog = false; act(R.string.sh5_saved) { S5Client.setDeadline(ref.id, ref.ownerKey!!, d); CollabStore.upsert(ctx, ref.copy(deadline = d)) } }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { deadlineDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (addDialog) {
        var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addDialog = false }, title = { Text(stringResource(R.string.sh5_add_reviewers)) },
            text = { Column {
                OutlinedTextField(n, { n = it }, label = { Text(stringResource(R.string.sh5_r_name)) }, singleLine = true)
                OutlinedTextField(e, { e = it }, label = { Text(stringResource(R.string.sh5_r_email)) }, singleLine = true)
            } },
            confirmButton = { TextButton(enabled = n.isNotBlank() || e.isNotBlank(), onClick = {
                addDialog = false
                val nx = extra.copy(reviewers = extra.reviewers + InvitedReviewer(n.trim(), e.trim()))
                act(R.string.sh5_saved) { S5Client.putExtra(ref.id, ref.ownerKey!!, nx); ReviewExtras.put(ctx, ref.id, nx); if (e.contains('@')) runCatching { S5Client.remind(ref.id, ref.ownerKey!!, e.trim(), first = true) } }
            }) { Text(stringResource(R.string.sh5_add)) } },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (inviteDialog) AlertDialog(onDismissRequest = { inviteDialog = false }, title = { Text(stringResource(R.string.sh5_invite)) },
        text = { InviteActions(inviteText(ctx, ref.title, ref.id, ref.code, webUrl, deadline, extra.message), webUrl ?: CollabClient.shareUrl(ref.id, ref.code), extra.subject.ifBlank { ref.title },
            extra.reviewers.map { it.email }.filter { it.contains('@') }) },
        confirmButton = { TextButton(onClick = { inviteDialog = false }) { Text(stringResource(R.string.ok)) } })
    if (confirmRevoke) AlertDialog(onDismissRequest = { confirmRevoke = false }, title = { Text(stringResource(R.string.sh5_revoke)) }, text = { Text(stringResource(R.string.sh5_revoke_q)) },
        confirmButton = { TextButton(onClick = { confirmRevoke = false
            act(R.string.sh5_revoked) { val code = S5Client.rotate(ref.id, ref.ownerKey!!); CollabStore.upsert(ctx, ref.copy(code = code))
                ReviewExtras.webUrl(ctx, ref.id)?.let { ReviewExtras.setWebUrl(ctx, ref.id, it.substringBefore("?c=") + "?c=" + code) } }
        }) { Text(stringResource(R.string.sh5_revoke)) } },
        dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(R.string.cancel)) } })
    if (privacy) ConsentDialog(ref, onDone = { privacy = false })
}

private fun activityText(ctx: android.content.Context, kind: String, name: String, count: Int, web: Boolean): String {
    val who = name.ifBlank { ctx.getString(R.string.sh5_someone) }
    val via = if (web) " " + ctx.getString(R.string.sh5_via_web) else ""
    return when (kind) {
        "comment" -> ctx.getString(R.string.sh5_act_comment, who, count) + via
        "opened" -> ctx.getString(R.string.sh5_act_opened, who) + via
        "consent" -> ctx.getString(R.string.sh5_act_consent, who)
        "deadline" -> ctx.getString(R.string.sh5_act_deadline)
        "ended" -> ctx.getString(R.string.sh5_act_ended)
        "reopened" -> ctx.getString(R.string.sh5_act_reopened)
        "revoked" -> ctx.getString(R.string.sh5_act_revoked)
        "remind", "invite" -> ctx.getString(R.string.sh5_act_remind, count)
        else -> kind
    }
}
