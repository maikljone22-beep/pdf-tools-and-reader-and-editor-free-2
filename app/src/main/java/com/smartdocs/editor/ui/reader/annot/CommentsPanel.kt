package com.smartdocs.editor.ui.reader.annot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.CommentItem
import com.smartdocs.editor.core.annot.CommentStore
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.ReviewStatus
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.annotColor
import com.smartdocs.editor.ui.reader.kindLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PDF subtype of a pending annotation kind (so pending and stored comments filter the same way). */
fun kindSubtype(k: AnnotKind): String = when (k) {
    AnnotKind.HIGHLIGHT -> "Highlight"; AnnotKind.UNDERLINE -> "Underline"; AnnotKind.STRIKEOUT -> "StrikeOut"; AnnotKind.SQUIGGLY -> "Squiggly"
    AnnotKind.INK -> "Ink"; AnnotKind.NOTE -> "Text"; AnnotKind.FREETEXT, AnnotKind.STAMP_TEXT -> "FreeText"; AnnotKind.LINE, AnnotKind.ARROW -> "Line"
    AnnotKind.RECT -> "Square"; AnnotKind.OVAL -> "Circle"; AnnotKind.STAMP_IMAGE -> "Stamp"; AnnotKind.REDACT -> "Redact"
    AnnotKind.POLYGON, AnnotKind.CLOUD -> "Polygon"; AnnotKind.POLYLINE -> "PolyLine"; AnnotKind.CARET, AnnotKind.REPLACE -> "Caret"
    AnnotKind.FILE_ATTACH, AnnotKind.SOUND, AnnotKind.VIDEO -> "FileAttachment"
}

/** String resource for a PDF annotation subtype, null for unknown ones. */
fun subtypeLabelRes(sub: String): Int? = when (sub) {
    "Highlight" -> R.string.annot_highlight; "Underline" -> R.string.annot_underline; "StrikeOut" -> R.string.annot_strikeout
    "Squiggly" -> R.string.annot_squiggly; "Ink" -> R.string.annot_ink; "Text" -> R.string.annot_note; "FreeText" -> R.string.annot_text
    "Line" -> R.string.annot_line; "Square" -> R.string.annot_rect; "Circle" -> R.string.annot_oval; "Stamp" -> R.string.annot_stamp
    "Polygon" -> R.string.annot_polygon; "PolyLine" -> R.string.an_polyline; "Caret" -> R.string.an_caret
    "FileAttachment" -> R.string.annot_attachment; "Sound" -> R.string.annot_audio; "Redact" -> R.string.annot_redact
    else -> null
}

fun statusLabelRes(s: ReviewStatus): Int = when (s) {
    ReviewStatus.NONE -> R.string.an_status_none; ReviewStatus.ACCEPTED -> R.string.an_status_accepted
    ReviewStatus.REJECTED -> R.string.an_status_rejected; ReviewStatus.CANCELLED -> R.string.an_status_cancelled
    ReviewStatus.COMPLETED -> R.string.an_status_completed
}

private enum class SortBy { PAGE, DATE, AUTHOR, TYPE, STATUS, COLOR, CHECKED }

/** One row of the panel: a pending annotation or a stored comment. */
private sealed interface Row2 {
    val page: Int; val subtype: String; val author: String; val date: Long; val y: Float
    val color: Int?; val status: Int; val checked: Boolean
    data class P(val a: Annot) : Row2 {
        override val page get() = a.page; override val subtype get() = kindSubtype(a.kind); override val author get() = a.author
        override val date get() = a.created; override val y get() = -(a.rects.firstOrNull()?.top ?: a.points.firstOrNull()?.second ?: a.paths.firstOrNull()?.firstOrNull()?.second ?: 0f)
        override val color get() = a.color; override val status get() = 0; override val checked get() = false
    }
    data class S(val c: CommentItem) : Row2 {
        override val page get() = c.page; override val subtype get() = c.subtype; override val author get() = c.author
        override val date get() = c.date; override val y get() = -maxOf(c.rect.top, c.rect.bottom)
        override val color get() = c.color; override val status get() = c.status.ordinal; override val checked get() = c.checked
    }
}

/**
 * Comments panel: pending and stored comments with threads (replies), review status chips with author and
 * date, filters by type AND author, sort by page / date / author, and the comment exchange menu.
 */
@Composable
fun CommentsPanel(state: ReaderState, onJump: (Int) -> Unit) {
    val h = state.annot2
    var sort by remember { mutableStateOf(SortBy.PAGE) }
    val types = remember { mutableStateListOf<String>() }
    val authors = remember { mutableStateListOf<String>() }
    var showFilter by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val colors = remember { mutableStateListOf<Int>() }
    val noAuthor = stringResource(R.string.an_unknown_author)

    val all: List<Row2> = state.annots.map { Row2.P(it) } + h.comments.map { Row2.S(it) }
    val allTypes = all.map { it.subtype }.distinct().sorted()
    val allAuthors = all.map { it.author.ifBlank { noAuthor } }.distinct().sorted()
    val allColors = all.mapNotNull { it.color }.distinct()
    val rows = all.filter { (types.isEmpty() || it.subtype in types) && (authors.isEmpty() || it.author.ifBlank { noAuthor } in authors) &&
        (colors.isEmpty() || it.color in colors) }
        .sortedWith(when (sort) {
            SortBy.PAGE -> compareBy<Row2>({ it.page }, { it.y })
            SortBy.DATE -> compareByDescending<Row2> { it.date }.thenBy { it.page }
            SortBy.AUTHOR -> compareBy<Row2>({ it.author.lowercase() }, { it.page }, { it.y })
            SortBy.TYPE -> compareBy<Row2>({ it.subtype }, { it.page }, { it.y })
            SortBy.STATUS -> compareBy<Row2>({ it.status }, { it.page }, { it.y })
            SortBy.COLOR -> compareBy<Row2>({ it.color ?: -1 }, { it.page }, { it.y })
            SortBy.CHECKED -> compareBy<Row2>({ !it.checked }, { it.page }, { it.y })
        })

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(showFilter || types.isNotEmpty() || authors.isNotEmpty(), { showFilter = !showFilter },
                label = { Text(stringResource(R.string.an_filter) + if (types.size + authors.size > 0) " (${types.size + authors.size})" else "") },
                leadingIcon = { Icon(Icons.Default.FilterList, null, Modifier.size(16.dp)) })
            Spacer(Modifier.width(6.dp))
            Box {
                AssistChip({ sortMenu = true }, label = { Text(stringResource(sortLabel(sort))) },
                    leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)) })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    SortBy.entries.map { it to sortLabel(it) }.forEach { (s, l) ->
                        DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { sort = s; sortMenu = false },
                            leadingIcon = { if (sort == s) Icon(Icons.Default.Check, null) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // section 7.63 / 24.73-24.74: expand or collapse every thread, step to the previous / next comment
            IconButton(onClick = { h.comments.forEach { expanded["${it.page}_${it.index}"] = true } }) { Icon(Icons.Default.UnfoldMore, stringResource(R.string.cx_expand_all)) }
            IconButton(onClick = { expanded.clear() }) { Icon(Icons.Default.UnfoldLess, stringResource(R.string.cx_collapse_all)) }
            IconButton(onClick = { h.cx.stepComment(false) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.cx_prev_comment)) }
            IconButton(onClick = { h.cx.stepComment(true) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.cx_next_comment)) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.an_more)) }
                DropdownMenu(more, { more = false }) { CommentsMenuItems(h) { more = false } }
            }
        }
        if (showFilter) {
            Text(stringResource(R.string.an_filter_type), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(types.isEmpty(), { types.clear() }, label = { Text(stringResource(R.string.an_all)) })
                allTypes.forEach { t -> FilterChip(t in types, { if (t in types) types.remove(t) else types.add(t) }, label = { Text(subtypeLabelRes(t)?.let { stringResource(it) } ?: t) }) }
            }
            Text(stringResource(R.string.an_filter_author), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(authors.isEmpty(), { authors.clear() }, label = { Text(stringResource(R.string.an_all)) })
                allAuthors.forEach { a -> FilterChip(a in authors, { if (a in authors) authors.remove(a) else authors.add(a) }, label = { Text(a) }, leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(14.dp)) }) }
            }
            // filter by colour (Foxit 24.76)
            if (allColors.size > 1) {
                Text(stringResource(R.string.cx_filter_color), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(colors.isEmpty(), { colors.clear() }, label = { Text(stringResource(R.string.an_all)) })
                    allColors.forEach { c -> com.smartdocs.editor.ui.reader.ColorDot(c, c in colors, 28.dp) { if (c in colors) colors.remove(c) else colors.add(c) } }
                }
            }
        }
        when {
            all.isEmpty() -> EmptyState(Icons.Default.Comment, stringResource(R.string.no_annotations))
            rows.isEmpty() -> EmptyState(Icons.Default.FilterList, stringResource(R.string.an_no_results))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { r -> when (r) { is Row2.P -> "p${r.a.id}"; is Row2.S -> "s${r.c.page}_${r.c.index}" } }) { r ->
                    when (r) {
                        is Row2.P -> PendingCard(state, r.a, onJump)
                        is Row2.S -> StoredCard(state, r.c, expanded["${r.c.page}_${r.c.index}"] == true,
                            onToggle = { val k = "${r.c.page}_${r.c.index}"; expanded[k] = expanded[k] != true }, onJump = onJump)
                    }
                }
            }
        }
    }
}

private val dateFmt get() = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

@Composable
private fun PendingCard(state: ReaderState, a: Annot, onJump: (Int) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable { onJump(a.page); state.edit.selectPending(a) }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(annotColor(a.color)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kindLabel(a.kind), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.an_unsaved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(listOf(stringResource(R.string.an_page_n, a.page + 1), a.author).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a.text.isNotBlank()) Text(a.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            // tabs audit CM-09: a locked comment is not deleted (section 7.58)
            val lockedMsg = stringResource(R.string.cx_locked_msg)
            IconButton(onClick = { if (a.locked) state.annot2.message = lockedMsg else state.removeAnnot(a.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.annot_delete)) }
        }
    }
}

@Composable
private fun StoredCard(state: ReaderState, c: CommentItem, expanded: Boolean, onToggle: () -> Unit, onJump: (Int) -> Unit) {
    val h = state.annot2
    var statusMenu by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var editReply by remember { mutableStateOf<com.smartdocs.editor.core.annot.ThreadReply?>(null) }
    val me = state.authorName.ifBlank { android.os.Build.MODEL ?: "" }
    editReply?.let { r -> ReplyStyleDialog(r, onDismiss = { editReply = null }) { a, col ->
        editReply = null
        h.launchBusy { h.fileOp { src, out, pw -> com.smartdocs.editor.core.annot.CommentOps7.setProps(src, out, pw, c.page, listOf(r.index), com.smartdocs.editor.core.annot.CommentOps7.Props(author = a, color = col)) } }
    } }
    OutlinedCard(Modifier.fillMaxWidth().clickable {
        onJump(c.page)
        state.existing.firstOrNull { it.page == c.page && it.index == c.index }?.let { state.edit.selectStored(it) }
    }) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(annotColor(c.color ?: 0x9E9E9E)))
                Spacer(Modifier.width(8.dp))
                Text(subtypeLabelRes(c.subtype)?.let { stringResource(it) } ?: c.subtype, style = MaterialTheme.typography.titleSmall)
                if (c.groupParent != null) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.Workspaces, stringResource(R.string.an_group), Modifier.size(14.dp)) }
                if (c.locked) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.Lock, stringResource(R.string.cx_locked), Modifier.size(14.dp)) }
                Spacer(Modifier.width(4.dp))
                Checkbox(c.checked, { v -> h.launchBusy { h.fileOp { src, out, pw -> CommentStore.setChecked(src, out, pw, c.page, c.index, v, me) } } }, Modifier.size(28.dp))
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.an_page_n, c.page + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(listOf(c.author.ifBlank { stringResource(R.string.an_unknown_author) }, if (c.date > 0) dateFmt.format(Date(c.date)) else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.contents.isNotBlank()) Text(c.contents, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 20 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box {
                    AssistChip({ statusMenu = true }, label = {
                        Text(if (c.status == ReviewStatus.NONE) stringResource(R.string.an_status)
                        else stringResource(R.string.an_status_by, stringResource(statusLabelRes(c.status)), c.statusAuthor.ifBlank { "—" }) +
                            if (c.statusDate > 0) " · " + dateFmt.format(Date(c.statusDate)) else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }, leadingIcon = { Icon(statusIcon(c.status), null, Modifier.size(16.dp)) })
                    DropdownMenu(statusMenu, { statusMenu = false }) {
                        ReviewStatus.entries.forEach { st ->
                            DropdownMenuItem(text = { Text(stringResource(statusLabelRes(st))) }, leadingIcon = { Icon(statusIcon(st), null) }, onClick = {
                                statusMenu = false
                                h.launchBusy { h.fileOp { src, out, pw -> CommentStore.setStatus(src, out, pw, c.page, c.index, st, me) } }
                            })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggle) {
                    Icon(Icons.Default.Reply, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (c.replies.isEmpty()) stringResource(R.string.annot_reply) else stringResource(R.string.an_replies, c.replies.size))
                }
                // tabs audit CM-09: locked comments stay; a stored comment is deleted only after a confirmation
                val lockedMsg = stringResource(R.string.cx_locked_msg)
                var confirm by remember { mutableStateOf(false) }
                IconButton(onClick = { if (c.locked) h.message = lockedMsg else confirm = true }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.annot_delete))
                }
                if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.tc_delete_comment_q)) },
                    confirmButton = { TextButton(onClick = { confirm = false; h.launchBusy { h.fileOp { src, out, pw -> CommentStore.deleteMany(src, out, pw, mapOf(c.page to listOf(c.index))) } } }) { Text(stringResource(R.string.annot_delete)) } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
            }
            if (expanded && c.history.size > 1) {
                // Review History (24.69): every status change with author and date
                Text(stringResource(R.string.cx_review_history), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                c.history.forEach { (st, au, d) ->
                    Text("\u2022 " + stringResource(statusLabelRes(st)) + " \u2014 " + au.ifBlank { "\u2014" } + if (d > 0) " \u00B7 " + dateFmt.format(Date(d)) else "",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 24.dp))
                }
            }
            if (expanded) {
                c.replies.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.SubdirectoryArrowLeft, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(listOf(r.author.ifBlank { stringResource(R.string.an_unknown_author) }, if (r.date > 0) dateFmt.format(Date(r.date)) else "").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                color = r.color?.let { annotColor(it) } ?: MaterialTheme.colorScheme.onSurface)
                            Text(r.text, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editReply = r }, Modifier.size(28.dp)) { Icon(Icons.Default.Edit, stringResource(R.string.cx_reply_style), Modifier.size(16.dp)) }
                        IconButton(onClick = { h.launchBusy { h.fileOp { src, out, pw -> CommentStore.deleteReply(src, out, pw, c.page, r.index) } } }, Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.delete), Modifier.size(16.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reply, { reply = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.an_reply_hint)) }, maxLines = 4,
                        visualTransformation = com.smartdocs.editor.ui.nve.spellTransformation(com.smartdocs.editor.ui.nve.rememberSpellIssues(reply)), trailingIcon = { com.smartdocs.editor.ui.nve.SpellCheckButton(reply, { reply = it }) })   // NVE: spell check
                    IconButton(onClick = {
                        val t = reply.trim(); if (t.isEmpty()) return@IconButton
                        reply = ""
                        h.launchBusy { h.fileOp { src, out, pw -> CommentStore.addReply(src, out, pw, c.page, c.index, me, t) } }
                    }, enabled = reply.isNotBlank() && !h.busy) { Icon(Icons.Default.Send, stringResource(R.string.an_send)) }
                }
            }
        }
    }
}

private fun statusIcon(s: ReviewStatus) = when (s) {
    ReviewStatus.NONE -> Icons.Default.RadioButtonUnchecked; ReviewStatus.ACCEPTED -> Icons.Default.ThumbUp
    ReviewStatus.REJECTED -> Icons.Default.ThumbDown; ReviewStatus.CANCELLED -> Icons.Default.Cancel; ReviewStatus.COMPLETED -> Icons.Default.TaskAlt
}

private fun sortLabel(s: SortBy): Int = when (s) {
    SortBy.PAGE -> R.string.an_sort_page; SortBy.DATE -> R.string.an_sort_date; SortBy.AUTHOR -> R.string.an_sort_author
    SortBy.TYPE -> R.string.cx_sort_type; SortBy.STATUS -> R.string.cx_sort_status; SortBy.COLOR -> R.string.cx_sort_color; SortBy.CHECKED -> R.string.cx_sort_checked
}

/** Edit a reply's author and colour (Foxit 24.83). */
@Composable
private fun ReplyStyleDialog(r: com.smartdocs.editor.core.annot.ThreadReply, onDismiss: () -> Unit, onOk: (String, Int?) -> Unit) {
    var author by remember { mutableStateOf(r.author) }
    var color by remember { mutableStateOf(r.color) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.cx_reply_style)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.cx_author)) }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.smartdocs.editor.ui.reader.PALETTE.forEach { c -> com.smartdocs.editor.ui.reader.ColorDot(c, color == c, 28.dp) { color = c } }
            }
        } },
        confirmButton = { TextButton(onClick = { onOk(author.trim(), color) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
