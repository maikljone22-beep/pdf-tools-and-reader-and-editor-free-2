package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.DocProps
import com.smartdocs.editor.core.edit5.DocPropsOps
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import java.text.DateFormat
import java.util.Calendar

/** Full document properties: every /Info field, custom properties, initial view, reading direction, language. */
@Composable
fun DocPropsScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var p by remember { mutableStateOf<DocProps?>(null) }
    var syncXmp by remember { mutableStateOf(true) }
    var resetCreated by remember { mutableStateOf(false) }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work) { val w = doc.work ?: return@LaunchedEffect; p = runCatching { DocPropsOps.read(w, doc.password) }.getOrNull() }
    ToolScaffold(stringResource(R.string.e5_props_title), nav, runner) {
        E5FileRow(doc)
        val cur = p ?: return@ToolScaffold
        fun set(f: (DocProps) -> DocProps) { p = f(cur) }
        E5TwoPane(options = {
            E5Section(stringResource(R.string.e5_props_desc)) {
                Field(cur.title, R.string.e5_p_title) { v -> set { it.copy(title = v) } }
                Field(cur.author, R.string.e5_p_author) { v -> set { it.copy(author = v) } }
                Field(cur.subject, R.string.e5_p_subject) { v -> set { it.copy(subject = v) } }
                Field(cur.keywords, R.string.e5_p_keywords) { v -> set { it.copy(keywords = v) } }
                Field(cur.creator, R.string.e5_p_creator) { v -> set { it.copy(creator = v) } }
                Field(cur.producer, R.string.e5_p_producer) { v -> set { it.copy(producer = v) } }
                Text(stringResource(R.string.e5_p_trapped))
                ChoiceChips(listOf("", "True", "False", "Unknown"), cur.trapped, { if (it.isEmpty()) stringResource(R.string.e5_default) else it }) { v -> set { it.copy(trapped = v) } }
                val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                Text(stringResource(R.string.e5_p_created, cur.created?.let { df.format(it.time) } ?: "—"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.e5_p_modified, cur.modified?.let { df.format(it.time) } ?: "—"), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.e5_p_reset_created), resetCreated) { resetCreated = it }
            }
            E5Section(stringResource(R.string.e5_props_custom)) {
                cur.custom.forEachIndexed { i, (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(k, { nk -> set { it.copy(custom = it.custom.toMutableList().also { l -> l[i] = nk to v }) } }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.e5_p_key)) })
                        OutlinedTextField(v, { nv -> set { it.copy(custom = it.custom.toMutableList().also { l -> l[i] = k to nv }) } }, Modifier.weight(1.4f), singleLine = true, label = { Text(stringResource(R.string.e5_p_value)) })
                        IconButton(onClick = { set { it.copy(custom = it.custom.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                    }
                }
                OutlinedButton(onClick = { set { it.copy(custom = it.custom + ("" to "")) } }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.e5_p_add_custom)) }
            }
        }, preview = {
            val v = cur.view
            fun setV(f: (com.smartdocs.editor.core.edit5.InitialView) -> com.smartdocs.editor.core.edit5.InitialView) = set { it.copy(view = f(it.view)) }
            E5Section(stringResource(R.string.e5_props_view)) {
                FloatField((v.openPage + 1).toFloat(), stringResource(R.string.e5_p_open_page, cur.pages)) { x -> setV { it.copy(openPage = (x.toInt() - 1).coerceIn(0, (cur.pages - 1).coerceAtLeast(0))) } }
                ChoiceChips(DestFit.values().toList(), v.openFit, { stringResource(fitLabel(it)) }) { f -> setV { it.copy(openFit = f) } }
                if (v.openFit == DestFit.XYZ) FloatField(v.openZoom * 100f, stringResource(R.string.e5_link_zoom)) { z -> setV { it.copy(openZoom = z / 100f) } }
                Text(stringResource(R.string.e5_p_layout))
                ChoiceChips(listOf("", "SinglePage", "OneColumn", "TwoColumnLeft", "TwoColumnRight", "TwoPageLeft", "TwoPageRight"), v.pageLayout, { layoutLabel(it) }) { s -> setV { it.copy(pageLayout = s) } }
                Text(stringResource(R.string.e5_p_mode))
                ChoiceChips(listOf("", "UseNone", "UseOutlines", "UseThumbs", "FullScreen", "UseOC", "UseAttachments"), v.pageMode, { modeLabel(it) }) { s -> setV { it.copy(pageMode = s) } }
                Text(stringResource(R.string.e5_p_direction))
                ChoiceChips(listOf("", "R2L", "L2R"), v.direction, { stringResource(when (it) { "R2L" -> R.string.e5_dir_r2l; "L2R" -> R.string.e5_dir_l2r; else -> R.string.e5_default }) }) { s -> setV { it.copy(direction = s) } }
                OutlinedTextField(v.language, { s -> setV { it.copy(language = s) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_p_language)) }, placeholder = { Text("ar-SA") })
            }
            E5Section(stringResource(R.string.e5_props_window)) {
                SwitchRow(stringResource(R.string.e5_p_doc_title), v.displayDocTitle) { b -> setV { it.copy(displayDocTitle = b) } }
                SwitchRow(stringResource(R.string.e5_p_fit_window), v.fitWindow) { b -> setV { it.copy(fitWindow = b) } }
                SwitchRow(stringResource(R.string.e5_p_center_window), v.centerWindow) { b -> setV { it.copy(centerWindow = b) } }
                SwitchRow(stringResource(R.string.e5_p_hide_toolbar), v.hideToolbar) { b -> setV { it.copy(hideToolbar = b) } }
                SwitchRow(stringResource(R.string.e5_p_hide_menubar), v.hideMenubar) { b -> setV { it.copy(hideMenubar = b) } }
                SwitchRow(stringResource(R.string.e5_p_hide_ui), v.hideWindowUI) { b -> setV { it.copy(hideWindowUI = b) } }
                Text(stringResource(R.string.e5_p_print_scaling))
                ChoiceChips(listOf("", "None", "AppDefault"), v.printScaling, { if (it.isEmpty()) stringResource(R.string.e5_default) else it }) { s -> setV { it.copy(printScaling = s) } }
                Text(stringResource(R.string.e5_p_duplex))
                ChoiceChips(listOf("", "Simplex", "DuplexFlipShortEdge", "DuplexFlipLongEdge"), v.duplex, { if (it.isEmpty()) stringResource(R.string.e5_default) else it }) { s -> setV { it.copy(duplex = s) } }
            }
            E5Section(stringResource(R.string.e5_props_facts)) {
                Text(stringResource(R.string.e5_p_facts, cur.version, cur.pages, yesNo(cur.encrypted), yesNo(cur.tagged), yesNo(cur.hasXmp)), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.e5_p_sync_xmp), syncXmp) { syncXmp = it }
            }
        })
        PrimaryButton(stringResource(R.string.save), icon = Icons.Default.Save) {
            val np = if (resetCreated) cur.copy(created = Calendar.getInstance()) else cur
            val sx = syncXmp
            runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, o, pw -> DocPropsOps.write(s, o, pw, np, sx) }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "props")
            }
        }
    }
}

@Composable
private fun Field(value: String, label: Int, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = label != R.string.e5_p_keywords && label != R.string.e5_p_subject, label = { Text(stringResource(label)) })

@Composable
private fun yesNo(b: Boolean) = stringResource(if (b) R.string.e5_yes else R.string.e5_no)

@Composable
private fun layoutLabel(s: String) = stringResource(when (s) {
    "SinglePage" -> R.string.e5_pl_single; "OneColumn" -> R.string.e5_pl_column; "TwoColumnLeft" -> R.string.e5_pl_two_col_l; "TwoColumnRight" -> R.string.e5_pl_two_col_r
    "TwoPageLeft" -> R.string.e5_pl_two_page_l; "TwoPageRight" -> R.string.e5_pl_two_page_r; else -> R.string.e5_default
})

@Composable
private fun modeLabel(s: String) = stringResource(when (s) {
    "UseNone" -> R.string.e5_pm_none; "UseOutlines" -> R.string.e5_pm_outlines; "UseThumbs" -> R.string.e5_pm_thumbs; "FullScreen" -> R.string.e5_pm_full
    "UseOC" -> R.string.e5_pm_layers; "UseAttachments" -> R.string.e5_pm_attachments; else -> R.string.e5_default
})
