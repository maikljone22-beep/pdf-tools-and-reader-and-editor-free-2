package com.smartdocs.editor.ui.view5

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.smartdocs.editor.R
import com.smartdocs.editor.core.view5.CommentFilter
import com.smartdocs.editor.ui.common.AppTopBar

/** Routes added by view5 (`view5Routes(nav)` inside the NavHost). */
object V5Routes {
    const val TRASH = "v5_trash"
    const val HELP = "v5_help"
    const val WHATS_NEW = "v5_whats_new"
}

fun NavGraphBuilder.view5Routes(nav: NavHostController) {
    composable(V5Routes.TRASH) { TrashScreen(nav) }
    composable(V5Routes.HELP) { HelpCenterScreen(nav) }
    composable(V5Routes.WHATS_NEW) { WhatsNewScreen(nav) }
}

/** Offline help articles: (title, body) string resources. */
val helpArticles = listOf(
    R.string.v5_help_t_measure to R.string.v5_help_b_measure,
    R.string.v5_help_t_comments to R.string.v5_help_b_comments,
    R.string.v5_help_t_manager to R.string.v5_help_b_manager,
    R.string.v5_help_t_stamps to R.string.v5_help_b_stamps,
    R.string.v5_help_t_reflow to R.string.v5_help_b_reflow,
    R.string.v5_help_t_split to R.string.v5_help_b_split,
    R.string.v5_help_t_present to R.string.v5_help_b_present,
    R.string.v5_help_t_overlays to R.string.v5_help_b_overlays,
    R.string.v5_help_t_history to R.string.v5_help_b_history,
    R.string.v5_help_t_commands to R.string.v5_help_b_commands,
    R.string.v5_help_t_trash to R.string.v5_help_b_trash,
    R.string.v5_help_t_print to R.string.v5_help_b_print,
    R.string.v5_help_t_tts to R.string.v5_help_b_tts,
)

@Composable
fun HelpCenterScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf(com.smartdocs.editor.ui.nve.shell.NveHelp.takeQuery() ?: "") }   // NVE-shell: "Get help on <keywords>"
    val shown = remember(q) {
        val f = CommentFilter.fold(q.trim())
        helpArticles.filter { (t, b) -> f.isEmpty() || f.split(' ').all { w -> CommentFilter.fold(ctx.getString(t) + " " + ctx.getString(b)).contains(w) } }
    }
    var open by remember { mutableStateOf<Int?>(null) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.v5_help_center), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(stringResource(R.string.v5_help_search)) })
            Row(Modifier.padding(vertical = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav.navigate(V5Routes.WHATS_NEW) }, label = { Text(stringResource(R.string.v5_whats_new)) }, leadingIcon = { Icon(Icons.Default.NewReleases, null) })
                AssistChip(onClick = { runCatching { nav.navigate("help") } }, label = { Text(stringResource(R.string.v5_faq_contact)) }, leadingIcon = { Icon(Icons.Default.SupportAgent, null) })
                // ai6 (§41 support chatbot): ask the assistant about the app
                if (com.smartdocs.editor.LocalSettings.current.aiEnabled) AssistChip(onClick = { nav.navigate(com.smartdocs.editor.ui.Routes.tool("ai_help")) },
                    label = { Text(stringResource(R.string.ai6h_help_chat)) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
            }
            // NVE-shell: feature guides, help assistant (chatbot), feedback survey
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.smartdocs.editor.ui.nve.shell.NveHelpChips(nav, q)
            }
            if (shown.isEmpty()) Text(stringResource(R.string.v5_help_none), Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.first }) { (t, b) ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize().clickable { open = if (open == t) null else t }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(t), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Icon(if (open == t) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                            }
                            if (open == t) { Spacer(Modifier.height(8.dp)); Text(stringResource(b), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

private val whatsNew = listOf(
    Icons.Default.Straighten to R.string.v5_new_measure, Icons.Default.Comment to R.string.v5_new_comments, Icons.Default.Forum to R.string.v5_new_manager,
    Icons.Default.Approval to R.string.v5_new_stamps, Icons.Default.ViewHeadline to R.string.v5_new_reflow, Icons.Default.VerticalSplit to R.string.v5_new_split,
    Icons.Default.Slideshow to R.string.v5_new_present, Icons.Default.Grid4x4 to R.string.v5_new_overlays, Icons.Default.Bolt to R.string.v5_new_commands,
    Icons.Default.DeleteSweep to R.string.v5_new_trash, Icons.Default.Print to R.string.v5_new_print, Icons.Default.Repeat to R.string.v5_new_tts,
)

@Composable
fun WhatsNewScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    Scaffold(topBar = { AppTopBar(stringResource(R.string.v5_whats_new), onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item { Text(stringResource(R.string.v5_whats_new_version, WhatsNew.versionName(ctx)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp)) }
            items(whatsNew) { (icon, text) ->
                ListItem(leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text(stringResource(text)) })
            }
        }
    }
}

object WhatsNew {
    @Suppress("DEPRECATION")
    fun versionCode(ctx: Context): Long = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun versionName(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")
}

/** Shows "What's new" once after each update (hook: call once in HomeScreen). First installs are skipped. */
@Composable
fun WhatsNewOnce(nav: NavHostController) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences(V5State.PREFS, Context.MODE_PRIVATE)
        val cur = WhatsNew.versionCode(ctx)
        val seen = p.getLong("whats_new_seen", -1L)
        p.edit().putLong("whats_new_seen", cur).apply()
        if (seen in 0 until cur) runCatching { nav.navigate(V5Routes.WHATS_NEW) }
    }
}
