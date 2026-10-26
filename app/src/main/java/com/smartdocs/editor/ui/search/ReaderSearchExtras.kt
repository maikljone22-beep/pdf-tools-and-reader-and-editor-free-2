package com.smartdocs.editor.ui.search

import android.content.Context
import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.redact.RedactMark
import com.smartdocs.editor.core.redact.RedactMarks
import com.smartdocs.editor.core.redact.RedactPrefs
import com.smartdocs.editor.core.search.FindReplace
import com.smartdocs.editor.core.search.ReplaceOutcome
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.ui.quick.QuickController
import com.smartdocs.editor.ui.quick.QuickInfo
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.TextSelection

/**
 * Second row of the reader's search bar: «استبدال بـ» + «استبدال» (current hit) / «استبدال الكل». Runs
 * [FindReplace] on the open document through [QuickController.apply] (pending annotations are saved first, the result
 * replaces the document in place at the same page, with the usual undo bar), then searches again.
 */
@Composable
fun ReaderReplaceRow(state: ReaderState, quick: QuickController) {
    val ctx = LocalContext.current
    var replacement by rememberSaveable { mutableStateOf("") }
    val canRun = state.searchQuery.isNotBlank() && !state.searching && !quick.running
    Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), singleLine = true,
            placeholder = { Text(stringResource(R.string.s5_rr_with)) }, shape = RoundedCornerShape(24.dp))
        TextButton(onClick = { ReaderReplace.run(ctx, state, quick, replacement, all = false) }, enabled = canRun && state.searchIndex >= 0) {
            Text(stringResource(R.string.s5_rr_one), maxLines = 1)
        }
        TextButton(onClick = { ReaderReplace.run(ctx, state, quick, replacement, all = true) }, enabled = canRun) {
            Text(stringResource(R.string.s5_rr_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Toggle shown in the search bar row (find ⇄ find & replace). */
@Composable
fun ReaderReplaceToggle(on: Boolean, onToggle: () -> Unit) {
    IconToggleButton(on, { onToggle() }) {
        Icon(Icons.Default.FindReplace, stringResource(R.string.s5_rr_toggle), tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

object ReaderReplace {
    fun run(ctx: Context, state: ReaderState, quick: QuickController, replacement: String, all: Boolean) {
        val query = state.searchQuery
        if (query.isBlank()) return
        val options = SearchOptions(norm = SearchPrefs.load(ctx, state.matchCase), wholeWord = state.wholeWords)
        val idx = state.searchIndex
        val hit = state.searchHits.getOrNull(idx)
        if (!all && (hit == null || hit.rects.isEmpty())) return
        val before = if (hit == null) emptyList() else state.searchHits.take(idx).filter { it.page == hit.page && it.rects.isNotEmpty() }.map { RectF(it.rects.first()) }
        var outcome: ReplaceOutcome? = null
        quick.apply(ctx.getString(R.string.s5_rr_working), { ctx.getString(R.string.s5_rr_done, outcome?.count ?: 0) }, "replaced",
            after = {
                val o = outcome
                quick.message = ctx.getString(R.string.s5_rr_done, o?.count ?: 0) +
                    (if (o?.fontWarning == true) "\n" + ctx.getString(R.string.s5_rr_font_warn) else "") +
                    (if (o?.lossy == true) "\n" + ctx.getString(R.string.s5_rr_lossy) else "")
                state.runSearch(query)
            }) { src, out, pw ->
            val o = if (all) FindReplace.replaceAllIn(ctx, src, out, pw, query, options, replacement, ctx.cacheDir)
                    else FindReplace.replaceHit(ctx, src, out, pw, hit!!.page, RectF(hit.rects.first()), before, query, options, replacement)
            if (o.count == 0) { out.delete(); throw QuickInfo(ctx.getString(R.string.s5_rr_none)) }
            outcome = o
        }
    }
}

/**
 * Redaction marks from the reader: the selection («تنقيح») or the whole current page («تنقيح الصفحة») become
 * standard `/Redact` annotations written into the document at once (through [QuickController.apply], so pending
 * annotations are saved first and the change can be undone). They render like other annotations; «تطبيق التنقيح»
 * opens the redaction tool, which loads them for review and true removal.
 */
object ReaderRedact {
    private fun id() = "rd-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"

    fun markSelection(ctx: Context, state: ReaderState, quick: QuickController, sel: TextSelection) {
        if (sel.rects.isEmpty()) return
        state.selection = null
        add(ctx, state, quick, RedactMark(id(), sel.page, sel.rects.map { PageGeometry.normPdf(it) }, RedactPrefs.lastStyle(ctx), sel.text.trim().take(80)))
    }

    fun markPage(ctx: Context, state: ReaderState, quick: QuickController) {
        if (state.pageCount == 0) return
        add(ctx, state, quick, RedactMark(id(), state.currentPage, emptyList(), RedactPrefs.lastStyle(ctx), "", wholePage = true))
    }

    private fun add(ctx: Context, state: ReaderState, quick: QuickController, m: RedactMark) {
        val author = state.authorName
        quick.apply(ctx.getString(R.string.s5_rd_working), { ctx.getString(R.string.s5_rd_marked, m.page + 1) }, "marked") { src, out, pw ->
            RedactMarks.save(src, out, pw, RedactMarks.load(src, pw) + m, author)
        }
    }

    fun count(state: ReaderState) = state.existing.count { it.subtype == "Redact" }
}

/** Banner over the page while the document holds `/Redact` marks: «تطبيق التنقيح» opens the redaction tool. */
@Composable
fun RedactMarksBanner(state: ReaderState, onApply: () -> Unit, modifier: Modifier = Modifier) {
    val n = ReaderRedact.count(state)
    if (n == 0) return
    Surface(modifier.padding(8.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Block, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.s5_rd_banner, n), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onApply) { Text(stringResource(R.string.s5_rd_apply)) }
        }
    }
}
