package com.smartdocs.editor.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.ThemeMode
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.launch

/** Settings grouped like the reference: General · PDF view · Annotations · Read aloud · AI · OCR · Notifications · About. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val s = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun upd(block: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(block) }
    val tiles = Brand.tiles
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // ---- General ----
            GroupCard(stringResource(R.string.ds_set_general)) {
                SwitchRow(stringResource(R.string.settings_autosave), s.autoSave, icon = Icons.Default.Save, tile = tiles.blue) { v -> upd { it.copy(autoSave = v) } }
                GroupDivider()
                GroupRow(stringResource(R.string.settings_appearance), icon = Icons.Default.Palette, tile = tiles.purple)
                Choice(
                    listOf(ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark, ThemeMode.SYSTEM to R.string.theme_system).map { (m, l) -> m to stringResource(l) },
                    s.theme,
                ) { m -> upd { it.copy(theme = m) } }
                GroupDivider()
                GroupRow(stringResource(R.string.settings_language), icon = Icons.Default.Language, tile = tiles.teal)
                Choice(listOf("ar" to "العربية", "en" to "English"), s.language) { v -> upd { it.copy(language = v) } }
                G6GeneralRows(s) { b -> upd(b) }   // gaps6: layout direction, product improvement
            }

            // ---- PDF view ----
            GroupCard(stringResource(R.string.ds_set_pdf_view)) {
                SwitchRow(stringResource(R.string.settings_restore_session), s.restoreSession, icon = Icons.Default.Restore) { v -> upd { it.copy(restoreSession = v) } }
                SwitchRow(stringResource(R.string.settings_highlight_fields), s.highlightFields, icon = Icons.Default.EditNote) { v -> upd { it.copy(highlightFields = v) } }
                SwitchRow(stringResource(R.string.settings_highlight_links), s.highlightLinks, icon = Icons.Default.Link) { v -> upd { it.copy(highlightLinks = v) } }
                SwitchRow(stringResource(R.string.settings_keep_zoom), s.keepZoom, icon = Icons.Default.ZoomIn) { v -> upd { it.copy(keepZoom = v) } }
                SwitchRow(stringResource(R.string.keep_screen_on), s.keepScreenOn, icon = Icons.Default.LightMode) { v -> upd { it.copy(keepScreenOn = v) } }
                SwitchRow(stringResource(R.string.settings_volume_keys), s.volumeKeysFlip, icon = Icons.Default.VolumeUp) { v -> upd { it.copy(volumeKeysFlip = v) } }
                SwitchRow(stringResource(R.string.settings_tap_edges), s.tapEdgesFlip, icon = Icons.Default.TouchApp) { v -> upd { it.copy(tapEdgesFlip = v) } }
                SwitchRow(stringResource(R.string.view_continuous), s.continuousScroll, icon = Icons.Default.ViewDay) { v -> upd { it.copy(continuousScroll = v) } }
                SwitchRow(stringResource(R.string.night_mode), s.nightMode, icon = Icons.Default.DarkMode) { v -> upd { it.copy(nightMode = v) } }
                G6ViewRows(s) { b -> upd(b) }      // gaps6: full screen, volume action, timeout, orientation
            }

            // ---- Document security (nitro-protect): JavaScript, Internet access, file launch ----
            NpSecurityCard(s) { b -> upd(b) }
            Np2TrustCard()      // nitro-protect2: Trust Manager (safe reading, privileged locations, Protected View, attachments, EU trusted list)

            // ---- Sync & transfer (gaps6) ----
            G6SyncCard(s, nav) { b -> upd(b) }
            com.smartdocs.editor.ui.cloud.SyncSettingsSection()   // share6: near-real-time, sync after save, cloud transfers over Wi-Fi only

            // ---- Annotations ----
            GroupCard(stringResource(R.string.ds_set_annotations)) {
                var author by remember(s.authorName) { mutableStateOf(s.authorName) }
                OutlinedTextField(
                    author, { author = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text(stringResource(R.string.settings_author)) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    trailingIcon = { if (author != s.authorName) IconButton(onClick = { upd { it.copy(authorName = author) } }) { Icon(Icons.Default.Check, stringResource(R.string.save)) } },
                )
                SwitchRow(stringResource(R.string.settings_pressure), s.pressureSensitivity, icon = Icons.Default.Gesture) { v -> upd { it.copy(pressureSensitivity = v) } }
            }

            // ---- Read aloud ----
            GroupCard(stringResource(R.string.settings_tts)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    com.smartdocs.editor.ui.common.LabeledSlider(stringResource(R.string.tts_speed), s.ttsRate, 0.5f..2f, { v -> upd { it.copy(ttsRate = v) } }) { "%.1fx".format(it) }
                }
                com.smartdocs.editor.ui.voice.ReadAloudVoiceSection(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            // ---- AI ----
            GroupCard(stringResource(R.string.settings_ai)) {
                G6AiSwitch(s) { b -> upd(b) }      // gaps6: AI master switch
                if (s.aiEnabled) {
                var key by remember(s.aiApiKey) { mutableStateOf(s.aiApiKey) }
                var endpoint by remember(s.aiEndpoint) { mutableStateOf(s.aiEndpoint) }
                var model by remember(s.aiModel) { mutableStateOf(s.aiModel) }
                val fieldMod = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                OutlinedTextField(endpoint, { endpoint = it }, fieldMod, label = { Text(stringResource(R.string.ai_endpoint)) }, singleLine = true, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(model, { model = it }, fieldMod, label = { Text(stringResource(R.string.ai_model)) }, singleLine = true, shape = RoundedCornerShape(16.dp))
                OutlinedTextField(key, { key = it }, fieldMod, label = { Text(stringResource(R.string.ai_api_key)) }, singleLine = true, shape = RoundedCornerShape(16.dp), visualTransformation = PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { upd { it.copy(aiApiKey = key.trim(), aiEndpoint = endpoint.trim().trimEnd('/'), aiModel = model.trim()) } }, shape = PillShape) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = { nav.navigate(Routes.tool("ai_settings")) }, shape = PillShape) { Text(stringResource(R.string.ds_ai_more)) }
                }
                }
            }

            // ---- OCR ----
            GroupCard(stringResource(R.string.tool_ocr)) {
                GroupRow(stringResource(R.string.ocr_language), icon = Icons.Default.Translate, tile = tiles.amber)
                Choice(listOf("ara+eng" to "عربي + إنجليزي", "ara" to "عربي", "eng" to "إنجليزي"), s.ocrLanguages) { v -> upd { it.copy(ocrLanguages = v) } }
            }

            // ---- Notifications ----
            GroupCard(stringResource(R.string.ds_set_notifications)) {
                GroupRow(stringResource(R.string.ds_set_notifications_sub), icon = Icons.Default.Notifications, tile = tiles.rose, onClick = {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(i) }
                })
            }

            // ---- About ----
            GroupCard(stringResource(R.string.settings_about)) {
                GroupRow(stringResource(R.string.v5_help_center), icon = Icons.Default.HelpOutline, tile = tiles.green, onClick = { nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.HELP) })
                GroupRow(stringResource(R.string.v5_trash), icon = Icons.Default.DeleteSweep, tile = tiles.purple, onClick = { nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.TRASH) })
                GroupRow(stringResource(R.string.v5_whats_new), icon = Icons.Default.NewReleases, tile = tiles.teal, onClick = { nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.WHATS_NEW) })
                GroupRow(stringResource(R.string.settings_help), icon = Icons.Default.HelpOutline, tile = tiles.green, onClick = { nav.navigate("help") })
                com.smartdocs.editor.core.ads.AdsPrivacyOptionsItem()   // share6: renders only where UMP requires it
                GroupDivider()
                GroupRow(stringResource(R.string.app_name), stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), icon = Icons.Default.Info, tile = tiles.blue)
            }
        }
    }
}

/** Segmented single choice under a row title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = v == selected, onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}
