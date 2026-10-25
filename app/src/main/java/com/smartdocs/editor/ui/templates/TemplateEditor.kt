package com.smartdocs.editor.ui.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.templates.TemplateDefaults
import com.smartdocs.editor.core.templates.TemplateField
import com.smartdocs.editor.core.templates.TemplateInput
import com.smartdocs.editor.core.templates.TemplateKind
import com.smartdocs.editor.core.templates.TemplateRenderer
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.FieldLabel
import com.smartdocs.editor.ui.tools.NumberField
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val accents = listOf(0x1565C0, 0x00695C, 0x6A1B9A, 0xC62828, 0xEF6C00, 0x37474F)
private val sizes = listOf("A4" to PDRectangle.A4, "A5" to PDRectangle.A5, "Letter" to PDRectangle.LETTER)

/** Organisation details reused between templates (letterhead, invoice, exam …). */
private class TemplatePrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("psf_templates", Context.MODE_PRIVATE)
    val logoFile = File(ctx.filesDir, "template_logo.png")
    var org: String get() = p.getString("org", "") ?: ""; set(v) = p.edit().putString("org", v).apply()
    var subtitle: String get() = p.getString("subtitle", "") ?: ""; set(v) = p.edit().putString("subtitle", v).apply()
    var footer: String get() = p.getString("footer", "") ?: ""; set(v) = p.edit().putString("footer", v).apply()
    var accent: Int get() = p.getInt("accent", 0x1565C0); set(v) = p.edit().putInt("accent", v).apply()
}

@Composable
internal fun TemplateEditor(nav: NavHostController, kind: TemplateKind, onBack: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val prefs = remember { TemplatePrefs(ctx) }
    val runner = rememberToolRunner()
    val inputs = kind.inputs
    var title by remember(kind) { mutableStateOf(TemplateDefaults.title(kind)) }
    var org by remember { mutableStateOf(prefs.org) }
    var subtitle by remember(kind) { mutableStateOf(if (kind == TemplateKind.LETTER || kind == TemplateKind.INVOICE) prefs.subtitle else "") }
    var body by remember(kind) { mutableStateOf("") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())) }
    var footer by remember { mutableStateOf(prefs.footer) }
    var currency by remember { mutableStateOf("") }
    var pages by remember(kind) { mutableStateOf("1") }
    var rows by remember(kind) { mutableStateOf(kind.defaultRows.takeIf { it > 0 }?.toString() ?: "") }
    var accent by remember { mutableIntStateOf(prefs.accent) }
    var landscape by remember(kind) { mutableStateOf(kind.landscapeDefault) }
    var size by remember { mutableStateOf("A4") }
    var withFields by remember(kind) { mutableStateOf(kind.hasFields) }
    var logo by remember { mutableStateOf<Bitmap?>(null) }
    var fileName by remember(kind) { mutableStateOf("") }
    LaunchedEffect(Unit) { if (prefs.logoFile.exists()) logo = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(prefs.logoFile.absolutePath) } }
    val pickLogo = rememberImagePicker(multiple = false) { files ->
        val f = files.firstOrNull() ?: return@rememberImagePicker
        val bmp = PdfOps.decodeScaled(f, 600)
        if (bmp != null) { logo = bmp; runCatching { prefs.logoFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    }

    val input = TemplateInput(
        title = title.trim(), org = org.trim(), subtitle = subtitle.trim(), body = body.trim(), date = date.trim(), footer = footer.trim(),
        currency = currency.trim(), logo = if (TemplateField.LOGO in inputs) logo else null,
        pages = (pages.toIntOrNull() ?: 1).coerceIn(1, 50),
        rows = rows.toIntOrNull()?.coerceIn(kind.rowsRange) ?: 0,
        accent = accent, landscape = landscape, pageSize = sizes.first { it.first == size }.second, withFields = withFields,
    )

    // live preview (debounced)
    var preview by remember(kind) { mutableStateOf<Bitmap?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    LaunchedEffect(input) {
        delay(450)
        previewBusy = true
        preview = runCatching { TemplateThumbs.render(ctx, kind, input.copy(pages = 1), 720) }.getOrNull() ?: preview
        previewBusy = false
    }

    val creating = stringResource(R.string.ps_templates_creating)
    fun create() {
        prefs.org = org.trim(); prefs.footer = footer.trim(); prefs.accent = accent
        if (kind == TemplateKind.LETTER || kind == TemplateKind.INVOICE) prefs.subtitle = subtitle.trim()
        val inp = input
        val name = fileName.trim().ifBlank { title.trim().ifBlank { kind.id } }.replace(Regex("""[\\/:*?"<>|]"""), "_")
        runner.run(creating) {
            val out = app.documents.uniqueFile(app.documents.docsDir, name)
            TemplateRenderer.render(ctx, kind, inp, out)
            runCatching { app.documents.touchRecent(out) }
            out
        }
    }

    Scaffold(topBar = { AppTopBar(templateName(kind), onBack = onBack) }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth > 700.dp
            val previewPane: @Composable (Modifier) -> Unit = { m -> PreviewPane(preview, previewBusy, landscape, m) }
            val formPane: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!tablet) previewPane(Modifier.fillMaxWidth().heightIn(max = 360.dp))
                    Text(templateDescription(kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (TemplateField.TITLE in inputs) OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(titleLabel(kind))) })
                    if (TemplateField.ORG in inputs) OutlinedTextField(org, { org = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(if (kind == TemplateKind.EXAM || kind == TemplateKind.TIMETABLE) R.string.ps_tpl_in_school else R.string.ps_tpl_in_org)) },
                        singleLine = kind != TemplateKind.EXAM, minLines = if (kind == TemplateKind.EXAM) 2 else 1)
                    if (TemplateField.SUBTITLE in inputs) OutlinedTextField(subtitle, { subtitle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(subtitleLabel(kind))) })
                    if (TemplateField.BODY in inputs) OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(if (kind == TemplateKind.EXAM) R.string.ps_tpl_in_instructions else R.string.ps_tpl_in_body)) },
                        placeholder = { Text(TemplateDefaults.body(kind), maxLines = 2) })
                    if (TemplateField.DATE in inputs) OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ps_tpl_in_date)) })
                    if (TemplateField.FOOTER in inputs) OutlinedTextField(footer, { footer = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ps_tpl_in_footer)) })
                    if (TemplateField.CURRENCY in inputs) OutlinedTextField(currency, { currency = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ps_tpl_in_currency)) }, placeholder = { Text("ر.ي") })
                    if (TemplateField.LOGO in inputs) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = pickLogo) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ps_tpl_in_logo)) }
                        Spacer(Modifier.width(12.dp))
                        logo?.let { b ->
                            Image(b.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                            IconButton(onClick = { logo = null; prefs.logoFile.delete() }) { Icon(Icons.Default.Close, stringResource(R.string.tl_remove)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (TemplateField.PAGES in inputs) NumberField(pages, stringResource(R.string.page_count), { pages = it }, Modifier.weight(1f), maxLen = 2)
                        if (TemplateField.ROWS in inputs) NumberField(rows, stringResource(rowsLabel(kind)), { rows = it }, Modifier.weight(1f), maxLen = 2)
                    }
                    if (TemplateField.ACCENT in inputs) { FieldLabel(stringResource(R.string.ps_tpl_in_color)); ColorChips(accents, accent) { accent = it } }
                    FieldLabel(stringResource(R.string.page_size))
                    ChoiceChips(sizes.map { it.first }, size, { it }) { size = it }
                    FieldLabel(stringResource(R.string.orientation))
                    ChoiceChips(listOf(false, true), landscape, { if (it) stringResource(R.string.landscape) else stringResource(R.string.portrait) }) { landscape = it }
                    if (kind.hasFields) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ps_tpl_with_fields))
                            Text(stringResource(R.string.ps_tpl_with_fields_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(withFields, { withFields = it })
                    }
                    OutlinedTextField(fileName, { fileName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.file_name)) }, placeholder = { Text(title.ifBlank { kind.id }) })
                    PrimaryButton(stringResource(R.string.ps_templates_create), enabled = !runner.running, icon = Icons.Default.NoteAdd) { create() }
                    runner.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (tablet) Row(Modifier.fillMaxSize()) {
                formPane(Modifier.weight(1f).fillMaxHeight())
                previewPane(Modifier.weight(1f).fillMaxHeight().padding(16.dp))
            } else formPane(Modifier.fillMaxSize())
        }
    }
    if (runner.running) ProgressDialog(runner.message)
    runner.result?.let { ResultSheet(it, nav, onDismiss = { runner.result = null }) }
}

@Composable
private fun PreviewPane(bmp: Bitmap?, busy: Boolean, landscape: Boolean, modifier: Modifier) {
    val ratio = if (landscape) 297f / 210f else 210f / 297f
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(shadowElevation = 4.dp, shape = RoundedCornerShape(6.dp), color = Color.White, modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = !landscape)) {
            Box(contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp.asImageBitmap(), stringResource(R.string.ps_templates_preview), Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit)
                if (busy || bmp == null) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

private fun titleLabel(k: TemplateKind): Int = when (k) {
    TemplateKind.EXAM -> R.string.ps_tpl_in_exam_name
    TemplateKind.MUSIC -> R.string.ps_tpl_in_song
    else -> R.string.ps_tpl_in_title
}

private fun subtitleLabel(k: TemplateKind): Int = when (k) {
    TemplateKind.CERTIFICATE -> R.string.ps_tpl_in_recipient
    TemplateKind.EXAM -> R.string.ps_tpl_in_subject
    TemplateKind.TIMETABLE -> R.string.ps_tpl_in_class
    TemplateKind.MUSIC -> R.string.ps_tpl_in_composer
    else -> R.string.ps_tpl_in_address
}

private fun rowsLabel(k: TemplateKind): Int = when (k) {
    TemplateKind.INVOICE -> R.string.ps_tpl_rows_items
    TemplateKind.MINUTES -> R.string.ps_tpl_rows_decisions
    TemplateKind.TODO -> R.string.ps_tpl_rows_tasks
    TemplateKind.TIMETABLE -> R.string.ps_tpl_rows_periods
    TemplateKind.EXAM -> R.string.ps_tpl_rows_questions
    else -> R.string.page_count
}
