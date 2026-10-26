package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.*

@Composable
private fun paramLabel(key: String): String = stringResource(
    when (key) {
        "langs" -> R.string.t5_p_langs; "clean" -> R.string.t5_preclean; "dpi" -> R.string.t5_opt_dpi; "quality" -> R.string.t5_opt_quality
        "strip" -> R.string.t5_opt_meta; "rasterizeMissingFonts" -> R.string.t5_pdfa_raster; "text" -> R.string.t5_p_text; "opacity" -> R.string.t5_p_opacity
        "size" -> R.string.t5_p_size; "start" -> R.string.t5_p_start; "arabicDigits" -> R.string.t5_p_arabic_digits; "degrees" -> R.string.t5_p_degrees
        "pages" -> R.string.t5_p_pages; "title" -> R.string.t5_p_title; "author" -> R.string.t5_p_author; "subject" -> R.string.t5_p_subject
        "keywords" -> R.string.t5_p_keywords; "forms" -> R.string.t5_p_forms_only; "lang" -> R.string.t5_p_lang; "password" -> R.string.t5_p_password
        else -> R.string.t5_p_value
    },
)

private val boolKeys = setOf("clean", "strip", "rasterizeMissingFonts", "arabicDigits", "forms")

/** Full-screen recipe editor: name, ordered steps with options, output naming. */
@Composable
fun RecipeEditor(initial: Recipe, onSave: (Recipe) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var steps by remember { mutableStateOf(initial.steps) }
    var pattern by remember { mutableStateOf(initial.output.pattern) }
    var sameFolder by remember { mutableStateOf(initial.output.sameFolder) }
    var overwrite by remember { mutableStateOf(initial.output.overwrite) }
    var addMenu by remember { mutableStateOf(false) }
    fun update(i: Int, s: Step) { steps = steps.toMutableList().also { it[i] = s } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.t5_rc_editor), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.t5_remove)) }
                    TextButton(onClick = { onSave(Recipe(initial.id, name.trim().ifBlank { "Recipe" }, steps, OutputOptions(pattern.ifBlank { "{name}" }, sameFolder, overwrite))) }, enabled = steps.isNotEmpty()) { Text(stringResource(R.string.save)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_rc_name)) })
                    steps.forEachIndexed { i, s ->
                        T5Card("${i + 1}. " + s.type.label()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(s.enabled, { update(i, s.copy(enabled = it)) })
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { steps = steps.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                                IconButton(onClick = { steps = steps.toMutableList().apply { add(i + 1, removeAt(i)) } }, enabled = i < steps.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.t5_move_down)) }
                                IconButton(onClick = { steps = steps.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Default.Close, stringResource(R.string.t5_remove)) }
                            }
                            s.type.defaults.keys.forEach { k ->
                                if (k in boolKeys) SwitchRow(paramLabel(k), s.bool(k)) { v -> update(i, s.copy(params = s.params + (k to v.toString()))) }
                                else OutlinedTextField(s.p(k), { v -> update(i, s.copy(params = s.params + (k to v))) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(paramLabel(k)) })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_rc_add_step)) }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            StepType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label()) }, onClick = { steps = steps + Step(t); addMenu = false }) }
                        }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.t5_rc_output), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(pattern, { pattern = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_rc_pattern)) }, supportingText = { Text(stringResource(R.string.t5_rc_tokens)) })
                    SwitchRow(stringResource(R.string.t5_rc_same_folder), sameFolder) { sameFolder = it }
                    SwitchRow(stringResource(R.string.t5_rc_overwrite), overwrite) { overwrite = it }
                }
            }
        }
    }
}
