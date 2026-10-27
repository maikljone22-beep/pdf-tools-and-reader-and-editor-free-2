package com.smartdocs.editor.ui.share5

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.collab.ReviewCheckWorker
import com.smartdocs.editor.core.collab.ReviewRef
import com.smartdocs.editor.core.pdf.TextToPdf
import com.smartdocs.editor.core.share5.ReviewLocal
import com.smartdocs.editor.core.share5.ReviewTracker
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tracker additions (Foxit §31, nav-attach): notifications inbox (bell), check interval, work offline / online
 * (reconnect publishes the pending comments), and "Create PDF from tracker details". [items] are the reviews shown.
 */
@Composable
fun RowScope.TrackerActions(nav: NavHostController, items: List<ReviewTracker.Item>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var unread by remember { mutableIntStateOf(ReviewLocal.unread(ctx)) }
    var inbox by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var intervalDialog by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(ReviewLocal.offline(ctx)) }

    IconButton(onClick = { inbox = true }) {
        BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) { Icon(Icons.Default.Notifications, stringResource(R.string.nat_rv_inbox)) }
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.nat_actions)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.nat_rv_interval)) }, leadingIcon = { Icon(Icons.Default.Schedule, null) }, onClick = { menu = false; intervalDialog = true })
            DropdownMenuItem(text = { Text(stringResource(if (offline) R.string.nat_rv_go_online else R.string.nat_rv_go_offline)) },
                leadingIcon = { Icon(if (offline) Icons.Default.CloudSync else Icons.Default.CloudOff, null) },
                onClick = {
                    menu = false
                    val goOnline = offline
                    ReviewLocal.setOffline(ctx, !goOnline); offline = !goOnline
                    if (goOnline) scope.launch {
                        // reconnect: publish what was kept pending, then refresh the list
                        val n = withContext(Dispatchers.IO) {
                            if (!CollabConfig.enabled) 0 else CollabStore.load(ctx).filter { !it.closed }.sumOf { r -> runCatching { CollabStore.publish(ctx, r) }.getOrDefault(0) }
                        }
                        Toast.makeText(ctx, ctx.getString(R.string.nat_rv_reconnected, n), Toast.LENGTH_LONG).show()
                        onChanged()
                    } else Toast.makeText(ctx, ctx.getString(R.string.nat_rv_offline_on), Toast.LENGTH_LONG).show()
                })
            DropdownMenuItem(text = { Text(stringResource(R.string.nat_rv_report)) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }, enabled = items.isNotEmpty(), onClick = {
                menu = false
                scope.launch {
                    val f = runCatching { trackerReport(ctx, app.documents.uniqueFile(app.documents.docsDir, ctx.getString(R.string.nat_rv_report_name)), items) }
                        .onFailure { Toast.makeText(ctx, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }.getOrNull()
                    if (f != null) nav.navigate(Routes.reader(Uri.fromFile(f).toString()))
                }
            })
        }
    }

    if (inbox) {
        var notes by remember { mutableStateOf(ReviewLocal.inbox(ctx)) }
        AlertDialog(onDismissRequest = { inbox = false }, title = { Text(stringResource(R.string.nat_rv_inbox)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (notes.isEmpty()) Text(stringResource(R.string.nat_rv_inbox_empty))
                    notes.forEach { n ->
                        ListItem(
                            modifier = Modifier.clickable { ReviewLocal.markRead(ctx, n.reviewId); inbox = false; unread = ReviewLocal.unread(ctx); nav.navigate(Share5Routes.review(n.reviewId)) },
                            leadingContent = { Icon(if (n.read) Icons.Default.Comment else Icons.Default.MarkChatUnread, null) },
                            headlineContent = { Text(n.title, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold) },
                            supportingContent = { Text(pluralStringResourceSafe(n.count) + " · " + S5Share.date(n.time)) },
                            colors = ListItemDefaults.colors(containerColor = AlertDialogDefaults.containerColor),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ReviewLocal.markAllRead(ctx); notes = ReviewLocal.inbox(ctx); unread = 0 }) { Text(stringResource(R.string.nat_rv_mark_read)) } },
            dismissButton = { TextButton(onClick = { ReviewLocal.clearInbox(ctx); notes = emptyList(); unread = 0 }) { Text(stringResource(R.string.nat_rv_clear)) } })
    }

    if (intervalDialog) {
        var sel by remember { mutableIntStateOf(ReviewLocal.interval(ctx)) }
        AlertDialog(onDismissRequest = { intervalDialog = false }, title = { Text(stringResource(R.string.nat_rv_interval)) },
            text = {
                Column {
                    ReviewLocal.INTERVALS.forEach { m ->
                        Row(Modifier.fillMaxWidth().selectable(sel == m, role = Role.RadioButton) { sel = m }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(sel == m, onClick = null); Spacer(Modifier.width(8.dp))
                            Text(intervalLabel(ctx, m))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { intervalDialog = false; ReviewLocal.setInterval(ctx, sel); runCatching { ReviewCheckWorker.schedule(ctx, update = true) } }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { intervalDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun pluralStringResourceSafe(n: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.s6_new_comments, n, n)

private fun intervalLabel(ctx: Context, m: Int): String = when {
    m == 0 -> ctx.getString(R.string.nat_rv_never)
    m < 60 -> ctx.getString(R.string.nat_rv_minutes, m)
    else -> ctx.getString(R.string.nat_rv_hours, m / 60)
}

/** Writes a PDF listing [items] (title, role, status, reviewers, comments, deadline) to [out]. */
private suspend fun trackerReport(ctx: Context, out: File, items: List<ReviewTracker.Item>): File {
    val lines = items.map { it ->
        val m = it.meta
        listOfNotNull(
            it.ref.title,
            ctx.getString(if (it.ref.isOwner) R.string.sh5_role_owner else R.string.sh5_role_reviewer),
            ctx.getString(if (it.ended) R.string.sh5_review_ended else R.string.sh5_review_open),
            m?.let { mm -> ctx.getString(R.string.sh5_n_reviewers, mm.reviewers.size) + ": " + mm.reviewers.joinToString(", ") { r -> r.name.ifBlank { "?" } } },
            ctx.getString(R.string.sh5_n_comments, it.comments),
            (m?.deadline ?: it.ref.deadline).takeIf { d -> d > 0 }?.let { d -> ctx.getString(R.string.sh5_due, S5Share.date(d)) },
            ReviewLocal.folders(ctx)[it.ref.id]?.let { f -> ctx.getString(R.string.nat_rv_folder_x, f) },
        ).joinToString("\n")
    }
    TextToPdf.convert(ctx, ReviewLocal.reportText(ctx.getString(R.string.sh5_tracker), lines), out, 11f, title = ctx.getString(R.string.sh5_tracker))
    return out
}

/** Folder filter chips of the tracker: all / top level / each folder. null = all, "" = top level. */
@Composable
fun TrackerFolderChips(folders: List<String>, selected: String?, onSelect: (String?) -> Unit, onRemove: (String) -> Unit) {
    if (folders.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected == null, { onSelect(null) }, label = { Text(stringResource(R.string.nat_rv_all)) })
        FilterChip(selected == "", { onSelect("") }, label = { Text(stringResource(R.string.nat_rv_top_level)) })
        folders.forEach { f ->
            FilterChip(selected == f, { onSelect(f) }, label = { Text(f) }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                trailingIcon = { if (selected == f) Icon(Icons.Default.Close, stringResource(R.string.nat_rv_remove_folder), Modifier.clickable { onRemove(f) }) })
        }
    }
}

/** Per-review menu: move to a folder, leave the review (reviewers: "Remove me"), save an archive copy. */
@Composable
fun TrackerItemMenu(ref: ReviewRef, folders: List<String>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var newFolder by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(ref.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nat_rv_move), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val cur = ReviewLocal.folders(ctx)[ref.id]
                (listOf("") + folders).forEach { f ->
                    Row(Modifier.fillMaxWidth().selectable(cur.orEmpty() == f, role = Role.RadioButton) { ReviewLocal.setFolder(ctx, ref.id, f); onChanged(); onDismiss() }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cur.orEmpty() == f, onClick = null); Spacer(Modifier.width(8.dp))
                        Text(f.ifEmpty { stringResource(R.string.nat_rv_top_level) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newFolder, { newFolder = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.nat_rv_new_folder)) })
                    IconButton(enabled = newFolder.isNotBlank(), onClick = { ReviewLocal.setFolder(ctx, ref.id, newFolder); onChanged(); onDismiss() }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.nat_rv_new_folder)) }
                }
                HorizontalDivider()
                TextButton(onClick = { saveArchiveCopy(ctx, ref); onDismiss() }) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nat_rv_archive)) }
                if (!ref.isOwner) TextButton(onClick = { confirmLeave = true }) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nat_rv_leave), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.nat_rv_leave)) }, text = { Text(stringResource(R.string.nat_rv_leave_q)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; leaveReview(ctx, ref); onChanged(); onDismiss() }) { Text(stringResource(R.string.nat_rv_leave)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } })
}

/** Foxit "Save As Archive Copy": a standalone copy of the review document with its comments, in Documents. */
fun saveArchiveCopy(ctx: Context, ref: ReviewRef): File? {
    val src = File(ref.localPath)
    if (!src.isFile) { Toast.makeText(ctx, R.string.sh5_review_gone, Toast.LENGTH_LONG).show(); return null }
    val app = ctx.applicationContext as com.smartdocs.editor.SmartDocsApp
    val out = app.documents.uniqueFile(app.documents.docsDir, ReviewLocal.archiveName(ref.title))
    return runCatching { src.copyTo(out, overwrite = true); out }
        .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.saved_to, it.absolutePath), Toast.LENGTH_LONG).show() }
        .onFailure { Toast.makeText(ctx, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }.getOrNull()
}

/**
 * Foxit "Remove me": stop taking part in a review on this device. The review is forgotten here (no more checks,
 * notifications or publishing); the working copy stays in Documents/Reviews and the comments already published stay
 * on the server.
 */
fun leaveReview(ctx: Context, ref: ReviewRef) {
    if (com.smartdocs.editor.core.collab.CollabLive.isAttached(File(ref.localPath))) com.smartdocs.editor.core.collab.CollabLive.detach(ctx, File(ref.localPath))
    CollabStore.remove(ctx, ref.id)
    ReviewLocal.setFolder(ctx, ref.id, null)
    Toast.makeText(ctx, R.string.nat_rv_left, Toast.LENGTH_LONG).show()
}
