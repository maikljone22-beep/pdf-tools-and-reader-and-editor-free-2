package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
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

private val LINK_COLORS = listOf(0x1565C0, 0x2E7D32, 0xC62828, 0x6A1B9A, 0xEF6C00, 0x212121)

/**
 * Edit what a link does and how it looks. Used by the links tool and by the reader's link tool.
 * [pageCount] bounds the "go to page" target; [onDelete] shows a delete button when editing an existing link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkEditorSheet(initial: LinkSpec, pageCount: Int, isNew: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (LinkSpec) -> Unit) {
    var s by remember(initial) { mutableStateOf(initial) }
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
    val editable = s.kind != LinkKind.OTHER
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (isNew) R.string.e5_link_new else R.string.e5_link_edit), style = MaterialTheme.typography.titleLarge)
            if (!editable) Text(stringResource(R.string.e5_link_readonly, s.uri), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                ChoiceChips(listOf(LinkKind.URI, LinkKind.PAGE, LinkKind.MAIL, LinkKind.REMOTE, LinkKind.LAUNCH, LinkKind.NAMED), s.kind, { stringResource(kindLabel(it)) }) { s = s.copy(kind = it) }
                when (s.kind) {
                    LinkKind.URI -> OutlinedTextField(s.uri, { s = s.copy(uri = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_url)) },
                        placeholder = { Text("https://") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
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
                        ChoiceChips(DestFit.values().toList(), s.fit, { stringResource(fitLabel(it)) }) { s = s.copy(fit = it) }
                        if (s.fit == DestFit.XYZ) OutlinedTextField(zoomText, { zoomText = it.filter { c -> c.isDigit() }.take(4) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.e5_link_zoom)) }, placeholder = { Text(stringResource(R.string.e5_link_zoom_keep)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (s.kind == LinkKind.REMOTE) SwitchRow(stringResource(R.string.e5_link_new_window), s.newWindow) { s = s.copy(newWindow = it) }
                    }
                    LinkKind.LAUNCH -> {
                        FileTarget(s.file) { filePicker.launch(arrayOf("*/*")) }
                        Text(stringResource(R.string.e5_link_launch_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinkKind.NAMED -> OutlinedTextField(s.named, { s = s.copy(named = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_named)) })
                    LinkKind.OTHER -> Unit
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.e5_link_look), style = MaterialTheme.typography.titleSmall)
            ChoiceChips(LinkBorder.values().toList(), s.border, { stringResource(borderLabel(it)) }) { s = s.copy(border = it) }
            if (s.border != LinkBorder.NONE) {
                ColorChips(LINK_COLORS, s.color) { s = s.copy(color = it) }
                Text(stringResource(R.string.e5_link_width, s.borderWidth.toInt()))
                Slider(s.borderWidth, { s = s.copy(borderWidth = it) }, valueRange = 1f..4f, steps = 2)
            }
            ChoiceChips(listOf("I", "O", "P", "N"), s.highlight, { stringResource(highlightLabel(it)) }) { s = s.copy(highlight = it) }
            OutlinedTextField(s.contents, { s = s.copy(contents = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_tooltip)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) OutlinedButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.delete)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                val valid = when (s.kind) {
                    LinkKind.URI -> s.uri.isNotBlank(); LinkKind.MAIL -> mailTo.contains('@'); LinkKind.PAGE -> pageText.toIntOrNull()?.let { it in 1..pageCount } == true
                    LinkKind.REMOTE, LinkKind.LAUNCH -> s.file.isNotBlank(); LinkKind.NAMED -> s.named.isNotBlank(); LinkKind.OTHER -> true
                }
                Button(enabled = valid, onClick = {
                    val p = (pageText.toIntOrNull() ?: 1) - 1
                    val z = zoomText.toIntOrNull()?.let { it / 100f } ?: 0f
                    onSave(when (s.kind) {
                        LinkKind.MAIL -> s.copy(uri = LinkDetect.mailto(mailTo, mailSubject, mailBody))
                        LinkKind.URI -> s.copy(uri = LinkDetect.normalizeUrl(s.uri))
                        LinkKind.PAGE, LinkKind.REMOTE -> s.copy(page = p.coerceAtLeast(0), zoom = z)
                        else -> s
                    })
                }) { Text(stringResource(R.string.save)) }
            }
        }
    }
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

fun fitLabel(f: DestFit) = when (f) { DestFit.XYZ -> R.string.e5_fit_xyz; DestFit.FIT -> R.string.e5_fit_page; DestFit.FITH -> R.string.e5_fit_width }

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
    LinkKind.OTHER -> s.uri
}
