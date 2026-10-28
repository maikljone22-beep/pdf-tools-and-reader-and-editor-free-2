package com.smartdocs.editor.ui.rpf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.rpf.RpfPrefs
import com.smartdocs.editor.core.rpf.RpfPrefs.K
import com.smartdocs.editor.ui.settings.SwitchRow

/**
 * reader-prefs rows of Settings → More preferences → Page display (Foxit §37): facing page count and gap, page size,
 * destination zoom, full-screen request and items, PDF/A view mode.
 */
@Composable
fun RpfDisplayPrefs() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { RpfPrefs.init(ctx) }
    Label(stringResource(R.string.rpf_facing_count))
    Chips(listOf(2 to "2", 3 to "3"), RpfPrefs.facingCount) { RpfPrefs.setInt(K.FACING_COUNT, it) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.rpf_page_gap, RpfPrefs.pageGapDp), style = MaterialTheme.typography.labelMedium)
        Slider(RpfPrefs.pageGapDp.toFloat(), { RpfPrefs.setInt(K.PAGE_GAP, it.toInt()) }, valueRange = 0f..32f, steps = 7)
    }
    SwitchRow(stringResource(R.string.rpf_show_page_size), RpfPrefs.showPageSize) { RpfPrefs.setBool(K.PAGE_SIZE, it) }
    SwitchRow(stringResource(R.string.rpf_keep_dest_zoom), RpfPrefs.keepZoomOnDestination, stringResource(R.string.rpf_keep_dest_zoom_sub)) { RpfPrefs.setBool(K.KEEP_DEST_ZOOM, it) }
    Label(stringResource(R.string.rpf_fs_request))
    Chips(listOf("ask" to stringResource(R.string.rpf_fs_req_ask), "allow" to stringResource(R.string.rpf_fs_req_allow), "never" to stringResource(R.string.rpf_fs_req_never)),
        RpfPrefs.fullScreenRequest) { RpfPrefs.setString(K.FS_REQUEST, it) }
    Label(stringResource(R.string.rpf_fs_items))
    SwitchRow(stringResource(R.string.rpf_fs_toolbar), RpfPrefs.fsToolbar) { RpfPrefs.setBool(K.FS_TOOLBAR, it) }
    SwitchRow(stringResource(R.string.rpf_fs_page_nav), RpfPrefs.fsPageNav) { RpfPrefs.setBool(K.FS_PAGE_NAV, it) }
    SwitchRow(stringResource(R.string.rpf_fs_tabs), RpfPrefs.fsTabs) { RpfPrefs.setBool(K.FS_TABS, it) }
    SwitchRow(stringResource(R.string.rpf_pdfa_view), RpfPrefs.pdfaViewMode, stringResource(R.string.rpf_pdfa_view_sub)) { RpfPrefs.setBool(K.PDFA_VIEW, it) }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l, maxLines = 1) }) }
    }
}
