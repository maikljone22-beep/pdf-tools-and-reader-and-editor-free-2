package com.smartdocs.editor.ui.nve.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R

/** Label of a reader tab id (the ids of [com.smartdocs.editor.core.nve.shell.ToolbarModel.DEFAULT_TABS]). */
@Composable
fun nveTabLabel(id: String): String {
    val ctx = LocalContext.current
    return when (id) {
        "VIEW" -> stringResource(R.string.tab_view); "COMMENT" -> stringResource(R.string.tab_comment)
        "CUSTOM" -> NveShellPrefs.customTabName(ctx) ?: stringResource(R.string.an_custom_tab)
        "EDIT" -> stringResource(R.string.tab_edit); "FILL_SIGN" -> stringResource(R.string.tab_fill_sign); "SIGN" -> stringResource(R.string.tab_sign)
        "TOOLS" -> stringResource(R.string.tab_tools); "PAGES" -> stringResource(R.string.tab_pages)
        else -> id
    }
}

@Composable
private fun chipLabel(id: String): String = stringResource(when (id) {
    "view_mode" -> R.string.rd_view_mode; "fit" -> R.string.fit_page; "night" -> R.string.night_mode; "page_color" -> R.string.page_color
    "reflow" -> R.string.reflow; "auto_flip" -> R.string.auto_flip; "rotate" -> R.string.rotate_view; "crop" -> R.string.crop_mode
    "magnifier" -> R.string.vw_magnifier; "search" -> R.string.search; "panels" -> R.string.rd_panels; "bookmarks" -> R.string.panel_bookmarks
    "thumbnails" -> R.string.panel_thumbnails; "read_aloud" -> R.string.tool_read_aloud; "ai" -> R.string.tool_ai; "share" -> R.string.share
    "print" -> R.string.print; "attachments" -> R.string.panel_attachments
    // tab-view-core V-26
    "v5_tell_me" -> R.string.v5_tell_me
    "v5_split" -> R.string.v5_split_view
    "v5_compare" -> R.string.v5_compare
    "v5_present" -> R.string.v5_presentation
    "v5_auto_scroll" -> R.string.v5_auto_scroll
    "v5_rulers" -> R.string.v5_rulers
    "v5_grid" -> R.string.v5_grid
    "v5_reading_ruler" -> R.string.v5_reading_ruler
    "v5_overlay" -> R.string.v5_overlay_settings
    "v5_tts_options" -> R.string.v5_read_aloud_options
    "nv_zoom" -> R.string.nv_zoom
    "nv_marquee" -> R.string.nv_marquee
    "nv_fit_height" -> R.string.nv_fit_height
    "nv_actual" -> R.string.nv_actual_size
    "nv_cont_facing" -> R.string.nv_continuous_facing
    "nv_reverse" -> R.string.nv_reverse_view
    "nv_word_count" -> R.string.nv_word_count
    "nv_text_viewer" -> R.string.nv_text_viewer
    "nv_quad" -> R.string.nv_quad_split
    "nv_transitions" -> R.string.nv_transitions
    "nv_page_snap" -> R.string.nv_page_snapshot
    "nv_area_snap" -> R.string.nv_area_snapshot
    "nv_paste" -> R.string.nv_paste
    "nv_select_all" -> R.string.nv_select_all
    "nv_signatures" -> R.string.nv_pane_signatures
    "nv_spell" -> R.string.nv_spell_doc
    else -> R.string.tvc_info_panel
})

/** SHL-3: show / hide and reorder the reader tabs, hide View-tab chips (prefs "nve_shell", read by the reader). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveToolbarCustomizeSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val order = remember { mutableStateListOf<String>().apply { addAll(NveToolbarPrefs.tabOrder(ctx)) } }
    val hidden = remember { mutableStateListOf<String>().apply { addAll(NveToolbarPrefs.hiddenTabs(ctx)) } }
    val hiddenChips = remember { mutableStateListOf<String>().apply { addAll(NveToolbarPrefs.hiddenChips(ctx)) } }
    fun save() = NveToolbarPrefs.save(ctx, order.toList(), hidden.toSet(), hiddenChips.toSet())
    ModalBottomSheet(onDismissRequest = { save(); onDismiss() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nl_customize_toolbar), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = {
                        order.clear(); order.addAll(com.smartdocs.editor.core.nve.shell.ToolbarModel.DEFAULT_TABS); hidden.clear(); hiddenChips.clear(); save()
                    }) { Text(stringResource(R.string.nl_reset)) }
                }
                Text(stringResource(R.string.nl_tabs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
            }
            itemsIndexed(order.toList(), key = { _, id -> "t_$id" }) { i, id ->
                ListItem(
                    headlineContent = { Text(nveTabLabel(id)) },
                    leadingContent = {
                        Checkbox(id !in hidden, { on -> if (on) hidden.remove(id) else if (order.size - hidden.size > 1) hidden.add(id); save() })
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { if (i > 0) { order.add(i - 1, order.removeAt(i)); save() } }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.nl_move_up)) }
                            IconButton(onClick = { if (i < order.lastIndex) { order.add(i + 1, order.removeAt(i)); save() } }, enabled = i < order.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.nl_move_down)) }
                        }
                    },
                )
            }
            item { Text(stringResource(R.string.nl_view_chips), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp)) }
            items(NveToolbarPrefs.VIEW_CHIPS, key = { "c_$it" }) { id ->
                ListItem(headlineContent = { Text(chipLabel(id)) },
                    leadingContent = { Checkbox(id !in hiddenChips, { on -> if (on) hiddenChips.remove(id) else hiddenChips.add(id); save() }) })
            }
            item { NveUserTabsEditor() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
