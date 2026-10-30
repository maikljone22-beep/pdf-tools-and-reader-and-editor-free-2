package com.smartdocs.editor.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.NpfPrefs
import com.smartdocs.editor.core.data.NpfPrefs.K
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.view5.NpfColorRow
import com.smartdocs.editor.ui.view5.NpfGridSettings
import kotlinx.coroutines.launch

/** Entry card on the main Settings screen (hook in SettingsScreen): opens [NpfSettingsScreen]. */
@Composable
fun NpfSettingsCard(nav: NavHostController) {
    GroupCard(stringResource(R.string.npf_more_title)) {
        GroupRow(stringResource(R.string.npf_more_title), stringResource(R.string.npf_more_sub), icon = Icons.Default.Tune, tile = Brand.tiles.teal,
            onClick = { nav.navigate(Routes.tool(ROUTE_ID)) })
        GroupDivider()
        // Retain markup tools: the reader's "continue" chip, persisted (verifier: present in the reader, not saved / not in Settings)
        SwitchRow(stringResource(R.string.npf_retain_tools), NpfPrefs.retainTools, stringResource(R.string.npf_retain_tools_sub), icon = Icons.Default.Repeat) { NpfPrefs.setBool(K.RETAIN_TOOLS, it) }
        SwitchRow(stringResource(R.string.npf_offline), NpfPrefs.offline, stringResource(R.string.npf_offline_sub), icon = Icons.Default.CloudOff) { v -> NpfPrefsActions.setOffline(nav.context, v) }
    }
}

const val ROUTE_ID = "npf_settings"

/** Side effects of some preferences (rescheduling work). */
object NpfPrefsActions {
    fun setOffline(c: Context, v: Boolean) {
        NpfPrefs.setBool(K.OFFLINE, v)
        // back online: periodic sync jobs pick up again (they stop quietly while offline)
        if (!v) runCatching { com.smartdocs.editor.core.sync.SyncWorker.schedule(c.applicationContext) }
    }
}

/** One searchable section of the screen (PREF-1: the search box filters by title and keywords). */
private class Section(val title: Int, val keywords: String, val content: @Composable ColumnScope.() -> Unit)

/**
 * nav-prefs: "More preferences" — Nitro §23 / Foxit §37 / old-plan PREF-1…9, SHL-10/13. Every option is stored in
 * [NpfPrefs] (or in the owning tool's preferences: OCR plus "t5_ocr", compare "compare5", measuring "view5") and
 * read where it applies; see the comments at each use (`nav-prefs:`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NpfSettingsScreen(nav: NavHostController) {
    var query by remember { mutableStateOf("") }
    val sections = remember { sections(nav) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.npf_more_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.npf_search_settings)) }, shape = RoundedCornerShape(24.dp))
            val q = npfFold(query)
            sections.forEach { s ->
                val title = stringResource(s.title)
                if (q.isBlank() || npfFold(title).contains(q) || npfFold(s.keywords).contains(q)) GroupCard(title) { s.content(this) }
            }
        }
    }
}

/** Arabic-insensitive folding for the settings search (alef forms, taa marbuta, diacritics, case). */
internal fun npfFold(s: String): String = s.lowercase()
    .replace(Regex("[ً-ْـ]"), "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').trim()

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l, maxLines = 1) }) }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Sw(label: Int, value: Boolean, sub: Int? = null, onChange: (Boolean) -> Unit) =
    SwitchRow(stringResource(label), value, sub?.let { stringResource(it) }, onChange = onChange)

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value, onChange, valueRange = range, steps = steps)
    }
}

private val SWATCHES = listOf(0x7C9CFF, 0xFFEB3B, 0x80DEEA, 0xA5D6A7, 0xFFAB91, 0xCE93D8, 0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x212121)
private val ACCENTS = listOf(0, 0x1565C0, 0x00897B, 0x6A1B9A, 0xC62828, 0xEF6C00, 0x2E7D32, 0x37474F)

private fun sections(nav: NavHostController): List<Section> = listOf(
    // ---------------- files & saving ----------------
    Section(R.string.npf_s_files, "save folder recent autosave recovery مجلد الحفظ الملفات الحديثة حفظ تلقائي استرداد") {
        val ctx = LocalContext.current
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                NpfPrefs.setString(K.SAVE_TREE, uri.toString())
                NpfPrefs.saveLast = System.currentTimeMillis()     // new outputs from now on
            }
        }
        val tree = NpfPrefs.saveTree
        val treeName = remember(tree) { tree.takeIf { it.isNotBlank() }?.let { runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name }.getOrNull() } }
        GroupRow(stringResource(R.string.npf_save_folder), treeName ?: stringResource(R.string.npf_save_folder_app), icon = Icons.Default.FolderOpen, onClick = { pick.launch(null) },
            trailing = if (tree.isNotBlank()) ({ TextButton(onClick = { NpfPrefs.setString(K.SAVE_TREE, "") }) { Text(stringResource(R.string.npf_clear)) } }) else null)
        Hint(stringResource(R.string.npf_save_folder_hint))
        Label(stringResource(R.string.npf_recent_limit))
        Chips(NpfPrefs.RECENT_CHOICES.map { it to if (it == 0) stringResource(R.string.npf_all) else "$it" }, NpfPrefs.recentLimit) { NpfPrefs.setInt(K.RECENT_LIMIT, it) }   // fc-settings: one setting (also Settings → General)
        Label(stringResource(R.string.npf_autosave_every))
        Chips(listOf(0 to stringResource(R.string.npf_on_leave), 2 to "2", 5 to "5", 10 to "10", 15 to "15").map { (v, l) -> v to if (v == 0) l else stringResource(R.string.npf_minutes, l) }, NpfPrefs.autoSaveMinutes) { NpfPrefs.setInt(K.AUTOSAVE_MIN, it) }
        Hint(stringResource(R.string.npf_autosave_hint))
        Label(stringResource(R.string.npf_recovery))
        Chips(listOf(0 to stringResource(R.string.npf_off), 1 to "1", 5 to "5", 10 to "10").map { (v, l) -> v to if (v == 0) l else stringResource(R.string.npf_minutes, l) }, NpfPrefs.recoveryMinutes) { NpfPrefs.setInt(K.RECOVERY_MIN, it) }
        Hint(stringResource(R.string.npf_recovery_hint))
        MailDefaultRows()
    },
    // ---------------- page display ----------------
    Section(R.string.npf_s_display, "layout zoom facing panel title rotation header footer تخطيط تكبير متقابلة لوحة عنوان تدوير ترويسة") {
        Label(stringResource(R.string.npf_layout))
        Chips(listOf("auto" to R.string.npf_auto, "single" to R.string.view_single, "continuous" to R.string.view_continuous, "facing" to R.string.view_facing, "cover" to R.string.npf_cover).map { it.first to stringResource(it.second) },
            NpfPrefs.defaultLayout) { NpfPrefs.setString(K.LAYOUT, it) }
        Label(stringResource(R.string.npf_zoom))
        Chips(listOf("width" to stringResource(R.string.fit_width), "page" to stringResource(R.string.fit_page), "z150" to "150%", "z200" to "200%"), NpfPrefs.defaultZoom) { NpfPrefs.setString(K.ZOOM, it) }
        Label(stringResource(R.string.npf_panel_on_open))
        Chips(listOf("none" to R.string.npf_none, "outline" to R.string.panel_outline, "thumbnails" to R.string.panel_thumbnails, "bookmarks" to R.string.panel_bookmarks, "comments" to R.string.panel_annotations)
            .map { it.first to stringResource(it.second) }, NpfPrefs.panelOnOpen) { NpfPrefs.setString(K.PANEL, it) }
        Slide(stringResource(R.string.npf_panel_width, NpfPrefs.panelWidthDp), NpfPrefs.panelWidthDp.toFloat(), 260f..520f) { NpfPrefs.setInt(K.PANEL_W, it.toInt()) }
        Sw(R.string.npf_doc_title, NpfPrefs.showDocTitle) { NpfPrefs.setBool(K.DOC_TITLE, it) }
        Sw(R.string.pc_doc_view_first, NpfPrefs.docViewFirstOpen, R.string.pc_doc_view_first_sub) { NpfPrefs.setBool(K.DOC_VIEW, it) }
        Label(stringResource(R.string.npf_tab_limit))
        Chips((2..10).map { it to it.toString() }, NpfPrefs.tabLimit) { NpfPrefs.setInt(K.TAB_LIMIT, it) }
        Label(stringResource(R.string.npf_present_bg))
        Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(NpfPrefs.presentBackground, listOf(0x000000, 0x212121, 0x263238, 0x0D1B2A, 0xFFFFFF)) { NpfPrefs.setInt(K.PRESENT_BG, it) } }
        Sw(R.string.npf_hf_rotation, NpfPrefs.hfIgnoreRotation, R.string.npf_hf_rotation_sub) { NpfPrefs.setBool(K.HF_IGNORE_ROT, it) }
        Hint(stringResource(R.string.npf_thin_lines_na))
        com.smartdocs.editor.ui.rpf.RpfDisplayPrefs()   // reader-prefs §37: facing count, gap, page size, destination zoom, full screen, PDF/A view
    },
    // ---------------- units, grid, guides, measuring ----------------
    Section(R.string.npf_s_units, "units grid snap guides measure وحدات شبكة التقاط أدلة قياس") {
        val ctx = LocalContext.current
        Label(stringResource(R.string.npf_units))
        Chips(listOf("cm" to "cm", "mm" to "mm", "in" to "in", "pt" to "pt"), NpfPrefs.units) { NpfPrefs.setString(K.UNITS, it) }
        Column(Modifier.padding(horizontal = 16.dp)) { NpfGridSettings() }
        val v5 = remember { ctx.getSharedPreferences("view5", Context.MODE_PRIVATE) }
        var snap by remember { mutableStateOf(v5.getBoolean("snap", true)) }
        Sw(R.string.npf_measure_snap, snap, R.string.npf_measure_snap_sub) { snap = it; v5.edit().putBoolean("snap", it).apply() }
    },
    // ---------------- commenting ----------------
    Section(R.string.npf_s_comments, "comment note popup font opacity markup retain summary تعليق ملاحظة خط شفافية ملخص") {
        Sw(R.string.npf_retain_tools, NpfPrefs.retainTools, R.string.npf_retain_tools_sub) { NpfPrefs.setBool(K.RETAIN_TOOLS, it) }
        Sw(R.string.npf_copy_selection, NpfPrefs.copySelection) { NpfPrefs.setBool(K.COPY_SEL, it) }
        Sw(R.string.cf_auto_popup, NpfPrefs.autoOpenPopup, R.string.cf_auto_popup_sub) { NpfPrefs.setBool(K.AUTO_POPUP, it) }
        com.smartdocs.editor.ui.reader.annot.CommentsRestSettings()   // comments-rest: navigation order (§37), pencil (§41)
        Label(stringResource(R.string.npf_note_font))
        Chips(listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono"), NpfPrefs.noteFont) { NpfPrefs.setString(K.NOTE_FONT, it) }
        // size / opacity are the comment tools' own preferences (CommentExtras, prefs "comment_prefs"): set here for every document
        val ctx = LocalContext.current
        val cp = remember { ctx.getSharedPreferences("comment_prefs", Context.MODE_PRIVATE) }
        var size by remember { mutableFloatStateOf(cp.getFloat("note_font", 16f)) }
        var alpha by remember { mutableFloatStateOf(cp.getFloat("note_opacity", 1f)) }
        Slide(stringResource(R.string.npf_note_size, size.toInt()), size, 10f..28f) { size = it; cp.edit().putFloat("note_font", it).apply() }
        Slide(stringResource(R.string.npf_note_opacity, (alpha * 100).toInt()), alpha, 0.3f..1f) { alpha = it; cp.edit().putFloat("note_opacity", it).apply() }
    },
    // ---------------- identity ----------------
    Section(R.string.npf_s_identity, "identity name title organisation unit email author هوية اسم منظمة قسم بريد مؤلف") { IdentityRows() },
    // ---------------- forms ----------------
    Section(R.string.npf_s_forms, "forms highlight required xfa barcode نماذج تمييز مطلوب باركود") {
        Sw(R.string.npf_xfa_auto, NpfPrefs.xfaAutoOpen, R.string.npf_xfa_auto_sub) { NpfPrefs.setBool(K.XFA_AUTO, it) }
        Sw(R.string.npf_xfa_banner, NpfPrefs.xfaBanner) { NpfPrefs.setBool(K.XFA_BANNER, it) }
        Hint(stringResource(R.string.npf_barcode_hint))
    },
    // ---------------- editing & languages ----------------
    Section(R.string.npf_s_editing, "digits arabic hindi typing edit أرقام هندية كتابة تحرير") {
        Label(stringResource(R.string.npf_digits))
        Chips(listOf("keep" to stringResource(R.string.npf_digits_keep), "arabic" to "٠١٢٣", "latin" to "0123"), NpfPrefs.digits) { NpfPrefs.setString(K.DIGITS, it) }
        Hint(stringResource(R.string.npf_digits_hint))
    },
    // ---------------- pages & bookmarks ----------------
    Section(R.string.npf_s_pages, "bookmarks delete replace pages إشارات حذف استبدال صفحات") {
        Sw(R.string.npf_bm_delete, NpfPrefs.removeBookmarksOnDelete) { NpfPrefs.setBool(K.BM_DELETE, it) }
        Sw(R.string.npf_bm_import, NpfPrefs.importBookmarksOnReplace) { NpfPrefs.setBool(K.BM_IMPORT, it) }
    },
    // ---------------- conversion, OCR, compare ----------------
    Section(R.string.npf_s_tools, "convert conversion ocr compare colours fonts mapping تحويل تعرف ضوئي مقارنة ألوان خطوط") { ToolDefaults(nav) },
    // ---------------- notifications ----------------
    Section(R.string.npf_s_notifications, "notifications sync review tasks إشعارات مزامنة مراجعة مهام") {
        val ctx = LocalContext.current
        Sw(R.string.npf_n_sync, NpfPrefs.notifySync, R.string.npf_n_sync_sub) { NpfPrefs.setBool(K.N_SYNC, it) }
        Sw(R.string.npf_n_review, NpfPrefs.notifyReview) { NpfPrefs.setBool(K.N_REVIEW, it) }
        Sw(R.string.npf_n_tasks, NpfPrefs.notifyTasks, R.string.npf_n_tasks_sub) { NpfPrefs.setBool(K.N_TASKS, it) }
        GroupRow(stringResource(R.string.ds_set_notifications_sub), icon = Icons.Default.Notifications, onClick = {
            val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        })
        FcsNotificationChannels()   // fc-settings: per-message settings (gear per notification channel)
    },
    // ---------------- search ----------------
    Section(R.string.npf_s_search, "search case whole words attachments comments results colour بحث حالة كلمات مرفقات نتائج لون") {
        Sw(R.string.case_sensitive, NpfPrefs.searchMatchCase) { NpfPrefs.setBool(K.S_CASE, it) }
        Sw(R.string.whole_words, NpfPrefs.searchWholeWords) { NpfPrefs.setBool(K.S_WHOLE, it) }
        Sw(R.string.npf_s_scopes, NpfPrefs.searchScopes, R.string.npf_s_scopes_sub) { NpfPrefs.setBool(K.S_SCOPES, it) }
        Sw(R.string.npf_s_attach, NpfPrefs.searchAttachmentContent, R.string.npf_s_attach_sub) { NpfPrefs.setBool(K.S_ATTACH, it) }
        Label(stringResource(R.string.npf_s_max))
        Chips(listOf(0 to stringResource(R.string.npf_all), 100 to "100", 500 to "500", 1000 to "1000"), NpfPrefs.searchMax) { NpfPrefs.setInt(K.S_MAX, it) }
        Label(stringResource(R.string.npf_s_hit))
        Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(NpfPrefs.hitColor, SWATCHES) { NpfPrefs.setInt(K.S_HIT, 0x66000000 or it) } }
        Label(stringResource(R.string.npf_s_hit_cur))
        Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(NpfPrefs.hitCurrentColor, SWATCHES) { NpfPrefs.setInt(K.S_HIT_CUR, 0x99000000.toInt() or it) } }
        com.smartdocs.editor.ui.nve.search.FcsIndexPrefsRows()   // fc-settings: index / search preferences (XMP, properties, tags, log, title, width, size cap)
    },
    // ---------------- accessibility ----------------
    Section(R.string.npf_s_a11y, "accessibility contrast colours reading order assistant وصول تباين ألوان ترتيب القراءة مساعد") {
        Sw(R.string.npf_a11y_override, NpfPrefs.a11yOverride, R.string.npf_a11y_override_sub) { NpfPrefs.setBool(K.A11Y_ON, it) }
        Label(stringResource(R.string.npf_a11y_presets))
        val presets = listOf(0x000000 to 0xFFFF00, 0x000000 to 0xFFFFFF, 0x000000 to 0x00FF00, 0xFFFFFF to 0x000000, 0xFFF8E1 to 0x4E342E, 0x0D1B2A to 0xE0E1DD)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (bg, fg) ->
                val on = NpfPrefs.a11yBackground == bg && NpfPrefs.a11yText == fg
                FilterChip(selected = on, onClick = { NpfPrefs.setInt(K.A11Y_BG, bg); NpfPrefs.setInt(K.A11Y_FG, fg); NpfPrefs.setBool(K.A11Y_ON, true) },
                    label = { Text("Aa", color = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or fg)) },
                    colors = FilterChipDefaults.filterChipColors(containerColor = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or bg), selectedContainerColor = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or bg)))
            }
        }
        Label(stringResource(R.string.npf_a11y_text))
        Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(NpfPrefs.a11yText, listOf(0xFFFF00, 0xFFFFFF, 0x00FF00, 0x000000, 0x4E342E, 0x80DEEA)) { NpfPrefs.setInt(K.A11Y_FG, it) } }
        Label(stringResource(R.string.npf_a11y_bg))
        Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(NpfPrefs.a11yBackground, listOf(0x000000, 0xFFFFFF, 0xFFF8E1, 0x0D1B2A, 0x263238, 0xE8F5E9)) { NpfPrefs.setInt(K.A11Y_BG, it) } }
        Label(stringResource(R.string.npf_read_order))
        Chips(listOf("infer" to stringResource(R.string.npf_read_infer), "raw" to stringResource(R.string.npf_read_raw)), NpfPrefs.readingOrder) { NpfPrefs.setString(K.READ_ORDER, it) }
        FcsA11yAssistantRow()   // fc-settings: Accessibility Setup Assistant
    },
    // ---------------- privacy & appearance ----------------
    Section(R.string.npf_s_general, "offline internet accent colour theme connected debug log pinned quick access دون اتصال إنترنت لون مميز مظهر متصل تصحيح سجل مثبتة") {
        val ctx = LocalContext.current
        Sw(R.string.npf_offline, NpfPrefs.offline, R.string.npf_offline_sub) { NpfPrefsActions.setOffline(ctx, it) }
        Hint(stringResource(R.string.npf_offline_hint))
        FcsGeneralRows()   // fc-settings: pinned-command position, ConnectedPDF, debug log
        Label(stringResource(R.string.npf_accent))
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(selected = NpfPrefs.accent == 0, onClick = { NpfPrefs.setInt(K.ACCENT, 0) }, label = { Text(stringResource(R.string.npf_accent_default)) })
                NpfColorRow(NpfPrefs.accent.takeIf { it != 0 } ?: -1, ACCENTS.drop(1)) { NpfPrefs.setInt(K.ACCENT, it) }
            }
        }
    },
    // ---------------- help ----------------
    Section(R.string.npf_s_help, "help community forum labs مساعدة مجتمع منتدى مختبر") {
        val ctx = LocalContext.current
        fun open(url: String) { if (com.smartdocs.editor.core.data.NpfNet.allowed()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        val community = stringResource(R.string.npf_url_community).ifBlank { "https://play.google.com/store/apps/details?id=" + ctx.packageName }
        val labs = stringResource(R.string.npf_url_labs).ifBlank { "https://play.google.com/apps/testing/" + ctx.packageName }
        GroupRow(stringResource(R.string.npf_community), stringResource(R.string.npf_community_sub), icon = Icons.Default.Forum, onClick = { open(community) })
        GroupRow(stringResource(R.string.npf_labs), stringResource(R.string.npf_labs_sub), icon = Icons.Default.Science, onClick = { open(labs) })
    },
)

/** Default e-mail subject / body of the share sheet ({file} = document name). */
@Composable
private fun MailDefaultRows() {
    val ctx = LocalContext.current
    var subject by remember { mutableStateOf(com.smartdocs.editor.core.share5.MailDefaults.subjectTemplate(ctx)) }
    var body by remember { mutableStateOf(com.smartdocs.editor.core.share5.MailDefaults.bodyTemplate(ctx)) }
    val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
    Label(stringResource(R.string.cf_mail_defaults))
    OutlinedTextField(subject, { subject = it; com.smartdocs.editor.core.share5.MailDefaults.set(ctx, subject, body) }, field, label = { Text(stringResource(R.string.cf_mail_subject)) }, singleLine = true)
    OutlinedTextField(body, { body = it; com.smartdocs.editor.core.share5.MailDefaults.set(ctx, subject, body) }, field, label = { Text(stringResource(R.string.cf_mail_body)) }, minLines = 2)
    Hint(stringResource(R.string.cf_mail_hint))
}

@Composable
private fun IdentityRows() {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val author = com.smartdocs.editor.LocalSettings.current.authorName
    var id by remember { mutableStateOf(com.smartdocs.editor.ui.view5.StampIdentity.load(ctx, author)) }
    var saved by remember { mutableStateOf(true) }
    val field = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
    OutlinedTextField(id.name, { id = id.copy(name = it); saved = false }, field, label = { Text(stringResource(R.string.v5_id_name)) }, singleLine = true)
    OutlinedTextField(id.title, { id = id.copy(title = it); saved = false }, field, label = { Text(stringResource(R.string.v5_id_title)) }, singleLine = true)
    OutlinedTextField(id.org, { id = id.copy(org = it); saved = false }, field, label = { Text(stringResource(R.string.v5_id_org)) }, singleLine = true)
    OutlinedTextField(id.unit, { id = id.copy(unit = it); saved = false }, field, label = { Text(stringResource(R.string.npf_id_unit)) }, singleLine = true)
    OutlinedTextField(id.email, { id = id.copy(email = it); saved = false }, field, label = { Text(stringResource(R.string.npf_id_email)) }, singleLine = true)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 7.73: one shared identity — saving also makes the name the comment author
        Button(enabled = !saved, onClick = {
            val v = id.copy(name = id.name.trim()); com.smartdocs.editor.ui.view5.StampIdentity.save(ctx, v); saved = true
            if (v.name.isNotBlank() && v.name != author) scope.launch { app.settings.update { it.copy(authorName = v.name) } }
        }) { Text(stringResource(R.string.save)) }
    }
    Hint(stringResource(R.string.npf_id_hint) + " " + stringResource(R.string.cf_id_shared))
}

@Composable
private fun ToolDefaults(nav: NavHostController) {
    val ctx = LocalContext.current
    GroupRow(stringResource(R.string.npf_convert_hub), stringResource(R.string.npf_convert_hub_sub), icon = Icons.Default.SwapHoriz, onClick = { nav.navigate(Routes.tool("convert_hub")) })
    GroupRow(stringResource(R.string.npf_to_pdf), icon = Icons.Default.PictureAsPdf, onClick = { nav.navigate(Routes.tool("to_pdf_plus")) })
    GroupRow(stringResource(R.string.npf_export), icon = Icons.Default.IosShare, onClick = { nav.navigate(Routes.tool("export_plus")) })
    FcsConvDefaultsRows()   // fc-settings: saved per-conversion defaults
    GroupDivider()
    // OCR defaults: the OCR tool's output, OCR plus pre-clean / language auto-detect (its own prefs "t5_ocr")
    Label(stringResource(R.string.npf_ocr_output))
    Chips(listOf("searchable" to stringResource(R.string.npf_ocr_searchable), "text" to stringResource(R.string.npf_ocr_text)), NpfPrefs.ocrOutput) { NpfPrefs.setString(K.OCR_OUTPUT, it) }
    val ocr = remember { ctx.getSharedPreferences("t5_ocr", Context.MODE_PRIVATE) }
    var clean by remember { mutableStateOf(ocr.getBoolean("clean", true)) }
    var auto by remember { mutableStateOf(ocr.getBoolean("auto", true)) }
    Sw(R.string.npf_ocr_clean, clean) { clean = it; ocr.edit().putBoolean("clean", it).apply() }
    Sw(R.string.npf_ocr_auto, auto) { auto = it; ocr.edit().putBoolean("auto", it).apply() }
    GroupDivider()
    // compare mark colours: the compare tool's saved default style
    var style by remember { mutableStateOf(com.smartdocs.editor.core.tools5.CompareStyle.saved(ctx)) }
    fun save(s: com.smartdocs.editor.core.tools5.CompareStyle) { style = s; com.smartdocs.editor.core.tools5.CompareStyle.saveDefault(ctx, s) }
    Label(stringResource(R.string.npf_cmp_ins))
    Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(style.insert, SWATCHES) { save(style.copy(insert = it)) } }
    Label(stringResource(R.string.npf_cmp_del))
    Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(style.delete, SWATCHES) { save(style.copy(delete = it)) } }
    Label(stringResource(R.string.npf_cmp_chg))
    Column(Modifier.padding(horizontal = 16.dp)) { NpfColorRow(style.replace, SWATCHES) { save(style.copy(replace = it)) } }
    GroupDivider()
    // font mapping editor
    Label(stringResource(R.string.npf_font_map))
    var map by remember { mutableStateOf(NpfPrefs.fontMap) }
    OutlinedTextField(map, { map = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), minLines = 3, maxLines = 8,
        placeholder = { Text("Helvetica = Arial\nTraditionalArabic = Amiri") })
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = map != NpfPrefs.fontMap, onClick = { NpfPrefs.setString(K.FONT_MAP, map) }) { Text(stringResource(R.string.save)) }
        Text(stringResource(R.string.npf_font_map_n, com.smartdocs.editor.core.data.NpfText.parseFontMap(map).size), style = MaterialTheme.typography.bodySmall)
    }
    Hint(stringResource(R.string.npf_font_map_hint))
}

/** Shown instead of an AI screen while AI is off (Settings switch or offline mode). */
@Composable
fun NpfAiOffScreen(nav: NavHostController) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.tool_ai), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.AutoAwesome, null)
            Text(stringResource(if (NpfPrefs.offline) R.string.npf_ai_off_offline else R.string.npf_ai_off), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { nav.popBackStack(); nav.navigate(Routes.SETTINGS) }) { Text(stringResource(R.string.settings)) }
        }
    }
}
