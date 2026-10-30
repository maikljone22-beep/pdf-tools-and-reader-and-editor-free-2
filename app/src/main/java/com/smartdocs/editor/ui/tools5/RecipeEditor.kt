@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smartdocs.editor.ui.tools5

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.E5Profiles
import com.smartdocs.editor.core.edit5.HeaderFooterOps
import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.search.patternLabel
import com.smartdocs.editor.ui.security.sanitizeLabel

@Composable
private fun paramLabel(key: String): String = stringResource(
    when (key) {
        "langs" -> R.string.t5_p_langs; "clean" -> R.string.t5_preclean; "dpi" -> R.string.t5_opt_dpi; "quality" -> R.string.t5_opt_quality
        "strip" -> R.string.t5_opt_meta; "rasterizeMissingFonts" -> R.string.t5_pdfa_raster; "text" -> R.string.t5_p_text; "opacity" -> R.string.t5_p_opacity
        "size" -> R.string.t5_p_size; "start" -> R.string.t5_p_start; "arabicDigits" -> R.string.t5_p_arabic_digits; "degrees" -> R.string.t5_p_degrees
        "pages" -> R.string.t5_p_pages; "title" -> R.string.t5_p_title; "author" -> R.string.t5_p_author; "subject" -> R.string.t5_p_subject
        "keywords" -> R.string.t5_p_keywords; "forms" -> R.string.t5_p_forms_only; "lang" -> R.string.t5_p_lang; "password" -> R.string.t5_p_password
        // nav-batch
        "profile" -> R.string.nbt_p_profile; "baseUrl" -> R.string.nbt_p_base_url; "custom" -> R.string.nbt_p_custom; "pdfua" -> R.string.nbt_p_pdfua
        "code" -> R.string.nbt_p_code; "header" -> R.string.nbt_p_header; "footer" -> R.string.nbt_p_footer; "prefix" -> R.string.nbt_p_prefix
        "suffix" -> R.string.nbt_p_suffix; "digits" -> R.string.nbt_p_digits; "position" -> R.string.nbt_p_position; "terms" -> R.string.nbt_p_terms
        "patterns" -> R.string.nbt_p_patterns; "regex" -> R.string.nbt_p_regex; "sanitize" -> R.string.nbt_p_sanitize; "items" -> R.string.nbt_p_items
        "format" -> R.string.nbt_p_format; "merged" -> R.string.nbt_print_merged; "recipe" -> R.string.nbt_p_recipe
        "mode" -> R.string.t5_p_alt_mode; "fallback" -> R.string.t5_p_alt_fallback; "onFail" -> R.string.nbt_p_on_fail; "report" -> R.string.nbt_p_report
        else -> R.string.t5_p_value
    },
)

private val boolKeys = setOf("clean", "strip", "rasterizeMissingFonts", "arabicDigits", "forms", "pdfua", "sanitize", "merged", "report")
private val multiLineKeys = setOf("custom", "terms", "regex", "code")

@Composable
private fun conflictLabel(c: Conflict): String = stringResource(when (c) {
    Conflict.RENAME -> R.string.nbt_conflict_rename; Conflict.OVERWRITE -> R.string.nbt_conflict_overwrite
    Conflict.SKIP -> R.string.nbt_conflict_skip; Conflict.ASK -> R.string.nbt_conflict_ask
})

@Composable
private fun slotLabel(s: HeaderFooterOps.Slot): String = stringResource(when (s) {
    HeaderFooterOps.Slot.TOP_LEFT -> R.string.e5_slot_tl; HeaderFooterOps.Slot.TOP_CENTER -> R.string.e5_slot_tc; HeaderFooterOps.Slot.TOP_RIGHT -> R.string.e5_slot_tr
    HeaderFooterOps.Slot.BOTTOM_LEFT -> R.string.e5_slot_bl; HeaderFooterOps.Slot.BOTTOM_CENTER -> R.string.e5_slot_bc; HeaderFooterOps.Slot.BOTTOM_RIGHT -> R.string.e5_slot_br
})

/** Short explanation of a step (shown under its title, and as the tooltip of a one-step "custom command"). */
@Composable
fun StepType.hint(): String? = when (this) {
    StepType.JAVASCRIPT -> stringResource(R.string.nbt_hint_js)
    StepType.SET_INFO -> stringResource(R.string.nbt_hint_info)
    StepType.WATERMARK, StepType.HEADER_FOOTER -> stringResource(R.string.nbt_hint_profile)
    StepType.PRINT -> stringResource(R.string.nbt_hint_print)
    StepType.RUN_RECIPE -> stringResource(R.string.nbt_hint_recipe)
    StepType.CONVERT_OFFICE -> stringResource(R.string.nbt_hint_convert)
    StepType.REDACT -> stringResource(R.string.nbt_hint_redact)
    StepType.ALT_TEXT -> stringResource(R.string.t5_hint_alt)
    StepType.PREFLIGHT -> stringResource(R.string.nbt_hint_preflight)
    StepType.DIVIDER -> stringResource(R.string.nbt_hint_divider)
    else -> null
}

/** Chips that edit a comma list of names ([all] with their labels). */
@Composable
private fun <T> MultiChips(all: List<T>, name: (T) -> String, label: @Composable (T) -> String, value: String, onValue: (String) -> Unit) {
    val chosen = RecipeOps.list(value).map { it.uppercase() }.toSet()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { t ->
            val on = name(t).uppercase() in chosen
            FilterChip(on, { onValue(all.filter { x -> (name(x).uppercase() in chosen) != (x == t) }.joinToString(",") { name(it) }) }, label = { Text(label(t)) })
        }
    }
}

/** One option of a step: a switch, choice chips or a text field depending on [key]. */
@Composable
fun StepParam(s: Step, key: String, recipes: List<Recipe>, selfId: String, onChange: (Step) -> Unit) {
    val ctx = LocalContext.current
    fun set(v: String) = onChange(s.copy(params = s.params + (key to v)))
    when {
        key in boolKeys -> SwitchRow(paramLabel(key), s.bool(key)) { set(it.toString()) }
        key == "format" -> {
            Text(paramLabel(key), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BatchSteps.officeConversions.keys.forEach { f -> FilterChip(s.p(key).equals(f, true), { set(f) }, label = { Text(f.uppercase()) }) }
            }
        }
        key == "profile" && s.type == StepType.PREFLIGHT -> {
            Text(stringResource(R.string.nbt_p_profile), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreflightProfile.entries.forEach { p -> FilterChip(s.p(key).equals(p.code, true), { set(p.code) }, label = { Text(p.code.uppercase()) }) }
            }
        }
        key == "onFail" -> {
            Text(paramLabel(key), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("stop" to R.string.nbt_fail_stop, "skip" to R.string.nbt_fail_skip, "continue" to R.string.nbt_fail_continue).forEach { (v, l) -> FilterChip(s.p(key).equals(v, true), { set(v) }, label = { Text(stringResource(l)) }) }
            }
        }
        key == "text" && s.type == StepType.DIVIDER ->
            OutlinedTextField(s.p(key), { set(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbt_divider_text)) })
        key == "mode" && s.type == StepType.ALT_TEXT -> {
            Text(paramLabel(key), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ai" to R.string.t5_alt_mode_ai, "text" to R.string.t5_alt_mode_text, "decorative" to R.string.t5_alt_mode_decorative).forEach { (v, l) ->
                    FilterChip(s.p(key).equals(v, true), { set(v) }, label = { Text(stringResource(l)) })
                }
            }
        }
        key == "position" -> {
            Text(paramLabel(key), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderFooterOps.Slot.entries.forEach { sl -> FilterChip(s.p(key).equals(sl.name, true), { set(sl.name) }, label = { Text(slotLabel(sl)) }) }
            }
        }
        key == "patterns" -> {
            Text(paramLabel(key), style = MaterialTheme.typography.labelMedium)
            MultiChips(PatternKind.entries.toList(), { it.name }, { patternLabel(it) }, s.p(key)) { set(it) }
        }
        key == "items" -> {
            Text(stringResource(R.string.nbt_p_items_hint), style = MaterialTheme.typography.labelMedium)
            MultiChips(SanitizeItem.entries.toList(), { it.name }, { sanitizeLabel(it) }, s.p(key)) { set(it) }
        }
        key == "profile" -> {
            // profiles saved in the Watermark / Background / Header & footer tools
            val kinds = if (s.type == StepType.HEADER_FOOTER) listOf("hf") else listOf("wm", "bg", "sec")
            val names = remember(kinds) { kinds.flatMap { k -> E5Profiles.list(ctx, k).map { it.name } }.distinct() }
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(s.p(key), { set(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(paramLabel(key)) },
                    supportingText = { Text(stringResource(if (names.isEmpty()) R.string.nbt_profiles_empty else R.string.nbt_profiles_hint)) },
                    trailingIcon = { if (names.isNotEmpty()) IconButton(onClick = { open = true }) { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.nbt_p_profile)) } })
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.nbt_profile_none)) }, onClick = { set(""); open = false })
                    names.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { set(n); open = false }) }
                }
            }
        }
        key == "recipe" -> {
            var open by remember { mutableStateOf(false) }
            val current = recipes.firstOrNull { it.id == s.p(key) || it.name.equals(s.p(key), true) }
            Box {
                OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(current?.title() ?: stringResource(R.string.nbt_p_recipe), maxLines = 1) }
                DropdownMenu(open, { open = false }) {
                    recipes.filter { it.id != selfId }.forEach { r -> DropdownMenuItem(text = { Text(r.title()) }, onClick = { set(r.id); open = false }) }
                }
            }
        }
        key == "text" && s.type == StepType.NOTE ->
            OutlinedTextField(s.p(key), { set(it) }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(R.string.nbt_note_text)) })
        key in multiLineKeys ->
            OutlinedTextField(s.p(key), { set(it) }, Modifier.fillMaxWidth(), minLines = if (key == "code") 4 else 2, maxLines = 12, label = { Text(paramLabel(key)) },
                supportingText = when (key) {
                    "custom" -> ({ Text(stringResource(R.string.nbt_p_custom_hint)) })
                    "terms" -> ({ Text(stringResource(R.string.nbt_p_terms_hint)) })
                    "code" -> ({ Text(stringResource(R.string.nbt_p_code_hint)) })
                    else -> null
                })
        else -> OutlinedTextField(s.p(key), { set(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(paramLabel(key)) })
    }
}

/** Full-screen recipe editor: name, description, ordered steps with options ("ask when run"), output naming and folder. */
@Composable
fun RecipeEditor(initial: Recipe, onSave: (Recipe) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var steps by remember { mutableStateOf(initial.steps) }
    var pattern by remember { mutableStateOf(initial.output.pattern) }
    var sameFolder by remember { mutableStateOf(initial.output.sameFolder) }
    var conflict by remember { mutableStateOf(initial.output.conflict) }
    var folderUri by remember { mutableStateOf(initial.output.folderUri) }
    var folderName by remember { mutableStateOf(initial.output.folderName) }
    var addMenu by remember { mutableStateOf(false) }
    val recipes = remember { RecipeStore.all(ctx) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) { SafFolders.persist(ctx, u); folderUri = u.toString(); folderName = SafFolders.name(ctx, u) }
    }
    fun update(i: Int, s: Step) { steps = steps.toMutableList().also { it[i] = s } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.t5_rc_editor), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.t5_remove)) }
                    TextButton(onClick = {
                        onSave(Recipe(initial.id, name.trim().ifBlank { "Recipe" }, steps,
                            OutputOptions(pattern.ifBlank { "{name}" }, sameFolder, conflict == Conflict.OVERWRITE, conflict, folderUri, folderName), description = description.trim()))
                    }, enabled = steps.isNotEmpty()) { Text(stringResource(R.string.save)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_rc_name)) })
                    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, label = { Text(stringResource(R.string.nbt_rc_description)) })
                    steps.forEachIndexed { i, s ->
                        T5Card("${i + 1}. " + s.type.label()) {
                            s.type.hint()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(s.enabled, { update(i, s.copy(enabled = it)) })
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { steps = steps.toMutableList().apply { add(i - 1, removeAt(i)) } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                                IconButton(onClick = { steps = steps.toMutableList().apply { add(i + 1, removeAt(i)) } }, enabled = i < steps.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.t5_move_down)) }
                                IconButton(onClick = { steps = steps.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Default.Close, stringResource(R.string.t5_remove)) }
                            }
                            s.type.defaults.keys.forEach { k -> StepParam(s, k, recipes, initial.id) { update(i, it) } }
                            if (s.type.defaults.isNotEmpty() && !s.type.passive && s.type != StepType.RUN_RECIPE)
                                SwitchRow(stringResource(R.string.nbt_ask_at_run), s.ask, stringResource(R.string.nbt_ask_at_run_sub)) { update(i, s.copy(ask = it)) }
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
                    if (!sameFolder) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { runCatching { pickFolder.launch(null) } }, Modifier.weight(1f)) {
                            Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp))
                            Text(if (folderUri.isEmpty()) stringResource(R.string.nbt_dest_default) else folderName.ifBlank { folderUri }, maxLines = 1)
                        }
                        if (folderUri.isNotEmpty()) IconButton(onClick = { folderUri = ""; folderName = "" }) { Icon(Icons.Default.Clear, stringResource(R.string.nbt_dest_clear)) }
                    }
                    Text(stringResource(R.string.nbt_conflict_title), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Conflict.entries.forEach { c -> FilterChip(conflict == c, { conflict = c }, label = { Text(conflictLabel(c)) }) }
                    }
                }
            }
        }
    }
}
