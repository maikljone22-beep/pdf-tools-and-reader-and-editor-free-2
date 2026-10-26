package com.smartdocs.editor.ui.gaps6

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** Screens added by gaps6. Registered from `acctRoutes(nav)` (ui/account/AcctRoutes.kt), so Navigation.kt is untouched. */
object G6Routes {
    const val DEVICES = "g6_devices"
    const val INVITE = "g6_invite"
    const val TRUSTED_CERTS = "g6_trusted_certs"
}

fun NavGraphBuilder.gaps6Routes(nav: NavHostController) {
    composable(G6Routes.DEVICES) { ActivatedDevicesScreen(nav) }
    composable(G6Routes.INVITE) { InviteScreen(nav) }
    composable(G6Routes.TRUSTED_CERTS) { com.smartdocs.editor.ui.signature.TrustedCertsScreen(nav) }
}
