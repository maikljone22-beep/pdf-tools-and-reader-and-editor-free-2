package com.smartdocs.editor.ui.convert6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Conv
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.convert6.Fit6
import com.smartdocs.editor.core.convert6.ImgFmt
import com.smartdocs.editor.core.convert6.Opt
import com.smartdocs.editor.core.convert6.Orient6
import com.smartdocs.editor.core.convert6.PageSize6
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.common.PageRangeField
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.FieldLabel
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools5.FileListEditor
import com.smartdocs.editor.ui.tools5.SwitchRow
import java.io.File

/** Good defaults per conversion (shared with the recipes, see [Cv6Catalog.defaultsFor]). */
fun defaultsFor(c: Conv): Cv6Opts = Cv6Catalog.defaultsFor(c)

/**
 * Options sheet of one conversion: the inputs (reorder / remove / add more), a password field when needed,
 * the options listed in [Conv.opts] and the convert button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Cv6OptionsSheet(
    conv: Conv, inputs: List<File>, onInputs: (List<File>) -> Unit, onAddMore: () -> Unit,
    o: Cv6Opts, onChange: (Cv6Opts) -> Unit,
    needPassword: Boolean, password: String, onPassword: (String) -> Unit,
    onDismiss: () -> Unit, onConvert: () -> Unit,
    /** The protected input the password is for (batches ask file by file), and whether the last try was wrong. */
    lockedName: String? = null, lockedWrong: Boolean = false,
) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(conv.from.icon(), conv.from.tile(), size = 40.dp, iconSize = 22.dp)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(horizontal = 6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                IconTile(conv.to.icon(), conv.to.tile(), size = 40.dp, iconSize = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text(conv.title(ctx), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            conv.hint(ctx).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (conv.inputs.isNotEmpty()) {
                FieldLabel(stringResource(R.string.cv6_inputs, inputs.size))
                FileListEditor(inputs, onInputs)
                if (conv.multi) OutlinedButton(onClick = onAddMore, shape = RoundedCornerShape(50)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_add_files)) }
            }
            if (needPassword) {
                if (lockedName != null) Text(stringResource(if (lockedWrong) R.string.cvf_pw_wrong else R.string.cvf_pw_for, lockedName),
                    style = MaterialTheme.typography.bodyMedium, color = if (lockedWrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cv_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Lock, null) }, shape = RoundedCornerShape(16.dp))
            }
            OptionsBody(conv, inputs.size, o, onChange)
            PrimaryButton(stringResource(R.string.cv6_convert), enabled = (conv.inputs.isEmpty() || inputs.isNotEmpty()) && (Opt.URL !in conv.opts || o.url.isNotBlank()) && (Opt.TEXT !in conv.opts || o.text.isNotBlank()) &&
                (Opt.RANGE !in conv.opts || com.smartdocs.editor.core.pdf.PdfOps.rangeSyntaxOk(o.range)) && (!needPassword || password.isNotEmpty()),
                icon = Icons.Default.SwapHoriz, onClick = onConvert)
        }
    }
}

@Composable
private fun OptionsBody(conv: Conv, inputCount: Int, o: Cv6Opts, onChange: (Cv6Opts) -> Unit) {
    val opts = conv.opts
    if (Opt.URL in opts) {
        OutlinedTextField(o.url, { onChange(o.copy(url = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cv6_url)) }, placeholder = { Text("https://") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), leadingIcon = { Icon(Icons.Default.Language, null) }, shape = RoundedCornerShape(16.dp))
    }
    if (Opt.TEXT in opts) {
        val clip = LocalClipboardManager.current
        LaunchedEffect(Unit) { if (o.text.isBlank()) clip.getText()?.text?.takeIf { it.isNotBlank() }?.let { onChange(o.copy(text = it)) } }
        OutlinedTextField(o.text, { onChange(o.copy(text = it)) }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp), label = { Text(stringResource(R.string.cv6_text)) }, shape = RoundedCornerShape(16.dp))
        TextButton(onClick = { clip.getText()?.text?.let { onChange(o.copy(text = it)) } }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_paste)) }
    }
    if (Opt.MERGE in opts && inputCount > 1) {
        FieldLabel(stringResource(R.string.cv6_output))
        ChoiceChips(listOf(true, false), o.merge, { if (it) stringResource(R.string.cv6_merge_one) else stringResource(R.string.cv6_merge_separate) }) { onChange(o.copy(merge = it)) }
    }
    if (Opt.AUTO_OCR in opts) SwitchRow(stringResource(R.string.cv6_auto_ocr), o.autoOcr, stringResource(R.string.cv6_auto_ocr_sub)) { onChange(o.copy(autoOcr = it)) }
    if (Opt.OCR_LANG in opts) {
        FieldLabel(stringResource(R.string.cv6_ocr_lang))
        ChoiceChips(listOf<String?>(null, "ara", "eng", "ara+eng"), o.ocrLangs, { when (it) { null -> stringResource(R.string.cv6_lang_auto); "ara" -> stringResource(R.string.cv6_lang_ar); "eng" -> stringResource(R.string.cv6_lang_en); else -> stringResource(R.string.cv6_lang_both) } }) { onChange(o.copy(ocrLangs = it)) }
    }
    if (Opt.LAYOUT in opts) {
        FieldLabel(stringResource(R.string.cv6_layout))
        ChoiceChips(listOf<Boolean?>(null, true, false), o.keepLayout, { when (it) { null -> stringResource(R.string.cv6_lang_auto); true -> stringResource(R.string.cv6_layout_keep); false -> stringResource(R.string.cv6_layout_flow) } }) { onChange(o.copy(keepLayout = it)) }
    }
    if (Opt.PPT_MODE in opts) {
        FieldLabel(stringResource(R.string.cv_ppt_mode))
        ChoiceChips(listOf(true, false), o.pptEditable, { if (it) stringResource(R.string.cv_ppt_editable) else stringResource(R.string.cv_ppt_image) }) { onChange(o.copy(pptEditable = it)) }
    }
    if (Opt.SINGLE_SHEET in opts) {
        FieldLabel(stringResource(R.string.cv_sheets))
        ChoiceChips(listOf(false, true), o.singleSheet, { if (it) stringResource(R.string.cv_single_sheet) else stringResource(R.string.cv_sheet_per_page) }) { onChange(o.copy(singleSheet = it)) }
    }
    if (Opt.TABLES in opts && (Opt.PPT_MODE !in opts || o.pptEditable)) SwitchRow(stringResource(R.string.cv_detect_tables), o.tables, stringResource(R.string.cv_detect_tables_sub)) { onChange(o.copy(tables = it)) }
    if (Opt.INCLUDE_IMAGES in opts && (Opt.PPT_MODE !in opts || o.pptEditable)) SwitchRow(stringResource(R.string.cv_include_images), o.includeImages, stringResource(R.string.cv_include_images_sub)) { onChange(o.copy(includeImages = it)) }
    if (Opt.IMG_FORMAT in opts) {
        FieldLabel(stringResource(R.string.cv_img_format))
        val keep = conv.id == "img_resize"
        val choices: List<ImgFmt?> = (if (keep) listOf<ImgFmt?>(null) else emptyList()) + ImgFmt.entries
        ChoiceChips(choices, o.imgFormat, { it?.name ?: stringResource(R.string.cv6_fmt_keep) }) { onChange(o.copy(imgFormat = it)) }
    }
    if (Opt.RESIZE in opts) {
        FieldLabel(stringResource(R.string.cv6_resize))
        val choices = listOf(25, 50, 75, -1024, -1600, -2048)
        val sel = if (o.maxSide > 0) -o.maxSide else o.resizePercent
        ChoiceChips(choices, sel, { if (it > 0) "$it%" else stringResource(R.string.cv6_max_side, -it) }) { v -> onChange(if (v > 0) o.copy(resizePercent = v, maxSide = 0) else o.copy(maxSide = -v)) }
    }
    if (Opt.DPI in opts) {
        FieldLabel(stringResource(R.string.cv_img_dpi))
        ChoiceChips(listOf(72, 110, 150, 200, 300), o.dpi, { "$it" }) { onChange(o.copy(dpi = it)) }
    }
    if (Opt.QUALITY in opts && o.imgFormat != ImgFmt.PNG) LabeledSlider(stringResource(R.string.cv6_quality), o.quality.toFloat(), 30f..100f, { onChange(o.copy(quality = it.toInt())) })
    if (Opt.IMG_OUTPUT in opts) {
        FieldLabel(stringResource(R.string.cv_img_output))
        ChoiceChips(listOf(true, false), o.zip, { if (it) stringResource(R.string.cv_img_zip) else stringResource(R.string.cv_img_separate) }) { onChange(o.copy(zip = it)) }
    }
    if (Opt.GRAY in opts && !o.bw) SwitchRow(stringResource(R.string.cv6_gray), o.gray) { onChange(o.copy(gray = it)) }
    if (Opt.BW in opts) SwitchRow(stringResource(R.string.pc_bw), o.bw, stringResource(R.string.pc_bw_sub)) { onChange(o.copy(bw = it)) }
    if (Opt.SHEET_PER_TABLE in opts) SwitchRow(stringResource(R.string.pc_sheet_per_table), o.perTable) { onChange(o.copy(perTable = it)) }
    if (Opt.PAGE_SETUP in opts) PageSetupOptions(o, onChange)
    if (Opt.NUP in opts) {
        FieldLabel(stringResource(R.string.cv6_nup))
        ChoiceChips(listOf(2, 3, 4, 6, 9), o.nUp, { "$it" }) { onChange(o.copy(nUp = it)) }
    }
    if (Opt.CSV_SHEETS in opts) SwitchRow(stringResource(R.string.cv6_csv_all), o.csvAllSheets, stringResource(R.string.cv6_csv_all_sub)) { onChange(o.copy(csvAllSheets = it)) }
    if (Opt.PDFA_STRICT in opts) SwitchRow(stringResource(R.string.cv6_pdfa_strict), o.pdfaStrict, stringResource(R.string.cv6_pdfa_strict_sub)) { onChange(o.copy(pdfaStrict = it)) }
    if (Opt.RANGE in opts) PageRangeField(o.range) { onChange(o.copy(range = it)) }
    // Foxit "Restore defaults" (keeps the page range)
    if (opts.any { it != Opt.RANGE } && o != defaultsFor(conv).copy(range = o.range))
        TextButton(onClick = { onChange(defaultsFor(conv).copy(range = o.range)) }) { Text(stringResource(R.string.pc_restore_defaults)) }
    // nav-attach: Create-PDF "attach source file"
    if (conv.to == com.smartdocs.editor.core.convert6.Fmt.PDF && conv.inputs.isNotEmpty())
        SwitchRow(stringResource(R.string.nat_attach_source), o.attachSource, stringResource(R.string.nat_attach_source_sub)) { onChange(o.copy(attachSource = it)) }
}

@Composable
private fun PageSetupOptions(o: Cv6Opts, onChange: (Cv6Opts) -> Unit) {
    val s = o.setup
    FieldLabel(stringResource(R.string.cv6_page_size))
    ChoiceChips(PageSize6.entries, s.size, { if (it == PageSize6.IMAGE) stringResource(R.string.cv6_size_image) else it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { onChange(o.copy(setup = s.copy(size = it))) }
    if (s.size != PageSize6.IMAGE) {
        FieldLabel(stringResource(R.string.cv6_orientation))
        ChoiceChips(Orient6.entries, s.orient, { when (it) { Orient6.AUTO -> stringResource(R.string.cv6_or_auto); Orient6.PORTRAIT -> stringResource(R.string.cv6_or_portrait); Orient6.LANDSCAPE -> stringResource(R.string.cv6_or_landscape) } }) { onChange(o.copy(setup = s.copy(orient = it))) }
        FieldLabel(stringResource(R.string.cv6_fit))
        ChoiceChips(Fit6.entries, s.fit, { when (it) { Fit6.CONTAIN -> stringResource(R.string.cv6_fit_contain); Fit6.COVER -> stringResource(R.string.cv6_fit_cover); Fit6.STRETCH -> stringResource(R.string.cv6_fit_stretch); Fit6.ORIGINAL -> stringResource(R.string.cv6_fit_original) } }) { onChange(o.copy(setup = s.copy(fit = it))) }
    }
    FieldLabel(stringResource(R.string.cv6_margins))
    ChoiceChips(listOf(0f, 18f, 36f, 54f), s.marginPt, { when (it) { 0f -> stringResource(R.string.cv6_margin_none); 18f -> stringResource(R.string.cv6_margin_small); 36f -> stringResource(R.string.cv6_margin_medium); else -> stringResource(R.string.cv6_margin_large) } }) { onChange(o.copy(setup = s.copy(marginPt = it))) }
}
