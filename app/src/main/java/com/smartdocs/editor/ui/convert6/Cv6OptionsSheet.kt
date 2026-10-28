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
import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.convert6.Fit6
import com.smartdocs.editor.core.convert6.Images6
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
import kotlin.math.roundToInt

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
    if (Opt.TEXT_RECOVERY in opts) {
        // row 51: text recovery off / problem pages only / all pages (problem pages = no text, unusable text, pictures of text)
        FieldLabel(stringResource(R.string.cv9_recovery))
        val mode = if (!o.autoOcr) 0 else if (o.recoverAllPages) 2 else 1
        ChoiceChips(listOf(0, 1, 2), mode, { when (it) { 0 -> stringResource(R.string.cv9_recovery_off); 1 -> stringResource(R.string.cv9_recovery_problem); else -> stringResource(R.string.cv9_recovery_all) } }) {
            onChange(o.copy(autoOcr = it > 0, recoverAllPages = it == 2))
        }
    } else if (Opt.AUTO_OCR in opts) SwitchRow(stringResource(R.string.cv6_auto_ocr), o.autoOcr, stringResource(R.string.cv6_auto_ocr_sub)) { onChange(o.copy(autoOcr = it)) }
    if (Opt.OCR_LANG in opts && (Opt.TEXT_RECOVERY !in opts || o.autoOcr)) {
        FieldLabel(stringResource(R.string.cv6_ocr_lang))
        ChoiceChips(listOf<String?>(null, "ara", "eng", "ara+eng"), o.ocrLangs, { when (it) { null -> stringResource(R.string.cv6_lang_auto); "ara" -> stringResource(R.string.cv6_lang_ar); "eng" -> stringResource(R.string.cv6_lang_en); else -> stringResource(R.string.cv6_lang_both) } }) { onChange(o.copy(ocrLangs = it)) }
    }
    if (Opt.LAYOUT in opts) {
        FieldLabel(stringResource(R.string.cv6_layout))
        // row 46: auto / page layout / flowing / precisely laid out (text boxes)
        val choice = if (o.exactLayout) "exact" else when (o.keepLayout) { null -> "auto"; true -> "keep"; false -> "flow" }
        ChoiceChips(listOf("auto", "keep", "flow", "exact"), choice, { when (it) { "auto" -> stringResource(R.string.cv6_lang_auto); "keep" -> stringResource(R.string.cv6_layout_keep)
            "flow" -> stringResource(R.string.cv6_layout_flow); else -> stringResource(R.string.cv9_layout_exact) } }) {
            onChange(when (it) { "exact" -> o.copy(exactLayout = true); "keep" -> o.copy(keepLayout = true, exactLayout = false)
                "flow" -> o.copy(keepLayout = false, exactLayout = false); else -> o.copy(keepLayout = null, exactLayout = false) })
        }
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
    if (Opt.FORM_FIELDS in opts) SwitchRow(stringResource(R.string.cv9_form_fields), o.recognizeFields, stringResource(R.string.cv9_form_fields_sub)) { onChange(o.copy(recognizeFields = it)) }
    if (Opt.XLSX_CELLS in opts) {
        // s6 Excel: split multi-line text into rows; reduce merged cells
        SwitchRow(stringResource(R.string.cv9_xlsx_split), o.xlsxSplitLines) { onChange(o.copy(xlsxSplitLines = it)) }
        SwitchRow(stringResource(R.string.cv9_xlsx_nomerge), o.xlsxNoMerge) { onChange(o.copy(xlsxNoMerge = it)) }
    }
    if (Opt.HEADERS in opts) {
        // s6: running headers / footers: automatic / keep as text / delete
        FieldLabel(stringResource(R.string.cv9_headers))
        ChoiceChips(listOf(0, 1, 2), o.headerMode, { when (it) { 0 -> stringResource(R.string.cv6_lang_auto); 1 -> stringResource(R.string.cv9_headers_keep); else -> stringResource(R.string.cv9_headers_delete) } }) { onChange(o.copy(headerMode = it)) }
    }
    if (Opt.TEXT_LAYOUT in opts) {
        // s6 Text: paragraphs or the PDF's lines; line width
        FieldLabel(stringResource(R.string.cv9_txt_layout))
        ChoiceChips(listOf(false, true), o.txtKeepLines, { if (it) stringResource(R.string.cv9_txt_lines) else stringResource(R.string.cv9_txt_paragraphs) }) { onChange(o.copy(txtKeepLines = it)) }
        FieldLabel(stringResource(R.string.cv9_txt_width))
        ChoiceChips(listOf(0, 72, 80, 100), o.txtWrap, { if (it == 0) stringResource(R.string.cv9_txt_nowrap) else it.toString() }) { onChange(o.copy(txtWrap = it)) }
    }
    if (Opt.CELL_COMMENTS in opts) SwitchRow(stringResource(R.string.cv9_cell_comments), o.cellComments) { onChange(o.copy(cellComments = it)) }
    if (Opt.SLIDES in opts) {
        // row 103: slide range and hidden slides
        OutlinedTextField(o.slideRange, { onChange(o.copy(slideRange = it)) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.cv9_slide_range)) }, placeholder = { Text(stringResource(R.string.cv9_all_slides)) },
            isError = !com.smartdocs.editor.core.pdf.PdfOps.rangeSyntaxOk(o.slideRange))
        SwitchRow(stringResource(R.string.cv9_hidden_slides), o.hiddenSlides) { onChange(o.copy(hiddenSlides = it)) }
    }
    if (Opt.SPEAKER_NOTES in opts) SwitchRow(stringResource(R.string.cv9_speaker_notes), o.speakerNotes) { onChange(o.copy(speakerNotes = it)) }
    if (Opt.BOOKMARKS in opts) SwitchRow(stringResource(R.string.cv9_bookmarks), o.bookmarks, stringResource(R.string.cv9_bookmarks_sub)) { onChange(o.copy(bookmarks = it)) }
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
    // fc-settings: keep these options as this conversion's default (Settings → Conversion defaults lists / forgets them)
    if (opts.any { it != Opt.RANGE && it != Opt.URL && it != Opt.TEXT }) {
        val ctx = LocalContext.current
        var saved by remember(conv.id) { mutableStateOf(com.smartdocs.editor.core.convert6.Cv6Defaults.has(ctx, conv.id)) }
        val savedMsg = stringResource(R.string.fcs_cv_saved)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                com.smartdocs.editor.core.convert6.Cv6Defaults.save(ctx, conv.id, o); saved = true
                android.widget.Toast.makeText(ctx, savedMsg, android.widget.Toast.LENGTH_SHORT).show()
            }, shape = RoundedCornerShape(50)) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fcs_cv_save_default)) }
            if (saved) TextButton(onClick = { com.smartdocs.editor.core.convert6.Cv6Defaults.clear(ctx, conv.id); saved = false }) { Text(stringResource(R.string.fcs_cv_forget_default)) }
        }
    }
    // nav-attach: Create-PDF "attach source file"
    if (conv.to == com.smartdocs.editor.core.convert6.Fmt.PDF && conv.inputs.isNotEmpty())
        SwitchRow(stringResource(R.string.nat_attach_source), o.attachSource, stringResource(R.string.nat_attach_source_sub)) { onChange(o.copy(attachSource = it)) }
}

@Composable
private fun PageSetupOptions(o: Cv6Opts, onChange: (Cv6Opts) -> Unit) {
    val s = o.setup
    FieldLabel(stringResource(R.string.cv6_page_size))
    ChoiceChips(PageSize6.entries, s.size, { when (it) { PageSize6.IMAGE -> stringResource(R.string.cv6_size_image); PageSize6.CUSTOM -> stringResource(R.string.cv9_size_custom)
        else -> it.name.lowercase().replaceFirstChar { c -> c.uppercase() } } }) { onChange(o.copy(setup = s.copy(size = it))) }
    // row 27: custom page size in millimetres
    if (s.size == PageSize6.CUSTOM) androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        listOf(true, false).forEach { isW ->
            val pt = if (isW) s.customW else s.customH
            var text by androidx.compose.runtime.remember(isW) { androidx.compose.runtime.mutableStateOf(Images6.ptToMm(pt).roundToInt().toString()) }
            androidx.compose.material3.OutlinedTextField(text, { v ->
                text = v.filter { it.isDigit() }.take(4)
                text.toFloatOrNull()?.let { mm -> val p = Images6.mmToPt(mm).coerceIn(Images6.MIN_PAGE_PT, Images6.MAX_PAGE_PT)
                    onChange(o.copy(setup = if (isW) s.copy(customW = p) else s.copy(customH = p))) }
            }, androidx.compose.ui.Modifier.width(120.dp), singleLine = true,
                label = { Text(stringResource(if (isW) R.string.cv9_width_mm else R.string.cv9_height_mm)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        }
    }
    if (s.size != PageSize6.IMAGE) {
        FieldLabel(stringResource(R.string.cv6_orientation))
        ChoiceChips(Orient6.entries, s.orient, { when (it) { Orient6.AUTO -> stringResource(R.string.cv6_or_auto); Orient6.PORTRAIT -> stringResource(R.string.cv6_or_portrait); Orient6.LANDSCAPE -> stringResource(R.string.cv6_or_landscape) } }) { onChange(o.copy(setup = s.copy(orient = it))) }
        FieldLabel(stringResource(R.string.cv6_fit))
        ChoiceChips(Fit6.entries, s.fit, { when (it) { Fit6.CONTAIN -> stringResource(R.string.cv6_fit_contain); Fit6.COVER -> stringResource(R.string.cv6_fit_cover); Fit6.STRETCH -> stringResource(R.string.cv6_fit_stretch); Fit6.ORIGINAL -> stringResource(R.string.cv6_fit_original) } }) { onChange(o.copy(setup = s.copy(fit = it))) }
    }
    FieldLabel(stringResource(R.string.cv6_margins))
    ChoiceChips(listOf(0f, 18f, 36f, 54f), s.marginPt, { when (it) { 0f -> stringResource(R.string.cv6_margin_none); 18f -> stringResource(R.string.cv6_margin_small); 36f -> stringResource(R.string.cv6_margin_medium); else -> stringResource(R.string.cv6_margin_large) } }) { onChange(o.copy(setup = s.copy(marginPt = it))) }
}
