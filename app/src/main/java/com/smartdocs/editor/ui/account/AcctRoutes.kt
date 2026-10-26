package com.smartdocs.editor.ui.account

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartdocs.editor.ui.cloud.CloudBrowserScreen
import com.smartdocs.editor.ui.cloud.ReviewScreen
import com.smartdocs.editor.ui.cloud.SyncScreen
import com.smartdocs.editor.ui.cloud.WifiScreen
import com.smartdocs.editor.ui.help.HelpScreen
import com.smartdocs.editor.ui.help.LicensesScreen
import com.smartdocs.editor.ui.help.PrivacyPolicyScreen
import com.smartdocs.editor.ui.gaps6.gaps6Routes

/** Routes of the account / cloud / help module. Registered in the NavHost with [acctRoutes]. */
object AcctRoutes {
    const val HELP = "help"
    const val PRIVACY = "help/privacy"
    const val LICENSES = "help/licenses"
    const val PAYWALL = "paywall"
    const val ACTIVATION = "activation"
    const val CLOUD_BROWSER = "cloud_browser/{provider}"
    const val SYNC = "sync"
    const val WIFI = "wifi_transfer"
    const val REVIEW = "review?id={id}&c={c}"

    fun cloudBrowser(provider: String) = "cloud_browser/$provider"
    fun review(id: String? = null, code: String? = null) =
        if (id == null) "review" else "review?id=${Uri.encode(id)}&c=${Uri.encode(code ?: "")}"
}

/** Adds every screen of this module to the NavHost: `acctRoutes(nav)` inside `NavHost { … }`. */
fun NavGraphBuilder.acctRoutes(nav: NavHostController) {
    composable(AcctRoutes.HELP) { HelpScreen(nav) }
    composable(AcctRoutes.PRIVACY) { PrivacyPolicyScreen(nav) }
    composable(AcctRoutes.LICENSES) { LicensesScreen(nav) }
    composable(AcctRoutes.PAYWALL) { PaywallScreen(nav) }
    composable(AcctRoutes.ACTIVATION) { ActivationScreen(nav) }
    composable(AcctRoutes.CLOUD_BROWSER, arguments = listOf(navArgument("provider") { type = NavType.StringType })) { e ->
        CloudBrowserScreen(nav, e.arguments?.getString("provider") ?: "drive")
    }
    composable(AcctRoutes.SYNC) { SyncScreen(nav) }
    gaps6Routes(nav)   // gaps6: devices, invite, trusted certificates
    composable(AcctRoutes.WIFI) { WifiScreen(nav) }
    composable(
        AcctRoutes.REVIEW,
        arguments = listOf(
            navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("c") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) { e -> ReviewScreen(nav, e.arguments?.getString("id")?.ifBlank { null }, e.arguments?.getString("c")?.ifBlank { null }) }
}
