package com.smartdocs.editor.core.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.smartdocs.editor.BuildConfig
import com.smartdocs.editor.core.billing.Pro
import com.smartdocs.editor.core.review.BuildCfg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob (banner + interstitial) behind Google's UMP consent.
 *
 * OFF unless `ads.properties` sets `enabled=true` and the unit ids (BuildConfig ADS_ENABLED / ADS_BANNER_ID /
 * ADS_INTERSTITIAL_ID, read through [BuildCfg]). Never for Pro users. Debug builds always request Google's test units.
 * The SDK is initialised only after consent allows ads (UMP `canRequestAds`), on a background thread.
 */
object AdsManager {
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    private const val INTERSTITIAL_GAP_MS = 4 * 60_000L

    private val debug: Boolean get() = BuildConfig.DEBUG

    /** ads.properties says enabled=true and has unit ids (debug builds only need enabled=true). */
    val configured: Boolean get() = BuildCfg.string("ADS_ENABLED").equals("true", true) &&
        (debug || (BuildCfg.string("ADS_BANNER_ID").isNotEmpty() && BuildCfg.string("ADS_INTERSTITIAL_ID").isNotEmpty()))

    val bannerId: String get() = if (debug) TEST_BANNER else BuildCfg.string("ADS_BANNER_ID")
    val interstitialId: String get() = if (debug) TEST_INTERSTITIAL else BuildCfg.string("ADS_INTERSTITIAL_ID")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initStarted = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    /** SDK initialised with consent: banners may load. */
    val ready: StateFlow<Boolean> = _ready
    private val _privacyOptionsRequired = MutableStateFlow(false)
    /** UMP says a "privacy options" entry must be offered (EEA/UK and similar). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired

    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false
    private var lastShown = 0L
    private var sessionCounted = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ads_state", Context.MODE_PRIVATE)

    /** Ads may be shown right now. */
    fun allowed(): Boolean = configured && _ready.value && !Pro.isActive

    /** Counts app sessions once per process (interstitials never show in the first session). */
    fun onSessionStart(c: Context) {
        if (sessionCounted) return
        sessionCounted = true
        val p = prefs(c); p.edit().putInt("sessions", p.getInt("sessions", 0) + 1).apply()
    }

    /** Requests the consent status, shows the consent form when required, then initialises the SDK if allowed. */
    fun gatherConsent(activity: Activity) {
        Pro.load(activity)
        if (!configured || Pro.isActive) return
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ -> afterConsent(activity, info) }
        }, { _ -> afterConsent(activity, info) })
        // consent from a previous session: start right away, in parallel with the update
        if (info.canRequestAds()) initialize(activity)
    }

    private fun afterConsent(activity: Activity, info: ConsentInformation) {
        _privacyOptionsRequired.value = info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (info.canRequestAds()) initialize(activity)
    }

    /** Re-opens the consent choices (settings "privacy options" row). */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            val info = UserMessagingPlatform.getConsentInformation(activity)
            if (!info.canRequestAds()) { _ready.value = false; interstitial = null } else initialize(activity)
            onDone()
        }
    }

    private fun initialize(activity: Activity) {
        if (Pro.isActive || !initStarted.compareAndSet(false, true)) return
        val app = activity.applicationContext
        scope.launch(Dispatchers.IO) {
            MobileAds.setRequestConfiguration(RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG).build())
            MobileAds.initialize(app) { _ ->
                scope.launch { _ready.value = true; preloadInterstitial(app) }
            }
        }
    }

    private fun preloadInterstitial(c: Context) {
        if (!allowed() || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(c.applicationContext, interstitialId, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loadingInterstitial = false }
            override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null; loadingInterstitial = false }
        })
    }

    /**
     * Shows an interstitial at a natural break (after a finished tool result is dismissed). No-op unless: ads allowed,
     * not the first session, at least 4 minutes since the last one, and an ad is already loaded (never waits).
     */
    fun maybeShowInterstitial(activity: Activity) {
        if (!allowed()) return
        val now = System.currentTimeMillis()
        if (prefs(activity).getInt("sessions", 0) < 2 || now - lastShown < INTERSTITIAL_GAP_MS) return
        val ad = interstitial ?: run { preloadInterstitial(activity); return }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { preloadInterstitial(activity) }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { preloadInterstitial(activity) }
        }
        lastShown = now
        ad.show(activity)
    }
}
