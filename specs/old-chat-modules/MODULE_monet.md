# MODULE_monet: server licences (Play verification + revocable codes) and AdMob ads

This module was written without running Gradle. APIs were checked with `javap`:
- play-services-ads-api 24.4.0: MobileAds, AdView/BaseAdView, AdSize, AdRequest, AdListener, InterstitialAd, FullScreenContentCallback, RequestConfiguration
- user-messaging-platform 3.2.0: UserMessagingPlatform, ConsentInformation, ConsentRequestParameters
- bcprov-jdk15to18 1.72: Ed25519Signer, Ed25519PublicKeyParameters

**Tested outside Gradle:**
- The licence worker ran on Node 22 with a mock KV and a mock Google API (OAuth JWT checked against a real RSA key). Covered:
  - activation: device limit, expired, bad and revoked codes, wrong device, tampered token
  - admin: generate, revoke, devices
  - Play: verify-purchase with acknowledgement, check
  - RTDN: void and expire
  - interop: codes from the Python generator in MODULE_acct are accepted.
- A token signed by the worker verifies in Java with BouncyCastle, using the same code as `LicenseClient.parse`. A one-character change makes it fail.

## Files
**Server** (`ai-proxy/license/`)
- `worker.js`: endpoints `/verify-purchase`, `/activate`, `/check`, `/admin/generate|revoke|devices`, `/rtdn`, `/health`.
- `wrangler.toml`, `keygen.mjs` (makes the Ed25519 key pair).
- `README.md`: deployment step by step, in Arabic, including the service account, RTDN/Pub/Sub, code management and the **app-ads.txt** note.

**App, licences** (`core/billing/`)
- `LicenseClient.kt` (new):
  - config: `LICENSE_URL` and `LICENSE_PUBLIC_KEY`, read through `BuildCfg.string`
  - device id: SHA-256 of ANDROID_ID + package
  - verifies Ed25519 tokens with BouncyCastle
  - `activate`, `verifyPurchase`, `check` (also migrates codes that were activated offline), `checkDue`
- `LicenseCheckWorker.kt` (new): periodic WorkManager job, every 3 days, needs a network connection.
- `Pro.kt` (changed):
  - `Entitlement` gains `license: String?`.
  - **Server mode:**
    - only signed entitlements count, for 7 days of grace after the last check and never past the token's signed `exp` (10 days)
    - an unsigned Play purchase counts for 3 days ("provisional", used when the server is down at purchase time)
    - unsigned codes do not count
  - The stored licence is re-verified on load: signature and device.
  - `isActive` re-evaluates against the clock.
  - `Pro.allows()` uses all of this.
- `ActivationCodes.kt` (changed):
  - new results `Revoked`, `DeviceLimit`, `Network`
  - `suspend redeem()` goes to the server when it is configured, otherwise offline
  - `online` flag
  - the embedded `PRO_CODE_SECRET` is ignored when the server is configured
- `BillingManager.kt` (changed):
  - Each purchase or restore calls `/verify-purchase` and stores the signed result. If the server says inactive (refund or revoke), Pro is removed.
  - `refreshAsync()` also re-checks codes when a check is due and schedules the worker.

**App, ads** (`core/ads/`)
- `AdsManager.kt`:
  - UMP consent, then `MobileAds.initialize` on Dispatchers.IO, only when ADS_ENABLED, consent allows it and the user is not Pro
  - max content rating PG
  - test ad units in debug builds
  - `privacyOptionsRequired` and `showPrivacyOptions()`
  - interstitial rules: at most one every 4 minutes, never in the first session, only when already loaded, never for Pro, preloaded
- `AdBanner.kt`:
  - `AdBanner(modifier)`: anchored adaptive banner. It joins the layout only after an ad has loaded, so it takes no space when disabled, Pro, offline or failed.
  - `AdsConsentHost()`
  - `rememberNaturalBreak()`
  - `AdsPrivacyOptionsItem()`

**UI**
- `ui/account/PaywallScreen.kt`:
  - The activation screen redeems codes asynchronously, with a progress indicator and the new error messages.
  - The online/offline note adapts.
  - A "no ads" benefit is shown when ads are configured.
- Strings: `res/values/strings_monet.xml` and `values-en/strings_monet.xml` (prefix `mn_`).

**Templates:** `ads.properties.example`, `license.properties.example`.

## For the lead to wire
1. **build.gradle.kts** `defaultConfig`. These are new fields. They are read by reflection, so the code compiles without them, and the existing `-keep class …BuildConfig` rule keeps them.
   ```kotlin
   val licProps = Properties().apply { rootProject.file("license.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
   buildConfigField("String", "LICENSE_URL", "\"${licProps.getProperty("licenseUrl", "")}\"")
   buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"${licProps.getProperty("licensePublicKey", "")}\"")
   ```
   Also add `license.properties` to `.gitignore`. When the server is used, leave `codeSecret` empty in `pro.properties` so the secret is no longer shipped.
2. **MainActivity**: call `com.smartdocs.editor.core.ads.AdsConsentHost()` once, right after `ReviewPromptHost()` inside the post-onboarding branch. This keeps the consent form away from onboarding, counts sessions and initialises the SDK. Without this call no ads ever show.
3. **Banners.** Call `AdBanner()` with no padding modifier.
   - **Home tab:** at the very bottom of the HomeScreen content, or in the Scaffold's `bottomBar` above the `NavigationBar` when the current route is HOME. For example: `bottomBar = { Column { if (route == HOME || route == TOOLS) AdBanner(); NavigationBar { … } } }`.
   - **Tools tab:** same as Home, or as the last item of the ToolsScreen list.
   - **ResultSheet** (`ui/common/Components.kt`): as the last child of the sheet's `Column`, below the action buttons.
   - **Never** in the reader, editors, signing, scanner or any full-screen document view.
4. **Interstitial** (ResultSheet, after success). Show it when the user closes the sheet, not when the sheet opens, so it never covers the result:
   ```kotlin
   val naturalBreak = com.smartdocs.editor.core.ads.rememberNaturalBreak()
   ModalBottomSheet(onDismissRequest = { onDismiss(); naturalBreak() }, …)
   ```
   - Do not call it from "Open" or "Share": the user is moving on to reading.
   - No other call sites are needed. It is a no-op unless every rule passes.
5. **Settings** (privacy / about section) and optionally HelpScreen: add `com.smartdocs.editor.core.ads.AdsPrivacyOptionsItem()`. It renders only when UMP says privacy options are required (EEA/UK and similar regions), which policy requires.
6. **Privacy policy** (`LegalScreens` / `ac_policy*`, owned by acct):
   - append `R.string.mn_policy_ads` and `R.string.mn_policy_license`
   - section 5 "Purchases" still says codes are verified on the device; when the licence server is configured, replace that sentence with `mn_policy_license`
7. **Play Console:**
   - App content → **Contains ads = Yes**.
   - The Data safety form must list the Advertising ID and device identifiers for ads (AdMob).
   - The Ads SDK merges the `AD_ID` permission itself.
   - Target audience: keep it at 13+. If under-13s are targeted, Families policy applies: certified ad networks and `tagForChildDirectedTreatment`.

## ads.properties (project root, already in .gitignore)
```
enabled=true
appId=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
bannerId=ca-app-pub-XXXXXXXXXXXXXXXX/1111111111
interstitialId=ca-app-pub-XXXXXXXXXXXXXXXX/2222222222
```
- Without the file, the test app id is used and ads are off.
- Debug builds always request Google's test units but still need `enabled=true`.
- A malformed `appId` crashes the SDK at startup.
- Publish **app-ads.txt** on the developer website listed in Play: see the end of `ai-proxy/license/README.md`.

## Behaviour summary
| | No licence server | Licence server configured |
|---|---|---|
| Play purchase / restore | client-side, 30-day offline grace | `/verify-purchase` → signed licence; 3-day provisional if unreachable; refund or revoke → Pro off |
| Activation code | offline HMAC (secret in APK) | `/activate`: HMAC on the server, max devices (KV), revocation list; no secret in the APK |
| Re-check | — | every 3 days (WorkManager) + at app start when due; offline grace 7 days after the last success |

## Limitations
- KV is eventually consistent. A revocation can take about 1 minute to apply everywhere, and two simultaneous activations can exceed the device limit by one.
- RTDN is authenticated by a secret in the push URL, not by Pub/Sub OIDC verification.
- A Play licence is bound to the device that verified it. Other devices of the same Google account verify through their own restore at app start.
- Grace and provisional periods use the device clock, bounded by the signed `exp`. A rooted or modified APK can still bypass any client gate.
- Banner connectivity is checked when the banner is composed, not observed. A banner that fails to load stays hidden until the screen is composed again.
- Interstitials show only at the call site documented above. There is no app-open ad and no rewarded ad.
