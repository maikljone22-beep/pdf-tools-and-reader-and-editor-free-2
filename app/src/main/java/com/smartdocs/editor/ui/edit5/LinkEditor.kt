package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.LinkBorder
import com.smartdocs.editor.core.edit5.LinkDetect
import com.smartdocs.editor.core.edit5.LinkKind
import com.smartdocs.editor.core.edit5.LinkSpec
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LINK_COLORS = listOf(0x1565C0, 0x2E7D32, 0xC62828, 0x6A1B9A, 0xEF6C00, 0x212121)

/**
 * Edit what a link does and how it looks. Used by the links tool and by the reader's link tool.
 * [pageCount] bounds the "go to page" target; [onDelete] shows a delete button when editing an existing link.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LinkEditorSheet(
    initial: LinkSpec, pageCount: Int, isNew: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null,
    /** FINAL_CHECK: the reader's current view (0-based page, zoom 1 = 100%), for "Use current view". */
    currentView: (() -> Pair<Int, Float>)? = null,
    /** FINAL_CHECK: the document's named destinations, picked from a list instead of typed. */
    destNames: List<String> = emptyList(),
    /** fix/links-bookmarks: the document's embedded files, for destinations inside an attachment (GoToE). */
    attachments: NavAttachments? = null,
    onSave: (LinkSpec) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // nav-bookmarks: a new link starts with the saved default appearance
    var s by remember(initial) { mutableStateOf(if (isNew) LinkDefaults.apply(ctx, initial) else initial) }
    val defaultSaved = stringResource(R.string.nbm_link_default_saved)
    val mail0 = remember(initial) { if (initial.kind == LinkKind.MAIL) LinkDetect.parseMailto(initial.uri) else Triple("", "", "") }
    var mailTo by remember(initial) { mutableStateOf(mail0.first) }
    var mailSubject by remember(initial) { mutableStateOf(mail0.second) }
    var mailBody by remember(initial) { mutableStateOf(mail0.third) }
    var pageText by remember(initial) { mutableStateOf((initial.page + 1).toString()) }
    var zoomText by remember(initial) { mutableStateOf(if (initial.zoom > 0f) (initial.zoom * 100).toInt().toString() else "") }
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = runCatching { app.documents.localFile(uri) }.getOrNull()
            if (f != null) s = s.copy(file = f.absolutePath, kind = if (f.extension.equals("pdf", true)) LinkKind.REMOTE else LinkKind.LAUNCH)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (isNew) R.string.e5_link_new else R.string.e5_link_edit), style = MaterialTheme.typography.titleLarge)
            run {
                ChoiceChips(listOf(LinkKind.URI, LinkKind.PAGE, LinkKind.MAIL, LinkKind.REMOTE, LinkKind.LAUNCH, LinkKind.NAMED, LinkKind.OTHER), s.kind,
                    { if (it == LinkKind.OTHER) stringResource(R.string.nbm_action_list) else stringResource(kindLabel(it)) }) { s = s.copy(kind = it) }
                // fix/links-bookmarks: shortcuts into the action list for a destination inside an attachment, media and sound
                if (s.kind != LinkKind.OTHER) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!attachments?.keys.isNullOrEmpty()) AssistChip(onClick = { s = s.copy(kind = LinkKind.OTHER, actions = listOf(com.smartdocs.editor.core.edit5.NavAction.GoToE(attachments!!.keys.first()))) },
                        label = { Text(stringResource(R.string.lbm_act_embedded)) })
                    AssistChip(onClick = { s = s.copy(kind = LinkKind.OTHER, actions = listOf(com.smartdocs.editor.core.edit5.NavAction.Media(""))) }, label = { Text(stringResource(R.string.lbm_act_media)) })
                }
                when (s.kind) {
                    LinkKind.URI -> RecentUrlField(s.uri, { s = s.copy(uri = it) })   // fix/links-bookmarks: recently used URLs
                    LinkKind.MAIL -> {
                        OutlinedTextField(mailTo, { mailTo = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_mail_to)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(mailSubject, { mailSubject = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_mail_subject)) })
                        OutlinedTextField(mailBody, { mailBody = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(R.string.e5_link_mail_body)) })
                    }
                    LinkKind.PAGE, LinkKind.REMOTE -> {
                        if (s.kind == LinkKind.REMOTE) FileTarget(s.file) { filePicker.launch(arrayOf("application/pdf")) }
                        OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (s.kind == LinkKind.PAGE) stringResource(R.string.e5_link_page_of, pageCount) else stringResource(R.string.e5_link_page)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        DestViewFields(s.view) { v -> s = s.withView(v); zoomText = if (v.zoom > 0f) (v.zoom * 100).toInt().toString() else "" }
                        // Foxit "set this position": the page and zoom the reader shows now
                        if (s.kind == LinkKind.PAGE && currentView != null) TextButton(onClick = {
                            val (pg, z) = currentView()
                            pageText = (pg + 1).toString()
                            s = s.copy(page = pg, fit = DestFit.XYZ, zoom = z)
                            zoomText = (z * 100).toInt().toString()
                        }) { Text(stringResource(R.string.fsg_link_current_view)) }
                        if (s.kind == LinkKind.REMOTE) {
                            // nav-bookmarks: a named destination of the other file (instead of a page)
                            OutlinedTextField(s.named, { s = s.copy(named = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_named_dest_opt)) })
                            SwitchRow(stringResource(R.string.e5_link_new_window), s.newWindow) { s = s.copy(newWindow = it) }
                        }
                    }
                    LinkKind.LAUNCH -> {
                        FileTarget(s.file) { filePicker.launch(arrayOf("*/*")) }
                        Text(stringResource(R.string.e5_link_launch_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinkKind.NAMED -> {
                        OutlinedTextField(s.named, { s = s.copy(named = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_named)) })
                        // Foxit: pick one of the document's named destinations
                        if (destNames.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            destNames.take(40).forEach { n -> FilterChip(s.named == n, { s = s.copy(named = n) }, label = { Text(n, maxLines = 1) }) }
                        }
                    }
                    LinkKind.OTHER -> CompositionLocalProvider(LocalDestNames provides destNames, LocalNavAttachments provides attachments) { ActionListEditor(s.actions, pageCount) { s = s.copy(actions = it) } }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.e5_link_look), style = MaterialTheme.typography.titleSmall)
            ChoiceChips(LinkBorder.values().toList(), s.border, { stringResource(borderLabel(it)) }) { s = s.copy(border = it) }
            if (s.border != LinkBorder.NONE) {
                NbmColorRow(LINK_COLORS, s.color) { s = s.copy(color = it) }
                Text(stringResource(R.string.e5_link_width, s.borderWidth.toInt()))
                Slider(s.borderWidth.coerceIn(1f, 12f), { s = s.copy(borderWidth = it.roundToInt().toFloat()) }, valueRange = 1f..12f, steps = 10)
            }
            ChoiceChips(listOf("I", "O", "P", "N"), s.highlight, { stringResource(highlightLabel(it)) }) { s = s.copy(highlight = it) }
            OutlinedTextField(s.contents, { s = s.copy(contents = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_tooltip)) })
            TextButton(onClick = { LinkDefaults.save(ctx, s); android.widget.Toast.makeText(ctx, defaultSaved, android.widget.Toast.LENGTH_SHORT).show() }) {
                Icon(Icons.Default.Star, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbm_link_use_default))
            }
            // nav-bookmarks: move / resize (user space points; the text quads follow)
            Text(stringResource(R.string.nbm_link_position), style = MaterialTheme.typography.titleSmall)
            RectFields(s.rect) { s = s.copy(rect = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) OutlinedButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.delete)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                val valid = when (s.kind) {
                    LinkKind.URI -> s.uri.isNotBlank(); LinkKind.MAIL -> mailTo.contains('@'); LinkKind.PAGE -> pageText.toIntOrNull()?.let { it in 1..pageCount } == true
                    LinkKind.REMOTE, LinkKind.LAUNCH -> s.file.isNotBlank(); LinkKind.NAMED -> s.named.isNotBlank(); LinkKind.OTHER -> actionsComplete(s.actions)
                }
                Button(enabled = valid, onClick = {
                    val p = (pageText.toIntOrNull() ?: 1) - 1
                    val z = zoomText.toIntOrNull()?.let { it / 100f } ?: 0f
                    onSave(when (s.kind) {
                        LinkKind.MAIL -> s.copy(uri = LinkDetect.mailto(mailTo, mailSubject, mailBody))
                        LinkKind.URI -> s.copy(uri = LinkDetect.normalizeUrl(s.uri)).also { com.smartdocs.editor.core.edit5.RecentUrls.add(ctx, it.uri) }
                        LinkKind.PAGE, LinkKind.REMOTE -> s.copy(page = p.coerceAtLeast(0), zoom = z)
                        else -> s
                    })
                }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

/** fix/links-bookmarks: an action list that can be written (no attachment / clip left unchosen). */
fun actionsComplete(l: List<com.smartdocs.editor.core.edit5.NavAction>): Boolean = l.isNotEmpty() && l.none {
    (it is com.smartdocs.editor.core.edit5.NavAction.GoToE && it.attachment.isBlank()) || (it is com.smartdocs.editor.core.edit5.NavAction.Media && it.file.isBlank() && it.index < 0)
}

@Composable
private fun FileTarget(path: String, onPick: () -> Unit) {
    OutlinedButton(onClick = onPick, Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp))
        Text(if (path.isBlank()) stringResource(R.string.e5_link_pick_file) else java.io.File(path).name, maxLines = 1)
    }
}

fun kindLabel(k: LinkKind) = when (k) {
    LinkKind.URI -> R.string.e5_kind_uri; LinkKind.PAGE -> R.string.e5_kind_page; LinkKind.MAIL -> R.string.e5_kind_mail
    LinkKind.REMOTE -> R.string.e5_kind_remote; LinkKind.LAUNCH -> R.string.e5_kind_launch; LinkKind.NAMED -> R.string.e5_kind_named; LinkKind.OTHER -> R.string.e5_kind_other
}

fun fitLabel(f: DestFit) = nbmFitLabel(f)

/** Left / bottom / width / height of a link rect (PDF points). */
@Composable
private fun RectFields(r: android.graphics.RectF, onChange: (android.graphics.RectF) -> Unit) {
    val l = minOf(r.left, r.right); val b = minOf(r.top, r.bottom); val w = kotlin.math.abs(r.right - r.left); val h = kotlin.math.abs(r.top - r.bottom)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FloatField(l, stringResource(R.string.nbm_view_left), Modifier.weight(1f)) { onChange(android.graphics.RectF(it, b + h, it + w, b)) }
        FloatField(b, stringResource(R.string.nbm_view_bottom), Modifier.weight(1f)) { onChange(android.graphics.RectF(l, it + h, l + w, it)) }
        FloatField(w, stringResource(R.string.nbm_width), Modifier.weight(1f)) { if (it > 1f) onChange(android.graphics.RectF(l, b + h, l + it, b)) }
        FloatField(h, stringResource(R.string.nbm_height), Modifier.weight(1f)) { if (it > 1f) onChange(android.graphics.RectF(l, b + it, l + w, b)) }
    }
}

private fun borderLabel(b: LinkBorder) = when (b) {
    LinkBorder.NONE -> R.string.e5_border_none; LinkBorder.SOLID -> R.string.e5_border_solid; LinkBorder.DASHED -> R.string.e5_border_dashed; LinkBorder.UNDERLINE -> R.string.e5_border_underline
}

private fun highlightLabel(h: String) = when (h) { "O" -> R.string.e5_hl_outline; "P" -> R.string.e5_hl_push; "N" -> R.string.e5_hl_none; else -> R.string.e5_hl_invert }

/** One-line description of a link for lists. */
@Composable
fun linkSummary(s: LinkSpec): String = when (s.kind) {
    LinkKind.URI -> s.uri
    LinkKind.MAIL -> LinkDetect.parseMailto(s.uri).first
    LinkKind.PAGE -> stringResource(R.string.e5_link_to_page, s.page + 1)
    LinkKind.NAMED -> s.named
    LinkKind.REMOTE -> java.io.File(s.file).name + " • " + stringResource(R.string.e5_link_to_page, s.page + 1)
    LinkKind.LAUNCH -> java.io.File(s.file).name
    LinkKind.OTHER -> s.actions.takeIf { it.isNotEmpty() }?.let { l -> actionSummary(l.first()) + (if (l.size > 1) " +${l.size - 1}" else "") } ?: s.uri
}

/** [spec] after editing an existing link at [old]: the text quads follow a moved / resized rect. */
fun movedLink(spec: LinkSpec, old: LinkSpec): LinkSpec {
    val o = old.rect; val n = spec.rect
    if (o == n || old.quads.isEmpty()) return spec.copy(quads = old.quads)
    val from = com.smartdocs.editor.core.edit5.LinkGeom.Box(minOf(o.left, o.right), minOf(o.top, o.bottom), maxOf(o.left, o.right), maxOf(o.top, o.bottom))
    val to = com.smartdocs.editor.core.edit5.LinkGeom.Box(minOf(n.left, n.right), minOf(n.top, n.bottom), maxOf(n.left, n.right), maxOf(n.top, n.bottom))
    val q = old.quads.map { r ->
        val m = com.smartdocs.editor.core.edit5.LinkGeom.mapQuads(floatArrayOf(r.left, r.top, r.right, r.bottom), from, to)
        android.graphics.RectF(m[0], m[1], m[2], m[3])
    }
    return spec.copy(quads = q)
}
