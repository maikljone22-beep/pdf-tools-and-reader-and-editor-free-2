package com.smartdocs.editor.ui.nve.shell

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import kotlinx.coroutines.launch

/** Settings cards that can be scrolled to, and the settings each one holds (string resources searched by title). */
private val INDEX: List<Pair<String, List<Int>>> = listOf(
    "general" to listOf(R.string.ds_set_general, R.string.settings_autosave, R.string.settings_appearance, R.string.settings_language, R.string.nl_accent,
        R.string.nl_recovery, R.string.nl_customize_toolbar, R.string.nl_rename_custom_tab, R.string.nl_recent_size, R.string.nl_default_save_folder, R.string.nl_trust_title),
    "view" to listOf(R.string.ds_set_pdf_view, R.string.settings_restore_session, R.string.settings_highlight_fields, R.string.settings_highlight_links,
        R.string.settings_keep_zoom, R.string.keep_screen_on, R.string.settings_volume_keys, R.string.settings_tap_edges, R.string.view_continuous, R.string.night_mode),
    "annotations" to listOf(R.string.ds_set_annotations, R.string.settings_author, R.string.settings_pressure),
    "tts" to listOf(R.string.settings_tts, R.string.tts_speed),
    "ai" to listOf(R.string.settings_ai, R.string.ai_endpoint, R.string.ai_model, R.string.ai_api_key),
    "ocr" to listOf(R.string.tool_ocr, R.string.ocr_language),
    "notifications" to listOf(R.string.ds_set_notifications, R.string.ds_set_notifications_sub),
    "about" to listOf(R.string.settings_about, R.string.v5_help_center, R.string.v5_trash, R.string.v5_whats_new, R.string.settings_help),
)

/** Records the position of a settings card (inside the scrolled column) so the search can scroll to it. */
fun Modifier.nveSettingsAnchor(anchors: SnapshotStateMap<String, Float>, key: String): Modifier =
    this.onGloballyPositioned { anchors[key] = it.positionInParent().y }

/** "Search settings" box at the top of Settings: matching settings, tap = scroll to its card. */
@Composable
fun NveSettingsSearch(scroll: ScrollState, anchors: SnapshotStateMap<String, Float>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val hits = remember(q) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) emptyList() else INDEX.flatMap { (card, ids) -> ids.map { card to ctx.getString(it) } }.filter { it.second.lowercase().contains(t) }.distinctBy { it.second }.take(12)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.nl_settings_search)) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } })
        hits.forEach { (card, label) ->
            ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable {
                q = ""; anchors[card]?.let { y -> scope.launch { scroll.animateScrollTo(y.toInt()) } }
            })
        }
        if (q.isNotBlank() && hits.isEmpty()) Text(stringResource(R.string.nl_settings_none), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
    }
}
