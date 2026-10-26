package com.smartdocs.editor.ui.share5

import android.net.Uri
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
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.share5.EmailReview
import com.smartdocs.editor.core.share5.ReviewTracker
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import java.io.File

/** Route "share5/reviews": the review tracker — sent by me / I take part in / by e-mail (SHR-2). */
@Composable
fun ReviewTrackerScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<ReviewTracker.Item>?>(null) }
    var email by remember { mutableStateOf(EmailReview.records(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { items = ReviewTracker.load(ctx); email = EmailReview.records(ctx) }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.sh5_tracker), onBack = { nav.popBackStack() }) { IconButton(onClick = { items = null; reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.sh5_refresh)) } } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.navigate(if (tab == 2) Share5Routes.emailNew(null) else Share5Routes.reviewNew(null)) },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.sh5_new)) }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.sh5_tab_sent, R.string.sh5_tab_joined, R.string.sh5_tab_email).forEachIndexed { i, r ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(r)) })
                }
            }
            if (items == null && tab != 2) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0, 1 -> {
                    val list = items.orEmpty().filter { it.ref.isOwner == (tab == 0) }.sortedWith(compareBy<ReviewTracker.Item> { it.ended }.thenByDescending { it.ref.lastSync })
                    if (items != null && list.isEmpty()) EmptyState(Icons.Default.RateReview, stringResource(R.string.sh5_tracker_empty))
                    else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { it.ref.id }) { it -> TrackerCard(it) { ReviewTracker.markSeen(ctx, it); nav.navigate(Share5Routes.review(it.ref.id)) } }
                    }
                }
                else -> if (email.isEmpty()) EmptyState(Icons.Default.ForwardToInbox, stringResource(R.string.sh5_email_empty))
                    else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(email, key = { it.reviewId }) { r ->
                            Surface(onClick = { nav.navigate(Routes.reader(Uri.fromFile(File(r.masterPath)).toString())) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconTile(Icons.Default.ForwardToInbox, Brand.tiles.teal, size = 42.dp, iconSize = 22.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(r.subject.ifBlank { r.fileName }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOf(S5Share.date(r.sentAt), r.recipients, ctx.getString(R.string.sh5_returned_n, r.merged.size)).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    IconButton(onClick = { EmailReview.remove(ctx, r.reviewId); email = EmailReview.records(ctx) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun TrackerCard(it: ReviewTracker.Item, onClick: () -> Unit) {
    val tiles = Brand.tiles
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BadgedBox(badge = { if (it.newComments > 0) Badge { Text(it.newComments.toString()) } }) {
                IconTile(if (it.ref.isOwner) Icons.Default.Outbox else Icons.Default.MoveToInbox, if (it.ref.isOwner) tiles.purple else tiles.teal, size = 42.dp, iconSize = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.ref.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val m = it.meta
                val parts = listOfNotNull(
                    m?.let { mm -> stringResource(R.string.sh5_n_reviewers, mm.reviewers.size) },
                    stringResource(R.string.sh5_n_comments, it.comments),
                    (m?.deadline ?: it.ref.deadline).takeIf { d -> d > 0 }?.let { d -> stringResource(R.string.sh5_due, S5Share.date(d)) },
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                it.gone -> StatusPill(stringResource(R.string.sh5_gone), MaterialTheme.colorScheme.error)
                it.ended -> StatusPill(stringResource(R.string.sh5_review_ended), MaterialTheme.colorScheme.outline)
                it.newComments > 0 -> StatusPill(stringResource(R.string.sh5_new_comments, it.newComments), MaterialTheme.colorScheme.primary)
            }
        }
    }
}
