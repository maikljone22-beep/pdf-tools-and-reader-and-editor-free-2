package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DecorType
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ToolCard

/** One edit5 tool: route id, label, icon and Tools-tab group (0 create, 1 organize, 2 sign/protect, 3 read). */
data class E5Tool(val id: String, val label: Int, val icon: ImageVector, val group: Int, val subtitle: Int? = null)

val EDIT5_TOOLS: List<E5Tool> = listOf(
    E5Tool("e5_links", R.string.e5_links_title, Icons.Default.Link, 1, R.string.e5_links_sub),
    E5Tool("e5_bookmarks", R.string.e5_bm_title, Icons.Default.Bookmarks, 1, R.string.e5_bm_sub),
    // nav-bookmarks: Destinations panel, Cross-Reference panel, document JavaScript (scripts, document actions, console)
    E5Tool("e5_destinations", R.string.nbm_dest_title, Icons.Default.Place, 1, R.string.nbm_dest_sub),
    E5Tool("e5_xref", R.string.nbm_xref_title, Icons.Default.Shortcut, 1, R.string.nbm_xref_sub),
    E5Tool("e5_doc_js", R.string.nbm_js_title, Icons.Default.Code, 1, R.string.nbm_js_sub),
    E5Tool("e5_header_footer", R.string.e5_hf_title, Icons.Default.ViewHeadline, 1, R.string.e5_hf_sub),
    E5Tool("e5_bates", R.string.e5_bates_title, Icons.Default.Numbers, 1, R.string.e5_bates_sub),
    E5Tool("e5_watermark", R.string.e5_wm_title, Icons.Default.BrandingWatermark, 1, R.string.e5_wm_sub),
    E5Tool("e5_background", R.string.e5_bg_title, Icons.Default.FormatColorFill, 1, R.string.e5_bg_sub),
    E5Tool("e5_security_wm", R.string.e5_sec_title, Icons.Default.Security, 2, R.string.e5_sec_sub),
    E5Tool("e5_page_labels", R.string.e5_labels_title, Icons.Default.Label, 1, R.string.e5_labels_sub),
    E5Tool("e5_crop", R.string.e5_crop_title, Icons.Default.Crop, 1, R.string.e5_crop_sub),
    E5Tool("e5_resize", R.string.e5_resize_title, Icons.Default.AspectRatio, 1, R.string.e5_resize_sub),
    E5Tool("e5_impose", R.string.e5_impose_title, Icons.Default.ViewModule, 1, R.string.e5_impose_sub),
    E5Tool("e5_page_ops", R.string.e5_pageops_title, Icons.Default.SwapVert, 1, R.string.e5_pageops_sub),
    E5Tool("e5_replace_pages", R.string.e5_op_replace, Icons.Default.FindReplace, 1),
    E5Tool("e5_split_page", R.string.e5_op_split_page, Icons.Default.VerticalSplit, 1),
    E5Tool("e5_smart_split", R.string.e5_op_smart_split, Icons.Default.CallSplit, 1),
    E5Tool("e5_interleave", R.string.e5_op_interleave, Icons.Default.Shuffle, 1),
    E5Tool("e5_copy_pages", R.string.e5_op_copy_pages, Icons.Default.FileCopy, 1),
    E5Tool("e5_templates", R.string.e5_tpl_title, Icons.Default.Dashboard, 0, R.string.e5_tpl_sub),
    E5Tool("e5_insert_blank", R.string.e5_blank_title, Icons.Default.NoteAdd, 0, R.string.e5_blank_sub),
    E5Tool("e5_doc_props", R.string.e5_props_title, Icons.Default.Info, 1, R.string.e5_props_sub),
)

val EDIT5_TOOL_IDS: Set<String> = EDIT5_TOOLS.map { it.id }.toSet() + "e5_hub"

/** Dispatcher for every edit5 route id (call from ToolScreens for ids in [EDIT5_TOOL_IDS]). */
@Composable
fun Edit5Screens(nav: NavHostController, id: String, uri: String?) {
    when (id) {
        "e5_hub" -> Edit5HubScreen(nav, uri)
        "e5_links" -> LinksScreen(nav, uri)
        "e5_bookmarks" -> BookmarksScreen(nav, uri)
        "e5_destinations" -> DestinationsScreen(nav, uri)
        "e5_xref" -> CrossRefScreen(nav, uri)
        "e5_doc_js" -> DocScriptsScreen(nav, uri)
        "e5_header_footer" -> HeaderFooterScreen(nav, uri)
        "e5_bates" -> BatesScreen(nav, uri)
        "e5_watermark" -> DecorScreen(nav, uri, DecorType.WATERMARK)
        "e5_background" -> DecorScreen(nav, uri, DecorType.BACKGROUND)
        "e5_security_wm" -> DecorScreen(nav, uri, DecorType.SECURITY)
        "e5_page_labels" -> PageLabelsScreen(nav, uri)
        "e5_crop" -> CropScreen(nav, uri)
        "e5_resize" -> ResizeScreen(nav, uri)
        "e5_impose" -> ImposeScreen(nav, uri)
        "e5_page_ops" -> PageOpsScreen(nav, uri, PageOp.REVERSE)
        "e5_replace_pages" -> PageOpsScreen(nav, uri, PageOp.REPLACE)
        "e5_split_page" -> PageOpsScreen(nav, uri, PageOp.SPLIT_PAGE)
        "e5_smart_split" -> PageOpsScreen(nav, uri, PageOp.SMART_SPLIT)
        "e5_interleave" -> PageOpsScreen(nav, uri, PageOp.INTERLEAVE)
        "e5_copy_pages" -> PageOpsScreen(nav, uri, PageOp.COPY_PAGES)
        "e5_templates" -> TemplatesScreen(nav, uri)
        "e5_insert_blank" -> InsertBlankScreen(nav, uri)
        "e5_doc_props" -> DocPropsScreen(nav, uri)
        else -> Edit5HubScreen(nav, uri)
    }
}

/** All edit5 tools as cards (tablet: more columns). Keeps the document [uri] when opening a tool. */
@Composable
fun Edit5HubScreen(nav: NavHostController, uri: String?) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.e5_hub_title), onBack = { nav.popBackStack() }) }) { pad ->
        LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.e5_hub_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(EDIT5_TOOLS, key = { it.id }) { t ->
                ToolCard(t.icon, stringResource(t.label), t.subtitle?.let { stringResource(it) }) { nav.navigate(Routes.tool(t.id, uri)) }
            }
        }
    }
}
