package com.smartdocs.editor.ui.nve.file

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DocProps
import com.smartdocs.editor.core.edit5.DocPropsOps
import com.smartdocs.editor.core.nve.DocInspect
import com.smartdocs.editor.core.nve.XmpOps
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

/**
 * NVE-file: document properties of the open document in the reader: Description / Advanced (page size, Fast Web
 * View, base URL, version, producer, tagged, PDF/A + PDF/UA, locks, mark as PDF/UA) / Security (method + current
 * permissions) / Fonts / XMP (view, export, import replace or merge). Changes are undoable steps of the reader's
 * history (state.applyInPlace).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveDocInfoSheet(state: ReaderState, nav: NavHostController, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var facts by remember { mutableStateOf<DocInspect.Facts?>(null) }
    var props by remember { mutableStateOf<DocProps?>(null) }
    var fonts by remember { mutableStateOf<List<DocInspect.FontEntry>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val file = state.file
    LaunchedEffect(file, state.renderVersion) {
        val f = file ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            facts = runCatching { DocInspect.read(f, state.password, state.currentPage) }.getOrNull()
            props = runCatching { DocPropsOps.read(f, state.password) }.getOrNull()
        }
        fonts = null
    }
    LaunchedEffect(tab, file, state.renderVersion) {
        if (tab == 3 && fonts == null) { val f = file ?: return@LaunchedEffect; fonts = withContext(Dispatchers.IO) { runCatching { DocInspect.readFonts(f, state.password) }.getOrDefault(emptyList()) } }
    }
    fun change(label: Int, op: suspend (java.io.File, java.io.File, String?) -> Unit, done: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { state.applyInPlace(label = { ctx.getString(label) }) { s, o, pw -> op(s, o, pw) } }
                .onSuccess { msg = ctx.getString(R.string.nf_applied); done?.invoke() }
                .onFailure { msg = it.message ?: ctx.getString(R.string.error_generic) }
            busy = false
        }
    }
    val exportXmp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/rdf+xml")) { uri: Uri? ->
        val f = file; if (uri == null || f == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { val b = XmpOps.exportFile(f, state.password) ?: error("no XMP"); ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) } ?: error("write") }.isSuccess }
            msg = ctx.getString(if (ok) R.string.nf_xmp_exported else R.string.nf_xmp_none)
        }
    }
    var importMode by remember { mutableStateOf(XmpOps.Mode.MERGE) }
    val importXmp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes == null || !XmpOps.isXmp(bytes)) { msg = ctx.getString(R.string.nf_xmp_invalid); return@launch }
            val mode = importMode
            change(R.string.nf_hi_xmp, { s, o, pw -> XmpOps.importFile(s, o, pw, bytes, mode) })
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text(stringResource(R.string.nf_props_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            ScrollableTabRow(tab, edgePadding = 12.dp) {
                listOf(R.string.nf_tab_desc, R.string.nf_tab_advanced, R.string.nf_tab_security, R.string.nf_tab_fonts, R.string.nf_tab_xmp).forEachIndexed { i, l ->
                    Tab(tab == i, { tab = i }, text = { Text(stringResource(l)) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            msg?.let { m -> Text(m, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val fa = facts
                when (tab) {
                    0 -> {
                        val p = props
                        if (p == null) CircularProgressIndicator() else {
                            val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                            KV(stringResource(R.string.e5_p_title), p.title); KV(stringResource(R.string.e5_p_author), p.author)
                            KV(stringResource(R.string.e5_p_subject), p.subject); KV(stringResource(R.string.e5_p_keywords), p.keywords)
                            KV(stringResource(R.string.e5_p_creator), p.creator); KV(stringResource(R.string.e5_p_producer), p.producer)
                            KV(stringResource(R.string.nf_created), p.created?.let { df.format(it.time) } ?: "—")
                            KV(stringResource(R.string.nf_modified), p.modified?.let { df.format(it.time) } ?: "—")
                            p.custom.forEach { (k, v) -> KV(k, v) }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    if (state.dirty && !state.save()) return@launch
                                    state.beforeExternalTool(ctx.getString(R.string.hi_tool_change))
                                    onDismiss(); nav.navigate(Routes.tool("e5_doc_props", state.fileUri))
                                }
                            }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf_edit_props)) }
                        }
                    }
                    1 -> if (fa == null) CircularProgressIndicator() else NveAdvancedPanel(fa, busy,
                        onBaseUrl = { url -> change(R.string.nf_hi_base_url, { s, o, pw -> XmpOps.setBaseUrlFile(s, o, pw, url) }) },
                        onMarkUa = { lang ->
                            var warns: List<XmpOps.UaWarning> = emptyList()
                            change(R.string.nf_hi_pdfua, { s, o, pw -> warns = XmpOps.markPdfUaFile(s, o, pw, lang) }) {
                                if (warns.isNotEmpty()) msg = ctx.getString(R.string.nf_ua_warn) + " " + warns.joinToString(", ") { w -> ctx.getString(uaWarnLabel(w)) }
                            }
                        })
                    2 -> if (fa == null) CircularProgressIndicator() else NveSecurityPanel(fa.security)
                    3 -> fonts?.let { NveFontsPanel(it) } ?: CircularProgressIndicator()
                    4 -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { exportXmp.launch((file?.nameWithoutExtension ?: "metadata") + ".xmp") }) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf_xmp_export)) }
                            OutlinedButton(enabled = !busy, onClick = { importMode = XmpOps.Mode.MERGE; importXmp.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf_xmp_append)) }
                            OutlinedButton(enabled = !busy, onClick = { importMode = XmpOps.Mode.REPLACE; importXmp.launch(arrayOf("*/*")) }) { Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf_xmp_replace)) }
                        }
                        Text(stringResource(R.string.nf_xmp_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val x = fa?.xmp
                        if (x.isNullOrBlank()) Text(stringResource(R.string.nf_xmp_none))
                        else SelectionContainer { Text(x, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

internal fun uaWarnLabel(w: XmpOps.UaWarning) = when (w) {
    XmpOps.UaWarning.UNTAGGED -> R.string.nf_ua_untagged
    XmpOps.UaWarning.NO_TITLE -> R.string.nf_ua_no_title
    XmpOps.UaWarning.NO_LANG -> R.string.nf_ua_no_lang
}

@Composable
internal fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.weight(0.6f)) { Text(v.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun yn(b: Boolean) = stringResource(if (b) R.string.e5_yes else R.string.e5_no)

/** Page size, Fast Web View, base URL (editable), version, producer, tagging, PDF/A / PDF/UA, locks, mark as PDF/UA. */
@Composable
fun NveAdvancedPanel(f: DocInspect.Facts, busy: Boolean, onBaseUrl: ((String) -> Unit)?, onMarkUa: ((String?) -> Unit)?) {
    f.firstPage?.let { p ->
        KV(stringResource(R.string.nf_page_size), String.format(java.util.Locale.US, "%.0f × %.0f pt · %.0f × %.0f mm · %.2f × %.2f in", p.widthPt, p.heightPt, p.widthMm, p.heightMm, p.widthIn, p.heightIn) + (p.name?.let { " ($it)" } ?: ""))
    }
    KV(stringResource(R.string.nf_pdf_version), f.version)
    KV(stringResource(R.string.e5_p_producer), f.producer)
    KV(stringResource(R.string.nf_pages), f.pages.toString())
    KV(stringResource(R.string.nf_fast_web), yn(f.linearized))
    KV(stringResource(R.string.nf_tagged), yn(f.tagged || f.hasStructTree))
    KV(stringResource(R.string.nf_lang), f.lang)
    KV(stringResource(R.string.nf_pdfa), f.pdfaClaim ?: stringResource(R.string.e5_no))
    KV(stringResource(R.string.nf_pdfua), f.pdfuaClaim?.let { "PDF/UA-$it" } ?: stringResource(R.string.e5_no))
    KV(stringResource(R.string.nf_locked), yn(f.locked))
    KV(stringResource(R.string.nf_signatures), f.signatures.toString() + if (f.certified) " · " + stringResource(R.string.nf_certified) else "")
    if (f.fieldLocks) KV(stringResource(R.string.nf_field_locks), yn(true))
    HorizontalDivider()
    var url by remember(f.baseUrl) { mutableStateOf(f.baseUrl) }
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = onBaseUrl != null,
        label = { Text(stringResource(R.string.nf_base_url)) }, placeholder = { Text("https://example.com/docs/") })
    if (onBaseUrl != null) Button(enabled = !busy && url.trim() != f.baseUrl, onClick = { onBaseUrl(url.trim()) }) { Text(stringResource(R.string.nf_base_url_save)) }
    if (onMarkUa != null) {
        HorizontalDivider()
        Text(stringResource(R.string.nf_ua_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.nf_ua_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!f.hasStructTree) Text(stringResource(R.string.nf_ua_untagged), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        var lang by remember(f.lang) { mutableStateOf(f.lang.ifBlank { if (java.util.Locale.getDefault().language == "ar") "ar" else "en" }) }
        if (f.lang.isBlank()) OutlinedTextField(lang, { lang = it }, singleLine = true, label = { Text(stringResource(R.string.nf_lang)) })
        Button(enabled = !busy && f.pdfuaClaim == null, onClick = { onMarkUa(lang.takeIf { it.isNotBlank() }) }) { Icon(Icons.Default.Accessibility, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nf_ua_mark)) }
    }
}

/** Encryption method and the permissions of the file as it is opened now. */
@Composable
fun NveSecurityPanel(s: DocInspect.Security) {
    KV(stringResource(R.string.nf_sec_encrypted), yn(s.encrypted))
    if (s.encrypted) {
        KV(stringResource(R.string.nf_sec_method), "${s.method} ${s.keyBits}-bit · R${s.revision} · ${s.filter}")
        KV(stringResource(R.string.nf_sec_opened_as), stringResource(if (s.owner) R.string.nf_sec_owner else R.string.nf_sec_user))
        KV(stringResource(R.string.nf_sec_meta), yn(s.encryptMetadata))
    }
    HorizontalDivider()
    Text(stringResource(R.string.nf_sec_permissions), style = MaterialTheme.typography.titleSmall)
    val p = s.permissions
    listOf(R.string.nf_perm_print to p.print, R.string.nf_perm_print_hq to p.printHighQuality, R.string.nf_perm_modify to p.modify,
        R.string.nf_perm_copy to p.copy, R.string.nf_perm_annotate to p.annotate, R.string.nf_perm_fill to p.fillForms,
        R.string.nf_perm_access to p.accessibility, R.string.nf_perm_assemble to p.assemble).forEach { (l, ok) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Block, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(stringResource(l), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Every font with type, encoding, embedded / subset state and where it is used. */
@Composable
fun NveFontsPanel(list: List<DocInspect.FontEntry>) {
    if (list.isEmpty()) { Text(stringResource(R.string.nf_fonts_none)); return }
    Text(stringResource(R.string.nf_fonts_count, list.size, list.count { !it.embedded }), style = MaterialTheme.typography.bodySmall)
    list.forEach { f ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.nf_font_type, f.type, f.encoding), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when { !f.embedded -> R.string.nf_font_not_embedded; f.subset -> R.string.nf_font_subset; else -> R.string.nf_font_embedded }),
                    style = MaterialTheme.typography.bodySmall, color = if (f.embedded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                val where = buildList {
                    if (f.pages.isNotEmpty()) add(stringResource(R.string.nf_font_pages, pagesText(f.pages)))
                    if (f.inAnnotations) add(stringResource(R.string.nf_font_in_annots))
                    if (f.inForms) add(stringResource(R.string.nf_font_in_forms))
                }
                if (where.isNotEmpty()) Text(where.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** {1,2,3,5} (zero-based) -> "1-4, 6" (one-based). */
internal fun pagesText(pages: Set<Int>): String {
    val s = pages.sorted(); val out = ArrayList<String>(); var i = 0
    while (i < s.size) { var j = i; while (j + 1 < s.size && s[j + 1] == s[j] + 1) j++; out += if (j > i) "${s[i] + 1}-${s[j] + 1}" else "${s[i] + 1}"; i = j + 1 }
    return out.joinToString(", ")
}
