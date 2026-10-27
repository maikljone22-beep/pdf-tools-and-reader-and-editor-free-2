package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Cv6Engine
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.edit5.PageOps5
import com.smartdocs.editor.core.edit5.PageOrganize
import com.smartdocs.editor.core.edit5.PageSet
import com.smartdocs.editor.core.edit5.PageSubset
import com.smartdocs.editor.core.edit5.PartName
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.reader.view.ReaderTabs
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Nitro 4 / Foxit 23a organize operations that PageOpsScreen does not have. */
enum class OrgOp { DELETE, DELETE_BLANK, ROTATE, MOVE, SWAP, REVERSE, EXTRACT, INSERT, INSERT_INTO, SPLIT_BOOKMARKS, SPLIT_RANGES, BOOKMARK_ORDER, MIX, FLATTEN, THUMBS }

private fun orgLabel(o: OrgOp) = when (o) {
    OrgOp.DELETE -> R.string.pc_op_delete; OrgOp.DELETE_BLANK -> R.string.pc_op_delete_blank; OrgOp.ROTATE -> R.string.pc_op_rotate
    OrgOp.MOVE -> R.string.pc_op_move; OrgOp.SWAP -> R.string.pc_op_swap; OrgOp.REVERSE -> R.string.e5_op_reverse; OrgOp.EXTRACT -> R.string.pc_op_extract
    OrgOp.INSERT -> R.string.pc_op_insert; OrgOp.INSERT_INTO -> R.string.pc_op_insert_into; OrgOp.SPLIT_BOOKMARKS -> R.string.pc_op_split_bookmarks
    OrgOp.SPLIT_RANGES -> R.string.pc_op_split_ranges; OrgOp.BOOKMARK_ORDER -> R.string.pc_op_bookmark_order; OrgOp.MIX -> R.string.pc_op_mix
    OrgOp.FLATTEN -> R.string.pc_op_flatten; OrgOp.THUMBS -> R.string.pc_op_thumbs
}

private fun partLabel(p: PartName) = when (p) {
    PartName.NUMBER -> R.string.pc_name_number; PartName.PADDED -> R.string.pc_name_padded; PartName.LETTER -> R.string.pc_name_letter; PartName.RANGE -> R.string.pc_name_range
}

private fun E5Doc.targetsOf(range: String, subset: PageSubset): List<Int> = PageSet.filter(PageSet.parse(range, pageCount), subset) { infos.getOrNull(it)?.landscape == true }

/** A file to insert or mix, with its display name (a converted web page / clipboard / other file is a temp PDF). */
private data class Src(val file: File, val name: String)

@Composable
fun PageOrganizeScreen(nav: NavHostController, uri: String?, initial: OrgOp = OrgOp.DELETE) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var op by remember { mutableStateOf(initial) }
    var range by remember { mutableStateOf("") }
    var subset by remember { mutableStateOf(PageSubset.ALL) }
    var degrees by remember { mutableIntStateOf(90) }
    var before by remember { mutableFloatStateOf(1f) }
    var a by remember { mutableFloatStateOf(1f) }
    var b by remember { mutableFloatStateOf(2f) }
    var mode by remember { mutableStateOf(PageOrganize.ExtractMode.ONE) }
    var deleteAfter by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(1f) }
    var naming by remember { mutableStateOf(PartName.NUMBER) }
    var keepNav by remember { mutableStateOf(true) }
    var keepBookmarks by remember { mutableStateOf(true) }
    var keepAttachments by remember { mutableStateOf(true) }
    var intoPos by remember { mutableStateOf(PageOrganize.IntoPos.END) }
    var embed by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    val sources = remember { mutableStateListOf<Src>() }
    val reversed = remember { mutableStateListOf<Boolean>() }
    fun add(fs: List<Src>) { sources += fs; repeat(fs.size) { reversed += false } }
    // converted sources come back on a worker thread
    suspend fun addLater(fs: List<Src>) = withContext(Dispatchers.Main) { add(fs) }
    val pickPdfs = rememberPdfPicker(multiple = true) { fs -> add(fs.map { Src(it, it.name) }) }
    val engine = remember { Cv6Engine(ctx, app.documents) }
    val converting = stringResource(R.string.processing)
    // non-PDF files (images, Office, text): converted to PDF first through the convert hub
    val pickAny = rememberPdfPicker(multiple = true, mimes = arrayOf("*/*")) { fs ->
        val conv = Cv6Catalog.byId("any_pdf") ?: return@rememberPdfPicker
        runner.runMany(converting) {
            val out = fs.map { f -> if (f.extension.equals("pdf", true)) f else engine.run(conv, listOf(f), Cv6Opts(merge = true), null) { _, _, _ -> }.files.first() }
            addLater(fs.zip(out).map { (f, o) -> Src(o, f.name) }); emptyList()
        }
    }
    val processing = stringResource(R.string.processing)
    val n = doc.pageCount
    ToolScaffold(stringResource(R.string.pc_organize_title), nav, runner) {
        E5FileRow(doc)
        ChoiceChips(OrgOp.values().toList(), op, { stringResource(orgLabel(it)) }) { op = it }
        val needsRange = op in setOf(OrgOp.DELETE, OrgOp.ROTATE, OrgOp.REVERSE, OrgOp.EXTRACT, OrgOp.INSERT_INTO, OrgOp.FLATTEN, OrgOp.MOVE)
        E5Section(stringResource(orgLabel(op))) {
            if (needsRange) {
                if (op == OrgOp.EXTRACT && mode == PageOrganize.ExtractMode.GROUPS) {
                    OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_groups)) }, placeholder = { Text("1-3, 5, 8-9") })
                } else RangeSubset(range, { range = it }, subset) { subset = it }
            }
            when (op) {
                OrgOp.DELETE -> Text(stringResource(R.string.pc_delete_help), style = MaterialTheme.typography.bodySmall)
                OrgOp.DELETE_BLANK -> Text(stringResource(R.string.pc_delete_blank_help), style = MaterialTheme.typography.bodySmall)
                OrgOp.ROTATE -> ChoiceChips(listOf(90, 180, 270), degrees, { stringResource(R.string.pc_degrees, it) }) { degrees = it }
                OrgOp.MOVE -> FloatField(before, stringResource(R.string.e5_insert_before_page, n + 1)) { before = it.coerceIn(1f, (n + 1).toFloat()) }
                OrgOp.SWAP -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatField(a, stringResource(R.string.pc_page_a), Modifier.weight(1f)) { a = it.coerceIn(1f, n.coerceAtLeast(1).toFloat()) }
                    FloatField(b, stringResource(R.string.pc_page_b), Modifier.weight(1f)) { b = it.coerceIn(1f, n.coerceAtLeast(1).toFloat()) }
                }
                OrgOp.EXTRACT -> {
                    ChoiceChips(PageOrganize.ExtractMode.values().toList(), mode, { stringResource(when (it) { PageOrganize.ExtractMode.ONE -> R.string.pc_extract_one; PageOrganize.ExtractMode.GROUPS -> R.string.pc_extract_groups; else -> R.string.pc_extract_each }) }) { mode = it }
                    SwitchRow(stringResource(R.string.pc_delete_after), deleteAfter) { deleteAfter = it }
                    SwitchRow(stringResource(R.string.pc_keep_nav), keepNav) { keepNav = it }
                }
                OrgOp.SPLIT_BOOKMARKS, OrgOp.SPLIT_RANGES -> {
                    if (op == OrgOp.SPLIT_BOOKMARKS) FloatField(level, stringResource(R.string.pc_bookmark_level)) { level = it.coerceIn(1f, 9f) }
                    else OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_groups)) }, placeholder = { Text("1-3, 4-10, 11-") })
                    Text(stringResource(R.string.pc_naming), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(PartName.values().toList(), naming, { stringResource(partLabel(it)) }) { naming = it }
                    SwitchRow(stringResource(R.string.pc_keep_nav), keepNav) { keepNav = it }
                }
                OrgOp.INSERT, OrgOp.MIX, OrgOp.INSERT_INTO -> {
                    SourceList(sources, if (op == OrgOp.MIX) reversed else null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = pickPdfs) { Text(stringResource(R.string.pc_add_pdfs)) }
                        if (op == OrgOp.INSERT) OutlinedButton(onClick = pickAny) { Text(stringResource(R.string.pc_add_any)) }
                    }
                    if (op == OrgOp.INSERT) {
                        // Foxit: insert from the open tabs, the clipboard text and a web page
                        val tabs = ReaderTabs.tabs.mapNotNull { t -> runCatching { File(android.net.Uri.parse(t.uri).path!!) }.getOrNull()?.takeIf { it.exists() && it != doc.source }?.let { Src(it, t.name) } }
                        if (tabs.isNotEmpty()) {
                            Text(stringResource(R.string.pc_from_tabs), style = MaterialTheme.typography.labelLarge)
                            tabs.forEach { t -> AssistChip(onClick = { add(listOf(t)) }, label = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                        }
                        OutlinedButton(onClick = {
                            val text = clip.getText()?.text.orEmpty()
                            val conv = Cv6Catalog.byId("clipboard_pdf")
                            if (text.isBlank() || conv == null) runner.error = ctx.getString(R.string.pc_clipboard_empty)
                            else runner.runMany(converting) { addLater(listOf(Src(engine.run(conv, emptyList(), Cv6Opts(text = text), null) { _, _, _ -> }.files.first(), ctx.getString(R.string.pc_clipboard)))); emptyList() }
                        }) { Text(stringResource(R.string.pc_add_clipboard)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_web_page)) })
                            OutlinedButton(enabled = url.isNotBlank(), onClick = {
                                val conv = Cv6Catalog.byId("url_pdf") ?: return@OutlinedButton
                                val u = url
                                runner.runMany(converting) { addLater(listOf(Src(engine.run(conv, emptyList(), Cv6Opts(url = u), null) { _, _, _ -> }.files.first(), u))); emptyList() }
                            }) { Text(stringResource(R.string.pc_add)) }
                        }
                        FloatField(before, stringResource(R.string.e5_insert_before_page, n + 1)) { before = it.coerceIn(1f, (n + 1).toFloat()) }
                        SwitchRow(stringResource(R.string.pc_keep_bookmarks), keepBookmarks) { keepBookmarks = it }
                        SwitchRow(stringResource(R.string.pc_keep_attachments), keepAttachments) { keepAttachments = it }
                    }
                    if (op == OrgOp.INSERT_INTO) {
                        ChoiceChips(PageOrganize.IntoPos.values().toList(), intoPos, { stringResource(when (it) { PageOrganize.IntoPos.START -> R.string.pc_at_start; PageOrganize.IntoPos.END -> R.string.pc_at_end; else -> R.string.pc_after_page }) }) { intoPos = it }
                        if (intoPos == PageOrganize.IntoPos.AFTER) FloatField(before, stringResource(R.string.pc_after_page)) { before = it.coerceAtLeast(0f) }
                    }
                    if (op == OrgOp.MIX) Text(stringResource(R.string.pc_mix_help), style = MaterialTheme.typography.bodySmall)
                }
                OrgOp.BOOKMARK_ORDER -> Text(stringResource(R.string.pc_bookmark_order_help), style = MaterialTheme.typography.bodySmall)
                OrgOp.THUMBS -> ChoiceChips(listOf(true, false), embed, { stringResource(if (it) R.string.pc_thumbs_embed else R.string.pc_thumbs_remove) }) { embed = it }
                else -> {}
            }
        }
        val ready = doc.work != null && when (op) {
            OrgOp.INSERT, OrgOp.INSERT_INTO, OrgOp.MIX -> sources.isNotEmpty()
            OrgOp.SWAP -> a.toInt() != b.toInt()
            OrgOp.SPLIT_RANGES -> range.isNotBlank()
            else -> true
        }
        PrimaryButton(stringResource(R.string.e5_run), enabled = ready, icon = Icons.Default.PlayArrow) {
            val o = op; val sel = if (o == OrgOp.EXTRACT && mode == PageOrganize.ExtractMode.GROUPS) emptyList() else doc.targetsOf(range, subset)
            val rg = range; val dg = degrees; val bf = before.toInt() - 1; val pa = a.toInt() - 1; val pb = b.toInt() - 1; val md = mode; val da = deleteAfter
            val lv = level.toInt() - 1; val nm = naming; val kn = keepNav; val kb = keepBookmarks; val ka = keepAttachments; val ip = intoPos; val em = embed
            val srcs = sources.toList(); val rev = reversed.toList()
            val base = doc.source?.nameWithoutExtension ?: "document"
            val unique: (File, String) -> File = { d, s -> app.documents.uniqueFile(d, s) }
            fun edit(suffix: String, block: suspend (File, File, String?) -> Unit) = runner.run(processing) {
                doc.apply(app.documents.tempDir, block); doc.export(app.documents.docsDir, unique, suffix)
            }
            when (o) {
                OrgOp.DELETE -> if (sel.isNotEmpty() && sel.size < n) edit("deleted") { s, out, p -> PageOrganize.delete(s, out, p, sel) } else runner.error = ctx.getString(R.string.pc_err_delete_all)
                OrgOp.DELETE_BLANK -> runner.run(processing) {
                    var removed = emptyList<Int>()
                    doc.apply(app.documents.tempDir) { s, out, p ->
                        removed = PageOrganize.deleteBlank(ctx, s, out, p)
                        if (removed.isEmpty()) s.copyTo(out, overwrite = true)
                    }
                    if (removed.isEmpty()) throw IllegalStateException(ctx.getString(R.string.pc_no_blank))
                    doc.export(app.documents.docsDir, unique, "no_blank")
                }
                OrgOp.ROTATE -> edit("rotated") { s, out, p -> PdfOps.rotatePages(s, sel, dg, out, p) }
                OrgOp.MOVE -> edit("moved") { s, out, p -> PageOrganize.arrange(s, out, p, PageOrganize.moveOrder(n, sel, bf)) }
                OrgOp.SWAP -> edit("swapped") { s, out, p -> PageOrganize.arrange(s, out, p, PageOrganize.swapOrder(n, pa, pb)) }
                OrgOp.REVERSE -> edit("reversed") { s, out, p -> PageOps5.reverse(s, out, p, sel) }
                OrgOp.FLATTEN -> edit("flattened") { s, out, p -> PageOrganize.flattenPages(s, out, p, sel) }
                OrgOp.BOOKMARK_ORDER -> runner.run(processing) {
                    var changed = false
                    doc.apply(app.documents.tempDir) { s, out, p -> changed = PageOrganize.rearrangeByBookmarks(s, out, p); if (!changed) s.copyTo(out, overwrite = true) }
                    if (!changed) throw IllegalStateException(ctx.getString(R.string.pc_order_same))
                    doc.export(app.documents.docsDir, unique, "by_bookmarks")
                }
                OrgOp.THUMBS -> edit(if (em) "thumbs" else "no_thumbs") { s, out, p -> if (em) PageOrganize.embedThumbnails(ctx, s, out, p) else PageOrganize.removeThumbnails(s, out, p) }
                OrgOp.INSERT -> edit("inserted") { s, out, p ->
                    // insert the files in list order, one after the other, at the same place
                    var cur = s; var at = bf
                    srcs.forEachIndexed { k, src ->
                        val dst = if (k == srcs.lastIndex) out else app.documents.newTemp("ins_$k")
                        PageOrganize.insertFile(cur, dst, p, src.file, null, at, kb, ka, src.name.substringBeforeLast('.'))
                        at += PdfOps.pageCount(src.file, null)
                        if (cur != s) cur.delete()
                        cur = dst
                    }
                }
                OrgOp.EXTRACT -> runner.runMany(processing) {
                    val w = doc.work!!; val pw = doc.password
                    val groups = when (md) { PageOrganize.ExtractMode.ONE -> listOf(sel); PageOrganize.ExtractMode.GROUPS -> PageOrganize.rangeGroups(rg, n); PageOrganize.ExtractMode.EACH -> sel.map { listOf(it) } }
                    require(groups.isNotEmpty() && groups.all { it.isNotEmpty() }) { ctx.getString(R.string.pc_err_range) }
                    val files = PageOrganize.writeParts(w, pw, groups, app.documents.docsDir, "${base}_extract", if (md == PageOrganize.ExtractMode.ONE) PartName.RANGE else nm, kn, unique)
                    val gone = groups.flatten().toSet()
                    if (da && gone.size < n) {
                        doc.apply(app.documents.tempDir) { s, out, p -> PageOrganize.delete(s, out, p, gone) }
                        files + doc.export(app.documents.docsDir, unique, "rest")
                    } else files
                }
                OrgOp.SPLIT_BOOKMARKS -> runner.runMany(processing) {
                    PageOrganize.splitByBookmarks(doc.work!!, doc.password, lv, app.documents.docsDir, base, nm, kn, unique).ifEmpty { throw IllegalStateException(ctx.getString(R.string.pc_no_bookmarks_level)) }
                }
                OrgOp.SPLIT_RANGES -> runner.runMany(processing) {
                    val groups = PageOrganize.rangeGroups(rg, n)
                    require(groups.isNotEmpty()) { ctx.getString(R.string.pc_err_range) }
                    PageOrganize.writeParts(doc.work!!, doc.password, groups, app.documents.docsDir, base, nm, kn, unique)
                }
                OrgOp.INSERT_INTO -> runner.runMany(processing) {
                    PageOrganize.insertInto(doc.work!!, doc.password, sel, srcs.map { it.file to null }, ip, bf + 1) { t -> unique(app.documents.docsDir, t.nameWithoutExtension + "_with_" + base) }
                }
                OrgOp.MIX -> runner.run(processing) {
                    val all = listOf(doc.work!! to doc.password) + srcs.map { it.file to null }
                    val out = unique(app.documents.docsDir, base + "_mixed")
                    PageOrganize.mix(all, listOf(false) + rev, out); out
                }
            }
        }
        ResultsList(runner.results, nav, "application/pdf")
    }
}

/** The files to insert / mix, in order, with up / down / remove (and "reverse pages" for Alternate Mix). */
@Composable
private fun SourceList(sources: MutableList<Src>, reversed: MutableList<Boolean>?) {
    sources.forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. ${s.name}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (reversed != null) {
                Text(stringResource(R.string.pc_reverse_short), style = MaterialTheme.typography.labelSmall)
                Checkbox(reversed.getOrElse(i) { false }, { v -> reversed[i] = v })
            }
            IconButton(onClick = { PageOrganize.moveItem(sources, i, i - 1); reversed?.let { PageOrganize.moveItem(it, i, i - 1) } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.pc_move_up)) }
            IconButton(onClick = { PageOrganize.moveItem(sources, i, i + 1); reversed?.let { PageOrganize.moveItem(it, i, i + 1) } }, enabled = i < sources.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.pc_move_down)) }
            IconButton(onClick = { sources.removeAt(i); reversed?.removeAt(i) }) { Icon(Icons.Default.Close, stringResource(R.string.pc_remove)) }
        }
    }
}
