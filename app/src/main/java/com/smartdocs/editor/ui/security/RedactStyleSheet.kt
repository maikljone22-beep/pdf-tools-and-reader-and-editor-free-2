package com.smartdocs.editor.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.smartdocs.editor.core.edit5.E5Font
import com.smartdocs.editor.core.redact.CodeSets
import com.smartdocs.editor.core.redact.RedactPrefs
import com.smartdocs.editor.core.redact.RedactStyle
import com.smartdocs.editor.ui.edit5.fontLabel
import com.smartdocs.editor.ui.search.ColorRow
import com.smartdocs.editor.ui.tools.ChoiceChips

private val FILLS = listOf(0x000000, 0xFFFFFF, 0x424242, 0x9E9E9E, 0xB71C1C, 0x0D47A1, 0x1B5E20)
private val OUTLINES = listOf(0xE53935, 0xFF9800, 0x1E88E5, 0x43A047, 0x000000)

/**
 * Mark properties: fill (white = whiteout), outline while marked, overlay text with the redaction codes, text
 * colour, size (0 = auto), repeat and alignment. [onApply] receives the edited style.
 */
@Composable
fun RedactStyleDialog(initial: RedactStyle, title: String, onDismiss: () -> Unit, onApply: (RedactStyle) -> Unit) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf(initial) }
    var codes by remember { mutableStateOf(RedactPrefs.codes(ctx)) }
    var codeMenu by remember { mutableStateOf(false) }
    var editCodes by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Label(stringResource(R.string.s5_fill))
                ColorRow(st.fill, { st = st.copy(fill = it) }, FILLS)
                if (st.fill == 0xFFFFFF) Text(stringResource(R.string.s5_whiteout_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.s5_outline), Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Switch(st.outline != null, { st = st.copy(outline = if (it) 0xE53935 else null) })
                }
                st.outline?.let { o -> ColorRow(o, { st = st.copy(outline = it) }, OUTLINES) }
                Label(stringResource(R.string.s5_overlay))
                OutlinedTextField(st.overlay, { st = st.copy(overlay = it) }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.s5_overlay_hint)) },
                    trailingIcon = {
                        Box {
                            IconButton(onClick = { codeMenu = true }) { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.s5_codes)) }
                            DropdownMenu(codeMenu, { codeMenu = false }) {
                                Text(codeSetLabel(RedactPrefs.activeSet(ctx)), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                codes.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { st = st.copy(overlay = c); codeMenu = false }) }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.s5_edit_codes)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { codeMenu = false; editCodes = true })
                            }
                        }
                    })
                if (st.overlay.isNotBlank()) {
                    Label(stringResource(R.string.s5_text_color))
                    ColorRow(st.textColor, { st = st.copy(textColor = it) }, listOf(0xFFFFFF, 0x000000, 0xE53935, 0xFFEB3B, 0x1E88E5))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.s5_font_size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text(if (st.fontSize <= 0f) stringResource(R.string.s5_auto) else "%.0f pt".format(st.fontSize), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(st.fontSize, { st = st.copy(fontSize = if (it < 4f) 0f else it) }, valueRange = 0f..48f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.s5_repeat), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Switch(st.repeat, { st = st.copy(repeat = it) })
                    }
                    if (!st.repeat) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        val labels = listOf(R.string.s5_align_left, R.string.s5_align_center, R.string.s5_align_right)
                        labels.forEachIndexed { i, l -> SegmentedButton(st.align == i, { st = st.copy(align = i) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(l)) } }
                    }
                    Label(stringResource(R.string.nt_redact_font))
                    val fonts = remember { listOf<E5Font?>(null) + E5Font.values().filter { it.available() } }
                    ChoiceChips(fonts, E5Font.values().firstOrNull { it.name == st.font },
                        { f -> if (f == null) stringResource(R.string.s5_auto) else stringResource(fontLabel(f)) }) { st = st.copy(font = it?.name ?: "") }
                    Label(stringResource(R.string.nt_redact_case))
                    val cases = listOf(RedactStyle.CASE_AS_TYPED to R.string.nt_case_as_typed, RedactStyle.CASE_UPPER to R.string.nt_case_upper,
                        RedactStyle.CASE_LOWER to R.string.nt_case_lower, RedactStyle.CASE_TITLE to R.string.nt_case_title)
                    ChoiceChips(cases.map { it.first }, st.textCase, { c -> stringResource(cases.first { it.first == c }.second) }) { st = st.copy(textCase = it) }
                    if (st.textCase != RedactStyle.CASE_AS_TYPED) Text(st.shownOverlay(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(st) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (editCodes) CodesEditor(onDismiss = { editCodes = false }) { codes = it; editCodes = false }
}

@Composable
private fun Label(t: String) = Text(t, Modifier.padding(top = 10.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)

@Composable
fun codeSetLabel(name: String) = if (name == CodeSets.DEFAULT_NAME) stringResource(R.string.nt_codeset_default) else name

/**
 * The Codes Editor (Nitro): pick a code set, create, rename or remove sets, and edit the codes of the selected set
 * (one per line; "reset" restores the Arabic defaults). Saving stores every set and makes the selected one active;
 * [onSave] receives its codes.
 */
@Composable
fun CodesEditor(onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var sets by remember { mutableStateOf(RedactPrefs.codeSets(ctx)) }
    var current by remember { mutableStateOf(RedactPrefs.activeSet(ctx).takeIf { n -> sets.any { it.name == n } } ?: sets.first().name) }
    var text by remember { mutableStateOf(sets.first { it.name == current }.codes.joinToString("\n")) }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Boolean?>(null) }   // true = new set, false = rename
    fun lines() = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun select(name: String) {
        sets = CodeSets.update(sets, current, lines())
        current = name; text = sets.first { it.name == name }.codes.joinToString("\n")
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.s5_edit_codes)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { menu = true }, Modifier.fillMaxWidth()) {
                            Text(codeSetLabel(current), Modifier.weight(1f), maxLines = 1); Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            sets.forEach { cs -> DropdownMenuItem(text = { Text(codeSetLabel(cs.name)) }, onClick = { menu = false; select(cs.name) },
                                leadingIcon = if (cs.name == current) ({ Icon(Icons.Default.Check, null) }) else null) }
                        }
                    }
                    IconButton(onClick = { naming = true }) { Icon(Icons.Default.Add, stringResource(R.string.nt_codeset_new)) }
                    IconButton(onClick = { naming = false }) { Icon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.nt_codeset_rename)) }
                    IconButton(onClick = {
                        val next = CodeSets.remove(sets, current)
                        if (next.size != sets.size) { sets = next; current = next.first().name; text = next.first().codes.joinToString("\n") }
                    }, enabled = sets.size > 1) { Icon(Icons.Default.Delete, stringResource(R.string.nt_codeset_remove)) }
                }
                Text(stringResource(R.string.s5_one_per_line), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), minLines = 6)
                TextButton(onClick = { text = RedactPrefs.DEFAULT_CODES.joinToString("\n") }) { Text(stringResource(R.string.s5_reset)) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val all = CodeSets.update(sets, current, lines())
            RedactPrefs.saveCodeSets(ctx, all); RedactPrefs.setActiveSet(ctx, current)
            onSave(all.first { it.name == current }.codes)
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
    naming?.let { isNew ->
        var name by remember(isNew) { mutableStateOf(if (isNew) "" else if (current == CodeSets.DEFAULT_NAME) "" else current) }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text(stringResource(if (isNew) R.string.nt_codeset_new else R.string.nt_codeset_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.nt_codeset_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && sets.none { it.name == name.trim() }, onClick = {
                if (isNew) {
                    sets = CodeSets.update(sets, current, lines())
                    val (next, used) = CodeSets.add(sets, name); sets = next; current = used; text = ""
                } else { sets = CodeSets.rename(CodeSets.update(sets, current, lines()), current, name); current = name.trim() }
                naming = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Picks pages for "repeat on pages": a range plus all / odd / even. Returns zero-based pages. */
@Composable
fun PagePickDialog(title: String, pageCount: Int, onDismiss: () -> Unit, onPick: (List<Int>) -> Unit) {
    var spec by remember { mutableStateOf("") }
    var parity by remember { mutableStateOf(com.smartdocs.editor.core.redact.RedactMath.PageParity.ALL) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(spec, { spec = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.pages_hint)) })
                ParityRow(parity) { parity = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(com.smartdocs.editor.core.redact.RedactMath.pages(spec, pageCount, parity)) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ParityRow(parity: com.smartdocs.editor.core.redact.RedactMath.PageParity, onChange: (com.smartdocs.editor.core.redact.RedactMath.PageParity) -> Unit) {
    val all = com.smartdocs.editor.core.redact.RedactMath.PageParity.values()
    val labels = listOf(R.string.s5_pages_all, R.string.s5_pages_odd, R.string.s5_pages_even)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        all.forEachIndexed { i, p -> SegmentedButton(parity == p, { onChange(p) }, SegmentedButtonDefaults.itemShape(i, all.size)) { Text(stringResource(labels[i])) } }
    }
}

