package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.nve.shell.HelpRank
import com.smartdocs.editor.core.nve.shell.Nps
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.view5.V5Routes
import com.smartdocs.editor.ui.view5.helpArticles
import kotlinx.coroutines.launch

/** Help entry points of the shell work: help search with keywords, the help assistant (chatbot), guides, survey. */
object NveHelp {
    @Volatile private var query: String? = null
    @Volatile private var ask: String? = null

    /** Command search "Get help on <keywords>": the help centre opens filtered by [q]. */
    fun openWithQuery(nav: NavHostController, q: String) { query = q; runCatching { nav.navigate(V5Routes.HELP) } }

    /** A search box question that starts an assistant conversation (the help assistant, grounded on the help articles). */
    fun askAi(nav: NavHostController, q: String) { ask = q; runCatching { nav.navigate(V5Routes.HELP) } }

    fun takeQuery(): String? = query.also { query = null }
    fun takeAsk(): String? = ask.also { ask = null }

    /** Help articles as (title, body) text. */
    fun articles(ctx: Context): List<Pair<String, String>> = helpArticles.map { (t, b) -> ctx.getString(t) to ctx.getString(b) }
}

/** Extra chips of the help centre (hook in HelpCenterScreen's chip row): guides, assistant, survey. */
@Composable
fun NveHelpChips(nav: NavHostController, query: String) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var guides by remember { mutableStateOf(false) }
    var bot by remember { mutableStateOf(NveHelp.takeAsk()) }
    var survey by remember { mutableStateOf(false) }
    AssistChip(onClick = { guides = true }, label = { Text(stringResource(R.string.nl_guides)) }, leadingIcon = { Icon(Icons.Default.School, null) })
    AssistChip(onClick = { bot = query }, label = { Text(stringResource(R.string.nl_help_bot)) }, leadingIcon = { Icon(Icons.Default.SupportAgent, null) })
    AssistChip(onClick = { survey = true }, label = { Text(stringResource(R.string.nl_nps_title)) }, leadingIcon = { Icon(Icons.Default.ThumbsUpDown, null) })
    if (guides) GuidesDialog(onOpen = { g -> guides = false; scope.launch { val f = NveGuides.create(ctx, app, g); nav.navigate(Routes.reader(Uri.fromFile(f).toString())) } }) { guides = false }
    bot?.let { q -> NveHelpBotDialog(q) { bot = null } }
    if (survey) NveNpsDialog { survey = false }
}

private data class BotMsg(val mine: Boolean, val text: String)

/**
 * Support chatbot ("how do I…"): answers from the help-centre articles. With AI enabled the assistant writes the
 * answer grounded on the best-matching articles; offline (or AI off) it shows those articles directly.
 */
@Composable
fun NveHelpBotDialog(initial: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<BotMsg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val articles = remember { NveHelp.articles(ctx) }
    val listState = rememberLazyListState()
    val noMatch = stringResource(R.string.nl_bot_no_match)
    fun ask(q: String) {
        if (q.isBlank()) return
        msgs += BotMsg(true, q); input = ""
        val hits = HelpRank.rank(articles, q)
        val grounded = hits.joinToString("\n\n") { i -> "## " + articles[i].first + "\n" + articles[i].second }
        val offline = if (hits.isEmpty()) noMatch else hits.joinToString("\n\n") { i -> "• " + articles[i].first + "\n" + articles[i].second }
        if (!settings.aiEnabled || !AiClient.available(settings)) { msgs += BotMsg(false, offline); return }
        busy = true
        scope.launch {
            val sys = "You are the SmartDocs PDF app help assistant. Answer the user's 'how do I' question briefly, in the user's language, " +
                "using ONLY these help articles. If they do not cover it, say so and suggest the Help centre.\n\n" + grounded.ifBlank { articles.joinToString("\n\n") { "## " + it.first + "\n" + it.second }.take(12_000) }
            val history = msgs.takeLast(6).map { ChatMessage(if (it.mine) "user" else "assistant", it.text) }
            val answer = runCatching { AiClient(settings).complete(listOf(ChatMessage("system", sys)) + history) }.getOrNull()
            msgs += BotMsg(false, answer?.takeIf { it.isNotBlank() } ?: offline)
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (initial.isNotBlank()) ask(initial) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.8f), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SupportAgent, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.nl_help_bot), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                if (msgs.isEmpty()) Text(stringResource(R.string.nl_bot_intro), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    items(msgs) { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                            Surface(color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                                Text(m.text, Modifier.padding(10.dp).widthIn(max = 420.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.nl_bot_hint)) }, maxLines = 3)
                    IconButton(onClick = { ask(input) }, enabled = !busy && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.nl_send)) }
                }
            }
        }
    }
}

/** In-app NPS survey: "How likely are you to recommend SmartDocs?" 0-10 + comment, kept locally, optional e-mail. */
@Composable
fun NveNpsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var score by remember { mutableIntStateOf(-1) }
    var comment by remember { mutableStateOf("") }
    var send by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.ThumbsUpDown, null) }, title = { Text(stringResource(R.string.nl_nps_title)) },
        text = {
            Column {
                Text(stringResource(R.string.nl_nps_q), style = MaterialTheme.typography.bodyMedium)
                FlowRowCompat {
                    (0..10).forEach { n -> FilterChip(score == n, { score = n }, label = { Text(n.toString()) }, modifier = Modifier.padding(2.dp)) }
                }
                Row { Text(stringResource(R.string.nl_nps_low), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)); Text(stringResource(R.string.nl_nps_high), style = MaterialTheme.typography.labelSmall) }
                OutlinedTextField(comment, { comment = it.take(1000) }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text(stringResource(R.string.nl_nps_comment)) }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(send, { send = it }); Text(stringResource(R.string.nl_nps_send), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = score >= 0, onClick = {
                NveNpsStore.save(ctx, score, comment)
                if (send) {
                    val body = "NPS: $score/10\n\n$comment\n\n" + com.smartdocs.editor.ui.help.deviceInfo(ctx)
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "SmartDocs feedback (NPS $score)"); putExtra(Intent.EXTRA_TEXT, body) }, null)) }
                }
                onDismiss()
            }) { Text(stringResource(R.string.nl_send)) }
        },
        dismissButton = { TextButton(onClick = { NveNpsStore.markAnswered(ctx); onDismiss() }) { Text(stringResource(R.string.nl_not_now)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) { FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { content() } }

/** Local NPS answers + the "ask once" bookkeeping (prefs nve_shell). */
object NveNpsStore {
    fun save(ctx: Context, score: Int, comment: String) {
        val p = NveShellPrefs.prefs(ctx)
        val list = p.getStringSet("nps", emptySet())!!.toMutableSet().apply { add(Nps.encode(System.currentTimeMillis(), score, comment)) }
        p.edit().putStringSet("nps", list).putBoolean("nps_answered", true).apply()
    }
    fun markAnswered(ctx: Context) { NveShellPrefs.prefs(ctx).edit().putBoolean("nps_answered", true).apply() }
    fun answers(ctx: Context): List<Triple<Long, Int, String>> = NveShellPrefs.prefs(ctx).getStringSet("nps", emptySet())!!.mapNotNull { Nps.decode(it) }.sortedBy { it.first }

    /** Count a launch; true when the survey should be shown now. */
    @Volatile private var counted = false

    fun onLaunch(ctx: Context): Boolean {
        if (counted) return false
        counted = true
        val p = NveShellPrefs.prefs(ctx)
        val now = System.currentTimeMillis()
        val installed = p.getLong("first_launch", 0L).takeIf { it > 0 } ?: now.also { p.edit().putLong("first_launch", it).apply() }
        val n = p.getInt("launches", 0) + 1
        p.edit().putInt("launches", n).apply()
        return Nps.shouldAsk(n, installed, now, p.getBoolean("nps_answered", false))
    }
}

/** Asks for the NPS survey once, after a week of use (hook once on Home). */
@Composable
fun NveNpsOnce() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = NveNpsStore.onLaunch(ctx) }
    if (show) NveNpsDialog { show = false }
}
