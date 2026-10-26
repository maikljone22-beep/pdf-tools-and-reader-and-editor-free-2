package com.smartdocs.editor.ui.tools

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.TileColors
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.EmptyIllustration
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.LocalToolTile
import com.smartdocs.editor.ui.common.SectionTitle
import com.smartdocs.editor.ui.common.ToolCard

data class ToolDef(val id: String, val label: Int, val icon: ImageVector, val group: Int)

/** Every tool in the Tools tab. Ids map to [ToolScreens]. */
val allTools = listOf(
    ToolDef("scan", R.string.tool_scan, Icons.Default.DocumentScanner, 0),
    ToolDef("images_to_pdf", R.string.tool_images_to_pdf, Icons.Default.Image, 0),
    ToolDef("blank", R.string.tool_blank, Icons.Default.NoteAdd, 0),
    ToolDef("templates", R.string.ps_templates_title, Icons.Default.Dashboard, 0),
    ToolDef("ocr", R.string.tool_ocr, Icons.Default.TextFields, 0),
    ToolDef("convert", R.string.tool_convert, Icons.Default.SwapHoriz, 0),

    ToolDef("organize", R.string.tool_organize, Icons.Default.AutoAwesomeMotion, 1),
    ToolDef("merge", R.string.tool_merge, Icons.Default.CallMerge, 1),
    ToolDef("split", R.string.tool_split, Icons.Default.CallSplit, 1),
    ToolDef("extract", R.string.tool_extract_pages, Icons.Default.ContentCut, 1),
    ToolDef("rotate", R.string.tool_rotate, Icons.Default.RotateRight, 1),
    ToolDef("compress", R.string.tool_compress, Icons.Default.Compress, 1),
    ToolDef("page_numbers", R.string.tool_page_numbers, Icons.Default.FormatListNumbered, 1),
    ToolDef("watermark", R.string.tool_watermark, Icons.Default.BrandingWatermark, 1),
    ToolDef("doc_info", R.string.tool_doc_info, Icons.Default.Info, 1),
    ToolDef("attachments", R.string.tool_attachments, Icons.Default.AttachFile, 1),

    ToolDef("sign", R.string.tool_sign, Icons.Default.Draw, 2),
    ToolDef("digital_sign", R.string.sig_digital, Icons.Default.VerifiedUser, 2),
    ToolDef("forms", R.string.tool_forms, Icons.Default.Checklist, 2),
    ToolDef("form_builder", R.string.tool_form_builder, Icons.Default.AddBox, 2),
    ToolDef("protect", R.string.tool_protect, Icons.Default.Lock, 2),
    ToolDef("cert_protect", R.string.ps_cert_protect_title, Icons.Default.Key, 2),
    ToolDef("unlock", R.string.tool_unlock, Icons.Default.LockOpen, 2),
    ToolDef("redact", R.string.tool_redact, Icons.Default.Block, 2),
    ToolDef("sanitize", R.string.s5_sanitize_title, Icons.Default.CleaningServices, 2),
    ToolDef("protect_advanced", R.string.s5_protect_title, Icons.Default.EnhancedEncryption, 2),
    ToolDef("flatten", R.string.tool_flatten, Icons.Default.Layers, 2),

    ToolDef("pdf_to_images", R.string.tool_pdf_to_images, Icons.Default.Collections, 3),
    ToolDef("pdf_to_text", R.string.tool_pdf_to_text, Icons.Default.Subject, 3),
    ToolDef("search_all", R.string.s5_global_title, Icons.Default.Search, 3),
    ToolDef("search_highlight", R.string.s5_mark_title, Icons.Default.BorderColor, 3),
    ToolDef("find_replace", R.string.s5_replace_title, Icons.Default.FindReplace, 3),
    ToolDef("compare", R.string.tool_compare, Icons.Default.Compare, 3),
    ToolDef("scan_studio", R.string.t5_scan_title, Icons.Default.DocumentScanner, 0),
    ToolDef("to_pdf_plus", R.string.t5_topdf_title, Icons.Default.PictureAsPdf, 0),
    ToolDef("mail_merge", R.string.t5_mm_title, Icons.Default.MergeType, 0),
    ToolDef("portfolio", R.string.t5_portfolio_title, Icons.Default.FolderZip, 0),
    ToolDef("ocr_plus", R.string.t5_ocr_title, Icons.Default.DynamicFeed, 3),
    ToolDef("export_plus", R.string.t5_export_title, Icons.Default.Output, 3),
    ToolDef("optimize", R.string.t5_opt_title, Icons.Default.Compress, 3),
    ToolDef("pdfa", R.string.t5_pdfa_title, Icons.Default.Inventory, 3),
    ToolDef("accessibility", R.string.t5_acc_title, Icons.Default.Accessibility, 3),
    ToolDef("recipes", R.string.t5_recipes_title, Icons.Default.AutoMode, 3),
    ToolDef("batch", R.string.t5_batch_jobs, Icons.Default.Schedule, 3),
    ToolDef("ai", R.string.tool_ai, Icons.Default.AutoAwesome, 3),
    ToolDef("read_aloud", R.string.tool_read_aloud, Icons.Default.RecordVoiceOver, 3),
    ToolDef("podcast", R.string.ai2_podcast_title, Icons.Default.Podcasts, 3),
    ToolDef("ai_spaces", R.string.a5_spaces, Icons.Default.Workspaces, 3),
    ToolDef("print", R.string.tool_print, Icons.Default.Print, 3),
    ToolDef("e5_hub", R.string.e5_hub_title, Icons.Default.EditNote, 1),
    ToolDef("esign", R.string.cn_tool_esign, Icons.Default.HistoryEdu, 2),           // share6
    ToolDef("connected", R.string.cn_tool_connected, Icons.Default.Link, 2),         // share6
    ToolDef("office", R.string.o5_tool_open, Icons.Default.Description, 0),          // office5: Word / Excel / PowerPoint
)

private val groupTitles = listOf(R.string.ds_group_create, R.string.ds_group_organize, R.string.ds_group_sign, R.string.ds_group_read)
private val groupIcons = listOf(Icons.Default.AddCircleOutline, Icons.Default.Dashboard, Icons.Default.Shield, Icons.Default.AutoStories)

/** Category color of a tool group. */
@Composable
fun toolGroupTile(group: Int): TileColors = with(Brand.tiles) {
    when (group) { 0 -> rose; 1 -> blue; 2 -> teal; else -> purple }
}

/** Launch counts per tool id, stored in SharedPreferences "tool_usage". */
object ToolUsage {
    private const val PREFS = "tool_usage"
    fun record(ctx: Context, id: String) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, 0)
        p.edit().putInt(id, p.getInt(id, 0) + 1).apply()
    }
    fun top(ctx: Context, n: Int): List<String> =
        ctx.applicationContext.getSharedPreferences(PREFS, 0).all
            .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(n).map { it.first }
}

/** Default "most used" before the user has any history. */
private val defaultTop = listOf("scan", "merge", "compress", "sign", "ocr", "convert")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val g6Tools = com.smartdocs.editor.core.gaps6.AiGate.filter(com.smartdocs.editor.LocalSettings.current, allTools) { it.id }
    var query by rememberSaveable { mutableStateOf("") }
    var usageVersion by remember { mutableIntStateOf(0) }
    val top = remember(usageVersion) {
        val used = ToolUsage.top(ctx, 6)
        (used + defaultTop).distinct().mapNotNull { id -> g6Tools.firstOrNull { it.id == id } }.take(6)
    }
    val labels = g6Tools.associate { it.id to stringResource(it.label) }
    val filtered = remember(query, labels) {
        val q = query.trim()
        if (q.isEmpty()) g6Tools else g6Tools.filter { labels[it.id].orEmpty().contains(q, ignoreCase = true) || it.id.contains(q, true) }
    }
    fun open(t: ToolDef) { ToolUsage.record(ctx, t.id); usageVersion++; nav.navigate(Routes.tool(t.id)) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_tools), style = MaterialTheme.typography.headlineSmall) },
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surface),
                )
                TextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    placeholder = { Text(stringResource(R.string.ds_tools_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                    singleLine = true, shape = PillShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { pad ->
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp), Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (query.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "top_title") { SectionTitleCompact(stringResource(R.string.ds_most_used), Icons.Default.Bolt, Brand.tiles.amber) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "top_row") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(top, key = { "top_" + it.id }) { t -> MostUsedChip(t, toolGroupTile(t.group)) { open(t) } }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyIllustration(R.drawable.ds_empty_search, stringResource(R.string.ds_no_results)) }
            }
            groupTitles.forEachIndexed { gi, title ->
                val inGroup = filtered.filter { it.group == gi }
                if (inGroup.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "g$gi") { SectionTitleCompact(stringResource(title), groupIcons[gi], toolGroupTile(gi)) }
                    items(inGroup, key = { it.id }) { t ->
                        CompositionLocalProvider(LocalToolTile provides toolGroupTile(gi)) {
                            ToolCard(t.icon, labels[t.id].orEmpty(), modifier = Modifier.animateItem()) { open(t) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitleCompact(text: String, icon: ImageVector, tile: TileColors) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tile, size = 28.dp, iconSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MostUsedChip(t: ToolDef, tile: TileColors, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = PillShape, color = tile.container) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = PillShape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Icon(t.icon, null, Modifier.padding(6.dp).size(18.dp), tint = tile.content)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(t.label), style = MaterialTheme.typography.labelLarge, color = tile.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
