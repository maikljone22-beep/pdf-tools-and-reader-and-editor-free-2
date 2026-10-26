package com.smartdocs.editor.core.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.smartdocs.editor.R
import com.smartdocs.editor.core.billing.Pro

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private fun online(c: Context): Boolean = runCatching {
    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(false)

/**
 * Anchored adaptive banner. It joins the layout only after an ad has loaded, so it takes no space when ads are off,
 * for Pro, offline, or when loading failed. Call it with no padding; never in the reader, editors, signing or scanner.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val ready by AdsManager.ready.collectAsStateWithLifecycle()
    val pro by Pro.isActiveFlow.collectAsStateWithLifecycle()
    if (!AdsManager.configured || !ready || pro) return
    val ctx = LocalContext.current
    if (!remember { online(ctx) }) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(320)
        var loaded by remember { mutableStateOf(false) }
        val view = remember(widthDp) {
            AdView(ctx).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                adUnitId = AdsManager.bannerId
                adListener = object : AdListener() {
                    override fun onAdLoaded() { loaded = true }
                    override fun onAdFailedToLoad(error: LoadAdError) { loaded = false }
                }
                loadAd(AdRequest.Builder().build())
            }
        }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(view, owner) {
            val obs = LifecycleEventObserver { _, e ->
                when (e) { Lifecycle.Event.ON_PAUSE -> view.pause(); Lifecycle.Event.ON_RESUME -> view.resume(); else -> Unit }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs); view.destroy() }
        }
        if (loaded) AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().wrapContentHeight())
    }
}

/** Call once after onboarding (MainActivity): counts the session, asks for consent when required, starts the SDK. */
@Composable
fun AdsConsentHost() {
    val ctx = LocalContext.current
    val pro by Pro.isActiveFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pro) {
        if (pro || !AdsManager.configured) return@LaunchedEffect
        AdsManager.onSessionStart(ctx)
        ctx.findActivity()?.let { runCatching { AdsManager.gatherConsent(it) } }
    }
}

/** Returns a callback for a "natural break" (closing a finished result sheet) that may show an interstitial. */
@Composable
fun rememberNaturalBreak(): () -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { ctx.findActivity()?.let { runCatching { AdsManager.maybeShowInterstitial(it) } }; Unit } }
}

/** Settings / help row "ad privacy options"; renders only when UMP requires it (EEA/UK and similar regions). */
@Composable
fun AdsPrivacyOptionsItem(modifier: Modifier = Modifier) {
    val required by AdsManager.privacyOptionsRequired.collectAsStateWithLifecycle()
    if (!required || !AdsManager.configured) return
    val ctx = LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.mn_privacy_options)) },
        supportingContent = { Text(stringResource(R.string.mn_privacy_options_sub)) },
        leadingContent = { Icon(Icons.Default.PrivacyTip, null) },
        modifier = modifier.clickable { ctx.findActivity()?.let { AdsManager.showPrivacyOptions(it) } },
    )
}
