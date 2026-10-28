package com.smartdocs.editor.ui.nve.shell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.Favorites
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.tools.ToolDef
import com.smartdocs.editor.ui.tools.ToolUsage
import com.smartdocs.editor.ui.tools.allTools
import com.smartdocs.editor.ui.tools.toolGroupTile

/**
 * Nitro "Favorite Tools": any tool of the Tools tab pinned into a row (Tools tab top and Home), up to
 * [Favorites.MAX]. [editing] = the Tools tab is in "choose favourites" mode (tapping a card stars it).
 */
@Composable
fun NveFavoritesSection(tools: List<ToolDef>, editing: Boolean, onEditing: (Boolean) -> Unit, onOpen: (ToolDef) -> Unit) {
    val ctx = LocalContext.current
    val v = NveShellPrefs.version.intValue
    val favs = remember(v, tools) { NveShellPrefs.favorites(ctx).mapNotNull { id -> tools.firstOrNull { it.id == id } } }
    val title = remember(v) { NveShellPrefs.favoritesTitle(ctx) }
    var rename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title ?: stringResource(R.string.nl_favorites), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(editing, { onEditing(!editing) }, label = { Text(stringResource(if (editing) R.string.nl_done else R.string.nl_fav_choose)) },
                leadingIcon = { Icon(if (editing) Icons.Default.Check else Icons.Default.StarOutline, null, Modifier.size(16.dp)) })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.nl_rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.nl_remove_all)) }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }, enabled = favs.isNotEmpty(),
                        onClick = { menu = false; NveShellPrefs.setFavorites(ctx, Favorites.removeAll()) })
                }
            }
        }
        if (favs.isEmpty()) Text(stringResource(R.string.nl_fav_empty, Favorites.MAX), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(favs, key = { _, t -> "fav_" + t.id }) { i, t ->
                FavChip(t, editing,
                    onClick = { if (editing) NveShellPrefs.toggleFavorite(ctx, t.id) else onOpen(t) },
                    onLeft = { NveShellPrefs.setFavorites(ctx, Favorites.move(favs.map { it.id }, i, i - 1)) },
                    onRight = { NveShellPrefs.setFavorites(ctx, Favorites.move(favs.map { it.id }, i, i + 1)) })
            }
        }
        if (editing) Text(stringResource(R.string.nl_fav_hint, favs.size, Favorites.MAX), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    if (rename) RenameDialog(stringResource(R.string.nl_rename), title ?: "", onOk = { NveShellPrefs.setFavoritesTitle(ctx, it) }) { rename = false }
}

@Composable
private fun FavChip(t: ToolDef, editing: Boolean, onClick: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit) {
    val tile = toolGroupTile(t.group)
    Surface(onClick = onClick, shape = PillShape, color = tile.container) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) IconButton(onClick = onLeft, Modifier.size(26.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nl_move_up), Modifier.size(16.dp)) }
            Icon(t.icon, null, Modifier.size(20.dp), tint = tile.content)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(t.label), style = MaterialTheme.typography.labelLarge, color = tile.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editing) {
                IconButton(onClick = onRight, Modifier.size(26.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.nl_move_down), Modifier.size(16.dp)) }
                Icon(Icons.Default.Close, stringResource(R.string.delete), Modifier.size(16.dp), tint = tile.content)
            }
        }
    }
}

/** Star overlay drawn on a Tools-tab card while choosing favourites. */
@Composable
fun BoxScope.NveFavoriteBadge(id: String) {
    val ctx = LocalContext.current
    val on = remember(NveShellPrefs.version.intValue, id) { id in NveShellPrefs.favorites(ctx) }
    Icon(if (on) Icons.Default.Star else Icons.Default.StarOutline, null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
}

/** Favourites row on Home (only when the user pinned something). */
@Composable
fun NveHomeFavorites(nav: NavHostController) {
    val ctx = LocalContext.current
    val v = NveShellPrefs.version.intValue
    val favs = remember(v) { NveShellPrefs.favorites(ctx).mapNotNull { id -> allTools.firstOrNull { it.id == id } } }
    if (favs.isEmpty()) return
    val title = remember(v) { NveShellPrefs.favoritesTitle(ctx) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title ?: stringResource(R.string.nl_favorites), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(favs, key = { _, t -> "hfav_" + t.id }) { _, t ->
                FavChip(t, false, onClick = { ToolUsage.record(ctx, t.id); nav.navigate(Routes.tool(t.id)) }, onLeft = {}, onRight = {})
            }
        }
    }
}
