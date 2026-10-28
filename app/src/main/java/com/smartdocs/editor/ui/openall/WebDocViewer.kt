package com.smartdocs.editor.ui.openall

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.FBlock
import com.smartdocs.editor.core.convert6.FileKind6
import com.smartdocs.editor.core.convert6.Text6
import com.smartdocs.editor.core.convert6.FlowHtml
import com.smartdocs.editor.core.convert6.LegacyEncrypted
import com.smartdocs.editor.core.convert6.OdfReader
import com.smartdocs.editor.core.convert6.ToPdf6
import com.smartdocs.editor.core.office.OfficeHtml
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.openall.*
import com.smartdocs.editor.core.tools5.EmlReader
import com.smartdocs.editor.core.tools5.HtmlBits
import com.smartdocs.editor.core.tools5.MarkdownHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the HTML-based viewer shows for one file. */
class WebDoc(
    val html: String,
    val baseUrl: String,
    val resources: (String) -> WebRes? = { null },
    val toc: List<TocItem> = emptyList(),
    val attachments: List<MailAttachment> = emptyList(),
    val hint: Int? = null,
    /** Paragraph texts for "Edit as Word" (old .doc / .ott / RTF imports). */
    val paragraphs: List<String>? = null,
    val isHtmlFile: Boolean = false,
)

/** Builds the HTML of every document-like format (open-all). Blocking: call on a background thread. */
object WebDocs {
    const val BASE = "https://doc.smartdocs.local/"
    private const val MAX_TEXT = 40L * 1024 * 1024

    private fun bytes(f: File, max: Long = 200L * 1024 * 1024): ByteArray {
        if (f.length() > max) throw IllegalStateException("file too large (${f.length() / (1024 * 1024)} MB)")
        return f.readBytes()
    }

    fun build(ctx: Context, v: ViewFile): WebDoc {
        val f = v.file!!
        val title = f.nameWithoutExtension
        return when (v.type) {
            FType.MARKDOWN -> {
                val md = TextDecode.decode(bytes(f, MAX_TEXT), truncated = false).text
                WebDoc(HtmlBits.page(title, MarkdownHtml.toHtmlBody(md), HtmlBits.rtlDominant(md)), BASE + "index.html")
            }
            FType.HTML -> WebDoc(TextDecode.decode(bytes(f, MAX_TEXT), truncated = false).text, BASE + "index.html", isHtmlFile = true)
            FType.MHT -> {
                val w = MhtReader.read(bytes(f))
                val base = w.baseUrl?.takeIf { it.startsWith("http") } ?: (BASE + "index.html")
                WebDoc(MailHtml.stripActive(w.html), base, { url -> w.resource(url)?.let { WebRes(it.type, it.data, it.charset) } }, isHtmlFile = true)
            }
            FType.EML -> {
                val d = EmlReader.read(bytes(f), title, mailLabels(ctx))
                WebDoc(MailHtml.stripActive(d.html), BASE + "mail.html", attachments = d.attachments.map { MailAttachment(it.name, it.mime, it.bytes) })
            }
            FType.MSG -> {
                val m = MsgReader.read(bytes(f, 100L * 1024 * 1024))
                WebDoc(MailHtml.page(m, mailLabels(ctx)), BASE + "mail.html", attachments = m.attachments.filter { it.cid == null || m.bodyHtml?.contains(it.cid) != true })
            }
            FType.FB2 -> { val b = Fb2Reader.read(bytes(f), title); WebDoc(b.html, BASE + "book.html", toc = b.toc) }
            FType.MOBI -> { val b = MobiReader.read(bytes(f), title); WebDoc(b.html, BASE + "book.html", toc = b.toc) }
            FType.PPT -> {
                // fix/files-index: slides laid out with their pictures (PptReader) when the file has shapes, else the texts
                val b = bytes(f)
                val model = runCatching { com.smartdocs.editor.core.convert6.PptReader.read(b) }.getOrNull()?.takeIf { m -> m.slides.any { it.shapes.isNotEmpty() } }
                if (model != null) WebDoc(com.smartdocs.editor.core.office.OfficeHtml.pptx(model, forPrint = false), BASE + "slides.html", hint = R.string.oa_readonly_hint)
                else WebDoc(FlowHtml.write(ToPdf6.slidesFlow(title, PptText.slides(b))), BASE + "slides.html", hint = R.string.oa_ppt_hint)
            }
            FType.ODP -> WebDoc(FlowHtml.write(ToPdf6.slidesFlow(title, OdfReader.readOdp(OdfReader.zipEntry(f)))), BASE + "slides.html", hint = R.string.oa_readonly_hint)
            FType.XLS, FType.ODS -> WebDoc(FlowHtml.write(ToPdf6.sheetsFlow(title, ToPdf6.readSheets(f, FileKind6.of(f)))), BASE + "sheet.html", hint = R.string.oa_readonly_hint)
            FType.XLSX -> WebDoc(OfficeHtml.xlsx(OoxmlReader.readXlsx(f)), BASE + "sheet.html", hint = R.string.oa_readonly_hint)
            FType.PPTX -> WebDoc(OfficeHtml.pptx(OoxmlReader.readPptx(f), forPrint = false), BASE + "slides.html", hint = R.string.oa_readonly_hint)
            FType.DOCX -> WebDoc(OfficeHtml.docx(OoxmlReader.readDocx(f), title), BASE + "doc.html", hint = R.string.oa_readonly_hint)
            FType.DOC, FType.RTF, FType.ODT -> {
                val flow = ToPdf6.readWordLike(f, FileKind6.of(f))
                val paras = flow.blocks.mapNotNull { b -> plainOf(b) }
                WebDoc(FlowHtml.write(flow), BASE + "doc.html", hint = if (v.type == FType.DOC) R.string.oa_doc_hint else R.string.oa_readonly_hint, paragraphs = paras)
            }
            else -> throw IllegalArgumentException(v.type.name)
        }
    }

    private fun plainOf(b: FBlock): String? = when (b) {
        is FBlock.Heading -> Text6.plain(b.runs)
        is FBlock.Para -> Text6.plain(b.runs)
        is FBlock.Item -> Text6.plain(b.runs)
        is FBlock.Quote -> Text6.plain(b.runs)
        is FBlock.Code -> b.text
        is FBlock.Table -> b.rows.joinToString("\n") { it.joinToString(" | ") }
        else -> null
    }

    /** Self-contained HTML for "Convert to PDF" of the formats the convert hub does not know. */
    fun forPrint(ctx: Context, v: ViewFile): String {
        val f = v.file!!
        return when (v.type) {
            FType.VCF, FType.ICS -> CardsHtml.page(ctx, f, v.type)
            FType.CODE, FType.TEXT, FType.CSV -> {
                val t = TextDecode.decode(bytes(f, 8L * 1024 * 1024), truncated = false).text
                val pretty = when (f.extension.lowercase()) { "json" -> Pretty.json(t); "xml" -> Pretty.xml(t); else -> null } ?: t
                HtmlBits.page(f.nameWithoutExtension, "<pre dir=\"auto\" style=\"font-family:monospace;font-size:9pt\">" + HtmlBits.esc(pretty) + "</pre>", HtmlBits.rtlDominant(t.take(4000)))
            }
            FType.MHT -> {
                // resources inlined as data: URIs (the print WebView must not fetch anything)
                val w = MhtReader.read(bytes(f))
                val base = runCatching { java.net.URI(w.baseUrl ?: "") }.getOrNull()
                Regex("""(?i)(src|href)\s*=\s*(["'])([^"']+)\2""").replace(MailHtml.stripActive(w.html)) { m ->
                    val ref = m.groupValues[3]
                    val abs = runCatching { base?.resolve(ref)?.toString() }.getOrNull() ?: ref
                    val part = w.resource(abs) ?: w.resource(ref)
                    if (part != null && !part.type.startsWith("text/html")) m.groupValues[1] + "=" + m.groupValues[2] + "data:" + part.type + ";base64," + java.util.Base64.getEncoder().encodeToString(part.data) + m.groupValues[2]
                    else m.value
                }
            }
            else -> build(ctx, v).html
        }
    }
}

/**
 * HTML-based viewer for Markdown, HTML, MHT, e-mail (EML/MSG), FB2, MOBI and the read-only Office views (old
 * .doc/.xls/.ppt, ODS/ODP/OTT, and Office files the editors cannot load). Safe WebView: no scripts, no network.
 */
@Composable
fun WebDocViewer(nav: NavHostController, v: ViewFile) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = v.file!!
    val doc by produceState<Result<WebDoc>?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { WebDocs.build(ctx, v) } } }
    var allowNet by remember { mutableStateOf(false) }
    var allowJs by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(100) }
    var night by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var fragment by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(false) }
    if (source) { TextViewer(nav, ViewFile(f, FType.CODE, v.uri, v.name, v.depth), onBack = { source = false }); return }
    val d = doc?.getOrNull()
    val err = doc?.exceptionOrNull()
    if (err is UnsupportedBookException) {
        FileInfoScreen(nav, v, when (err.reason) { "drm" -> R.string.oa_why_drm; "huffcdic" -> R.string.oa_why_huff; else -> null }); return
    }
    val actions = buildList {
        add(ViewerAction(R.string.oa_font_bigger, Icons.Default.TextIncrease) { zoom = (zoom + 15).coerceAtMost(300) })
        add(ViewerAction(R.string.oa_font_smaller, Icons.Default.TextDecrease) { zoom = (zoom - 15).coerceAtLeast(50) })
        add(ViewerAction(R.string.oa_night, Icons.Default.DarkMode) { night = !night })
        if (v.type == FType.MARKDOWN || v.type == FType.HTML) add(ViewerAction(R.string.oa_raw, Icons.Default.Code) { source = true })
        if (v.type == FType.MARKDOWN) add(ViewerAction(R.string.oa_edit, Icons.Default.Edit) { nav.navigate(com.smartdocs.editor.ui.office.OfficeRoutes.route(Uri.fromFile(f).toString())) })
        if (d?.isHtmlFile == true && !allowJs) add(ViewerAction(R.string.oa_scripts_allow, Icons.Default.Code) { allowJs = true })
        if (d?.paragraphs != null) add(ViewerAction(R.string.oa_edit_as_docx, Icons.Default.EditNote) {
            scope.launch {
                val out = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension, "docx")
                        com.smartdocs.editor.ui.office.ImportState(f, com.smartdocs.editor.core.office5.PlainImport.Doc(d.paragraphs)).toDocx(target)
                    }.getOrNull()
                }
                if (out != null) com.smartdocs.editor.ui.office.OfficeRoutes.open(nav, out)
            }
        })
    }
    ViewerScaffold(nav, v, v.name, actions = actions,
        topIcons = { if (d?.toc?.isNotEmpty() == true) IconButton(onClick = { showToc = true }) { Icon(Icons.Default.Toc, stringResource(R.string.oa_toc)) } },
        convert = { out -> ViewerConvert.toPdf(ctx, v, out) },
        bottomBar = { if (d != null && d.attachments.isNotEmpty()) AttachmentBar(nav, v, d.attachments) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                doc == null -> CenteredProgress()
                d == null -> ErrorPane(if (err is LegacyEncrypted) ctx.getString(R.string.oa_why_encrypted) else if (err is UnsafeXmlException) ctx.getString(R.string.oa_xml_unsafe) else err?.message)
                else -> {
                    d.hint?.let { h -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) { Text(stringResource(h), Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall) } }
                    if (blocked && !allowNet) Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.oa_remote_blocked), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { allowNet = true }) { Text(stringResource(R.string.oa_remote_allow)) }
                        }
                    }
                    val html = remember(d, night) { if (night) injectHead(d.html, NIGHT_CSS) else d.html }
                    val bytes = remember(html) { html.toByteArray(Charsets.UTF_8) }
                    val mainUrl = d.baseUrl
                    SafeWebView(
                        url = mainUrl + (if (fragment.isNotEmpty()) "#$fragment" else ""),
                        resolver = { u -> if (u == mainUrl) WebRes("text/html", bytes, "utf-8") else d.resources(u) },
                        modifier = Modifier.fillMaxSize(),
                        allowNetwork = allowNet, allowJs = allowJs, textZoom = zoom,
                        onBlocked = { blocked = true },
                        onLink = { u -> openLink(ctx, u) },
                        reloadKey = "$night|$allowNet|$allowJs",
                    )
                }
            }
        }
    }
    if (showToc && d != null) TocSheet(d.toc, onPick = { fragment = it.fragment; showToc = false }, onDismiss = { showToc = false })
}

/** Link taps from documents: web / mail links go to the system (the user tapped them), others are ignored. */
fun openLink(ctx: Context, u: String) {
    val s = u.lowercase()
    if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("mailto:") || s.startsWith("tel:")) {
        if (s.contains(".smartdocs.local/") || s.contains("cid.local/")) return
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(toc: List<TocItem>, onPick: (TocItem) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.oa_toc), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(toc) { t ->
                Text(t.title, Modifier.fillMaxWidth().clickable { onPick(t) }.padding(start = (16 + 16 * t.level.coerceAtMost(6)).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** E-mail attachments: each opens in the in-app viewer (written to the cache first). */
@Composable
fun AttachmentBar(nav: NavHostController, v: ViewFile, atts: List<MailAttachment>) {
    val ctx = LocalContext.current
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(stringResource(R.string.oa_attachments, atts.size), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                atts.forEachIndexed { i, a ->
                    AssistChip(onClick = {
                        if (v.depth >= 3) return@AssistChip
                        val dir = OpenAll.workDir(ctx, "mail_${v.name.hashCode()}")
                        val safe = a.name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(120).ifBlank { "attachment" }
                        val out = File(dir, "${i}_$safe")
                        runCatching { if (!out.exists() || out.length() != a.bytes.size.toLong()) out.writeBytes(a.bytes) }
                        nav.navigate(OpenAll.route(Uri.fromFile(out).toString(), v.depth + 1))
                    }, label = { Text(a.name + " (" + com.smartdocs.editor.core.files.DocumentRepository.formatSize(a.bytes.size.toLong()) + ")", maxLines = 1) },
                        leadingIcon = { Icon(iconFor(FileSniffer.detect(a.name, a.bytes.copyOf(minOf(a.bytes.size, 4096)), a.mime)), null) })
                }
            }
        }
    }
}
