package com.smartdocs.editor.ui

import com.smartdocs.editor.ui.view5.view5Routes
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.account.AccountScreen
import com.smartdocs.editor.ui.account.acctRoutes
import com.smartdocs.editor.ui.cloud.CloudScreen
import com.smartdocs.editor.ui.files.FilesScreen
import com.smartdocs.editor.ui.home.HomeScreen
import com.smartdocs.editor.ui.reader.ReaderScreen
import com.smartdocs.editor.ui.settings.SettingsScreen
import com.smartdocs.editor.ui.tools.ToolScreens
import com.smartdocs.editor.ui.tools.ToolsScreen
import com.smartdocs.editor.ui.share5.share5Routes
import com.smartdocs.editor.ui.connected.connRoutes

object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val TOOLS = "tools"
    const val CLOUD = "cloud"
    const val ACCOUNT = "account"
    const val SETTINGS = "settings"
    const val READER = "reader/{uri}?page={page}"
    const val TOOL = "tool/{id}?uri={uri}"

    fun reader(uri: String, page: Int = -1) = "reader/${Uri.encode(uri)}?page=$page"
    fun tool(id: String, uri: String? = null) = "tool/$id?uri=${Uri.encode(uri ?: "")}"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector, val iconSel: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.FILES, R.string.nav_files, Icons.Outlined.Folder, Icons.Filled.Folder),
    Tab(Routes.TOOLS, R.string.nav_tools, Icons.Outlined.GridView, Icons.Filled.GridView),
    Tab(Routes.CLOUD, R.string.nav_cloud, Icons.Outlined.Cloud, Icons.Filled.Cloud),
    Tab(Routes.ACCOUNT, R.string.nav_account, Icons.Outlined.Person, Icons.Filled.Person),
)

@Composable
fun AppNavHost(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }
    val onTab: (Tab) -> Unit = { t ->
        nav.navigate(t.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }

    CrashReportDialog()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // tablets: navigation rail at the start edge (right side in RTL) instead of the bottom bar
        val rail = maxWidth > 700.dp
        Scaffold(
            bottomBar = {
                AnimatedVisibility(showBar && !rail, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                  Column {
                    // share6 (monet): banner only on the Home and Tools tabs (never in reader/editors/signing/scanner); renders nothing when ads are off or Pro
                    if (route == Routes.HOME || route == Routes.TOOLS) com.smartdocs.editor.core.ads.AdBanner()
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                        tabs.forEach { t ->
                            val selected = route == t.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onTab(t) },
                                icon = { Icon(if (selected) t.iconSel else t.icon, null) },
                                label = { Text(stringResource(t.label), maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                  }
                }
            }
        ) { pad ->
            Row(Modifier.fillMaxSize()) {
                if (showBar && rail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        header = { Spacer(Modifier.height(8.dp)) },
                    ) {
                        Spacer(Modifier.weight(1f))
                        tabs.forEach { t ->
                            val selected = route == t.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { onTab(t) },
                                icon = { Icon(if (selected) t.iconSel else t.icon, null) },
                                label = { Text(stringResource(t.label), maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.padding(vertical = 4.dp),
                                colors = NavigationRailItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                NavHost(
                    nav, startDestination = Routes.HOME,
                    modifier = (if (showBar && !rail) Modifier.padding(pad).consumeWindowInsets(pad) else Modifier).weight(1f).fillMaxHeight(),
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(160)) },
                ) {
                    composable(Routes.HOME) { HomeScreen(nav); com.smartdocs.editor.ui.onboarding.HomeCoachMarks() }
                    composable(Routes.FILES) { FilesScreen(nav) }
                    composable(Routes.TOOLS) { ToolsScreen(nav) }
                    composable(Routes.CLOUD) { CloudScreen(nav) }
                    composable(Routes.ACCOUNT) { AccountScreen(nav) }
                    composable(Routes.SETTINGS) { SettingsScreen(nav) }
                    composable(
                        Routes.READER,
                        arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("page") { type = NavType.IntType; defaultValue = -1 }),
                    ) { e ->
                        // office5: one central dispatch — Office files opened through Routes.reader(...) go to the Office editor
                        val u = Uri.decode(e.arguments?.getString("uri") ?: "")
                        val office = androidx.compose.runtime.remember(u) { com.smartdocs.editor.ui.office.OfficeRoutes.isOffice(u) }
                        if (office) com.smartdocs.editor.ui.office.OfficeScreen(nav, u)
                        else ReaderScreen(nav, uri = u, startPage = e.arguments?.getInt("page") ?: -1)
                    }
                    composable(
                        Routes.TOOL,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("uri") { type = NavType.StringType; defaultValue = "" }),
                    ) { e ->
                        ToolScreens(nav, id = e.arguments?.getString("id") ?: "", uri = Uri.decode(e.arguments?.getString("uri") ?: "").ifBlank { null })
                    }
                    // office5: Word / Excel / PowerPoint editors
                    composable(
                        com.smartdocs.editor.ui.office.OfficeRoutes.ROUTE,          // "office5/{uri}"
                        arguments = listOf(navArgument("uri") { type = NavType.StringType }),
                    ) { e -> com.smartdocs.editor.ui.office.OfficeScreen(nav, Uri.decode(e.arguments?.getString("uri") ?: "")) }
                    // account / cloud / help / review screens (acct module, see MODULE_acct.md)
                    acctRoutes(nav)
                    share5Routes(nav)   // share6: share & review, storage (+ "storage" alias), app data
                    com.smartdocs.editor.ui.webdav.webDavRoutes(nav)   // nav-attach: WebDAV places
                    connRoutes(nav)     // share6: signature requests, connected / protected documents
                    view5Routes(nav)
                }
            }
        }
    }
}

/** On the launch after a crash: offer to share the report (email, WhatsApp, Telegram…). */
@Composable
private fun CrashReportDialog() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.smartdocs.editor.core.util.CrashLog.pending(ctx)) }
    val r = report ?: return
    AlertDialog(
        onDismissRequest = { com.smartdocs.editor.core.util.CrashLog.clear(ctx); report = null },
        title = { Text(stringResource(R.string.crash_title)) },
        text = { Text(stringResource(R.string.crash_text)) },
        confirmButton = {
            TextButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, "SmartDocs crash report"); putExtra(android.content.Intent.EXTRA_TEXT, r) }
                runCatching { ctx.startActivity(android.content.Intent.createChooser(send, null)) }
                com.smartdocs.editor.core.util.CrashLog.clear(ctx); report = null
            }) { Text(stringResource(R.string.crash_send)) }
        },
        dismissButton = { TextButton(onClick = { com.smartdocs.editor.core.util.CrashLog.clear(ctx); report = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
