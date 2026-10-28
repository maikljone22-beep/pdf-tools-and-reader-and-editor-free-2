package com.smartdocs.editor.ui.edit5

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.DestView
import com.smartdocs.editor.core.edit5.Digits
import com.smartdocs.editor.core.edit5.LinkBorder
import com.smartdocs.editor.core.edit5.LinkSpec
import com.smartdocs.editor.core.edit5.NavAction
import com.smartdocs.editor.core.edit5.NavActions
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Shared pieces of the navigation tools (nav-bookmarks): custom colour, destination view fields, the action-list
 * editor used by bookmarks and links, link appearance defaults and small text file import / export helpers.
 */

/** RGB colour dialog (sliders + hex). */
@Composable
fun NbmColorDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var r by remember { mutableFloatStateOf(((initial shr 16) and 0xFF).toFloat()) }
    var g by remember { mutableFloatStateOf(((initial shr 8) and 0xFF).toFloat()) }
    var b by remember { mutableFloatStateOf((initial and 0xFF).toFloat()) }
    fun rgb() = (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    var hex by remember { mutableStateOf("%06X".format(initial and 0xFFFFFF)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nbm_custom_color)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(36.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF000000.toInt() or rgb())))
            listOf("R" to r, "G" to g, "B" to b).forEachIndexed { i, (l, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l, Modifier.width(20.dp))
                    Slider(v, { nv -> when (i) { 0 -> r = nv; 1 -> g = nv; else -> b = nv }; hex = "%06X".format(rgb()) }, Modifier.weight(1f), valueRange = 0f..255f)
                }
            }
            OutlinedTextField(hex, { t ->
                hex = t.filter { it.isLetterOrDigit() }.take(6).uppercase()
                hex.toIntOrNull(16)?.takeIf { hex.length == 6 }?.let { c -> r = ((c shr 16) and 0xFF).toFloat(); g = ((c shr 8) and 0xFF).toFloat(); b = (c and 0xFF).toFloat() }
            }, singleLine = true, prefix = { Text("#") }, label = { Text(stringResource(R.string.nbm_hex)) })
        }
    }, confirmButton = { Button(onClick = { onPick(rgb()) }) { Text(stringResource(R.string.nbm_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Preset swatches plus a "custom" swatch that opens [NbmColorDialog]. */
@Composable
fun NbmColorRow(presets: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f, fill = false)) { ColorChips(presets, selected, onSelect) }
        val isCustom = selected !in presets
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (isCustom) Color(0xFF000000.toInt() or selected) else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (isCustom) 3.dp else 1.dp, if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { custom = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Palette, stringResource(R.string.nbm_custom_color), Modifier.size(18.dp), tint = if (isCustom) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (custom) NbmColorDialog(selected, onDismiss = { custom = false }) { custom = false; onSelect(it) }
}

fun nbmFitLabel(f: DestFit) = when (f) {
    DestFit.XYZ -> R.string.e5_fit_xyz; DestFit.FIT -> R.string.e5_fit_page; DestFit.FITH -> R.string.e5_fit_width
    DestFit.FITV -> R.string.nbm_fit_height; DestFit.FITB -> R.string.nbm_fit_visible; DestFit.FITBH -> R.string.nbm_fit_visible_width; DestFit.FITR -> R.string.nbm_fit_rect
}

private fun fmt(v: Float?) = v?.let { if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, it) } ?: ""
private fun parse(t: String) = Digits.toWestern(t).replace(',', '.').toFloatOrNull()

/**
 * Fit choice with the numbers it uses: zoom % for XYZ, top for FitH / FitBH, left for FitV, a rectangle for FitR.
 * [pageW] / [pageH] give the default FitR rectangle (the whole page).
 */
@Composable
fun DestViewFields(view: DestView, pageW: Float = 595f, pageH: Float = 842f, onChange: (DestView) -> Unit) {
    var zoom by remember { mutableStateOf(if (view.zoom > 0f) (view.zoom * 100).toInt().toString() else "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceChips(DestFit.values().toList(), view.fit, { stringResource(nbmFitLabel(it)) }) { f ->
            onChange(if (f == DestFit.FITR && view.rect == null) view.copy(fit = f, rect = listOf(0f, 0f, pageW, pageH)) else view.copy(fit = f))
        }
        when (view.fit) {
            DestFit.XYZ -> OutlinedTextField(zoom, { t -> zoom = t.filter { it.isDigit() }.take(4); onChange(view.copy(zoom = zoom.toIntOrNull()?.let { it / 100f } ?: 0f)) },
                Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_zoom)) },
                placeholder = { Text(stringResource(R.string.e5_link_zoom_keep)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            DestFit.FITH, DestFit.FITBH -> NumField(stringResource(R.string.nbm_view_top), view.top) { onChange(view.copy(top = it)) }
            DestFit.FITV -> NumField(stringResource(R.string.nbm_view_left), view.left) { onChange(view.copy(left = it)) }
            DestFit.FITR -> {
                val r = view.rect ?: listOf(0f, 0f, pageW, pageH)
                Text(stringResource(R.string.nbm_view_rect_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.nbm_view_left, R.string.nbm_view_bottom, R.string.nbm_view_right, R.string.nbm_view_top).forEachIndexed { i, l ->
                        NumField(stringResource(l), r[i], Modifier.weight(1f)) { v -> onChange(view.copy(rect = r.toMutableList().also { it[i] = v ?: 0f })) }
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun NumField(label: String, value: Float?, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Float?) -> Unit) {
    var t by remember { mutableStateOf(fmt(value)) }
    OutlinedTextField(t, { s -> t = s; onChange(parse(s)) }, modifier, singleLine = true, label = { Text(label, maxLines = 1) },
        placeholder = { Text(stringResource(R.string.nbm_keep)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

/** What the add menu of [ActionListEditor] creates. */
private enum class NewAction(val label: Int) {
    PAGE(R.string.nbm_act_goto), URI(R.string.nbm_act_uri), REMOTE(R.string.nbm_act_remote), LAUNCH(R.string.nbm_act_launch),
    NAMED(R.string.nbm_act_named), JS(R.string.nbm_act_js), PRINT(R.string.nbm_act_print), EXTRACT(R.string.nbm_act_extract)
}

/** Label of an action for lists. */
@Composable
fun actionSummary(a: NavAction): String = when (a) {
    is NavAction.GoTo -> if (a.named.isNotBlank()) stringResource(R.string.nbm_sum_named_dest, a.named) else stringResource(R.string.e5_link_to_page, a.page + 1)
    is NavAction.Uri -> a.uri
    is NavAction.GoToR -> java.io.File(a.file).name + (if (a.named.isNotBlank()) " • " + a.named else " • " + stringResource(R.string.e5_link_to_page, a.page + 1))
    is NavAction.Launch -> java.io.File(a.file).name
    is NavAction.Named -> stringResource(R.string.nbm_act_named) + ": " + a.name
    is NavAction.Js -> NavActions.pageScript(a.script)?.let { (k, f, t) -> stringResource(if (k == "print") R.string.nbm_sum_print else R.string.nbm_sum_extract, f, t) }
        ?: (stringResource(R.string.nbm_act_js) + ": " + a.script.lineSequence().firstOrNull().orEmpty().take(40))
    is NavAction.Kept -> stringResource(R.string.nbm_act_kept, a.type)
}

/**
 * Editor of an action list (Nitro "Actions" tab, several actions run in order): add, edit in place, reorder,
 * delete. Action types the app does not edit are listed as kept and stay unchanged.
 */
@Composable
fun ActionListEditor(actions: List<NavAction>, pageCount: Int, onChange: (List<NavAction>) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (actions.isEmpty()) Text(stringResource(R.string.nbm_act_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().clickable(enabled = a !is NavAction.Kept) { editing = i }, verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium)
                Text(actionSummary(a), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { if (i > 0) onChange(actions.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.nbm_move_up)) }
                IconButton(onClick = { onChange(actions.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbm_act_add)) }
            DropdownMenu(menu, { menu = false }) {
                NewAction.values().forEach { n ->
                    DropdownMenuItem(text = { Text(stringResource(n.label)) }, onClick = {
                        menu = false
                        val a = when (n) {
                            NewAction.PAGE -> NavAction.GoTo(0, DestView(DestFit.FIT)); NewAction.URI -> NavAction.Uri("https://")
                            NewAction.REMOTE -> NavAction.GoToR(""); NewAction.LAUNCH -> NavAction.Launch("")
                            NewAction.NAMED -> NavAction.Named("NextPage"); NewAction.JS -> NavAction.Js("")
                            NewAction.PRINT -> NavAction.Js(NavActions.printScript(1, pageCount.coerceAtLeast(1)))
                            NewAction.EXTRACT -> NavAction.Js(NavActions.extractScript(1, pageCount.coerceAtLeast(1)))
                        }
                        onChange(actions + a); editing = actions.size
                    })
                }
            }
        }
    }
    val ei = editing
    val ea = ei?.let { actions.getOrNull(it) }
    if (ei != null && ea != null) ActionDialog(ea, pageCount, onDismiss = { editing = null }) { na -> editing = null; onChange(actions.toMutableList().also { it[ei] = na }) }
}

/** Named destinations of the document being edited, for the "go to named destination" pickers (FINAL_CHECK §16). */
val LocalDestNames = androidx.compose.runtime.staticCompositionLocalOf { emptyList<String>() }

/** Edit one action. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionDialog(initial: NavAction, pageCount: Int, onDismiss: () -> Unit, onSave: (NavAction) -> Unit) {
    var a by remember { mutableStateOf(initial) }
    val app = com.smartdocs.editor.LocalApp.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = runCatching { app.documents.localFile(uri) }.getOrNull() ?: return@launch
            a = when (val c = a) { is NavAction.GoToR -> c.copy(file = f.absolutePath); is NavAction.Launch -> c.copy(file = f.absolutePath); else -> c }
        }
    }
    val pageRange = (a as? NavAction.Js)?.let { NavActions.pageScript(it.script) }
    var from by remember { mutableStateOf(pageRange?.second?.toString() ?: "1") }
    var to by remember { mutableStateOf(pageRange?.third?.toString() ?: pageCount.toString()) }
    var page by remember { mutableStateOf(((initial as? NavAction.GoTo)?.page ?: (initial as? NavAction.GoToR)?.page ?: 0).plus(1).toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(actionSummary(initial).take(40)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val c = a) {
                is NavAction.GoTo -> {
                    OutlinedTextField(c.named, { a = c.copy(named = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_named_dest_opt)) })
                    // pick one of the document's named destinations instead of typing it
                    val names = LocalDestNames.current
                    if (names.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.take(40).forEach { n -> FilterChip(c.named == n, { a = c.copy(named = if (c.named == n) "" else n) }, label = { Text(n, maxLines = 1) }) }
                    }
                    if (c.named.isBlank()) {
                        OutlinedTextField(page, { page = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.e5_link_page_of, pageCount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        DestViewFields(c.view) { a = c.copy(view = it) }
                    }
                }
                is NavAction.Uri -> OutlinedTextField(c.uri, { a = c.copy(uri = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_link_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                is NavAction.GoToR -> {
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (c.file.isBlank()) stringResource(R.string.e5_link_pick_file) else java.io.File(c.file).name, maxLines = 1)
                    }
                    OutlinedTextField(c.named, { a = c.copy(named = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_named_dest_opt)) })
                    if (c.named.isBlank()) OutlinedTextField(page, { page = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.e5_link_page)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    SwitchRow(stringResource(R.string.e5_link_new_window), c.newWindow) { a = c.copy(newWindow = it) }
                }
                is NavAction.Launch -> {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (c.file.isBlank()) stringResource(R.string.e5_link_pick_file) else java.io.File(c.file).name, maxLines = 1)
                    }
                    Text(stringResource(R.string.e5_link_launch_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is NavAction.Named -> ChoiceChips(NavActions.NAMED, c.name, { namedLabel(it) }) { a = c.copy(name = it) }
                is NavAction.Js -> if (pageRange != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.nbm_from_page)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.nbm_to_page)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                } else {
                    OutlinedTextField(c.script, { a = c.copy(script = it) }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text(stringResource(R.string.nbm_act_js)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text(stringResource(R.string.nbm_js_store_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is NavAction.Kept -> Text(stringResource(R.string.nbm_act_kept, c.type))
            }
        }
    }, confirmButton = {
        Button(onClick = {
            val p = ((page.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
            onSave(when (val c = a) {
                is NavAction.GoTo -> c.copy(page = p.coerceAtMost((pageCount - 1).coerceAtLeast(0)))
                is NavAction.GoToR -> c.copy(page = p)
                is NavAction.Uri -> c.copy(uri = com.smartdocs.editor.core.edit5.LinkDetect.normalizeUrl(c.uri))
                is NavAction.Js -> if (pageRange != null) {
                    val f = (from.toIntOrNull() ?: 1).coerceIn(1, pageCount.coerceAtLeast(1)); val t = (to.toIntOrNull() ?: f).coerceIn(f, pageCount.coerceAtLeast(1))
                    NavAction.Js(if (pageRange.first == "print") NavActions.printScript(f, t) else NavActions.extractScript(f, t))
                } else c
                else -> c
            })
        }) { Text(stringResource(R.string.nbm_ok)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun namedLabel(n: String): String = stringResource(when (n) {
    "NextPage" -> R.string.nbm_named_next; "PrevPage" -> R.string.nbm_named_prev; "FirstPage" -> R.string.nbm_named_first; "LastPage" -> R.string.nbm_named_last
    "GoBack" -> R.string.nbm_named_back; "GoForward" -> R.string.nbm_named_forward; "Print" -> R.string.nbm_named_print; "Find" -> R.string.nbm_named_find
    "FullScreen" -> R.string.nbm_named_fullscreen; "SaveAs" -> R.string.nbm_named_saveas; "Close" -> R.string.nbm_named_close
    "ZoomViewIn" -> R.string.nbm_named_zoomin; "ZoomViewOut" -> R.string.nbm_named_zoomout; else -> R.string.nbm_act_named
})

// ---------------- link appearance defaults (Nitro "Use current appearance as new default") ----------------

object LinkDefaults {
    private const val PREFS = "nbm_link_defaults"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [spec] with the saved border, width, colour and highlight (unchanged when nothing was saved). */
    fun apply(c: Context, spec: LinkSpec): LinkSpec {
        val sp = p(c)
        if (!sp.contains("border")) return spec
        return spec.copy(
            border = runCatching { LinkBorder.valueOf(sp.getString("border", null)!!) }.getOrDefault(spec.border),
            borderWidth = sp.getFloat("width", spec.borderWidth), color = sp.getInt("color", spec.color), highlight = sp.getString("hl", spec.highlight) ?: spec.highlight,
        )
    }

    fun save(c: Context, s: LinkSpec) = p(c).edit().putString("border", s.border.name).putFloat("width", s.borderWidth).putInt("color", s.color).putString("hl", s.highlight).apply()
}

// ---------------- text files (XML / JSON) ----------------

/** Launcher that asks where to save a text file and writes the text [content] returns. */
@Composable
fun rememberTextExport(mime: String, content: () -> String): (String) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ok = stringResource(R.string.nbm_exported)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content().toByteArray(Charsets.UTF_8)) } ?: error("no stream") } }
            Toast.makeText(ctx, if (r.isSuccess) ok else (r.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
    return { name -> launcher.launch(name) }
}

/** Launcher that opens a text file and hands its bytes to [onOpened] (on the main thread). */
@Composable
fun rememberTextImport(mimes: Array<String>, onOpened: (ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }.getOrNull()
            if (bytes != null) onOpened(bytes) else Toast.makeText(ctx, ctx.getString(R.string.nbm_import_failed), Toast.LENGTH_LONG).show()
        }
    }
    return { launcher.launch(mimes) }
}
