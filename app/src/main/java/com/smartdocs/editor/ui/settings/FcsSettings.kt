package com.smartdocs.editor.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Cv6Catalog
import com.smartdocs.editor.core.convert6.Cv6Defaults
import com.smartdocs.editor.core.data.FcsPrefs
import com.smartdocs.editor.core.data.NpfPrefs
import com.smartdocs.editor.core.util.DebugLog
import com.smartdocs.editor.ui.convert6.title
import com.smartdocs.editor.ui.tools5.TO_PDF_DEFAULTS
import com.smartdocs.editor.ui.view5.V5Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// fc-settings: Settings rows added for the final check (FINAL_CHECK.md, area nav/settings). Hooked into
// NpfSettingsScreen (conversion defaults, general, notifications, accessibility sections).

@Composable
private fun FcsLabel(text: String) = Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge)

@Composable
private fun FcsHint(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Saved per-conversion defaults ("Save as default" in the converters): list, forget one, forget all. */
@Composable
fun FcsConvDefaultsRows() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val ids = remember(version) { Cv6Defaults.savedIds(ctx) }
    val screens = remember(version) { Cv6Defaults.savedScreens(ctx) }
    FcsLabel(stringResource(R.string.fcs_cv_title))
    if (ids.isEmpty() && screens.isEmpty()) { FcsHint(stringResource(R.string.fcs_cv_none)); return }
    val forget = stringResource(R.string.fcs_cv_forget_default)
    ids.forEach { id ->
        val name = Cv6Catalog.byId(id)?.title(ctx) ?: id
        GroupRow(name, icon = Icons.Default.Tune, trailing = { IconButton(onClick = { Cv6Defaults.clear(ctx, id); version++ }) { Icon(Icons.Default.Clear, forget) } })
    }
    screens.forEach { sc ->
        val name = if (sc == TO_PDF_DEFAULTS) stringResource(R.string.npf_to_pdf) else sc
        GroupRow(name, icon = Icons.Default.Tune, trailing = { IconButton(onClick = { Cv6Defaults.clearScreen(ctx, sc); version++ }) { Icon(Icons.Default.Clear, forget) } })
    }
    TextButton(onClick = { Cv6Defaults.clearAll(ctx); version++ }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.fcs_cv_forget_all)) }
}

/** General: pinned-command position, ConnectedPDF switch, debug log. */
@Composable
fun FcsGeneralRows() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // quick access (pinned commands) position
    var qat by remember { mutableStateOf(V5Commands.qatPosition(ctx)) }
    FcsLabel(stringResource(R.string.fcs_qat_pos))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(V5Commands.QAT_TOP to R.string.fcs_qat_top, V5Commands.QAT_BOTTOM to R.string.fcs_qat_bottom).forEach { (k, l) ->
            FilterChip(qat == k, { V5Commands.setQatPosition(ctx, k); qat = k }, label = { Text(stringResource(l)) })
        }
    }
    // Foxit §33 "Disable ConnectedPDF"
    SwitchRow(stringResource(R.string.fcs_connected_on), !FcsPrefs.connectedOff, stringResource(R.string.fcs_connected_on_sub), icon = Icons.Default.Link) { FcsPrefs.setConnectedOff(!it) }
    // Foxit §37 "Startup: debug mode"
    SwitchRow(stringResource(R.string.fcs_debug), FcsPrefs.debugLog, stringResource(R.string.fcs_debug_sub), icon = Icons.Default.BugReport) { v ->
        FcsPrefs.setDebugLog(v)
        if (v) DebugLog.log("app", "debug log on")
    }
    if (FcsPrefs.debugLog) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                val f = withContext(Dispatchers.IO) { runCatching { DebugLog.export(ctx) }.getOrNull() } ?: return@launch
                runCatching {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fcs_debug_share)) }
        TextButton(onClick = { DebugLog.clear(ctx) }) { Text(stringResource(R.string.fcs_debug_clear)) }
    }
}

/** Per-message notification settings: one row with a gear per notification channel of the app (Foxit §37). */
@Composable
fun FcsNotificationChannels() {
    val ctx = LocalContext.current
    if (Build.VERSION.SDK_INT < 26) return
    val channels = remember { runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notificationChannels.sortedBy { it.name.toString() } }.getOrDefault(emptyList()) }
    FcsLabel(stringResource(R.string.fcs_n_channels))
    if (channels.isEmpty()) { FcsHint(stringResource(R.string.fcs_n_channels_none)); return }
    val gear = stringResource(R.string.fcs_n_gear)
    channels.forEach { ch ->
        fun open() {
            val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ch.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }
        val off = ch.importance == NotificationManager.IMPORTANCE_NONE
        GroupRow(ch.name.toString(), if (off) stringResource(R.string.fcs_n_off) else null, icon = Icons.Default.Notifications, onClick = ::open,
            trailing = { IconButton(onClick = ::open) { Icon(Icons.Default.Settings, gear) } })
    }
}

/** Accessibility Setup Assistant (Foxit 34.26): a short wizard over the reading and contrast preferences. */
@Composable
fun FcsA11yAssistantRow() {
    var open by remember { mutableStateOf(false) }
    GroupRow(stringResource(R.string.fcs_a11y_assistant), stringResource(R.string.fcs_a11y_assistant_sub), icon = Icons.Default.AccessibilityNew, onClick = { open = true })
    if (open) FcsA11yAssistant { open = false }
}

@Composable
fun FcsA11yAssistant(onDone: () -> Unit) {
    val app = LocalApp.current
    val s = LocalSettings.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val last = 3
    // the choices are written when "Finish" is pressed; Cancel leaves everything as it was
    var contrast by remember { mutableStateOf(NpfPrefs.a11yOverride) }
    var colors by remember { mutableStateOf(NpfPrefs.a11yBackground to NpfPrefs.a11yText) }
    var order by remember { mutableStateOf(NpfPrefs.readingOrder) }
    var layout by remember { mutableStateOf(NpfPrefs.defaultLayout) }
    var zoom by remember { mutableStateOf(NpfPrefs.defaultZoom) }
    var rate by remember { mutableFloatStateOf(s.ttsRate) }
    var screenOn by remember { mutableStateOf(s.keepScreenOn) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.fcs_a11y_assistant) + "  (" + (step + 1) + "/" + (last + 1) + ")") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    0 -> {
                        Text(stringResource(R.string.fcs_a11y_s1))
                        SwitchRow(stringResource(R.string.npf_a11y_override), contrast) { contrast = it }
                        if (contrast) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0x000000 to 0xFFFF00, 0x000000 to 0xFFFFFF, 0xFFFFFF to 0x000000, 0x0D1B2A to 0xE0E1DD).forEach { (bg, fg) ->
                                FilterChip(colors == bg to fg, { colors = bg to fg }, label = { Text("Aa", color = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or fg)) },
                                    colors = FilterChipDefaults.filterChipColors(containerColor = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or bg), selectedContainerColor = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or bg)))
                            }
                        }
                    }
                    1 -> {
                        Text(stringResource(R.string.fcs_a11y_s2))
                        listOf("infer" to R.string.npf_read_infer, "raw" to R.string.npf_read_raw).forEach { (k, l) ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(order == k, { order = k }); Text(stringResource(l)) }
                        }
                    }
                    2 -> {
                        Text(stringResource(R.string.fcs_a11y_s3))
                        listOf("single" to R.string.fcs_a11y_single, "continuous" to R.string.fcs_a11y_continuous, "auto" to R.string.fcs_a11y_auto).forEach { (k, l) ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(layout == k, { layout = k }); Text(stringResource(l)) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("width" to stringResource(R.string.fit_width), "page" to stringResource(R.string.fit_page), "z150" to "150%", "z200" to "200%").forEach { (k, l) ->
                                FilterChip(zoom == k, { zoom = k }, label = { Text(l) })
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.fcs_a11y_s4))
                        com.smartdocs.editor.ui.common.LabeledSlider(stringResource(R.string.tts_speed), rate, 0.5f..2f, { rate = it }) { "%.1fx".format(it) }
                        SwitchRow(stringResource(R.string.keep_screen_on), screenOn) { screenOn = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (step < last) step++ else {
                    NpfPrefs.setBool(NpfPrefs.K.A11Y_ON, contrast)
                    if (contrast) { NpfPrefs.setInt(NpfPrefs.K.A11Y_BG, colors.first); NpfPrefs.setInt(NpfPrefs.K.A11Y_FG, colors.second) }
                    NpfPrefs.setString(NpfPrefs.K.READ_ORDER, order)
                    NpfPrefs.setString(NpfPrefs.K.LAYOUT, layout)
                    NpfPrefs.setString(NpfPrefs.K.ZOOM, zoom)
                    val r = rate; val on = screenOn
                    scope.launch { app.settings.update { it.copy(ttsRate = r, keepScreenOn = on) } }
                    onDone()
                }
            }) { Text(stringResource(if (step < last) R.string.fcs_next else R.string.fcs_finish)) }
        },
        dismissButton = {
            Row {
                if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.fcs_prev)) }
                TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
