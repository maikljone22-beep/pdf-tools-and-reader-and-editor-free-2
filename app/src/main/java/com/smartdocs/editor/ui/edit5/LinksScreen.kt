package com.smartdocs.editor.ui.edit5

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.LinkKind
import com.smartdocs.editor.core.edit5.LinkOps
import com.smartdocs.editor.core.edit5.LinkSpec
import com.smartdocs.editor.core.edit5.PageLinkInfo
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

/** Real link annotations: draw a rectangle to add, tap a link to edit / delete, convert text URLs, remove all. */
@Composable
fun LinksScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var page by remember(doc.source) { mutableIntStateOf(0) }
    var links by remember { mutableStateOf<List<PageLinkInfo>>(emptyList()) }
    var editing by remember { mutableStateOf<Pair<LinkSpec, PageLinkInfo?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val processing = stringResource(R.string.processing)
    val doneN = stringResource(R.string.e5_links_added_n)
    val removedN = stringResource(R.string.e5_links_removed_n)
    val hint = stringResource(R.string.e5_links_hint)

    LaunchedEffect(doc.work, doc.version, page) {
        val w = doc.work ?: return@LaunchedEffect
        links = runCatching { LinkOps.list(w, doc.password, page) }.getOrDefault(emptyList())
    }
    fun run(op: suspend (java.io.File, java.io.File, String?) -> Unit, after: (() -> Unit)? = null) {
        busy = true
        scope.launch {
            runCatching { doc.apply(app.documents.tempDir, op) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            busy = false; after?.invoke()
        }
    }

    ToolScaffold(stringResource(R.string.e5_links_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            E5TwoPane(options = {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                E5Section(stringResource(R.string.e5_links_on_page, links.size)) {
                    if (links.isEmpty()) Text(stringResource(R.string.e5_links_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    links.forEach { l ->
                        ListItem(headlineContent = { Text(linkSummary(l.spec), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(kindLabel(l.spec.kind))) },
                            leadingContent = { Icon(if (l.spec.kind == LinkKind.PAGE) Icons.Default.Bookmark else Icons.Default.Link, null) },
                            modifier = Modifier.clickable { editing = l.spec to l })
                    }
                }
                E5Section(stringResource(R.string.e5_links_tools)) {
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            var n = 0
                            runCatching {
                                doc.apply(app.documents.tempDir) { src, out, pw ->
                                    val s = PdfSession.open(ctx, src, pw)
                                    val pages = try { (0 until s.pageCount).associateWith { s.text(it) to s.charBoxes(it) } } finally { s.closeAsync() }
                                    n = LinkOps.linkifyText(src, out, pw, pages)
                                    if (n == 0 && !out.exists()) src.copyTo(out, overwrite = true)
                                }
                            }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
                            busy = false
                            snack.showSnackbar(doneN.format(n))
                        }
                    }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.e5_links_linkify)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { var n = 0; run({ s, o, p -> n = LinkOps.removeAll(s, o, p, webOnly = true) }) { scope.launch { snack.showSnackbar(removedN.format(n)) } } }, Modifier.weight(1f)) { Text(stringResource(R.string.e5_links_remove_web)) }
                        OutlinedButton(onClick = { var n = 0; run({ s, o, p -> n = LinkOps.removeAll(s, o, p, webOnly = false) }) { scope.launch { snack.showSnackbar(removedN.format(n)) } } }, Modifier.weight(1f)) { Text(stringResource(R.string.e5_links_remove_all)) }
                    }
                }
            }, preview = {
                PageStepper(page, doc.pageCount) { page = it }
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(),
                    onTap = { x, y ->
                        val (ux, uy) = info.toUser(x, y)
                        links.lastOrNull { val r = it.spec.rect; ux in minOf(r.left, r.right)..maxOf(r.left, r.right) && uy in minOf(r.top, r.bottom)..maxOf(r.top, r.bottom) }?.let { editing = it.spec to it }
                    },
                    onRect = { r -> if (r.width() > 6f && r.top - r.bottom > 6f) editing = LinkSpec(info.rectToUser(r)) to null }) { map ->
                    links.forEach { l -> e5Box(map(info.rectToDisplay(l.spec.rect)), if (l.editable) Color(0xFF1565C0) else Color.Gray) }
                }
            })
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed, icon = Icons.Default.Save) {
                runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "links") }
            }
        }
    }
    if (busy) com.smartdocs.editor.ui.common.ProgressDialog(processing)
    editing?.let { (spec, existing) ->
        LinkEditorSheet(spec, doc.pageCount, isNew = existing == null, onDismiss = { editing = null },
            onDelete = existing?.let { e -> { editing = null; run({ s, o, p -> LinkOps.delete(s, o, p, mapOf(e.page to setOf(e.index))) }) } }) { result ->
            editing = null
            if (existing == null) run({ s, o, p -> LinkOps.add(s, o, p, mapOf(page to listOf(result))) })
            else run({ s, o, p -> LinkOps.update(s, o, p, existing.page, existing.index, result.copy(rect = existing.spec.rect, quads = existing.spec.quads)) })
        }
    }
}

/** Display-space rect helper used by previews of other screens. */
internal fun RectF.normalized(): RectF = RectF(minOf(left, right), maxOf(top, bottom), maxOf(left, right), minOf(top, bottom))
