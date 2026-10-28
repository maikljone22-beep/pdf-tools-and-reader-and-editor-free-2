# SmartDocs: server and keys setup (step by step)

This file holds **no secrets**. It names the settings you must create and where they go. Keep the real values in a password manager.

The app works **without any server**: reading, editing, signing, OCR, convert and offline tools all run on the phone. Each section says which features the server or key turns on, and whether it is **optional**.

Contents:
0. Tools you need once
1. AI proxy (`ai-proxy/`) — optional
2. Collaboration / shared review (`ai-proxy/collab`) — optional
3. Share links (`ai-proxy/share`) — optional
4. E-signature requests and online forms (`ai-proxy/esign`) — optional
5. Connected documents (`ai-proxy/connected`) — optional
6. Licence server (`ai-proxy/license`) — optional
7. Cloud storage sign-in (OAuth) — optional per provider
8. Ads (AdMob + consent) — optional, off by default
9. Release signing and Google Play
10. Summary: the app-side files

---

## 0. Tools you need once
1. Create a free Cloudflare account (Workers + KV).
2. Install Node.js LTS, then `npm install -g wrangler`.
3. Sign in once with `wrangler login`. It opens the browser.
4. Every worker below is deployed from its own folder:
   ```
   cd ai-proxy/<worker>
   wrangler deploy
   ```
   - Its address will be `https://<worker-name>.<your-subdomain>.workers.dev`.
   - Secrets are set with `wrangler secret put NAME`, which asks for the value. Never write secrets into `wrangler.toml` or into git.
   - `APP_TOKEN` is a long random string, e.g. the output of `openssl rand -hex 32`. The same value goes into the app's properties file so that only your app can call the worker.

---

## 1. AI proxy: `ai-proxy/` (worker `smartdocs-ai`) — OPTIONAL
Turns on the AI chat, summary, translate, rewrite, voices, image questions, transcription, image generation and web search, without each user typing an API key. Without it, users can still enter their own key in AI settings.

1. In `ai-proxy/wrangler.toml`, check the `[vars]`.
   - `PROVIDER_URL` + `DEFAULT_MODEL` are Groq by default; any OpenAI-compatible API works.
   - The speech, vision, transcription, image and embedding blocks can each be removed to turn that feature off.
2. Secrets:
   - `wrangler secret put PROVIDER_KEY`: the key of the chat provider (required).
   - `wrangler secret put APP_TOKEN`: required.
   - Optional:
     - `TTS_KEY` (AI voices);
     - `STT_KEY` (transcription; otherwise PROVIDER_KEY is used);
     - `IMAGE_KEY` (image generation);
     - `EMBED_KEY` (better passage ranking);
     - `SEARCH_KEY` (Brave Search API, live web search).
3. Optional daily limit per device:
   - `wrangler kv namespace create RATE`;
   - uncomment the `[[kv_namespaces]] binding = "RATE"` block and paste the id.
4. `wrangler deploy`.
5. App side: create **`ai.properties`** in the project root (git-ignored):
   - `proxyUrl=` the worker address;
   - `appToken=` the same APP_TOKEN.

## 2. Collaboration / shared review: `ai-proxy/collab` (worker `smartdocs-collab`) — OPTIONAL
Turns on shared reviews (comments synced between reviewers), the review tracker and the "open in app" page. It also relays the Box sign-in (section 7).

1. `wrangler kv namespace create COLLAB`, then paste the id into `ai-proxy/collab/wrangler.toml` (binding `COLLAB`).
2. Optional: an R2 bucket for big PDFs. Uncomment `[[r2_buckets]] binding = "FILES"`. Without it, PDFs are kept in KV, up to 20 MB each.
3. Secrets:
   - `APP_TOKEN` (recommended);
   - for Box sign-in: `BOX_CLIENT_ID` and `BOX_CLIENT_SECRET`.
4. `wrangler deploy`.
5. App side, **`cloud.properties`**:
   - `collabUrl=` the worker address;
   - `collabToken=` its APP_TOKEN.

## 3. Share links: `ai-proxy/share` (worker `smartdocs-share`) — OPTIONAL
Turns on share links, review invitations and reminders by e-mail. It needs section 2.

1. In `ai-proxy/share/wrangler.toml`:
   - bind the **same** COLLAB KV id as section 2;
   - `wrangler kv namespace create SHARE` and paste that id (binding `SHARE`);
   - if collab uses R2, bind the same bucket as `FILES`.
2. `[vars]`:
   - `PUBLIC_URL` = this worker's address;
   - `COLLAB_URL` = the collab address;
   - `MAIL_FROM` (and `MAIL_API_URL`, e.g. the Resend API) if you want e-mails.
3. Secrets:
   - `APP_TOKEN` (the same as collab, or its own);
   - optional `MAIL_API_KEY`, which enables e-mails.
4. `wrangler deploy`. The cron (every 30 minutes) sends reminders and digests.
5. App side, `cloud.properties`:
   - `shareUrl=`
   - `shareToken=`

## 4. E-signature requests and online forms: `ai-proxy/esign` (worker `smartdocs-esign`) — OPTIONAL
Turns on "request signatures", the signer web page, bulk send, **online forms (sign by link)**, branding on the signing page and e-mails, and the `/v` verify page.

**The latest version must be deployed for online forms and branding.** An older worker answers 404 for forms.

1. `wrangler kv namespace create ESIGN`, then paste the id (binding `ESIGN`).
2. Optional: R2 bucket `FILES`.
3. `[vars]`:
   - `PUBLIC_URL` = the worker address (used in links and by the 6-hourly reminder cron);
   - optional `MAIL_API_URL` + `MAIL_FROM` for e-mails.
4. Secrets:
   - `APP_TOKEN` (required);
   - optional `MAIL_API_KEY`.
5. `wrangler deploy`.
6. App side, `cloud.properties`:
   - `esignUrl=`
   - `esignToken=`
   E-sign screens stay hidden until both are set.

## 5. Connected documents: `ai-proxy/connected` (worker `smartdocs-connected`) — OPTIONAL
Turns on connected documents: versions, readers, revoke, "a newer version exists".

1. `wrangler kv namespace create DOCS`, then paste the id (binding `DOCS`).
2. Optional: R2 bucket `FILES`.
3. Secret: `APP_TOKEN`.
4. `wrangler deploy`.
5. App side, `cloud.properties`:
   - `connectedUrl=`
   - `connectedToken=`

## 6. Licence server: `ai-proxy/license` (worker `smartdocs-license`) — OPTIONAL
Turns on server-checked activation codes, device limits, revocation and Play purchase checks. Without it the app uses offline codes plus Play Billing ("client-only mode").

1. `wrangler kv namespace create LICENSES`, then paste the id.
2. `node keygen.mjs` (in `ai-proxy/license`) prints a key pair.
   - Put **PUBLIC_KEY** into `[vars] PUBLIC_KEY` (it is not secret).
   - Keep SIGNING_KEY for step 3.
3. Secrets:
   - `SIGNING_KEY`;
   - `CODE_SECRET`: the same value as `codeSecret` in `pro.properties`, so existing codes keep working;
   - `ADMIN_KEY`: a long random string for `/admin/*`;
   - `PLAY_SERVICE_ACCOUNT`: the whole JSON of a Google Cloud service account that has access to your app in the Play Console (for purchase checks);
   - `RTDN_SECRET`: a random string. It is part of the Pub/Sub push URL for Play real-time notifications.
4. `[vars]`: `PACKAGE_NAME` (com.smartdocs.editor), `MAX_DEVICES`, `TOKEN_DAYS`.
5. `wrangler deploy`.
6. App side, **`license.properties`** (see `license.properties.example`):
   - `licenseUrl=`
   - `licensePublicKey=` the PUBLIC_KEY.
7. **`pro.properties`**: `codeSecret=`. Only needed if you sell offline activation codes; otherwise optional.

## 7. Cloud storage sign-in (OAuth) — OPTIONAL per provider
Every provider is optional. A provider without keys is hidden or shows "not configured". The system file picker (Android's document providers) always works without keys.

Redirect URIs: the app receives `smartdocs://oauth/<provider>`; the intent filter is already in the manifest.

- **Google Drive** (also Google account / app-data backup):
  1. In Google Cloud Console, create a project and enable the **Google Drive API**.
  2. OAuth consent screen: add the scopes the app asks for (Drive file access and `drive.appdata` for backup).
  3. Create an **OAuth client of type Android**: package `com.smartdocs.editor` plus the **SHA-1 of the signing key**. Make one client for your debug key and one for the Play App Signing key (Play Console → App integrity). No key goes into the app, because Android matches the package and SHA-1.
- **OneDrive (Microsoft)**:
  1. Azure portal → App registrations → New, for personal + work accounts.
  2. Platform "Mobile and desktop", redirect URI `smartdocs://oauth/onedrive`.
  3. API permission Files.ReadWrite (and offline_access).
  4. In `cloud.properties`:
     - `onedriveClientId=`
     - optional `onedriveRedirect=` (if you used another URI)
- **Dropbox**:
  1. Dropbox App Console → create an app (scoped access).
  2. Add the redirect URI `smartdocs://oauth/dropbox`.
  3. Permissions: files.content.read / write.
  4. In `cloud.properties`:
     - `dropboxAppKey=`
     - optional `dropboxRedirect=`
- **Box**:
  1. Box Developer Console → custom app (OAuth 2.0).
  2. The redirect URI must be `<collabUrl>/oauth/box`, because Box needs https. The collab worker passes it back to the app.
  3. Put the client id and secret as **collab worker secrets** `BOX_CLIENT_ID` / `BOX_CLIENT_SECRET`.
  4. In `cloud.properties`: `boxClientId=`, and nothing else.
     - By default the redirect is `<collabUrl>/oauth/box` and the token exchange goes to `<collabUrl>/oauth-token/box`, where the collab worker adds the secret. So the app ships no secret.
     - `boxRedirect=` and `boxTokenUrl=` are only needed to override those defaults.
     - `boxClientSecret=` is a fallback that puts the secret inside the APK. Avoid it.
- **WebDAV** (Nextcloud, NAS): no setup. Users add a place in the Cloud tab with their own URL, user and password.
- `supportEmail=` in `cloud.properties` is the address shown in Help (optional).

## 8. Ads: AdMob + consent (UMP) — OPTIONAL, OFF by default
1. AdMob → add the app → create a banner and an interstitial unit.
2. AdMob → Privacy & messaging: create a **GDPR consent message** (and the US states message if needed). The app shows it through the UMP SDK before loading ads.
3. Copy `ads.properties.example` to **`ads.properties`**:
   - `enabled=true`
   - `appId=` copy it exactly; a malformed id crashes the Ads SDK at start;
   - `bannerId=`
   - `interstitialId=`
4. Debug builds always use Google's test units. Paid / pro users see no ads.
5. Add an `app-ads.txt` on your developer website (AdMob shows the line to publish).

## 9. Release signing and Google Play
- **Upload key:** `keystore.properties` + `release.jks` in the project root.
  - Both are git-ignored. **Back them up** in at least two safe places.
  - Keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
- **Play App Signing:** enrol when you create the app.
  - Google then holds the real app-signing key, and your `release.jks` is only the **upload key**. A lost upload key can be reset by Google support; a lost app-signing key cannot.
  - Use the **App signing key's SHA-1** (Play Console → Test and release → App integrity) for the Google Drive Android OAuth client in section 7.
- **Play Billing products:** create the in-app products / subscriptions with the same ids the app uses (see `core/billing/BillingManager.kt`). For server checks, link the service account from section 6.
- **Data safety form:** declare the optional servers you deploy: AI text sent to your AI proxy, review/share/esign documents stored on your workers, ads/consent.

## 9b. Verified invite links (https://smartdocs.app/invite/…) — OPTIONAL
The app declares an auto-verified link for `https://smartdocs.app/invite` (added with fix/fc-settings). Android opens such links directly in the app only after it has verified that you own the domain; without it, the link opens in the browser (the in-app `smartdocs://` links keep working).
1. Get the SHA-256 fingerprints of the keys that sign the installed app:
   - Play App Signing key: Play Console → *Test and release → App integrity → App signing* → "SHA-256 certificate fingerprint".
   - Your upload / local release key (for side-loaded builds): `keytool -list -v -keystore release.jks -alias <keyAlias>` → the `SHA256:` line (it asks for the store password).
   - Optional, for debug builds: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`.
2. Create the file `assetlinks.json` with this content (one entry, several fingerprints allowed):
   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "com.smartdocs.editor",
       "sha256_cert_fingerprints": ["AA:BB:…:FF", "11:22:…:99"]
     }
   }]
   ```
3. Publish it at exactly `https://smartdocs.app/.well-known/assetlinks.json`:
   - served over HTTPS with a valid certificate, HTTP 200, no redirect, `Content-Type: application/json`;
   - on Cloudflare Pages / Workers, a static file at `.well-known/assetlinks.json` works; on other hosts make sure `.well-known` is not blocked.
4. Check it: `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://smartdocs.app&relation=delegate_permission/common.handle_all_urls` must list your package; on a device, `adb shell pm verify-app-links --re-verify com.smartdocs.editor` then `adb shell pm get-app-links com.smartdocs.editor` should show `smartdocs.app: verified`.
5. If you use another domain, change the `android:host` of the invite `<intent-filter>` in `AndroidManifest.xml` to it and publish the file there.

## 10. Summary: the app-side files
All are in the project root, git-ignored. **Keys only, never commit them.**

| File | Keys | Needed for |
|---|---|---|
| `ai.properties` | `proxyUrl`, `appToken` | AI without a user key (optional) |
| `cloud.properties` | `collabUrl`, `collabToken`, `shareUrl`, `shareToken`, `esignUrl`, `esignToken`, `connectedUrl`, `connectedToken`, `onedriveClientId`, `onedriveRedirect`, `dropboxAppKey`, `dropboxRedirect`, `boxClientId`, `boxRedirect`, `boxClientSecret` (avoid), `boxTokenUrl`, `supportEmail` | Review, share, e-sign, connected, OneDrive / Dropbox / Box (each optional) |
| `license.properties` | `licenseUrl`, `licensePublicKey` | Licence server (optional) |
| `pro.properties` | `codeSecret` | Offline activation codes (optional) |
| `ads.properties` | `enabled`, `appId`, `bannerId`, `interstitialId` | Ads (optional, off by default) |
| `keystore.properties` + `release.jks` | `storeFile`, `storePassword`, `keyAlias`, `keyPassword` | Release builds (required for Play) |

After changing a properties file, rebuild the app; the values are compiled in. A missing file or key simply turns that feature off.
