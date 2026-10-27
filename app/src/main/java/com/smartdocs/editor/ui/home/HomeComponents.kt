package com.smartdocs.editor.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.DocTag
import com.smartdocs.editor.core.data.RecentDoc
import com.smartdocs.editor.core.data.Tag
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.IconTile
import kotlinx.coroutines.launch
import java.io.File

/** Recent document list row: page thumbnail, name, size · date · pages, favorite star, more menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocRow(d: RecentDoc, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val thumb = rememberDocThumb(d)
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMenu).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbBox(thumb, 46.dp, 60.dp, kind = kindOfName(d.name))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                append(DocumentRepository.formatSize(d.size)); append(" · "); append(DocumentRepository.formatDate(d.lastOpened))
                if (d.pageCount > 0) append(" · ${d.pageCount} ص")
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (d.pageCount > 1 && d.lastPage > 0) {
                LinearProgressIndicator(
                    progress = { (d.lastPage + 1f) / d.pageCount }, modifier = Modifier.padding(top = 6.dp).width(96.dp).height(3.dp).clip(PillShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {},
                )
            }
        }
        if (d.favorite) Icon(Icons.Default.Star, null, Modifier.size(20.dp), tint = Brand.tiles.amber.content)
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
    }
}

@Composable
fun DocCard(d: RecentDoc, onOpen: () -> Unit) = DocCard(d, Modifier.width(120.dp), onMenu = null, onOpen = onOpen)

/** Grid / favorites card with a large page thumbnail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocCard(d: RecentDoc, modifier: Modifier, onMenu: (() -> Unit)?, onOpen: () -> Unit) {
    val thumb = rememberDocThumb(d)
    Column(modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onOpen, onLongClick = onMenu).padding(4.dp)) {
        Box {
            ThumbBox(thumb, 0.dp, 0.dp, kind = kindOfName(d.name), modifier = Modifier.fillMaxWidth().aspectRatio(0.75f))
            if (d.favorite) Icon(Icons.Default.Star, null, Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp), tint = Brand.tiles.amber.content)
        }
        Spacer(Modifier.height(6.dp))
        Text(d.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
        if (d.pageCount > 0) Text("${d.pageCount} ص", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Kept for compatibility: fixed-size thumbnail from a stored path. */
@Composable
fun Thumb(path: String?, w: Dp, h: Dp) = ThumbBox(path?.takeIf { it.endsWith(".jpg") }, w, h)

/** Long-press / "⋮" sheet for a recent document: favorite, organize, share, remove record, tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocMenuSheet(d: RecentDoc, tags: List<Tag>, nav: NavHostController, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val docTags by app.db.tags().tagsForDoc(d.uri).collectAsStateWithLifecycle(initialValue = emptyList())
    var newTag by remember { mutableStateOf("") }
    val tiles = Brand.tiles
    // gaps6: at most TagLimit.MAX (5) tags per file
    var limitHit by remember { mutableStateOf(false) }
    val atLimit = !com.smartdocs.editor.core.gaps6.TagLimit.canAdd(docTags.size)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ThumbBox(rememberDocThumb(d), 44.dp, 58.dp, kind = kindOfName(d.name))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${DocumentRepository.formatSize(d.size)} · ${DocumentRepository.formatDate(d.lastOpened)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            // primary actions as a row of big buttons (one tap each)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SheetAction(if (d.favorite) Icons.Default.Star else Icons.Outlined.StarBorder, stringResource(if (d.favorite) R.string.ds_remove_favorite else R.string.ds_add_favorite), Modifier.weight(1f)) {
                    scope.launch { app.db.docs().setFavorite(d.uri, !d.favorite) }; onDismiss()
                }
                SheetAction(Icons.Default.AutoAwesomeMotion, stringResource(R.string.tool_organize), Modifier.weight(1f)) { onDismiss(); nav.navigate(Routes.tool("organize", d.uri)) }
                SheetAction(Icons.Default.Share, stringResource(R.string.share), Modifier.weight(1f)) {
                    val f = File(Uri.parse(d.uri).path ?: ""); if (f.exists()) runCatching { nav.context.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f)), null)) }; onDismiss()
                }
                SheetAction(Icons.Default.DeleteOutline, stringResource(R.string.ds_remove_record), Modifier.weight(1f)) { scope.launch { app.db.docs().remove(d.uri) }; onDismiss() }
            }
            com.smartdocs.editor.ui.nve.shell.NveDocMenuExtras(d, onDismiss)   // NVE-shell: copy path, new window, save to default folder
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Default.Label, tiles.teal, size = 28.dp, iconSize = 16.dp); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t ->
                    val on = docTags.any { it.id == t.id }
                    FilterChip(
                        selected = on, shape = PillShape,
                        onClick = {
                            when (com.smartdocs.editor.core.gaps6.TagLimit.toggle(docTags.map { it.id }, t.id)) {
                                com.smartdocs.editor.core.gaps6.TagLimit.Decision.DETACH -> { limitHit = false; scope.launch { app.db.tags().detach(DocTag(d.uri, t.id)) } }
                                com.smartdocs.editor.core.gaps6.TagLimit.Decision.ATTACH -> scope.launch { app.db.tags().attach(DocTag(d.uri, t.id)) }
                                com.smartdocs.editor.core.gaps6.TagLimit.Decision.LIMIT_REACHED -> limitHit = true
                            }
                        },
                        label = { Text(t.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color))) },
                    )
                }
            }
            if (limitHit || atLimit) Text(
                stringResource(R.string.g6_tag_limit, com.smartdocs.editor.core.gaps6.TagLimit.MAX),
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = if (limitHit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newTag, { newTag = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.add_tag)) }, singleLine = true, shape = PillShape)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(enabled = newTag.isNotBlank(), onClick = {
                    if (atLimit) { limitHit = true; return@FilledIconButton }
                    scope.launch {
                        val colors = listOf(0xFFE53935, 0xFF8E24AA, 0xFF3949AB, 0xFF00897B, 0xFFFB8C00, 0xFF6D4C41)
                        val id = app.db.tags().insert(Tag(name = newTag.trim(), color = colors[(tags.size) % colors.size]))
                        if (id > 0) app.db.tags().attach(DocTag(d.uri, id))
                        newTag = ""
                    }
                }) { Icon(Icons.Default.Add, stringResource(R.string.add_tag)) }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
