package com.smartdocs.editor.ui.edit5

import android.content.Intent
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.E5Geom
import com.smartdocs.editor.core.edit5.LinkDetect
import com.smartdocs.editor.core.edit5.LinkKind
import com.smartdocs.editor.core.edit5.LinkOps
import com.smartdocs.editor.core.edit5.LinkSpec
import com.smartdocs.editor.core.edit5.PageLinkInfo
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.launch
import java.io.File

/*
 * Reader-side hooks for the real link tool (see MODULE_edit5_r5.md). Rects from the reader are in the reader's
 * "PDF space" (PDFium page coordinates = PDF user space), the same space LinkOps uses, so no conversion is needed.
 * Every change goes through ReaderState.applyInPlace (pending annotations are saved first, undo copy kept).
 */

private fun applyLinks(state: ReaderState, msgOk: String, op: suspend (File, File, String?) -> Unit) {
    state.scope.launch {
        runCatching { state.applyInPlace(label = { state.context.getString(R.string.hi_links) }) { src, out, pw -> op(src, out, pw) } }
            .onSuccess { Toast.makeText(state.context, msgOk, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(state.context, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }
    }
}

/** Create a link on [page] for the rectangle the user drew with the LINK tool. Replaces the old note-based fake link. */
@Composable
fun ReaderLinkCreateSheet(state: ReaderState, page: Int, rect: RectF, onDismiss: () -> Unit) {
    val ok = stringResource(R.string.e5_link_saved)
    LinkEditorSheet(LinkSpec(rect = rect.normalized()), state.pageCount, isNew = true, onDismiss = onDismiss) { spec ->
        onDismiss()
        applyLinks(state, ok) { s, o, p -> LinkOps.add(s, o, p, mapOf(page to listOf(spec))) }
    }
}

/** Create a link from the current text selection (one quad per line, so it follows the text). */
@Composable
fun ReaderLinkFromSelection(state: ReaderState, onDismiss: () -> Unit) {
    val sel = state.selection ?: run { LaunchedEffect(Unit) { onDismiss() }; return }
    val ok = stringResource(R.string.e5_link_saved)
    val lines = remember(sel) { LinkOps.linesOf(sel.rects) }
    val union = remember(lines) { E5Geom.union(lines) ?: RectF() }
    val guess = remember(sel) { LinkDetect.find(sel.text).firstOrNull()?.target }
    val initial = remember(sel) {
        when {
            guess == null -> LinkSpec(union, quads = lines, contents = sel.text.take(120))
            guess.startsWith("mailto:") -> LinkSpec(union, LinkKind.MAIL, uri = guess, quads = lines)
            else -> LinkSpec(union, LinkKind.URI, uri = guess, quads = lines)
        }
    }
    LinkEditorSheet(initial, state.pageCount, isNew = true, onDismiss = onDismiss) { spec ->
        onDismiss(); state.selection = null
        applyLinks(state, ok) { s, o, p -> LinkOps.add(s, o, p, mapOf(sel.page to listOf(spec))) }
    }
}

/** The real link under ([x], [y]) (reader PDF space) on [page], or null. For the LINK tool's tap (edit mode). */
suspend fun linkAt(state: ReaderState, page: Int, x: Float, y: Float): PageLinkInfo? {
    val f = state.file ?: return null
    return runCatching { LinkOps.at(f, state.password, page, x, y) }.getOrNull()
}

/**
 * Links of [page]: tap one to edit or delete it. With [initial] the editor opens directly on that link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPageLinksSheet(state: ReaderState, page: Int, initial: PageLinkInfo? = null, onDismiss: () -> Unit) {
    var links by remember { mutableStateOf<List<PageLinkInfo>?>(null) }
    var editing by remember { mutableStateOf(initial) }
    val ok = stringResource(R.string.e5_link_saved)
    val deleted = stringResource(R.string.e5_link_deleted)
    LaunchedEffect(page, state.file) { val f = state.file; links = if (f == null) emptyList() else runCatching { LinkOps.list(f, state.password, page) }.getOrDefault(emptyList()) }
    val e = editing
    if (e != null) {
        LinkEditorSheet(e.spec, state.pageCount, isNew = false, onDismiss = { if (initial != null) onDismiss() else editing = null },
            onDelete = { onDismiss(); applyLinks(state, deleted) { s, o, p -> LinkOps.delete(s, o, p, mapOf(e.page to setOf(e.index))) } }) { spec ->
            onDismiss()
            applyLinks(state, ok) { s, o, p -> LinkOps.update(s, o, p, e.page, e.index, movedLink(spec, e.spec)) }
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.e5_links_on_page, links?.size ?: 0), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val l = links
            when {
                l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                l.isEmpty() -> Text(stringResource(R.string.e5_links_none_reader), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> l.forEach { li ->
                    ListItem(headlineContent = { Text(linkSummary(li.spec), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(kindLabel(li.spec.kind))) },
                        leadingContent = { Icon(Icons.Default.Link, null) },
                        modifier = Modifier.clickable { editing = li })
                }
            }
        }
    }
}

/**
 * Follow a link PDFium does not report (GoToR to another PDF, Launch of another file) at ([x], [y]).
 * Returns true when handled. [openPdf] opens a PDF in the reader at a page. The target is named in an explicit
 * warning first; executable-like files, and every launch when the setting blocks them, are refused.
 */
suspend fun followFileLinkAt(state: ReaderState, page: Int, x: Float, y: Float, openPdf: (File, Int) -> Unit): Boolean {
    val l = linkAt(state, page, x, y) ?: return false
    if (l.spec.kind != LinkKind.REMOTE && l.spec.kind != LinkKind.LAUNCH) return false
    val base = state.file?.parentFile
    val target = File(l.spec.file).let { if (it.isAbsolute) it else File(base, l.spec.file) }
    if (!com.smartdocs.editor.ui.security.DocSecurityGate.confirmLaunch(state.context, l.spec.file.ifBlank { target.name })) return true
    if (!target.exists()) { Toast.makeText(state.context, state.context.getString(R.string.e5_link_file_missing, l.spec.file), Toast.LENGTH_LONG).show(); return true }
    if (l.spec.kind == LinkKind.REMOTE || target.extension.equals("pdf", true)) {
        // nav-bookmarks: a named destination in the other file is looked up there (a protected file opens at the page)
        val pg = if (l.spec.kind == LinkKind.REMOTE && l.spec.named.isNotBlank()) com.smartdocs.editor.core.edit5.NamedDests.pageOf(target, l.spec.named) ?: l.spec.page else l.spec.page
        openPdf(target, pg); return true
    }
    runCatching {
        val uri = state.app.documents.shareUri(target)
        state.context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, com.smartdocs.editor.ui.common.mimeFor(target.extension)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    return true
}


/**
 * LINK tool tap at ([x], [y]) (reader PDF space): opens the editor of the link there, or the page's link list when
 * the tap hit no link. One composable so the reader hook stays a single line.
 */
@Composable
fun ReaderLinkAtSheet(state: ReaderState, page: Int, x: Float, y: Float, onDismiss: () -> Unit) {
    var resolved by remember { mutableStateOf(false) }
    var hit by remember { mutableStateOf<PageLinkInfo?>(null) }
    LaunchedEffect(page, x, y) { hit = linkAt(state, page, x, y); resolved = true }
    if (resolved) ReaderPageLinksSheet(state, page, hit, onDismiss)
}

/**
 * The reader prompt switch for the LINK tool: a drawn rectangle creates a link, a tap edits the link under it.
 * Returns false for other prompts (the caller shows its usual dialog).
 */
@Composable
fun ReaderLinkPrompt(state: ReaderState, isLinkTool: Boolean, page: Int, x: Float, y: Float, rect: RectF?, onDismiss: () -> Unit): Boolean {
    if (!isLinkTool) return false
    if (rect != null) ReaderLinkCreateSheet(state, page, rect, onDismiss) else ReaderLinkAtSheet(state, page, x, y, onDismiss)
    return true
}
