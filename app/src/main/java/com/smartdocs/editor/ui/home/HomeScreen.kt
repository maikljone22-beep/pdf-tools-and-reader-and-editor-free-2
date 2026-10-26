package com.smartdocs.editor.ui.home

import com.smartdocs.editor.ui.onboarding.coachTarget
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.RecentDoc
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.TileColors
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.*
import com.smartdocs.editor.ui.tools.ToolUsage
import java.util.Calendar

private data class Quick(val icon: ImageVector, val label: Int, val tool: String, val tile: @Composable () -> TileColors)

private const val PREFS_HOME = "home_ui"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    com.smartdocs.editor.ui.view5.WhatsNewOnce(nav)
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val recents by app.db.docs().recents().collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by app.db.docs().favorites().collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by app.db.tags().tags().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedTag by rememberSaveable { mutableStateOf<Long?>(null) }
    val tagged by (selectedTag?.let { app.db.tags().docsForTag(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())
    var menuDoc by remember { mutableStateOf<RecentDoc?>(null) }
    var addSheet by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val prefs = remember { ctx.applicationContext.getSharedPreferences(PREFS_HOME, 0) }
    var grid by remember { mutableStateOf(prefs.getBoolean("recent_grid", false)) }
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString())) } }
    fun goTab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    }
    fun openDoc(d: RecentDoc) = nav.navigate(Routes.reader(d.uri, d.lastPage))
    fun runTool(id: String) { if (id == "open") pick() else { ToolUsage.record(ctx, id); nav.navigate(Routes.tool(id)) } }

    val quick = listOf(
        Quick(Icons.Default.FolderOpen, R.string.open_file, "open") { Brand.tiles.teal },
        Quick(Icons.Default.DocumentScanner, R.string.create_from_scan, "scan") { Brand.tiles.rose },
        Quick(Icons.Default.Image, R.string.create_from_images, "images_to_pdf") { Brand.tiles.purple },
        Quick(Icons.Default.NoteAdd, R.string.create_blank, "blank") { Brand.tiles.blue },
    )
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addSheet = true }, expanded = fabExpanded,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.create_new)) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val cols = ((maxWidth - 32.dp) / 128.dp).toInt().coerceIn(2, 8)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 104.dp)) {
                // ---- header ----
                item(key = "header") { Header(settings.authorName, onSettings = { nav.navigate(Routes.SETTINGS) }, onAccount = { goTab(Routes.ACCOUNT) }) }
                item(key = "search") { SearchPill(query, { query = it }) }

                val q = query.trim()
                if (q.isNotEmpty()) {
                    val hits = (recents + favorites).distinctBy { it.uri }.filter { it.name.contains(q, ignoreCase = true) }
                    item(key = "search_title") { SectionTitle(stringResource(R.string.search_results, hits.size)) }
                    if (hits.isEmpty()) item(key = "search_empty") { EmptyIllustration(R.drawable.ds_empty_search, stringResource(R.string.ds_no_results)) }
                    items(hits, key = { "s" + it.uri }) { d -> DocRow(d, onOpen = { openDoc(d) }, onMenu = { menuDoc = d }, modifier = Modifier.animateItem()) }
                    item(key = "search_files") {
                        GoRow(stringResource(R.string.ds_search_all_files), Icons.Default.ManageSearch) { goTab(Routes.FILES) }
                    }
                    return@LazyColumn
                }

                // ---- quick create ----
                item(key = "quick") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(quick, key = { it.tool }) { qk ->
                            val coach = when (qk.tool) { "open" -> com.smartdocs.editor.ui.onboarding.CoachIds.HOME_OPEN; "scan" -> com.smartdocs.editor.ui.onboarding.CoachIds.HOME_SCAN; else -> null }
                            Box(if (coach != null) Modifier.coachTarget(coach) else Modifier) {
                                QuickCard(qk.icon, stringResource(qk.label), qk.tile()) { runTool(qk.tool) }
                            }
                        }
                    }
                }

                // ---- continue reading ----
                recents.firstOrNull()?.let { last ->
                    item(key = "continue") { ContinueCard(last) { openDoc(last) } }
                }

                // ---- favorites ----
                if (favorites.isNotEmpty()) {
                    item(key = "fav_title") { SectionTitle(stringResource(R.string.favorites)) }
                    item(key = "fav_row") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(favorites, key = { "f" + it.uri }) { d -> DocCard(d, Modifier.width(120.dp).animateItem(), onMenu = { menuDoc = d }) { openDoc(d) } }
                        }
                    }
                }

                // ---- tags ----
                if (tags.isNotEmpty()) item(key = "tags") {
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = selectedTag == null, onClick = { selectedTag = null }, label = { Text(stringResource(R.string.recent_files)) },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) }, shape = PillShape)
                        }
                        items(tags, key = { "tag" + it.id }) { t ->
                            FilterChip(
                                selected = selectedTag == t.id, onClick = { selectedTag = if (selectedTag == t.id) null else t.id },
                                label = { Text(t.name) }, shape = PillShape,
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color))) },
                            )
                        }
                    }
                }

                // ---- recent / tagged documents ----
                val docs = if (selectedTag != null) tagged else recents
                item(key = "recent_title") {
                    SectionTitle(tags.firstOrNull { it.id == selectedTag }?.name ?: stringResource(R.string.recent_files)) {
                        if (docs.isNotEmpty()) IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("recent_grid", grid).apply() }) {
                            AnimatedContent(grid, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "viewmode") { g ->
                                Icon(if (g) Icons.Default.ViewList else Icons.Default.GridView, stringResource(if (g) R.string.view_list else R.string.view_grid))
                            }
                        }
                    }
                }
                if (docs.isEmpty()) item(key = "recent_empty") {
                    EmptyIllustration(
                        R.drawable.ds_empty_docs, stringResource(R.string.no_recent), stringResource(R.string.ds_home_empty_sub),
                        action = { Button(onClick = pick, shape = PillShape) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_file)) } },
                    )
                } else if (grid) {
                    items(docs.chunked(cols), key = { row -> "g" + row.first().uri }) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).animateItem(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { d -> DocCard(d, Modifier.weight(1f), onMenu = { menuDoc = d }) { openDoc(d) } }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                    items(docs, key = { "r" + it.uri }) { d -> DocRow(d, onOpen = { openDoc(d) }, onMenu = { menuDoc = d }, modifier = Modifier.animateItem()) }
                }
            }
        }
    }

    if (addSheet) AddSheet(onDismiss = { addSheet = false }) { id -> addSheet = false; runTool(id) }
    menuDoc?.let { d -> DocMenuSheet(d, tags, nav, onDismiss = { menuDoc = null }) }
}

@Composable
private fun Header(author: String, onSettings: () -> Unit, onAccount: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = stringResource(if (hour in 4..11) R.string.ds_good_morning else R.string.ds_good_evening)
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (author.isBlank()) greet else "$greet، ${author.trim()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
        Box(
            Modifier.padding(end = 8.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onAccount),
            contentAlignment = Alignment.Center,
        ) {
            val initial = author.trim().firstOrNull()?.uppercase()
            if (initial != null) Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            else Icon(Icons.Default.Person, stringResource(R.string.nav_account), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query, onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.ds_home_search)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
        singleLine = true, shape = PillShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun QuickCard(icon: ImageVector, label: String, tile: TileColors, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = tile.container, modifier = Modifier.size(width = 124.dp, height = 116.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f)) {
                Icon(icon, null, Modifier.padding(10.dp).size(24.dp), tint = tile.content)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = tile.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContinueCard(d: RecentDoc, onResume: () -> Unit) {
    val thumb = rememberDocThumb(d)
    Surface(
        onClick = onResume, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ThumbBox(thumb, 60.dp, 80.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ds_continue_reading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(d.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.pageCount > 0) {
                    val page = (d.lastPage + 1).coerceIn(1, d.pageCount)
                    Text(stringResource(R.string.page_of, page, d.pageCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { page.toFloat() / d.pageCount }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(PillShape),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f), drawStopIndicator = {},
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = onResume, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.ds_resume)) }
        }
    }
}

@Composable
private fun GoRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronLeft, null)
        }
    }
}

/** "+" sheet: every create / import entry point in one place (one tap each). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = Brand.tiles
    val entries = listOf(
        Triple("blank", R.string.create_blank, Icons.Default.NoteAdd) to t.blue,
        Triple("images_to_pdf", R.string.create_from_images, Icons.Default.Image) to t.purple,
        Triple("scan", R.string.create_from_scan, Icons.Default.DocumentScanner) to t.rose,
        Triple("open", R.string.open_file, Icons.Default.FolderOpen) to t.teal,
        Triple("merge", R.string.tool_merge, Icons.Default.CallMerge) to t.amber,
        Triple("convert", R.string.tool_convert, Icons.Default.SwapHoriz) to t.green,
        Triple("form_builder", R.string.tool_form_builder, Icons.Default.AddBox) to t.blue,
        Triple("wifi", R.string.wifi_transfer, Icons.Default.Wifi) to t.teal,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(stringResource(R.string.create_new), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp)) {
            val perRow = if (maxWidth > 500.dp) 8 else 4
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                entries.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (e, tile) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onPick(e.first) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                IconTile(e.third, tile, size = 52.dp, iconSize = 26.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(e.second), style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

