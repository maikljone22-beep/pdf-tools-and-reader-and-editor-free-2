# MODULE gaps6 (round 5): REMAINING_PLAN section B gaps

Foldables, Files (copy, unzip, tag limit), Settings rows, Account (Google sign-in, devices, invite), Home (template entry,
tool wizard, two-pane), Organize (insert pages from the camera), signature extras (trusted certificates, certificate details,
appearance designer, change log before signing).

Not run: gradle. Type-checked with the Compose plugin against the last `classes.jar` and the dependency jars
(`scratchpad/gaps6/kg6.sh`). The only errors came from `ui.view5` symbols that are missing from the stale classes.jar. With a
small stub for them the check is clean, including the Compose backend. JUnit: `scratchpad/gaps6/test.sh` passes 20 of 20.

## Gradle dependency (lead: add)
```kotlin
implementation("androidx.window:window:1.2.0")   // WindowInfoTracker / FoldingFeature (in the gradle cache)
```
It is used only in `ui/gaps6/FoldState.kt`. Nothing else is new. Google sign-in uses the existing `play-services-auth:22.0.0`,
camera pages use the existing ML Kit document scanner, and certificates use the existing BouncyCastle.

## Files
New:
- `core/gaps6/ToolWizard.kt`: goal-to-tool keyword matcher. It uses `ArabicNormalizer` (fold + light stem) and covers all 50 ids of `ToolsScreen.allTools`.
- `core/gaps6/TagLimit.kt` (max 5 tags), `Referral.kt` (per-install code and link), `FoldLayout.kt` (pure posture / layout decision).
- `core/gaps6/G6Prefs.kt`: `AiGate`, `LayoutDir`, `TransferPolicy`, `ReaderViewPrefs`. The last one writes the reader's "view" prefs for the timeout and orientation lock.
- `core/gaps6/AccountLocal.kt`: `InstallId`, `ActivatedDevices` (local list), `ReferralStore`.
- `core/gaps6/GoogleAccount.kt`: sign-in through AuthorizationClient (`openid email profile`), giving name, e-mail and photo, plus sign-out.
- `core/files/FileCopy.kt`: copy files and folders into a folder with free names, plus the unzip target.
- `core/sign/CertFormat.kt`, `TrustPath.kt`, `RevisionDiff.kt`, `SigAppearanceSpec.kt`: pure logic.
- `core/sign/TrustedCerts.kt` (store, import of .cer / .crt / .pem), `CertInspector.kt` (details and trust per signature), `RevisionSnapshot.kt` (PdfBox snapshots and the change list).
- `ui/gaps6/FoldState.kt`: **`rememberFoldState()`** and `TwoPane(fold, …)`, which splits at the hinge and is RTL aware.
- `ui/gaps6/ReaderHooks.kt`: `G6ReaderEffects`, `G6Keys.handle`, `TabletopSplit`.
- `ui/gaps6/ToolWizardSheet.kt` (card, content, sheet), `DetailPanes.kt` (Home and Files detail panes), `CameraPages.kt`, `AccountExtras.kt`
  (Google card, `ActivatedDevicesScreen`, `InviteScreen`), `Gaps6Routes.kt` (routes `g6_devices`, `g6_invite`, `g6_trusted_certs`).
- `ui/settings/Gaps6SettingsSection.kt`: the new settings rows.
- `ui/signature/TrustedCertsScreen.kt` (screen plus `CertDetailsBlock`), `CertTrust.kt`, `SigAppearanceDesigner.kt` (state, renderer, designer card), `SignChanges.kt`.
- `res/values/strings_gaps6.xml` and `res/values-en/strings_gaps6.xml`: 131 keys, prefix `g6_`.
- Tests: `app/src/test/java/com/smartdocs/editor/core/gaps6/{ToolWizardTest,Gaps6LogicTest,SignExtrasTest}.kt`.

Edited (all additive):
- `core/data/SettingsStore.kt`: new fields, and the `volumeKeysFlip` default is now **true**.
- `ui/settings/SettingsScreen.kt`, `ui/home/HomeScreen.kt`, `ui/home/HomeComponents.kt` (tag limit).
- `ui/files/FilesScreen.kt`, `ui/files/FilesComponents.kt` (copy, unzip, two-pane).
- `ui/account/AccountScreen.kt`, `AcctRoutes.kt` (calls `gaps6Routes(nav)`), `PaywallScreen.kt` (records the activated device).
- `ui/signature/DigitalSignScreen.kt`, `ui/pages/OrganizeScreen.kt` (camera button).
- `core/files/ZipUtil.kt`: the zip-slip check now needs `root + File.separator`.

## New AppSettings fields (`core/data/SettingsStore.kt`)
| field | default | read by |
|---|---|---|
| `fullScreenReader` | false | `G6ReaderEffects` (hook R1) |
| `volumeKeysAction` | "flip" ("flip" or "zoom") | `G6Keys.handle` (hooks R2, R3) |
| `volumeKeysFlip` | **true** (was false) | existing reader code |
| `layoutDirection` | "auto" ("auto", "rtl" or "ltr") | `LayoutDir.isRtl` (hook M1) |
| `autoSync` | true | **sync engine (cloud agent), hook S1** |
| `transferNetwork` | "wifi" ("wifi" or "any") | **Wi-Fi transfer / uploads (cloud agent), hook S2**, via `TransferPolicy.allowed(ctx, settings)` |
| `aiEnabled` | true | AI entry points (hooks A1 to A6). The wizard, Account and Settings already honour it. |
| `rememberOrientation` | true | `G6ReaderEffects` (clears the lock on close when false) |

The keep-screen-on timeout (1/5/10/30 min or never) and the orientation lock (auto/portrait/landscape) are written directly into the
reader's SharedPreferences "view" (`screen_on_time`, `orientation_lock`, `orientation_lock_landscape`), so they work without a hook.
The data-collection toggle uses the existing `AcctPrefs.analyticsOptIn`.

## Hooks for the lead (exact)
**M1 MainActivity.kt** (RTL/LTR override). Find:
```kotlin
                SmartDocsTheme(mode = settings.theme, rtl = settings.language == "ar") {
```
Replace with:
```kotlin
                SmartDocsTheme(mode = settings.theme, rtl = com.smartdocs.editor.core.gaps6.LayoutDir.isRtl(settings)) {
```

**R1 ui/reader/ReaderScreen.kt** (foldable book posture: two pages across the hinge; full-screen status bar; orientation reset). Find:
```kotlin
    com.smartdocs.editor.ui.reader.view.ReaderViewEffects(state, settings, rtl)
```
Insert after it:
```kotlin
    val fold = com.smartdocs.editor.ui.gaps6.rememberFoldState()
    com.smartdocs.editor.ui.gaps6.G6ReaderEffects(state, settings, fold)   // MODULE_gaps6
```

**R2 ui/reader/ReaderScreen.kt** (volume keys flip or zoom). Find:
```kotlin
com.smartdocs.editor.ui.view5.V5Keys.onKey(state, e.nativeKeyEvent) || state.vx.handleKeyCode(e.nativeKeyEvent.keyCode, settings.volumeKeysFlip, rtl)
```
Replace the part after `||` with:
```kotlin
com.smartdocs.editor.ui.gaps6.G6Keys.handle(state, e.nativeKeyEvent.keyCode, settings, rtl)
```

**R3 ui/reader/view/ScreenControls.kt** (hardware keys through ReaderKeyBus). Find:
```kotlin
    DisposableEffect(state, volume, rtl) {
        val h: (Int) -> Boolean = { code -> vx.handleKeyCode(code, volume, rtl) }
```
Replace with:
```kotlin
    DisposableEffect(state, volume, rtl, settings.volumeKeysAction) {
        val h: (Int) -> Boolean = { code -> com.smartdocs.editor.ui.gaps6.G6Keys.handle(state, code, settings, rtl) }
```

**R4 ui/reader/ReaderScreen.kt** (optional: tabletop posture puts the document over the panel). Needs R1. Wrap the page `Box(Modifier.weight(1f)…)`
content in `com.smartdocs.editor.ui.gaps6.TabletopSplit(fold, showPanel = panel != null && !tablet, content = { …existing content… }, panel = { PanelsContent(state, panel!!, onPanel = { panel = it }, onJump = { state.jumpTo(it) }, onAiOutline = { nav.navigate(Routes.tool("ai", state.fileUri)) }, onClose = { panel = null }) })`.
Then change `if (!tablet && panel != null) ModalBottomSheet(` to `if (!tablet && panel != null && !fold.isTabletop) ModalBottomSheet(`.

**A1 to A6: AI master switch** (`AiGate`; settings come from `LocalSettings.current`):
- A1 `ui/tools/ToolsScreen.kt` in `ToolsScreen`. After `val ctx = LocalContext.current` add
  `val g6Tools = com.smartdocs.editor.core.gaps6.AiGate.filter(com.smartdocs.editor.LocalSettings.current, allTools) { it.id }`.
  Then replace `allTools` with `g6Tools` on the 3 lines in that function (the `top` computation, `labels`, `filtered`).
- A2 `ui/reader/ReaderToolbars.kt`: `ToolChip(Icons.Default.AutoAwesome, stringResource(R.string.tool_ai), onClick = a.ai)` becomes
  `if (com.smartdocs.editor.LocalSettings.current.aiEnabled) ToolChip(...)`. Where `readerTools` is iterated, use
  `readerTools.filter { it !is ReaderToolItem.Screen || com.smartdocs.editor.core.gaps6.AiGate.allowsTool(com.smartdocs.editor.LocalSettings.current, it.id) }`.
- A3 `ui/reader/ReaderOverlays.kt`: wrap both `ToolChip(Icons.Default.Translate, …ai_translate…)` and `ToolChip(Icons.Default.AutoAwesome, …ai_explain…)`
  in `if (com.smartdocs.editor.LocalSettings.current.aiEnabled) { … }`.
- A4 `ui/reader/ReaderPanels.kt`: wrap the `Button(onClick = onAiOutline)` in the same `if`.
- A5 `ui/view5/Commands5.kt`: skip the `V5Command("ai", …)` entry when `!settings.aiEnabled`.
- A6 `ui/tools/ToolScreens.kt`: optional guard so deep links cannot open disabled AI tools. Before the `when (id)`, add
  `if (!com.smartdocs.editor.core.gaps6.AiGate.allowsTool(com.smartdocs.editor.LocalSettings.current, id)) { nav.popBackStack(); return }`.

**S1 core/sync/SyncWorker.kt (cloud agent)**: at the start of an *automatic* (periodic) run, return success without syncing when
`!(applicationContext as SmartDocsApp).settings.settings.first().autoSync`. Manual "sync now" ignores the flag.

**S2 ui/cloud/WifiScreen.kt and cloud uploads (cloud agent)**: before starting a transfer, check
`com.smartdocs.editor.core.gaps6.TransferPolicy.allowed(ctx, settings)`. It returns false on mobile data when `transferNetwork == "wifi"`.
For WorkManager jobs, use `NetworkType.UNMETERED` when `settings.transferNetwork == "wifi"`, otherwise `CONNECTED`.

**L1 (optional) invite deep link**: an intent-filter for `https://smartdocs.app/invite` (App Links, needs domain verification) in
AndroidManifest. In `handleIntent` call `com.smartdocs.editor.core.gaps6.ReferralStore.redeem(this, uri.toString())`.
Without it, the friend types or pastes the code or link in Account → Invite friends.

Storage row: Settings → Sync & transfer → Storage calls `nav.navigate("storage")` inside `runCatching`, so it does nothing until the
sharing agent registers the `storage` route. Help centre, trash, what's new and the notifications settings link already existed in Settings.

## Status per item
1. **Dual-screen / foldable: DONE (reader part needs hooks R1, R4)**. `rememberFoldState()` uses WindowInfoTracker folding features.
   Home and Files show list + detail with `TwoPane` on unfolded or dual screens (split at the hinge) and on screens ≥ 840 dp.
   In the reader, book posture or a separating vertical hinge switches to FACING (two pages, one each side) and restores the previous mode after (R1).
   Tabletop document + panel is `TabletopSplit` (R4, optional).
2. **Files: DONE**.
   - "Copy to" is in the row menu and the selection bar, with the same folder picker as move. Name clashes become "name (2)"; a folder cannot be copied into itself.
   - "Extract" on .zip files runs `ZipUtil.unzip` into a new unique folder, with a snackbar that opens it.
   - Tags are limited to 5 per file in the tag sheet, with a message.
3. **Settings: DONE**:
   - full-screen top bar (R1)
   - volume keys flip or zoom (R2, R3), default on
   - RTL/LTR override (M1)
   - auto-sync and transfer network: stored and shown; enforcement needs S1 and S2 (cloud agent)
   - AI master switch: hides Settings fields, the Account row and wizard results; the rest needs A1 to A6
   - keep-screen-on timeout (1/5/10/30 min, never)
   - orientation lock and remember orientation
   - data collection
   - storage row; help, trash, what's new and notifications were already there
4. **Account: DONE, with local parts**.
   - Google sign-in: no legacy GoogleSignIn in play-services-auth 22, so it uses AuthorizationClient with `openid email profile`, then the userinfo endpoint as a fallback. Needs the same Cloud Console Android OAuth client as Drive.
   - Activated devices: there is no licence-server endpoint in core/billing, so the list is local. It is recorded on a successful activation and can be deleted.
   - Invite: per-install code (8-char Crockford base32 of SHA-256 of a random install id) and link `https://smartdocs.app/invite?ref=<code>`.
   - Referral counter: invites sent, plus redeeming a friend's code once. "Friends joined" needs a server and shows "—".
5. **Home: DONE**. "From template" opens `Routes.tool("templates")` (quick row and the + sheet). The tool wizard is a card and sheet
   (the detail pane on large screens). It ranks all 50 tool ids with Arabic normalization, offers the 10 suggested goals, and tapping opens `Routes.tool(id)`.
6. **Home/Files two-pane on tablets: DONE** (see 1).
7. **Insert pages from the camera in Organize: DONE**. The ML Kit scanner returns JPEG pages, then the existing insert dialog
   (before / after the selection / at the end) runs `insertImages`.
8. **Signature extras: DONE**.
   - Trusted certificates screen (import .cer / .crt / .pem, list, delete), plus "trust this certificate" on a verified signature.
   - The verify tab shows trusted / untrusted. A signature is trusted when its chain is built through the embedded certificates and ends at a trusted certificate, with signatures checked.
   - Certificate details: serial, key usage, EKU, issuer, validity, algorithm, SHA-256.
   - Appearance designer (name, reason, location, date, DN, logo, labels on/off, logo left/right/top) with a live preview. When it is on, digital signing uses the rendered image as the visible signature.
   - "Changes made to this document" compares against the last signed revision (signature ByteRange), or the previous incremental revision.
     It lists pages added, removed, rotated or resized, fields added, removed or changed, annotations added or removed, and links added or removed.
     It shows as a card and is confirmed before signing when there are changes.
   - Read aloud: skipped as instructed.

Limitations:
- Trust uses only the user's list (no system CA store, no revocation; LTV is separate).
- The change log matches pages by index, so a reordered page shows as several changes.
- The appearance is drawn with Android Canvas, so Arabic shaping is native.
