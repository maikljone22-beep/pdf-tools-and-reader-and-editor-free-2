package com.smartdocs.editor.ui.help

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.ui.common.AppTopBar

/** Route "help/privacy": the privacy policy in Arabic and English. */
@Composable
fun PrivacyPolicyScreen(nav: NavHostController) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.ac_privacy_policy), onBack = { nav.popBackStack() }) }) { pad ->
        PrivacyPolicyContent(Modifier.padding(pad).fillMaxSize())
    }
}

/** Policy body with an Arabic / English switch (also used in a bottom sheet during onboarding). */
@Composable
fun PrivacyPolicyContent(modifier: Modifier = Modifier) {
    var english by remember { mutableStateOf(false) }
    Column(modifier) {
        TabRow(selectedTabIndex = if (english) 1 else 0) {
            Tab(selected = !english, onClick = { english = false }, text = { Text("العربية") })
            Tab(selected = english, onClick = { english = true }, text = { Text("English") })
        }
        // the English text reads left-to-right even inside the RTL app
        CompositionLocalProvider(LocalLayoutDirection provides if (english) LayoutDirection.Ltr else LayoutDirection.Rtl) {
            SelectionContainer(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(stringResource(if (english) R.string.ac_policy_title_en else R.string.ac_policy_title_ar), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.ac_policy_updated), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(if (english) R.string.ac_policy_en else R.string.ac_policy_ar), style = MaterialTheme.typography.bodyMedium)
                    // monet (share6): advertising + licence verification sections (Arabic/English via the en/ar resource sets)
                    PolicyAppendix(english)
                }
            }
        }
    }
}

private data class Lib(val name: String, val owner: String, val license: String, val summary: Int, val url: String)

private val libraries = listOf(
    Lib("Jetpack Compose, AndroidX (Activity, Navigation, Room, DataStore, WorkManager, Browser…)", "Google / AOSP", "Apache License 2.0", R.string.ac_lic_apache, "https://www.apache.org/licenses/LICENSE-2.0"),
    Lib("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains", "Apache License 2.0", R.string.ac_lic_apache, "https://www.apache.org/licenses/LICENSE-2.0"),
    Lib("PdfBox-Android (Apache PDFBox port)", "Tom Roush / Apache Software Foundation", "Apache License 2.0", R.string.ac_lic_apache, "https://github.com/TomRoush/PdfBox-Android"),
    Lib("PDFium", "Google / Foxit", "BSD 3-Clause", R.string.ac_lic_bsd, "https://pdfium.googlesource.com/pdfium/+/main/LICENSE"),
    Lib("PdfiumAndroid (io.legere)", "Legere / Bartosz Schiller", "Apache License 2.0", R.string.ac_lic_apache, "https://github.com/johngray1965/PdfiumAndroidKt"),
    Lib("Tesseract OCR", "Tesseract contributors", "Apache License 2.0", R.string.ac_lic_apache, "https://github.com/tesseract-ocr/tesseract"),
    Lib("Tesseract4Android", "Adaptech", "Apache License 2.0", R.string.ac_lic_apache, "https://github.com/adaptech-cz/Tesseract4Android"),
    Lib("Leptonica", "Dan Bloomberg", "BSD 2-Clause", R.string.ac_lic_bsd, "http://leptonica.org/about-the-license.html"),
    Lib("Bouncy Castle", "Legion of the Bouncy Castle", "MIT (Bouncy Castle Licence)", R.string.ac_lic_mit, "https://www.bouncycastle.org/licence.html"),
    Lib("OkHttp, Okio", "Square", "Apache License 2.0", R.string.ac_lic_apache, "https://square.github.io/okhttp/"),
    Lib("Coil", "Coil contributors", "Apache License 2.0", R.string.ac_lic_apache, "https://github.com/coil-kt/coil"),
    Lib("Noto Naskh Arabic, Noto Sans Arabic", "Google", "SIL Open Font License 1.1", R.string.ac_lic_ofl, "https://openfontlicense.org"),
    Lib("Material icons", "Google", "Apache License 2.0", R.string.ac_lic_apache, "https://github.com/google/material-design-icons"),
    Lib("ML Kit (document scanner, digital ink), Play services, Play Billing, Play In-App Review", "Google", "Android SDK / Google APIs Terms", R.string.ac_lic_google, "https://developers.google.com/terms"),
)

/** Route "help/licenses": open-source components and their licences. */
@Composable
fun LicensesScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    Scaffold(topBar = { AppTopBar(stringResource(R.string.ac_licenses), onBack = { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.ac_licenses_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(libraries) { lib ->
                Surface(
                    onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lib.url))) } },
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(lib.name, style = MaterialTheme.typography.titleSmall)
                                Text(lib.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Text(lib.license, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(lib.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Sections 10–11 (ads, licence server). The tab language picks the text; values-en holds the English version. */
@Composable
private fun PolicyAppendix(english: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = remember(english) {
        val cfg = android.content.res.Configuration(ctx.resources.configuration)
        cfg.setLocale(java.util.Locale(if (english) "en" else "ar"))
        ctx.createConfigurationContext(cfg).resources
    }
    Spacer(Modifier.height(12.dp))
    Text(res.getString(R.string.mn_policy_ads), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Text(res.getString(R.string.mn_policy_license), style = MaterialTheme.typography.bodyMedium)
}
