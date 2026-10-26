package com.smartdocs.editor.ui.edit5

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageLabelFormat
import com.smartdocs.editor.ui.reader.ReaderState

/**
 * Page labels of the open document (null when it has none), refreshed when the file is replaced.
 * For the reader page indicator and the go-to-page dialog (optional hooks, see MODULE_edit5_r5.md).
 */
@Composable
fun rememberPageLabels(state: ReaderState): List<String>? {
    val labels by produceState<List<String>?>(null, state.file, state.session) {
        val f = state.file ?: return@produceState
        value = runCatching {
            val (ranges, n) = PageBoxOps.readLabels(f, state.password)
            if (ranges.isEmpty()) null else PageLabelFormat.labels(ranges, n)
        }.getOrNull()
    }
    return labels
}

/** "iv (4 / 120)" when the page has a label different from its number, else "4 / 120". */
fun pageIndicator(labels: List<String>?, page: Int, count: Int): String {
    val l = labels?.getOrNull(page)
    return if (l != null && l != (page + 1).toString()) "$l (${page + 1} / $count)" else "${page + 1} / $count"
}

/** Zero-based page for typed text: a page label ("iv", "A-3") first, else a plain page number. */
fun resolvePageInput(labels: List<String>?, typed: String, count: Int): Int? =
    PageLabelFormat.find(labels ?: (1..count).map { it.toString() }, typed)
