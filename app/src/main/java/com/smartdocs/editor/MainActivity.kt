package com.smartdocs.editor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.theme.SmartDocsTheme
import com.smartdocs.editor.ui.AppNavHost
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

val LocalApp = staticCompositionLocalOf<SmartDocsApp> { error("no app") }
val LocalSettings = compositionLocalOf { AppSettings() }

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<Uri?>(null)
    private val incomingRoute = MutableStateFlow<String?>(null)
    private var openedByIntent = false

    // volume keys / keyboard page turning in the reader
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        com.smartdocs.editor.ui.reader.view.ReaderKeyBus.handle(keyCode) || super.onKeyDown(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        com.smartdocs.editor.core.billing.BillingManager.get(this).refreshAsync() // re-verify the Pro entitlement
        setContent {
            val app = application as SmartDocsApp
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            CompositionLocalProvider(LocalApp provides app, LocalSettings provides settings) {
                SmartDocsTheme(mode = settings.theme, rtl = com.smartdocs.editor.core.gaps6.LayoutDir.isRtl(settings)) {
                    // first run: onboarding + consent before anything else (acct module)
                    var onboarded by remember { mutableStateOf(com.smartdocs.editor.core.review.AcctPrefs.onboardingDone(this@MainActivity)) }
                    if (!onboarded) {
                        com.smartdocs.editor.ui.onboarding.OnboardingScreen(onDone = { onboarded = true })
                    } else {
                        val nav = rememberNavController()
                        val uri by incoming.collectAsStateWithLifecycle()
                        LaunchedEffect(uri) {
                            // share6: returned e-mail-review comment files (.xfdf / .fdf) → merge offer; everything else → reader (Office files are dispatched there)
                            uri?.let { val ext = (it.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
                                nav.navigate(if (ext == "xfdf" || ext == "fdf") com.smartdocs.editor.ui.share5.Share5Routes.returned(it.toString()) else Routes.reader(it.toString())); incoming.value = null }
                        }
                        val route by incomingRoute.collectAsStateWithLifecycle()
                        LaunchedEffect(route) { route?.let { nav.navigate(it); incomingRoute.value = null } }
                        AppNavHost(nav)
                        com.smartdocs.editor.ui.view5.V5ShellHost(nav)
                        // reopen the last document when the app is launched normally (setting "restore session")
                        com.smartdocs.editor.ui.reader.view.RestoreLastSession(nav, enabled = !openedByIntent)
                        com.smartdocs.editor.ui.cloud.DeepLinkHandler(nav)
                        com.smartdocs.editor.ui.security.DocSecurityHost()   // nitro-protect: Internet-access / file-launch questions of documents
                        com.smartdocs.editor.core.review.ReviewPromptHost()
                        com.smartdocs.editor.core.ads.AdsConsentHost()   // share6: UMP consent; no ads ever show without it (no-op when ads are off)
                    }
                }
            }
        }
        com.smartdocs.editor.ui.view5.V5Shortcuts.publish(this)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>?, menu: android.view.Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId); data?.addAll(com.smartdocs.editor.ui.view5.V5Keys.groups(this))
    }

    private fun handleIntent(intent: Intent?) {
        if (com.smartdocs.editor.ui.view5.V5Shortcuts.handleIntent(this, intent)) return
        if (com.smartdocs.editor.core.tools5.ShareImport.handles(intent)) {
            openedByIntent = true
            val app = application as SmartDocsApp
            val i = intent!!
            lifecycleScope.launch {
                // a failing share (revoked permission, unreadable stream) must not take the app down
                val read = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.smartdocs.editor.core.tools5.ShareImport.read(this@MainActivity, i, java.io.File(app.documents.tempDir, "shared"))
                    }
                }
                read.onSuccess {
                    com.smartdocs.editor.core.tools5.ShareImport.pending = it
                    incomingRoute.value = Routes.tool("share_to_pdf")
                }.onFailure { Toast.makeText(this@MainActivity, getString(R.string.error_generic), Toast.LENGTH_LONG).show() }
            }
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) return
        openedByIntent = true
        // smartdocs://oauth/… (cloud sign-in) and smartdocs://review/… (shared review) → acct module
        if (com.smartdocs.editor.ui.cloud.DeepLinks.isOurs(uri)) { com.smartdocs.editor.ui.cloud.DeepLinks.pending.value = uri; return }
        // Most senders grant only a temporary (non-persistable) read permission tied to this activity instance,
        // so a content:// document is copied into our own storage right away instead of at reader open time.
        if (uri.scheme == "file") { incoming.value = uri; return }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // open-all: audio / video are streamed from the sender (a film is never copied whole); the viewer plays the content uri
        if (com.smartdocs.editor.ui.openall.OpenAll.isStreamedMedia(this, uri)) { incoming.value = uri; return }
        val app = application as SmartDocsApp
        lifecycleScope.launch {
            val local = runCatching { app.documents.localFile(uri) }.getOrNull()
            if (local != null) incoming.value = Uri.fromFile(local)
            else Toast.makeText(this@MainActivity, getString(R.string.error_generic), Toast.LENGTH_LONG).show()
        }
    }
}
