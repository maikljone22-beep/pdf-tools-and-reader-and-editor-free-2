package com.smartdocs.editor.ui.reader.annot

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.ToolChip
import kotlin.math.roundToInt

/** A tool that can be placed in a toolbar: icon, label and the group it belongs to in the picker. */
data class ToolEntry(val tool: ReaderTool, val icon: ImageVector, val label: Int, val group: Int)

/** Every annotation tool of the reader, grouped like the reference (comment / drawing / edit / fill & sign). */
object ToolCatalog {
    val all: List<ToolEntry> = listOf(
        ToolEntry(ReaderTool.NOTE, Icons.Default.StickyNote2, R.string.annot_note, R.string.an_group_comment),
        ToolEntry(ReaderTool.ATTACH, Icons.Default.AttachFile, R.string.annot_attachment, R.string.an_group_comment),
        ToolEntry(ReaderTool.STAMP, Icons.Default.Approval, R.string.annot_stamp, R.string.an_group_comment),
        ToolEntry(ReaderTool.FREETEXT, Icons.Default.TextFields, R.string.annot_text, R.string.an_group_comment),
        ToolEntry(ReaderTool.HIGHLIGHT, Icons.Default.Highlight, R.string.annot_highlight, R.string.an_group_comment),
        ToolEntry(ReaderTool.UNDERLINE, Icons.Default.FormatUnderlined, R.string.annot_underline, R.string.an_group_comment),
        ToolEntry(ReaderTool.SQUIGGLY, Icons.Default.Waves, R.string.annot_squiggly, R.string.an_group_comment),
        ToolEntry(ReaderTool.STRIKEOUT, Icons.Default.FormatStrikethrough, R.string.annot_strikeout, R.string.an_group_comment),
        ToolEntry(ReaderTool.REPLACE, Icons.Default.FindReplace, R.string.an_replace, R.string.an_group_comment),
        ToolEntry(ReaderTool.CARET, Icons.Default.KeyboardCapslock, R.string.an_caret, R.string.an_group_comment),
        ToolEntry(ReaderTool.MEASURE, Icons.Default.Straighten, R.string.annot_measure, R.string.an_group_comment),
        ToolEntry(ReaderTool.SOUND, Icons.Default.Mic, R.string.annot_audio, R.string.an_group_comment),
        ToolEntry(ReaderTool.VIDEO, Icons.Default.Movie, R.string.an_video, R.string.an_group_comment),
        ToolEntry(ReaderTool.SNAPSHOT, Icons.Default.Screenshot, R.string.an_snapshot, R.string.an_group_comment),
        ToolEntry(ReaderTool.SELECT, Icons.Default.HighlightAlt, R.string.an_select, R.string.an_group_comment),
        ToolEntry(ReaderTool.TICKMARK, Icons.Default.LooksOne, R.string.cx_tickmark, R.string.an_group_comment),
        ToolEntry(ReaderTool.INK, Icons.Default.Draw, R.string.annot_ink, R.string.an_group_drawing),
        ToolEntry(ReaderTool.ERASER, Icons.Default.CleaningServices, R.string.annot_eraser, R.string.an_group_drawing),
        ToolEntry(ReaderTool.RECT, Icons.Default.CropSquare, R.string.annot_rect, R.string.an_group_drawing),
        ToolEntry(ReaderTool.OVAL, Icons.Default.Circle, R.string.annot_oval, R.string.an_group_drawing),
        ToolEntry(ReaderTool.LINE, Icons.Default.HorizontalRule, R.string.annot_line, R.string.an_group_drawing),
        ToolEntry(ReaderTool.ARROW, Icons.Default.ArrowOutward, R.string.annot_arrow, R.string.an_group_drawing),
        ToolEntry(ReaderTool.POLYLINE, Icons.Default.Timeline, R.string.an_polyline, R.string.an_group_drawing),
        ToolEntry(ReaderTool.POLYGON, Icons.Default.Pentagon, R.string.annot_polygon, R.string.an_group_drawing),
        ToolEntry(ReaderTool.CLOUD, Icons.Default.Cloud, R.string.annot_cloud, R.string.an_group_drawing),
        ToolEntry(ReaderTool.TEXTBOX, Icons.Default.TextFields, R.string.rd_add_text, R.string.an_group_edit),
        ToolEntry(ReaderTool.IMAGE, Icons.Default.Image, R.string.rd_add_image, R.string.an_group_edit),
        ToolEntry(ReaderTool.LINK, Icons.Default.Link, R.string.annot_link, R.string.an_group_edit),
        ToolEntry(ReaderTool.FS_TEXT, Icons.Default.TextFields, R.string.fill_sign_text, R.string.an_group_fill),
        ToolEntry(ReaderTool.FS_CHECK, Icons.Default.Check, R.string.fill_sign_check, R.string.an_group_fill),
        ToolEntry(ReaderTool.FS_CROSS, Icons.Default.Close, R.string.fill_sign_cross, R.string.an_group_fill),
        ToolEntry(ReaderTool.FS_DOT, Icons.Default.FiberManualRecord, R.string.fill_sign_dot, R.string.an_group_fill),
        ToolEntry(ReaderTool.FS_LINE, Icons.Default.HorizontalRule, R.string.fill_sign_line, R.string.an_group_fill),
        ToolEntry(ReaderTool.FS_RRECT, Icons.Default.CropDin, R.string.nf2_rounded_rect, R.string.an_group_fill),
        ToolEntry(ReaderTool.FS_COMB, Icons.Default.Pin, R.string.nf2_comb, R.string.an_group_fill),
    )

    fun of(t: ReaderTool): ToolEntry? = all.firstOrNull { it.tool == t }

    /** The tools of MODULE_annot2 shown after the existing ones in the Comment tab. */
    val commentExtras = listOf(ReaderTool.REPLACE, ReaderTool.CARET, ReaderTool.POLYLINE, ReaderTool.POLYGON, ReaderTool.CLOUD,
        ReaderTool.ATTACH, ReaderTool.SOUND, ReaderTool.VIDEO, ReaderTool.SNAPSHOT, ReaderTool.SELECT, ReaderTool.TICKMARK).mapNotNull { of(it) }
}

/** User's favourite tools, in order, persisted in SharedPreferences "toolset". */
object ToolsetStore {
    private const val KEY = "tools"
    private val DEFAULT = listOf(ReaderTool.HIGHLIGHT, ReaderTool.INK, ReaderTool.NOTE, ReaderTool.FREETEXT, ReaderTool.RECT)

    fun load(context: Context): List<ReaderTool> {
        val raw = context.getSharedPreferences("toolset", Context.MODE_PRIVATE).getString(KEY, null) ?: return DEFAULT
        return raw.split(',').mapNotNull { n -> runCatching { ReaderTool.valueOf(n.trim()) }.getOrNull() }.filter { ToolCatalog.of(it) != null }.distinct()
    }

    fun save(context: Context, tools: List<ReaderTool>) {
        context.getSharedPreferences("toolset", Context.MODE_PRIVATE).edit().putString(KEY, tools.joinToString(",") { it.name }).apply()
    }
}

/** Click on any catalog tool (custom tab or comment tab): pickers for stamp / image, ours through the host. */
fun clickTool(state: ReaderState, actions: ReaderActions, t: ReaderTool) {
    when (t) {
        ReaderTool.STAMP -> { state.selectTool(ReaderTool.STAMP); if (state.tool == ReaderTool.STAMP) actions.pickStamp() }
        ReaderTool.IMAGE -> { state.selectTool(ReaderTool.IMAGE); if (state.tool == ReaderTool.IMAGE) actions.pickImage() }
        in Annot2Host.OWN_TOOLS -> state.annot2.pick(t)
        else -> state.selectTool(t)
    }
}

/** Bottom bar of the custom tab: the chosen tools, then "customise". */
@Composable
fun CustomToolbar(state: ReaderState, actions: ReaderActions) {
    val ctx = LocalContext.current
    var tools by remember { mutableStateOf(ToolsetStore.load(ctx)) }
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tools.isEmpty()) Text(stringResource(R.string.an_toolset_empty), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        tools.mapNotNull { ToolCatalog.of(it) }.forEach { e ->
            ToolChip(e.icon, stringResource(e.label), selected = state.tool == e.tool) { clickTool(state, actions, e.tool) }
        }
        VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
        ToolChip(Icons.Default.Tune, stringResource(R.string.an_toolset_edit)) { editing = true }
    }
    if (editing) ToolsetEditSheet(tools, onChange = { tools = it; ToolsetStore.save(ctx, it) }, onDismiss = { editing = false })
}

/** "Add tools": added list (long-press drag to reorder, remove) + every tool grouped, with add / remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsetEditSheet(tools: List<ReaderTool>, onChange: (List<ReaderTool>) -> Unit, onDismiss: () -> Unit) {
    val list = remember { mutableStateListOf<ReaderTool>().apply { addAll(tools) } }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowH = with(LocalDensity.current) { 52.dp.toPx() }
    fun commit() = onChange(list.toList())
    ModalBottomSheet(onDismissRequest = { commit(); onDismiss() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 12.dp)) {
            item {
                Text(stringResource(R.string.an_toolset_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                com.smartdocs.editor.ui.nve.shell.NveCustomTabActions(onRemoveAll = { list.clear(); commit() })   // NVE-shell: rename tab, remove all
                Text(stringResource(R.string.an_toolset_added) + " · " + stringResource(R.string.an_toolset_drag), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                if (list.isEmpty()) Text(stringResource(R.string.an_toolset_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
            itemsIndexed(list.toList(), key = { _, t -> "a_" + t.name }) { i, t ->
                val e = ToolCatalog.of(t) ?: return@itemsIndexed
                val dragging = i == dragIndex
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 8f else 0f }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .pointerInput(t) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragIndex = list.indexOf(t); dragOffset = 0f },
                                onDrag = { ch, d ->
                                    ch.consume(); dragOffset += d.y
                                    val from = dragIndex
                                    val to = (from + (dragOffset / rowH).roundToInt()).coerceIn(0, list.lastIndex)
                                    if (from >= 0 && to != from) { list.add(to, list.removeAt(from)); dragOffset -= (to - from) * rowH; dragIndex = to }
                                },
                                onDragEnd = { dragIndex = -1; dragOffset = 0f; commit() },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                            )
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DragIndicator, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp)); Icon(e.icon, null); Spacer(Modifier.width(12.dp))
                    Text(stringResource(e.label), Modifier.weight(1f))
                    IconButton(onClick = { list.remove(t); commit() }) { Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
                }
            }
            item { Text(stringResource(R.string.an_toolset_all), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp)) }
            ToolCatalog.all.groupBy { it.group }.forEach { (group, entries) ->
                item(key = "g_$group") { Text(stringResource(group), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp, 10.dp, 8.dp, 2.dp)) }
                entries.forEach { e ->
                    item(key = "c_" + e.tool.name) {
                        val added = e.tool in list
                        ListItem(
                            headlineContent = { Text(stringResource(e.label)) },
                            leadingContent = { Icon(e.icon, null) },
                            trailingContent = {
                                IconButton(onClick = { if (added) list.remove(e.tool) else list.add(e.tool); commit() }) {
                                    Icon(if (added) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline, stringResource(if (added) R.string.ai6h_remove else R.string.ai6h_add),
                                        tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.clickable { if (added) list.remove(e.tool) else list.add(e.tool); commit() },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
