package com.smartdocs.editor.ui.nve

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.DocTexts
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bridges the reader host to the screens of the other nitro-view-edit parts (file, panes, shell). */
internal object NveExternal {
    @Composable
    fun toolbarCustomize(s: ReaderState, onDismiss: () -> Unit) {
        @Suppress("UNUSED_VARIABLE") val unused = s
        com.smartdocs.editor.ui.nve.shell.NveToolbarCustomizeSheet(onDismiss)
    }

    @Composable
    fun saveAs(s: ReaderState, nav: NavHostController, onDismiss: () -> Unit) =
        com.smartdocs.editor.ui.nve.file.NveSaveAsSheet(s, nav, exportCopy = { out -> s.exportWithAnnots(out) }, onDismiss = onDismiss)

    @Composable
    fun docInfo(s: ReaderState, nav: NavHostController, onDismiss: () -> Unit) = com.smartdocs.editor.ui.nve.file.NveDocInfoSheet(s, nav, onDismiss)

    @Composable
    fun pane(s: ReaderState, pane: NvePane, nav: NavHostController, onJump: (Int) -> Unit, onClose: () -> Unit) {
        val v5 = com.smartdocs.editor.ui.view5.V5State.of(s)
        com.smartdocs.editor.ui.nve.panes.NvePaneContent(s, pane, onJump = onJump, onClose = onClose,
            onOpenPdf = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString())) },
            onOpenTool = { id -> v5.env?.actions?.navigateTool?.invoke(id) ?: nav.navigate(Routes.tool(id, s.fileUri)) },
            onShowRect = { p, r -> NveZoom.zoomToPageRect(s, p, r[0], r[1], r[2], r[3]) })
    }
}

/**
 * Whole-document spell check (Foxit "Check Spelling"): pending comments, stored comments and text form fields go
 * through the document spell-check dialog; corrections of stored texts are one undo step.
 */
@Composable
fun NveDocSpell(s: ReaderState) {
    val n = NveState.of(s)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stored by produceState<List<com.smartdocs.editor.core.nve.DocText>?>(null) {
        val f = s.file
        value = if (f == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { com.smartdocs.editor.core.pdf.PdfOps.load(f, s.password).use { DocTexts.collect(it) } }.getOrDefault(emptyList())
        }
    }
    val list = stored ?: return
    val pending = s.annots.filter { it.text.isNotBlank() && it.image == null }
    val targets = pending.map { SpellTarget("p:" + it.id, ctx.getString(R.string.page_of, it.page + 1, s.pageCount) + " · " + it.kind.name.lowercase(), it.text) } +
        list.map { SpellTarget(it.id, (if (it.page >= 0) ctx.getString(R.string.page_of, it.page + 1, s.pageCount) + " · " else "") + it.kind, it.text) }
    if (targets.isEmpty()) {
        LaunchedEffect(Unit) { NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_spell_nothing)); n.close() }
        return
    }
    DocumentSpellCheckDialog(targets, onApply = { changed ->
        n.close()
        val storedChanges = HashMap<String, String>()
        changed.forEach { t ->
            if (t.id.startsWith("p:")) {
                val id = t.id.removePrefix("p:").toLongOrNull()
                s.annots.firstOrNull { it.id == id }?.let { a -> s.replaceAnnot(a.copy(text = t.text)) }
            } else storedChanges[t.id] = t.text
        }
        if (storedChanges.isNotEmpty()) scope.launch {
            runCatching {
                s.applyInPlace(label = { ctx.getString(R.string.nv_spell_doc) }) { src, out, pw ->
                    withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { d -> DocTexts.apply(d, storedChanges); d.save(out) } }
                }
            }.onFailure { NveSelectionOps.toast(ctx, it.message ?: ctx.getString(R.string.nv_failed)) }
        }
    }, onDismiss = { n.close() })
}
