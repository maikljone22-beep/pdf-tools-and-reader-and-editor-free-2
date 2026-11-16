package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.lifecycle.viewModelScope
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.batch.*
import com.smartdocs.editor.core.pdf.*
import com.smartdocs.editor.core.redact.*
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.*
import com.smartdocs.editor.ui.tools.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private fun initialStep(tool: String): StepType = when (tool) {
    "compress", "optimize" -> StepType.OPTIMIZE
    "rotate" -> StepType.ROTATE
    "watermark", "e5_watermark" -> StepType.WATERMARK
    "flatten" -> StepType.FLATTEN
    "ocr", "ocr_studio" -> StepType.OCR
    "convert", "convert_hub" -> StepType.CONVERT_OFFICE
    "redact" -> StepType.REDACT
    "sanitize" -> StepType.SANITIZE
    "e5_header_footer" -> StepType.HEADER_FOOTER
    "page_numbers" -> StepType.PAGE_NUMBERS
    else -> StepType.PROTECT
}

class BulkJobViewModel : androidx.lifecycle.ViewModel() {
    val batch = FileBatch(viewModelScope)
    val passwords = ConcurrentHashMap<String, String>()
    val passwordFields = mutableStateMapOf<String, String>()
    val generatedOwner = mutableStateOf<String?>(null)
}

/** Shared multi-file configuration and sequential processing page for file-based tool operations. */
@Composable
fun BulkToolsScreen(nav: NavHostController, uri: String?, initialTool: String) {
    val app = LocalApp.current
    val ctx = LocalContext.current.applicationContext
    val jobViewModel: BulkJobViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val batch = jobViewModel.batch
    val state by batch.state.collectAsState()
    val preset = rememberInputFile(uri)
    val incomingFiles = remember { nav.previousBackStackEntry?.savedStateHandle?.remove<ArrayList<String>>("bulk_files")?.map(::File).orEmpty() }
    var files by rememberSaveable(stateSaver = SharedFileListSaver) { mutableStateOf((listOfNotNull(preset.value) + incomingFiles).distinctBy { it.absolutePath }) }
    val passwords = jobViewModel.passwords
    var commonPassword by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(Step(initialStep(initialTool))) }
    var operationChosen by remember { mutableStateOf(initialTool in setOf("compress", "optimize", "rotate", "watermark", "e5_watermark", "flatten", "ocr", "ocr_studio", "convert", "convert_hub", "redact", "sanitize", "e5_header_footer", "page_numbers", "protect", "protect_advanced", "unlock", "e5_links")) }
    var unlocking by remember { mutableStateOf(initialTool == "unlock") }
    var linkMode by remember { mutableStateOf(initialTool == "e5_links") }
    var linkSpec by remember { mutableStateOf(com.smartdocs.editor.core.edit5.BulkLinkSpec()) }
    var linkSettings by remember { mutableStateOf(false) }
    var pageInfos by remember { mutableStateOf<List<com.smartdocs.editor.core.edit5.PageInfo>>(emptyList()) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(files.firstOrNull(), commonPassword, linkSettings) {
        if (linkSettings) {
            pageInfos = emptyList(); settingsError = null
            val src = files.firstOrNull()
            if (src != null) try { pageInfos = com.smartdocs.editor.core.edit5.PageBoxOps.pageInfos(src, commonPassword.takeIf { it.isNotEmpty() } ?: SessionPasswords.get(src)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { settingsError = ctx.getString(R.string.password_wrong); linkSettings = false }
        }
    }
    var processing by remember { mutableStateOf(batch.state.value.files.isNotEmpty()) }
    var selectedResult by remember { mutableStateOf<File?>(null) }
    var openPassword by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    var restrict by remember { mutableStateOf(false) }
    var perms by remember { mutableStateOf(Permissions()) }
    var algorithm by remember { mutableStateOf(EncAlgo.AES_256) }
    var encScope by remember { mutableStateOf(EncScope.ALL) }
    var generatedOwner by jobViewModel.generatedOwner
    var confirmLeave by remember { mutableStateOf(false) }
    val picked = rememberPdfPicker(multiple = true, mimes = arrayOf("application/pdf", "image/*", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
        files = (files + it).distinctBy { f -> f.absolutePath }
    }
    val wrong = stringResource(R.string.password_wrong)
    val failed = stringResource(R.string.error_generic)
    batch.errorMessage = { e -> when { e is OutOfMemoryError -> ctx.getString(R.string.tttl_out_of_memory); isPasswordError(e) -> wrong; else -> e.message ?: failed } }
    val title = stringResource(R.string.bulk_title)
    val recipes = remember { RecipeStore.all(ctx) }
    fun leave() { if (state.running) confirmLeave = true else processing = false }
    if (processing) {
        androidx.activity.compose.BackHandler { leave() }
        Scaffold(topBar = { AppTopBar(title, onBack = ::leave) }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val completed = state.files.count { it.status == FileTaskStatus.COMPLETED }
                Text(stringResource(R.string.bulk_count, completed, state.files.size))
                LinearProgressIndicator(progress = { if (state.files.isEmpty()) 0f else state.files.count { it.status in setOf(FileTaskStatus.COMPLETED, FileTaskStatus.FAILED) }.toFloat() / state.files.size }, modifier = Modifier.fillMaxWidth())
                if (state.running) Button(onClick = { batch.stop() }) { Text(stringResource(R.string.bulk_stop)) }
                else {
                    OutlinedButton(onClick = { batch.resume() }, enabled = state.files.any { it.status in setOf(FileTaskStatus.WAITING, FileTaskStatus.STOPPED) }) { Text(stringResource(R.string.bulk_resume)) }
                    OutlinedButton(onClick = { batch.retryFailed() }, enabled = state.files.any { it.status == FileTaskStatus.FAILED }) { Text(stringResource(R.string.bulk_retry_failed)) }
                }
                generatedOwner?.let { owner ->
                    Text(stringResource(R.string.ttcx_owner_generated))
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(owner) }
                }
                state.files.forEachIndexed { index, item ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Row {
                            com.smartdocs.editor.ui.home.ThumbBox(com.smartdocs.editor.ui.home.rememberFileThumb(item.input), 40.dp, 48.dp, file = item.input)
                            Text(item.input.name, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(stringResource(when (item.status) {
                            FileTaskStatus.WAITING -> R.string.bulk_waiting; FileTaskStatus.RUNNING -> R.string.bulk_running
                            FileTaskStatus.COMPLETED -> R.string.bulk_completed; FileTaskStatus.FAILED -> R.string.bulk_failed; FileTaskStatus.STOPPED -> R.string.bulk_stopped
                        }))
                        if (item.status == FileTaskStatus.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (item.status == FileTaskStatus.FAILED || item.status == FileTaskStatus.STOPPED) {
                            OutlinedTextField(jobViewModel.passwordFields[item.input.absolutePath].orEmpty(), { passwords[item.input.absolutePath] = it; jobViewModel.passwordFields[item.input.absolutePath] = it },
                                label = { Text(stringResource(R.string.bulk_current_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.running)
                            TextButton(onClick = { batch.retry(index) }, enabled = !state.running) { Text(stringResource(R.string.tte5_retry)) }
                        }
                        item.outputs.forEach { output ->
                            TextButton(onClick = { selectedResult = output }) { Icon(Icons.Default.OpenInNew, null); Text(output.name) }
                        }
                    } }
                }
                val finished = state.files.flatMap { it.outputs }
                ResultsList(finished, nav, if (finished.all { it.extension.equals("pdf", true) }) "application/pdf" else "*/*")
            }
        }
        if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.bulk_stop)) },
            text = { Text(stringResource(R.string.bulk_leave_hint)) },
            confirmButton = { TextButton(onClick = { batch.stop(); confirmLeave = false }) { Text(stringResource(R.string.bulk_stop)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } })
        selectedResult?.let { ResultSheet(it, nav, onDismiss = { selectedResult = null }) }
        return
    }
    ToolScaffold(title, nav) {
        Text(stringResource(R.string.bulk_hint))
        OutlinedButton(onClick = picked, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.add_files)) }
        files.forEach { f ->
            Row {
                com.smartdocs.editor.ui.home.ThumbBox(com.smartdocs.editor.ui.home.rememberFileThumb(f), 40.dp, 48.dp, file = f)
                Text(f.name, Modifier.weight(1f).padding(8.dp))
                IconButton(onClick = { files = files - f }) { Icon(Icons.Default.Close, stringResource(R.string.tl_remove)) }
            }
        }
        OutlinedTextField(commonPassword, { commonPassword = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.bulk_current_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        com.smartdocs.editor.ui.tools.ChoiceChips(listOf(false, true), unlocking, { stringResource(if (it) R.string.remove_security else R.string.bulk_apply_tool) }) { unlocking = it; operationChosen = true }
        if (!unlocking) {
            var operations by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { operations = true }) { Text(if (!operationChosen) stringResource(R.string.bulk_choose_operation) else if (linkMode) stringResource(R.string.bulk_links_title) else step.type.label()) }
            if (operations) AlertDialog(onDismissRequest = { operations = false }, title = { Text(stringResource(R.string.bulk_apply_tool)) },
                text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { linkMode = true; operationChosen = true; operations = false }) { Text(stringResource(R.string.bulk_links_title)) }
                    StepType.entries.filter { !it.passive && it != StepType.RUN_RECIPE }.forEach { type ->
                        TextButton(onClick = { step = Step(type); linkMode = false; operationChosen = true; operations = false }) { Text(type.label()) }
                    }
                } }, confirmButton = { TextButton(onClick = { operations = false }) { Text(stringResource(R.string.cancel)) } })
            if (!operationChosen) Text(stringResource(R.string.bulk_choose_operation))
            else if (linkMode) {
                OutlinedButton(onClick = { linkSettings = true }, enabled = files.isNotEmpty()) { Text(stringResource(R.string.bulk_apply_tool)) }
                settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (linkSettings && pageInfos.isNotEmpty()) com.smartdocs.editor.ui.edit5.BulkLinksSheet(files.first(), commonPassword.takeIf { it.isNotEmpty() } ?: SessionPasswords.get(files.first()), pageInfos, 0, linkSpec,
                    onDismiss = { linkSettings = false }) { linkSpec = it; linkSettings = false }
            } else if (step.type == StepType.PROTECT) {
                OutlinedTextField(openPassword, { openPassword = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password_open)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(ownerPassword, { ownerPassword = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password_permissions)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                ChoiceChips(EncAlgo.entries, algorithm, { it.name.replace('_', '-') }) { algorithm = it; if (!it.aes) encScope = EncScope.ALL }
                ChoiceChips(EncScope.entries.filter { it == EncScope.ALL || algorithm.aes }, encScope, { stringResource(when (it) { EncScope.ALL -> R.string.s5_scope_all; EncScope.ALL_BUT_METADATA -> R.string.s5_scope_meta; EncScope.ATTACHMENTS_ONLY -> R.string.s5_scope_att }) }) { encScope = it }
                Row { Text(stringResource(R.string.s5_restrict), Modifier.weight(1f)); Switch(restrict, { restrict = it }) }
                if (restrict) {
                    listOf(R.string.perm_print to perms.print, R.string.perm_copy to perms.copy, R.string.perm_modify to perms.modify, R.string.perm_annotate to perms.annotate, R.string.perm_fill_forms to perms.fillForms, R.string.s5_perm_assemble to perms.assemble, R.string.s5_perm_access to perms.accessibility, R.string.s5_print_high to perms.printHigh).forEachIndexed { i, (label, on) ->
                        Row { Text(stringResource(label), Modifier.weight(1f)); Switch(on, { value -> perms = when (i) {
                            0 -> perms.withPrint(if (value) PrintLevel.HIGH else PrintLevel.NONE); 1 -> perms.copy(copy = value)
                            2 -> perms.copy(modify = value); 3 -> perms.copy(annotate = value); 4 -> perms.copy(fillForms = value)
                            5 -> perms.copy(assemble = value); 6 -> perms.copy(accessibility = value); else -> perms.copy(printHigh = value && perms.print)
                        } }) }
                    }
                }
            } else step.type.defaults.keys.forEach { key -> StepParam(step, key, recipes, "", onChange = { step = it }) }
        }
        val options = ProtectOptions(openPassword, ownerPassword, algorithm, if (restrict) perms else Permissions(), encScope)
        val protectionValid = openPassword.isNotEmpty() || (restrict && perms != Permissions())
        val validLink = when (linkSpec.action) { com.smartdocs.editor.core.edit5.BulkLinkAction.REPLACE_TARGET -> linkSpec.target.isNotBlank(); com.smartdocs.editor.core.edit5.BulkLinkAction.REPLACE_WITH_TEXT -> linkSpec.text.isNotBlank(); else -> linkSpec.text.isNotBlank() || linkSpec.target.isNotBlank() }
        val valid = if (linkMode && !unlocking) validLink else unlocking || step.type != StepType.PROTECT || (protectionValid && ProtectAdvanced.passwordsFit(options) && (ownerPassword.isEmpty() || ownerPassword != openPassword) && (encScope != EncScope.ATTACHMENTS_ONLY || openPassword.isNotEmpty()))
        PrimaryButton(stringResource(R.string.bulk_start), enabled = files.isNotEmpty() && operationChosen && valid) {
            if (processing || batch.state.value.running) return@PrimaryButton
            val chosenLinks = linkSpec.takeIf { linkMode && !unlocking }
            val inputs = files.toList(); val chosen = step; val remove = unlocking; val fallbackPassword = commonPassword
            val protectOptions = if (chosenLinks == null && chosen.type == StepType.PROTECT && !remove) options.copy(ownerPassword = ProtectAdvanced.ownerFor(options)) else options
            generatedOwner = if (chosenLinks == null && !remove && chosen.type == StepType.PROTECT && ownerPassword.isEmpty()) protectOptions.ownerPassword else null
            val work = app.documents.newTemp("bulk_work").let { File(it.parentFile, it.nameWithoutExtension).apply { mkdirs() } }
            val recipeRunner = RecipeRunner(ctx, work)
            processing = true
            batch.start(inputs) { src ->
                val pw = passwords[src.absolutePath] ?: fallbackPassword.takeIf { it.isNotEmpty() } ?: SessionPasswords.get(src)
                if (chosenLinks != null) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, "links"))
                    try { com.smartdocs.editor.core.edit5.BulkLinkOps.apply(ctx, src, out, pw, chosenLinks); ToolOutputs.record(out, src); listOf(out) }
                    catch (e: Throwable) { out.delete(); throw e }
                } else if (remove || chosen.type == StepType.PROTECT) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, outName(src, if (remove) "unlocked" else "protected"))
                    try {
                        if (remove) PdfOps.removeSecurity(src, pw.orEmpty(), out)
                        else {
                            val inspected = ProtectAdvanced.inspectSource(src, pw)
                            require(!com.smartdocs.editor.core.security.ReaderRestrictions.restricted(inspected.security)) { ctx.getString(R.string.np2_change_needs_owner) }
                            ProtectAdvanced.protect(src, out, pw, protectOptions)
                        }
                        ToolOutputs.record(out, src)
                        listOf(out)
                    } catch (e: Throwable) { out.delete(); throw e }
                } else {
                    val recipe = Recipe("bulk", chosen.type.name.lowercase(), listOf(chosen))
                    val result = recipeRunner.run(src, pw, recipe, recipe.name, app.documents.docsDir, inputs.indexOf(src), conflict = Conflict.RENAME)
                    check(result.ok) { result.message }
                    listOf(File(requireNotNull(result.output)))
                }
            }
        }
    }
}
