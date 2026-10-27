package com.smartdocs.editor.ui.help

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.R
import com.smartdocs.editor.core.review.AcctPrefs
import com.smartdocs.editor.core.review.BuildCfg
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.util.CrashLog
import com.smartdocs.editor.ui.account.AcctRoutes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import com.smartdocs.editor.ui.settings.SwitchRow

private val faqs = listOf(
    R.string.ac_faq1_q to R.string.ac_faq1_a, R.string.ac_faq2_q to R.string.ac_faq2_a,
    R.string.ac_faq3_q to R.string.ac_faq3_a, R.string.ac_faq4_q to R.string.ac_faq4_a,
    R.string.ac_faq5_q to R.string.ac_faq5_a, R.string.ac_faq6_q to R.string.ac_faq6_a,
    R.string.ac_faq7_q to R.string.ac_faq7_a, R.string.ac_faq8_q to R.string.ac_faq8_a,
)

/** Route "help": FAQ, feedback, legal, activation, version. */
@Composable
fun HelpScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val tiles = Brand.tiles
    var analytics by remember { mutableStateOf(AcctPrefs.analyticsOptIn(ctx)) }
    var feedbackDialog by remember { mutableStateOf(false) }
    val tipsReset = stringResource(R.string.ac_tips_reset)

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ac_help_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // header
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconTile(Icons.Default.PictureAsPdf, tiles.blue, size = 72.dp, iconSize = 40.dp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            GroupCard(stringResource(R.string.ac_faq)) {
                faqs.forEachIndexed { i, (q, a) ->
                    if (i > 0) GroupDivider()
                    FaqItem(stringResource(q), stringResource(a))
                }
            }

            GroupCard(stringResource(R.string.ac_support)) {
                GroupRow(stringResource(R.string.ac_feedback), stringResource(R.string.ac_feedback_sub), icon = Icons.Default.Feedback, tile = tiles.teal, onClick = { feedbackDialog = true })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_rate), stringResource(R.string.ac_rate_sub), icon = Icons.Default.StarRate, tile = tiles.amber, onClick = { openStore(ctx) })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_show_tips), icon = Icons.Default.TipsAndUpdates, tile = tiles.purple, onClick = {
                    AcctPrefs.resetCoachMarks(ctx); Toast.makeText(ctx, tipsReset, Toast.LENGTH_SHORT).show()
                })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_activation), stringResource(R.string.ac_activation_sub), icon = Icons.Default.Key, tile = tiles.green, onClick = { nav.navigate(AcctRoutes.ACTIVATION) })
            }

            GroupCard(stringResource(R.string.ac_privacy_section)) {
                SwitchRow(stringResource(R.string.ac_analytics_title), analytics, stringResource(R.string.ac_analytics_short), icon = Icons.Default.Insights, tile = tiles.blue) {
                    analytics = it; AcctPrefs.setAnalyticsOptIn(ctx, it)
                }
                GroupDivider()
                GroupRow(stringResource(R.string.ac_privacy_policy), icon = Icons.Default.Policy, tile = tiles.teal, onClick = { nav.navigate(AcctRoutes.PRIVACY) })
                GroupDivider()
                GroupRow(stringResource(R.string.ac_licenses), icon = Icons.Default.Gavel, tile = tiles.rose, onClick = { nav.navigate(AcctRoutes.LICENSES) })
            }

            GroupCard(stringResource(R.string.ac_about)) {
                GroupRow(stringResource(R.string.app_name), stringResource(R.string.settings_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"), icon = Icons.Default.Info, tile = tiles.blue)
            }
        }
    }

    if (feedbackDialog) {
        val hasCrash = remember { CrashLog.pending(ctx) != null || CrashLog.file(ctx).exists() }
        var attach by remember { mutableStateOf(hasCrash) }
        var attachDoc by remember { mutableStateOf(false) }   // NVE-shell: attach the crashing PDF
        AlertDialog(
            onDismissRequest = { feedbackDialog = false },
            icon = { Icon(Icons.Default.Feedback, null) },
            title = { Text(stringResource(R.string.ac_feedback)) },
            text = {
                Column {
                    Text(stringResource(R.string.ac_feedback_info), style = MaterialTheme.typography.bodyMedium)
                    if (hasCrash) Row(Modifier.fillMaxWidth().clickable { attach = !attach }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(attach, { attach = it }); Text(stringResource(R.string.ac_feedback_crash))
                    }
                    if (hasCrash) com.smartdocs.editor.ui.nve.shell.NveCrashAttachRow(attachDoc) { attachDoc = it }   // NVE-shell
                }
            },
            confirmButton = { TextButton(onClick = {
                feedbackDialog = false
                if (attachDoc) runCatching { ctx.startActivity(com.smartdocs.editor.ui.nve.shell.NveCrash.shareIntent(ctx, (CrashLog.pending(ctx)?.take(20_000) ?: "") + "\n" + deviceInfo(ctx), true)) }   // NVE-shell
                else sendFeedback(ctx, attach)
            }) { Text(stringResource(R.string.ac_send)) } },
            dismissButton = { TextButton(onClick = { feedbackDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FaqItem(q: String, a: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Device + app details appended to feedback mails. */
fun deviceInfo(ctx: Context): String = buildString {
    appendLine("—")
    appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Locale: ${ctx.resources.configuration.locales[0]}")
}

/** Opens the mail app (or a share sheet when no support address is configured). */
fun sendFeedback(ctx: Context, attachCrash: Boolean) {
    val subject = ctx.getString(R.string.ac_feedback_subject, ctx.getString(R.string.app_name), BuildConfig.VERSION_NAME)
    val crash = if (attachCrash) CrashLog.pending(ctx)?.take(20_000) else null
    val body = buildString {
        appendLine(ctx.getString(R.string.ac_feedback_hint)); appendLine(); appendLine(); appendLine()
        append(deviceInfo(ctx))
        if (crash != null) { appendLine(); appendLine("Crash log:"); appendLine(crash) }
    }
    val to = BuildCfg.string("SUPPORT_EMAIL")
    val intent = if (to.isNotEmpty()) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to)); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
    } else Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body) }, null)
    try { ctx.startActivity(intent) } catch (e: ActivityNotFoundException) {
        runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body) }, null)) }
    }
}

fun openStore(ctx: Context) {
    val pkg = ctx.packageName
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
    catch (e: ActivityNotFoundException) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))) } }
}
