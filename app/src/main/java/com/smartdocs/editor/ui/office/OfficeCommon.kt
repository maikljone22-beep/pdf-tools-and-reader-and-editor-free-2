package com.smartdocs.editor.ui.office

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import java.io.File

/** Contract between an editor (Word / Excel / PowerPoint / text) and the shared Office chrome. */
interface OfficeHost {
    val file: File
    /** Extension written by [saveTo] ("docx", "xlsx", "pptx", "csv", "txt"). */
    val ext: String
    /** Read inside composition to observe changes. */
    val tick: Int
    val modified: Boolean
    val canUndo: Boolean
    val canRedo: Boolean
    fun undo()
    fun redo()
    /** Flushes pending input (formula bar, text field) into the model. */
    fun commitPending() {}
    /** Writes the document to [target] (blocking; call off the main thread). */
    fun saveTo(target: File)
}

fun Color.Companion.hex(h: String?): Color? = h?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

fun Color.toHex(): String = String.format("%06X", (this.value shr 32).toInt() and 0xFFFFFF)

/** One ribbon tab: [id] is stable (for registry extensions), [label] a string resource. */
class RibbonTab(val id: String, val label: Int)

/** Office-style tab strip; the content row of the selected tab is drawn by the caller below it. */
@Composable
fun RibbonTabs(tabs: List<RibbonTab>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { t ->
            val on = t.id == selected
            Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(t.id) }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(t.label), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

/** Horizontally scrolling row of ribbon commands. */
@Composable
fun RibbonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun RibbonButton(icon: ImageVector, label: Int, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val desc = stringResource(label)
    Column(
        Modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, desc, Modifier.size(22.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        Text(desc, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
    }
}

/** A text command (style names, number formats…). */
@Composable
fun RibbonChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text, maxLines = 1) }, modifier = Modifier.padding(horizontal = 3.dp))
}

@Composable
fun RibbonDivider() { Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant)) }

/** A colour swatch button showing the current colour. */
@Composable
fun RibbonColor(icon: ImageVector, label: Int, color: Color?, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        RibbonButton(icon, label, onClick = onClick)
        Box(Modifier.padding(bottom = 16.dp).width(20.dp).height(3.dp).background(color ?: Color.Transparent))
    }
}

val OFFICE_PALETTE = listOf(
    "000000", "434343", "666666", "999999", "CCCCCC", "FFFFFF",
    "C00000", "FF0000", "FFC000", "FFFF00", "92D050", "00B050",
    "00B0F0", "0070C0", "002060", "7030A0", "E6B8B7", "FCE4D6",
    "FFF2CC", "E2EFDA", "DDEBF7", "D9E1F2", "1F3864", "833C0B",
)

/** Palette dialog; [allowNone] adds an "automatic / no colour" choice (returns null). */
@Composable
fun ColorPickerDialog(title: Int, allowNone: Boolean, palette: List<String> = OFFICE_PALETTE, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                LazyVerticalGrid(GridCells.Fixed(6), Modifier.heightIn(max = 260.dp)) {
                    items(palette) { h ->
                        Box(Modifier.padding(4.dp).size(36.dp).clip(CircleShape).background(Color.hex(h)!!).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape).clickable { onPick(h) })
                    }
                }
                if (allowNone) TextButton(onClick = { onPick(null) }) { Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.o5_no_color)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } },
    )
}

/** Single text field dialog. */
@Composable
fun TextInputDialog(title: Int, initial: String, label: Int? = null, number: Boolean = false, singleLine: Boolean = true, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(v, { v = it }, singleLine = singleLine, label = label?.let { { Text(stringResource(it)) } },
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text(stringResource(R.string.o5_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } },
    )
}

/** Two numbers (rows x columns). */
@Composable
fun SizeDialog(title: Int, a: Int, b: Int, labelA: Int, labelB: Int, onDismiss: () -> Unit, onOk: (Int, Int) -> Unit) {
    var x by remember { mutableStateOf(a.toString()) }; var y by remember { mutableStateOf(b.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(x, { x = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(labelA)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(y, { y = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(labelB)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        },
        confirmButton = { TextButton(onClick = { onOk(x.toIntOrNull()?.coerceIn(1, 200) ?: a, y.toIntOrNull()?.coerceIn(1, 60) ?: b) }) { Text(stringResource(R.string.o5_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } },
    )
}

/** Font size list (Office steps). */
val FONT_SIZES = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 40f, 48f, 60f, 72f)

fun nextSize(cur: Float, up: Boolean): Float =
    if (up) FONT_SIZES.firstOrNull { it > cur + 0.01f } ?: (cur + 4f) else FONT_SIZES.lastOrNull { it < cur - 0.01f } ?: maxOf(1f, cur - 1f)

@Composable
fun FontSizeDialog(current: Float, onDismiss: () -> Unit, onPick: (Float) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.o5_font_size)) },
        text = {
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 300.dp)) {
                items(FONT_SIZES) { s ->
                    val on = kotlin.math.abs(s - current) < 0.1f
                    Box(Modifier.padding(4.dp).clip(RoundedCornerShape(8.dp)).background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onPick(s) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(com.smartdocs.editor.core.office5.FxCoerce.general(s.toDouble()))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } },
    )
}

/** "Save changes?" on leaving an edited document. */
@Composable
fun UnsavedDialog(onSave: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.o5_unsaved_title)) },
        text = { Text(stringResource(R.string.o5_unsaved_text)) },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.o5_save)) } },
        dismissButton = { Row { TextButton(onClick = onDiscard) { Text(stringResource(R.string.o5_discard)) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } } },
    )
}

/** Small Find bar used by Word and text editors. */
@Composable
fun FindBar(query: String, onQuery: (String) -> Unit, replace: String?, onReplace: ((String) -> Unit)?, count: Int, index: Int, onPrev: () -> Unit, onNext: () -> Unit,
            onReplaceOne: (() -> Unit)?, onReplaceAll: (() -> Unit)?, onClose: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text(stringResource(R.string.o5_find)) }, modifier = Modifier.weight(1f))
                Text(if (count == 0) "0" else "${index + 1}/$count", Modifier.padding(horizontal = 6.dp), fontSize = 12.sp)
                IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.o5_prev)) }
                IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.o5_next)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.o5_close)) }
            }
            if (replace != null && onReplace != null) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replace, onReplace, singleLine = true, placeholder = { Text(stringResource(R.string.o5_replace_with)) }, modifier = Modifier.weight(1f))
                TextButton(onClick = { onReplaceOne?.invoke() }, enabled = count > 0) { Text(stringResource(R.string.o5_replace)) }
                TextButton(onClick = { onReplaceAll?.invoke() }, enabled = count > 0) { Text(stringResource(R.string.o5_replace_all)) }
            }
        }
    }
}
