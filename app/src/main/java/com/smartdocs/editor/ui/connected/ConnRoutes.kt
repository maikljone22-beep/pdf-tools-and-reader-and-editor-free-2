package com.smartdocs.editor.ui.connected

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartdocs.editor.ui.esign.EsignCreateScreen
import com.smartdocs.editor.ui.esign.EsignDashboardScreen
import com.smartdocs.editor.ui.esign.EsignDetailScreen
import com.smartdocs.editor.ui.esign.EsignSignScreen

/** Routes of signature requests, connected documents and the enterprise-protection notice. */
object ConnRoutes {
    const val ESIGN = "esign"
    const val ESIGN_CREATE = "esign/create?uri={uri}"
    const val ESIGN_DETAIL = "esign/req/{id}"
    const val ESIGN_SIGN = "esign/sign?id={id}&t={t}"
    const val CONNECTED = "connected?uri={uri}"
    const val CONNECTED_DOC = "connected/doc/{id}"
    const val CONNECTED_OPEN = "connected/open?id={id}&uri={uri}"
    const val ENTERPRISE = "enterprise_protected?uri={uri}"

    private fun q(s: String?) = Uri.encode(s ?: "")
    fun esignCreate(uri: String? = null) = "esign/create?uri=${q(uri)}"
    fun esignDetail(id: String) = "esign/req/$id"
    fun esignSign(id: String, t: String) = "esign/sign?id=${q(id)}&t=${q(t)}"
    fun connected(uri: String? = null) = "connected?uri=${q(uri)}"
    fun connectedDoc(id: String) = "connected/doc/$id"
    fun connectedOpen(id: String, uri: String? = null) = "connected/open?id=${q(id)}&uri=${q(uri)}"
    fun enterprise(uri: String) = "enterprise_protected?uri=${q(uri)}"
}

private fun opt(name: String) = navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }

/** `connRoutes(nav)` inside `NavHost { … }`. */
fun NavGraphBuilder.connRoutes(nav: NavHostController) {
    composable(ConnRoutes.ESIGN) { EsignDashboardScreen(nav) }
    composable(ConnRoutes.ESIGN_CREATE, arguments = listOf(opt("uri"))) { e -> EsignCreateScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }) }
    composable(ConnRoutes.ESIGN_DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e -> EsignDetailScreen(nav, e.arguments?.getString("id") ?: "") }
    composable(ConnRoutes.ESIGN_SIGN, arguments = listOf(opt("id"), opt("t"))) { e ->
        EsignSignScreen(nav, e.arguments?.getString("id").orEmpty(), e.arguments?.getString("t").orEmpty())
    }
    composable(ConnRoutes.CONNECTED, arguments = listOf(opt("uri"))) { e -> ConnectedHomeScreen(nav, e.arguments?.getString("uri")?.ifBlank { null }) }
    composable(ConnRoutes.CONNECTED_DOC, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e -> ConnectedDocScreen(nav, e.arguments?.getString("id") ?: "") }
    composable(ConnRoutes.CONNECTED_OPEN, arguments = listOf(opt("id"), opt("uri"))) { e ->
        ConnectedOpenScreen(nav, e.arguments?.getString("id").orEmpty(), e.arguments?.getString("uri")?.ifBlank { null })
    }
    composable(ConnRoutes.ENTERPRISE, arguments = listOf(opt("uri"))) { e -> EnterpriseProtectedScreen(nav, e.arguments?.getString("uri").orEmpty()) }
}

/**
 * `smartdocs://esign/sign?id=<id>&t=<token>`  -> in-app signing
 * `smartdocs://esign/req/<id>`                 -> my request
 * `smartdocs://doc/<id>`                       -> connected document (status / download latest)
 */
object ConnDeepLinks {
    fun handle(nav: NavHostController, u: Uri) {
        when (u.host) {
            "esign" -> {
                val seg = u.pathSegments
                val id = u.getQueryParameter("id") ?: seg.getOrNull(1)
                when {
                    seg.firstOrNull() == "req" && id != null -> nav.navigate(ConnRoutes.esignDetail(id))
                    id != null && u.getQueryParameter("t") != null -> nav.navigate(ConnRoutes.esignSign(id, u.getQueryParameter("t")!!))
                    else -> nav.navigate(ConnRoutes.ESIGN)
                }
            }
            "doc" -> u.pathSegments.firstOrNull()?.takeIf { com.smartdocs.editor.core.connected.ConnMarker.isId(it) }
                ?.let { nav.navigate(ConnRoutes.connectedOpen(it)) } ?: nav.navigate(ConnRoutes.connected())
        }
    }

    /** Parses a pasted https signing link (`…/s/<id>?t=<token>`) or app link. Returns (id, token) or null. */
    fun parseSignLink(text: String): Pair<String, String>? {
        val u = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        val t = u.getQueryParameter("t") ?: return null
        val id = u.getQueryParameter("id") ?: u.pathSegments.let { s -> val i = s.indexOf("s"); if (i >= 0) s.getOrNull(i + 1) else null } ?: return null
        return id to t
    }
}
