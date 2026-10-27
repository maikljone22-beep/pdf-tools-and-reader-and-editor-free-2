package com.smartdocs.editor.ui.edit5

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.DocJsConsole
import com.smartdocs.editor.core.edit5.E5Bookmark
import com.smartdocs.editor.core.edit5.NavAction
import com.smartdocs.editor.core.edit5.NavIo
import com.smartdocs.editor.core.edit5.OutlineNav
import com.smartdocs.editor.core.edit5.OutlineStore
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Reader-side navigation hooks (nav-bookmarks): add a real PDF bookmark from the text selection, the outline
 * panel's search / find current / expand-collapse all / wrap / text size, and running a bookmark's actions.
 * File changes go through ReaderState.applyInPlace and NavIo (an incremental update on signed files).
 */

/** "Add bookmark" from the selected text: the title is the text, the target is the top of the selection. */
@Composable
fun ReaderBookmarkFromSelection(state: ReaderState, onDismiss: () -> Unit) {
    val sel = state.selection ?: run { LaunchedEffect(Unit) { onDismiss() }; return }
    var title by remember(sel) { mutableStateOf(sel.text.replace(Regex("\\s+"), " ").trim().take(120)) }
    val ok = stringResource(R.string.nbm_bm_added)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nbm_bm_from_selection)) }, text = {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.e5_bm_name)) })
    }, confirmButton = {
        Button(enabled = title.isNotBlank(), onClick = {
            onDismiss()
            val top = sel.rects.maxOfOrNull { maxOf(it.top, it.bottom) }?.let { it + 4f }
            val bm = E5Bookmark(OutlineModel.newKey(), title.trim(), sel.page, 0, fit = DestFit.XYZ, top = top)
            state.selection = null
            state.scope.launch {
                runCatching {
                    state.applyInPlace(label = { state.context.getString(R.string.nbm_bm_from_selection) }) { src, out, pw ->
                        withContext(Dispatchers.IO) { NavIo.edit(src, out, pw) { doc, _ -> OutlineStore.write(doc, OutlineNav.insertForPage(OutlineStore.read(doc), sel.page, bm)) } }
                    }
                }.onSuccess { Toast.makeText(state.context, ok, Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(state.context, it.message ?: it.toString(), Toast.LENGTH_LONG).show() }
            }
        }) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Outline panel options kept per device: wrap long titles, text size step (-2..4). */
object OutlinePrefs {
    private fun p(c: Context) = c.getSharedPreferences("nbm_reader_outline", Context.MODE_PRIVATE)
    fun wrap(c: Context) = p(c).getBoolean("wrap", true)
    fun size(c: Context) = p(c).getInt("size", 0)
    fun setWrap(c: Context, v: Boolean) = p(c).edit().putBoolean("wrap", v).apply()
    fun setSize(c: Context, v: Int) = p(c).edit().putInt("size", v.coerceIn(-2, 4)).apply()
}

/**
 * Tool row of the reader's outline panel: search box, find current bookmark, expand / collapse all and a menu with
 * wrap and text size.
 */
@Composable
fun OutlineToolbar(query: String, onQuery: (String) -> Unit, onFindCurrent: () -> Unit, onExpandAll: () -> Unit, onCollapseAll: () -> Unit,
                   wrap: Boolean, onWrap: (Boolean) -> Unit, size: Int, onSize: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, onQuery, Modifier.weight(1f), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.nbm_clear)) } },
            placeholder = { Text(stringResource(R.string.nbm_search_bookmarks)) })
        IconButton(onClick = onFindCurrent) { Icon(Icons.Default.MyLocation, stringResource(R.string.nbm_find_current)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.nbm_more)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_expand_all)) }, leadingIcon = { Icon(Icons.Default.UnfoldMore, null) }, onClick = { menu = false; onExpandAll() })
                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_collapse_all)) }, leadingIcon = { Icon(Icons.Default.UnfoldLess, null) }, onClick = { menu = false; onCollapseAll() })
                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_wrap)) }, leadingIcon = { Checkbox(wrap, null) }, onClick = { onWrap(!wrap) })
                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_text_bigger)) }, leadingIcon = { Icon(Icons.Default.ZoomIn, null) }, enabled = size < 4, onClick = { onSize(size + 1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_text_smaller)) }, leadingIcon = { Icon(Icons.Default.ZoomOut, null) }, enabled = size > -2, onClick = { onSize(size - 1) })
            }
        }
    }
}

/**
 * Run the actions of outline item [index] (pre-order) of the open file, for bookmarks without a page target:
 * go to, page navigation, web links (through the Internet access gate), JavaScript (only when allowed).
 */
fun runOutlineActions(state: ReaderState, index: Int, onJump: (Int) -> Unit) {
    val f = state.file ?: return
    state.scope.launch {
        val b = runCatching { OutlineStore.load(f, state.password).first.getOrNull(index) }.getOrNull() ?: return@launch
        val ctx = state.context
        if (b.actions.isEmpty()) { if (b.page >= 0) onJump(b.page); return@launch }
        for (a in b.actions) when (a) {
            is NavAction.GoTo -> if (a.page >= 0) onJump(a.page)
            is NavAction.Named -> when (a.name) {
                "NextPage" -> onJump(state.currentPage + 1); "PrevPage" -> onJump((state.currentPage - 1).coerceAtLeast(0))
                "FirstPage" -> onJump(0); "LastPage" -> onJump(state.pageCount - 1)
                else -> Toast.makeText(ctx, ctx.getString(R.string.nbm_named_unsupported, a.name), Toast.LENGTH_SHORT).show()
            }
            is NavAction.Uri -> com.smartdocs.editor.ui.security.DocSecurityGate.web(ctx, a.uri) { com.smartdocs.editor.ui.reader.ReaderIntents.openUri(ctx, a.uri) }
            is NavAction.Js -> runCatching { DocJsConsole.run(f, state.password, a.script, 500) }
                .onSuccess { r -> (r.alerts + r.log).firstOrNull()?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
                .onFailure { if (it is DocJsConsole.Disabled) Toast.makeText(ctx, ctx.getString(R.string.nbm_js_console_off), Toast.LENGTH_SHORT).show() }
            is NavAction.GoToR, is NavAction.Launch, is NavAction.Kept -> Toast.makeText(ctx, ctx.getString(R.string.nbm_action_not_run, actionLabelPlain(ctx, a)), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun actionLabelPlain(ctx: Context, a: NavAction): String = when (a) {
    is NavAction.GoToR -> java.io.File(a.file).name
    is NavAction.Launch -> java.io.File(a.file).name
    is NavAction.Kept -> a.type
    else -> ctx.getString(R.string.nbm_action_list)
}
