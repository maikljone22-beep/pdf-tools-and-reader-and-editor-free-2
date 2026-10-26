package com.smartdocs.editor.ui.share5

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.share5.AppDataSync
import com.smartdocs.editor.core.share5.S5Config
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow

/** Routes of the share5 module. Register with `share5Routes(nav)` inside the NavHost. */
object Share5Routes {
    const val HUB = "share5"
    const val SHARE = "share5/share?uri={uri}&page={page}"
    const val REVIEWS = "share5/reviews"
    const val REVIEW_NEW = "share5/review_new?uri={uri}"
    const val REVIEW = "share5/review/{id}"
    const val VIEWS = "share5/views"
    const val VIEW_NEW = "share5/view_new?uri={uri}"
    const val VIEW = "share5/view/{id}"
    const val EMAIL_NEW = "share5/email_new?uri={uri}"
    const val RETURNED = "share5/returned?uri={uri}"
    const val STORAGE = "share5/storage"
    const val APPDATA = "share5/appdata"

    private fun q(uri: String?) = Uri.encode(uri ?: "")
    fun share(uri: String, page: Int = 0) = "share5/share?uri=${q(uri)}&page=$page"
    fun reviewNew(uri: String?) = "share5/review_new?uri=${q(uri)}"
    fun review(id: String) = "share5/review/${Uri.encode(id)}"
    fun viewNew(uri: String?) = "share5/view_new?uri=${q(uri)}"
    fun view(id: String) = "share5/view/${Uri.encode(id)}"
    fun emailNew(uri: String?) = "share5/email_new?uri=${q(uri)}"
    fun returned(uri: String) = "share5/returned?uri=${q(uri)}"
}

private fun uriArg() = navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null }

fun NavGraphBuilder.share5Routes(nav: NavHostController) {
    composable(Share5Routes.HUB) { ShareHubScreen(nav) }
    composable(Share5Routes.SHARE, arguments = listOf(uriArg(), navArgument("page") { type = NavType.IntType; defaultValue = 0 })) { e ->
        ShareOptionsScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }, e.arguments?.getInt("page") ?: 0)
    }
    composable(Share5Routes.REVIEWS) { ReviewTrackerScreen(nav) }
    composable(Share5Routes.REVIEW_NEW, arguments = listOf(uriArg())) { e -> ReviewCreateScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }) }
    composable(Share5Routes.REVIEW, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e -> ReviewManageScreen(nav, e.arguments?.getString("id").orEmpty()) }
    composable(Share5Routes.VIEWS) { ViewLinksScreen(nav) }
    composable(Share5Routes.VIEW_NEW, arguments = listOf(uriArg())) { e -> ViewLinkCreateScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }) }
    composable(Share5Routes.VIEW, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e -> ViewLinkDetailScreen(nav, e.arguments?.getString("id").orEmpty()) }
    composable(Share5Routes.EMAIL_NEW, arguments = listOf(uriArg())) { e -> EmailReviewScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }) }
    composable(Share5Routes.RETURNED, arguments = listOf(uriArg())) { e -> ReturnedScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }) }
    composable(Share5Routes.STORAGE) { StorageScreen(nav) }
    composable("storage") { StorageScreen(nav) }   // alias used by the Settings screen (gaps6)
    composable(Share5Routes.APPDATA) { AppDataScreen(nav) }
}

/** Route "share5": entry point to every sharing feature. */
@Composable
fun ShareHubScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val tiles = Brand.tiles
    LaunchedEffect(Unit) { AppDataSync.autoSyncQuietly(ctx) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_hub), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (!S5Config.enabled) NotConfiguredNote(Modifier.padding(16.dp))
            GroupCard(stringResource(R.string.sh5_sec_review)) {
                GroupRow(stringResource(R.string.sh5_review_new), stringResource(R.string.sh5_review_new_sub), icon = Icons.Default.GroupAdd, tile = tiles.purple,
                    onClick = if (CollabConfig.enabled) ({ nav.navigate(Share5Routes.reviewNew(null)) }) else null)
                GroupDivider()
                GroupRow(stringResource(R.string.sh5_tracker), stringResource(R.string.sh5_tracker_sub), icon = Icons.Default.FactCheck, tile = tiles.blue,
                    onClick = { nav.navigate(Share5Routes.REVIEWS) })
                GroupDivider()
                GroupRow(stringResource(R.string.sh5_email_review), stringResource(R.string.sh5_email_review_sub), icon = Icons.Default.ForwardToInbox, tile = tiles.teal,
                    onClick = { nav.navigate(Share5Routes.emailNew(null)) })
            }
            GroupCard(stringResource(R.string.sh5_sec_links)) {
                GroupRow(stringResource(R.string.sh5_view_new), stringResource(R.string.sh5_view_new_sub), icon = Icons.Default.Link, tile = tiles.amber,
                    onClick = if (S5Config.enabled) ({ nav.navigate(Share5Routes.viewNew(null)) }) else null)
                GroupDivider()
                GroupRow(stringResource(R.string.sh5_views), stringResource(R.string.sh5_views_sub), icon = Icons.Default.Insights, tile = tiles.green,
                    onClick = { nav.navigate(Share5Routes.VIEWS) })
            }
            GroupCard(stringResource(R.string.sh5_sec_device)) {
                GroupRow(stringResource(R.string.sh5_appdata), stringResource(R.string.sh5_appdata_sub), icon = Icons.Default.DevicesOther, tile = tiles.rose,
                    onClick = { nav.navigate(Share5Routes.APPDATA) })
                GroupDivider()
                GroupRow(stringResource(R.string.sh5_storage), stringResource(R.string.sh5_storage_sub), icon = Icons.Default.Storage, tile = tiles.blue,
                    onClick = { nav.navigate(Share5Routes.STORAGE) })
            }
        }
    }
}
