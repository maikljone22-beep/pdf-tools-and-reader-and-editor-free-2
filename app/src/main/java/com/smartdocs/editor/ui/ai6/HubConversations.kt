package com.smartdocs.editor.ui.ai6

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.SavedChat
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ai6 (AI-5): where a saved chat reopens — its Space, or the hub over its file(s), with the chat id in the fragment. */
object ChatLinks {
    /** Files of a chat: stored list, else the files its citations point at (chats saved before ai6). */
    fun files(c: SavedChat): List<String> = c.files.ifEmpty { c.messages.flatMap { m -> m.citations.map { it.filePath } }.distinct() }

    /** Hub `uri` argument, or null when the chat's documents no longer exist. */
    fun hubArg(c: SavedChat, exists: (String) -> Boolean = { File(it).exists() }): String? {
        val frag = "#tab=chat&chat=" + c.id
        if (c.scope.startsWith("space:")) return c.scope + frag
        val fs = files(c).filter(exists)
        return when {
            fs.isEmpty() -> null
            fs.size == 1 -> Uri.fromFile(File(fs[0])).toString() + frag
            else -> "files:" + fs.joinToString("|") + frag
        }
    }

    fun label(c: SavedChat, spaceName: (String) -> String?): String =
        if (c.scope.startsWith("space:")) spaceName(c.scope.removePrefix("space:")).orEmpty()
        else files(c).joinToString("، ") { File(it).nameWithoutExtension }
}

private fun openChat(nav: NavHostController, c: SavedChat): Boolean {
    val arg = ChatLinks.hubArg(c) ?: return false
    nav.navigate(Routes.tool("ai", arg)); return true
}

/** Route `tool/ai_chats`: every saved conversation (documents and Spaces), newest first; open or delete. */
@Composable
fun ConversationsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val store = remember { AiStore(ctx) }
    var chats by remember { mutableStateOf<List<SavedChat>?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { chats = withContext(Dispatchers.IO) { store.allChats() } }
    val spaces = remember { store.spaces().associate { it.id to it.name } }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.ai6h_conversations), onBack = { nav.popBackStack() }) }) { pad ->
        val list = chats
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Default.Forum, stringResource(R.string.a5_no_history), Modifier.padding(pad).fillMaxSize())
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { c ->
                    ConversationRow(c, spaces, onOpen = { if (!openChat(nav, c)) missing = true }, onDelete = { store.deleteChat(c.id); chats = store.allChats() })
                }
            }
        }
    }
    if (missing) AlertDialog(onDismissRequest = { missing = false }, text = { Text(stringResource(R.string.ai6h_chat_missing)) },
        confirmButton = { TextButton(onClick = { missing = false }) { Text(stringResource(R.string.a5_close)) } })
}

@Composable
private fun ConversationRow(c: SavedChat, spaces: Map<String, String>, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c.scope.startsWith("space:")) Icons.Default.Workspaces else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { "…" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ChatLinks.label(c) { spaces[it] } + " · " + android.text.format.DateUtils.getRelativeTimeSpanString(c.updated) + " · " + ctx.getString(R.string.a5_n_messages, c.messages.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.a5_delete)) }
        }
    }
}

/** Home card (AI-5): the three latest conversations and "all". Hidden when AI is off or there are none. */
@Composable
fun HomeConversationsCard(nav: NavHostController) {
    val settings = LocalSettings.current
    if (!settings.aiEnabled) return
    val ctx = LocalContext.current
    var chats by remember { mutableStateOf<List<SavedChat>>(emptyList()) }
    LaunchedEffect(Unit) { chats = withContext(Dispatchers.IO) { runCatching { AiStore(ctx).allChats().take(3) }.getOrDefault(emptyList()) } }
    if (chats.isEmpty()) return
    val spaces = remember(chats) { AiStore(ctx).spaces().associate { it.id to it.name } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai6h_conversations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { nav.navigate(Routes.tool("ai_chats")) }) { Text(stringResource(R.string.ai6h_all)) }
        }
        chats.forEach { c -> ConversationRow(c, spaces, onOpen = { openChat(nav, c) }, onDelete = null) }
    }
}
