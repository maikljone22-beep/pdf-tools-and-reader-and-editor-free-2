package com.smartdocs.editor.ui.forms

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms.Display
import com.smartdocs.editor.core.forms.FieldScripts
import com.smartdocs.editor.core.forms.FormCalc
import com.smartdocs.editor.core.forms.FormCalcModel
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.pdf.FormField
import com.smartdocs.editor.core.pdf.FormOps
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** AcroForm filler: list fields, edit values, save / reset / export / import / flatten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormsScreen(nav: NavHostController, uri: String?) {
    val writeBackForms = com.smartdocs.editor.ui.common.rememberWriteBack()
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()

    var file by remember { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }) }
    var fields by remember { mutableStateOf<List<FormField>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var result by remember { mutableStateOf<File?>(null) }
    // audit 5 T-09: the document the tool was opened on starts with the reader's password
    var password by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(file)) }
    var askPassword by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    // form calculations (Acrobat AF* scripts evaluated in Kotlin) and XFA state
    var calc by remember { mutableStateOf(FormCalcModel.EMPTY) }
    var xfa by remember { mutableStateOf(XfaSupport.Info(XfaSupport.Kind.NONE, 0)) }
    var xfaFields by remember { mutableStateOf<List<XfaSupport.XField>>(emptyList()) }
    val xfaEdits = remember { mutableStateMapOf<String, String>() }
    var flattenInfo by remember { mutableStateOf(false) }
    val computed by remember {
        derivedStateOf {
            val model = calc
            if (!model.hasCalculations) emptyMap<String, String>() else model.recalculate(fields.associate { it.name to (edits[it.name] ?: it.value) })
        }
    }
    val dynamicXfa = xfa.kind == XfaSupport.Kind.DYNAMIC

    fun reload() {
        val f = file ?: return
        scope.launch {
            loading = true; loaded = false; edits.clear(); xfaEdits.clear()
            try {
                fields = FormOps.listFields(f, password)
                calc = FormCalc.load(f, password)
                xfa = XfaSupport.detect(f, password)
                xfaFields = if (xfa.kind == XfaSupport.Kind.DYNAMIC) runCatching { XfaSupport.readFields(f, password) }.getOrDefault(emptyList()) else emptyList()
                loaded = true
            }
            catch (e: Exception) {
                if (e.message?.contains("password", true) == true || e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) askPassword = true
                else snack.showSnackbar(ctx.getString(R.string.ttcx_open_failed))      // audit 5 C-9: no raw English
            } finally { loading = false }
        }
    }
    LaunchedEffect(file) { reload() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> password = com.smartdocs.editor.core.pdf.SessionPasswords.get(f); file = f } }

    fun valueOf(f: FormField) = edits[f.name] ?: computed[f.name] ?: f.value

    /** Values to write (user edits + recalculated fields) and the formatted text for their appearances. */
    fun pending(): Pair<Map<String, String>, Map<String, String>> {
        val values = LinkedHashMap<String, String>(edits)
        computed.forEach { (k, v) -> values[k] = v }
        val all = fields.associate { it.name to (values[it.name] ?: it.value) }
        val display = HashMap(calc.displayMap(all).filterKeys { it in values || calc.isCalculated(it) })
        computed.keys.forEach { k -> if (k !in display) display[k] = all[k].orEmpty() }
        return values to display
    }
    val pendingCount = edits.size + computed.keys.count { it !in edits }
    val savingMsg = stringResource(R.string.pg_form_saving)
    val processing = stringResource(R.string.processing)

    fun save(overwrite: Boolean) {
        val src = file ?: return
        val (values, display) = pending()
        val xValues = xfaEdits.toMap()
        val dropXfa = xfa.kind == XfaSupport.Kind.STATIC
        val dyn = dynamicXfa
        runner.run(savingMsg) {
            val out = if (overwrite) app.documents.newTemp("form") else app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_filled")
            try {
                if (dyn) XfaSupport.saveValues(src, xValues, out, password)
                else FormOps.fillFields(src, values, out, password, ctx, display, dropXfa)
            } catch (e: Exception) { out.delete(); throw e }
            if (overwrite) {
                // audit 5 C-3: never `out.copyTo(src)` (not atomic, no backup: a kill mid-copy truncated the original).
                // replaceOriginal keeps a trash copy and swaps atomically; the central signed-file gate lives there
                try { app.documents.replaceOriginal(src, out, password) } finally { out.delete() }
                runCatching { app.documents.touchRecent(src) }; src
            } else {
                runCatching { app.documents.touchRecent(out) }
                // the filled form is the source transformed: "Save in original" may replace it (audit 5 E-1 / C-2)
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            }
        }
    }
    LaunchedEffect(runner.result) { val r = runner.result; if (r != null && r == file) writeBackForms(r) }
    fun reset() {
        val src = file ?: return
        runner.run(processing) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_reset")
            FormOps.resetAll(src, out, password); com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
        }
    }
    fun flatten() {
        val src = file ?: return
        if (dynamicXfa) {
            // the XFA layout is generated as an AcroForm (the reader's in-page XFA form) and that copy is flattened
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_flat")
                val tmp = app.documents.newTemp("xfagen")
                try {
                    val withValues = if (xfaEdits.isNotEmpty()) app.documents.newTemp("xfavals").also { XfaSupport.saveValues(src, xfaEdits.toMap(), it, password) } else src
                    try { com.smartdocs.editor.core.forms5.XfaGenerated.generate(ctx, withValues, if (withValues == src) password else null, tmp) } finally { if (withValues != src) withValues.delete() }
                    PdfOps.flattenForms(tmp, out)
                } finally { tmp.delete() }
                com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
            }
            return
        }
        val (values, display) = pending()
        val dropXfa = xfa.kind == XfaSupport.Kind.STATIC
        runner.run(processing) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_flat")
            // apply pending edits first so the flattened copy carries them
            val tmp = if (values.isNotEmpty() || dropXfa) app.documents.newTemp("formflat").also { FormOps.fillFields(src, values, it, password, ctx, display, dropXfa) } else src
            PdfOps.flattenForms(tmp, out, password)
            if (tmp != src) tmp.delete()
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
        }
    }
    fun dropXfa() {
        val src = file ?: return
        runner.run(processing) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_acroform")
            FormOps.fillFields(src, emptyMap(), out, password, ctx, emptyMap(), true)
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
        }
    }
    fun openInReader() { file?.let { f -> nav.navigate(com.smartdocs.editor.ui.Routes.reader(Uri.fromFile(f).toString(), 0)) } }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null; if (it == file) reload() } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }

    // JSON export / import of values
    val exportedMsg = stringResource(R.string.pg_form_exported)
    val importFailed = stringResource(R.string.pg_form_import_failed)
    val exportFailed = stringResource(R.string.ttcx_export_failed)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch {
            val json = buildJsonObject { fields.forEach { f -> put(f.name, JsonPrimitive(valueOf(f))) } }.toString()
            // audit 5 C-26: a failed write (or no stream) is reported, not shown as "exported"
            val ok = withContext(Dispatchers.IO) { runCatching { (ctx.contentResolver.openOutputStream(u, "wt") ?: error("no stream")).use { it.write(json.toByteArray()) } }.isSuccess }
            snack.showSnackbar(if (ok) exportedMsg else exportFailed)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    Json.parseToJsonElement(text).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.content }.getOrDefault(v.toString()) }
                }.getOrNull()
            }
            if (parsed == null) snack.showSnackbar(importFailed)
            else {
                val names = fields.map { it.name }.toSet()
                var n = 0
                parsed.forEach { (k, v) -> if (k in names) { edits[k] = v; n++ } }
                snack.showSnackbar(ctx.getString(R.string.pg_form_imported, n))
            }
        }
    }

    var dataFormat by remember { mutableStateOf(com.smartdocs.editor.core.formsx.DataFormat.FDF) }
    var dataMenu by remember { mutableStateOf(false) }
    val dataExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { u ->
        val src = file
        if (u != null && src != null) scope.launch {
            val (values, display) = pending()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // pending edits are applied to a temp copy first so the export carries them
                    val tmp = if (values.isNotEmpty()) app.documents.newTemp("formdata").also { FormOps.fillFields(src, values, it, password, ctx, display, false) } else src
                    try {
                        PdfOps.load(tmp, if (tmp == src) password else password).use { d ->
                            (ctx.contentResolver.openOutputStream(u, "wt") ?: error("no stream")).use { o -> com.smartdocs.editor.core.formsx.FormData.export(d, dataFormat, o, src.name) }
                        }
                    } finally { if (tmp != src) tmp.delete() }
                }.isSuccess
            }
            // audit 5 C-26: a failed DATA EXPORT said "import failed"
            snack.showSnackbar(if (ok) exportedMsg else exportFailed)
        }
    }
    val dataImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                    val name = com.smartdocs.editor.ui.forms5.displayName(ctx, u)
                    val fmt = com.smartdocs.editor.core.formsx.FormData.detect(name, bytes.copyOf(minOf(bytes.size, 512))) ?: error("format")
                    com.smartdocs.editor.core.formsx.FormData.parse(bytes, fmt)
                }.getOrNull()
            }
            if (parsed == null) snack.showSnackbar(importFailed)
            else {
                val byName = fields.associateBy { it.name }
                var n = 0
                parsed.forEach { (k, v) ->
                    val f = byName[k] ?: return@forEach
                    val nv = when (f.type) {
                        FieldType.CHECKBOX -> (!(v.isBlank() || v == "Off" || v.equals("false", true))).toString()
                        FieldType.RADIO -> if (v == "Off") "" else v
                        FieldType.BUTTON, FieldType.SIGNATURE -> return@forEach
                        else -> v
                    }
                    if (nv == f.value) edits.remove(k) else edits[k] = nv
                    n++
                }
                snack.showSnackbar(ctx.getString(R.string.pg_form_imported, n))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.pg_form_title), onBack = { nav.popBackStack() }) {
            if (loaded && (fields.isNotEmpty() || xfaFields.isNotEmpty())) {
                if (!dynamicXfa) IconButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) }) { Icon(Icons.Default.FileDownload, stringResource(R.string.form_import)) }
                if (!dynamicXfa) IconButton(onClick = { exporter.launch((file?.nameWithoutExtension ?: "form") + ".json") }) { Icon(Icons.Default.FileUpload, stringResource(R.string.form_export)) }
                if (!dynamicXfa) Box {
                    IconButton(onClick = { dataMenu = true }) { Icon(Icons.Default.ImportExport, stringResource(R.string.f8_data_menu)) }
                    DropdownMenu(dataMenu, { dataMenu = false }) {
                        listOf(com.smartdocs.editor.core.formsx.DataFormat.FDF, com.smartdocs.editor.core.formsx.DataFormat.XFDF, com.smartdocs.editor.core.formsx.DataFormat.XML,
                            com.smartdocs.editor.core.formsx.DataFormat.CSV).forEach { fm ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.f8_export_as, fm.ext.uppercase())) }, onClick = {
                                dataMenu = false; dataFormat = fm; dataExporter.launch((file?.nameWithoutExtension ?: "form") + "." + fm.ext)
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.f8_import_data)) }, onClick = { dataMenu = false; dataImporter.launch(arrayOf("*/*")) })
                    }
                }
                Box {
                    IconButton(onClick = { saveMenu = true }) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                    DropdownMenu(saveMenu, { saveMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.save)) }, enabled = pendingCount > 0 || xfaEdits.isNotEmpty(), onClick = { saveMenu = false; save(true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_as)) }, onClick = { saveMenu = false; save(false) })
                        HorizontalDivider()
                        if (!dynamicXfa) DropdownMenuItem(text = { Text(stringResource(R.string.form_reset)) }, onClick = { saveMenu = false; reset() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pg_form_flatten)) }, onClick = { saveMenu = false; flatten() })
                    }
                }
            }
        } },
        bottomBar = {
            if (loaded && (fields.isNotEmpty() || xfaFields.isNotEmpty())) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val changes = if (dynamicXfa) xfaEdits.size else pendingCount
                    val total = if (dynamicXfa) xfaFields.size else fields.size
                    Text(if (changes == 0) stringResource(R.string.form_fields) + ": $total" else stringResource(R.string.pg_form_modified, changes), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { flatten() }) { Text(stringResource(R.string.pg_form_flatten)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { save(false) }, enabled = !runner.running) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save_as)) }
                }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth > 700.dp
            LazyColumn(if (tablet) Modifier.widthIn(max = 760.dp).fillMaxHeight().align(Alignment.TopCenter) else Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item { FilePickRow(file, stringResource(R.string.open_file), pick); Spacer(Modifier.height(12.dp)) }
                if (loaded && xfa.kind != XfaSupport.Kind.NONE) item(key = "xfa_banner") { XfaBanner(xfa, onFlatten = { flatten() }, onOpenReader = { openInReader() }, onDropXfa = { dropXfa() }) }
                if (loaded && calc.hasCalculations && !dynamicXfa) item(key = "calc_note") {
                    Text(stringResource(R.string.ps_calc_note, calc.order.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (loading) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.loading)) } }
                else if (loaded && dynamicXfa) {
                    if (xfaFields.isEmpty()) item { EmptyState(Icons.Default.Checklist, stringResource(R.string.ps_xfa_no_fields)) }
                    else items(xfaFields, key = { "x:" + it.path }) { xf ->
                        XfaFieldEditor(xf, xfaEdits[xf.path] ?: xf.value, edited = xf.path in xfaEdits) { v -> if (v == xf.value) xfaEdits.remove(xf.path) else xfaEdits[xf.path] = v }
                    }
                }
                else if (loaded && fields.isEmpty()) item { EmptyState(Icons.Default.Checklist, stringResource(R.string.form_no_fields)) }
                else {
                    val byPage = fields.groupBy { it.page }
                    byPage.keys.sorted().forEach { pg ->
                        item(key = "h$pg") { SectionTitle(if (pg >= 0) stringResource(R.string.pg_page_n, pg + 1) else stringResource(R.string.form_fields)) }
                        items(byPage[pg]!!, key = { it.name + "#" + it.page }) { f ->
                            val sc = calc.scripts[f.name]
                            val v = valueOf(f)
                            FieldEditor(f, v, edited = f.name in edits || f.name in computed, scripts = sc, display = if (sc?.format != null) calc.display(f.name, v) else null,
                                onChange = { nv -> if (nv == f.value) edits.remove(f.name) else edits[f.name] = nv })
                        }
                    }
                }
            }
        }
    }

    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }
    if (flattenInfo) XfaFlattenDialog { flattenInfo = false }
    if (askPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { askPassword = false }, title = { Text(stringResource(R.string.password_enter)) },
            text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.password)) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) },
            confirmButton = { TextButton(onClick = { password = pw; askPassword = false; reload() }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun typeLabel(t: FieldType): String = stringResource(when (t) {
    FieldType.TEXT -> R.string.pg_form_type_text; FieldType.CHECKBOX -> R.string.pg_form_type_check; FieldType.RADIO -> R.string.pg_form_type_radio
    FieldType.COMBO -> R.string.pg_form_type_combo; FieldType.LIST -> R.string.pg_form_type_list; FieldType.SIGNATURE -> R.string.pg_form_type_signature
    FieldType.BUTTON -> R.string.pg_form_type_button; FieldType.UNKNOWN -> R.string.form_fields
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditor(f: FormField, value: String, edited: Boolean, scripts: FieldScripts? = null, display: Display? = null, onChange: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if (edited) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.label.ifBlank { f.name }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(typeLabel(f.type), style = MaterialTheme.typography.labelSmall) }, enabled = false)
                if (f.readOnly) Text(" · " + stringResource(R.string.pg_form_read_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(6.dp))
            when (f.type) {
                FieldType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = value.equals("true", true), onCheckedChange = { onChange(it.toString()) }, enabled = !f.readOnly)
                    Spacer(Modifier.width(8.dp)); Text(if (value.equals("true", true)) stringResource(R.string.yes) else stringResource(R.string.no))
                }
                FieldType.RADIO -> Column {
                    f.options.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = value == opt, onClick = { onChange(opt) }, enabled = !f.readOnly); Text(opt) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = value.isBlank() || value == "Off", onClick = { onChange("") }, enabled = !f.readOnly); Text(stringResource(R.string.pg_form_none_option)) }
                }
                FieldType.COMBO, FieldType.LIST -> {
                    var open by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (!f.readOnly) open = it }) {
                        OutlinedTextField(value, { if (f.type == FieldType.COMBO) onChange(it) }, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable), readOnly = f.type == FieldType.LIST || f.readOnly, singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
                        ExposedDropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pg_form_none_option)) }, onClick = { onChange(""); open = false })
                            f.options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); open = false }) }
                        }
                    }
                }
                FieldType.SIGNATURE -> Text(if (f.signed) stringResource(R.string.pg_form_signed) else stringResource(R.string.pg_form_unsigned), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FieldType.BUTTON -> Text(f.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> if (scripts?.calc != null) CalculatedValue(display ?: Display(value), unsupported = false)
                    else ScriptedTextField(value, onChange, scripts, display, f.readOnly, f.multiline)
            }
        }
    }
}
