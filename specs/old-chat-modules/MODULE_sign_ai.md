# MODULE sign_ai — Signatures, Digital signing, Fill profile, Stamps, AI

## Files created
Strings: `app/src/main/res/values/strings_sign_ai.xml` (all names prefixed `sa_`; existing `sig_*`, `stamp_*`, `ai_*` reused).

Core
- `core/pdf/StampRenderer.kt` — `renderTextStamp(text, subtitle, color, widthPx = 600, border)`, `renderBilingual`, `renderDynamic`, `Border` enum, colour constants + `palette`.
- `core/pdf/DigitalSignature.kt` — `Identity`, `SignOptions`, `VisibleSignature`, `VerifyResult`; `loadPkcs12(stream|file, password)`, `generateSelfSigned(out, cn, org, email, password)`, `sign(context, src, out, identity, options, visible?, password?)` (PdfBox `addSignature` + BouncyCastle CMS detached, `saveIncremental`), `verify(file): List<VerifyResult>`, `ownKeystoreFile(context)`, `friendlyName(dn)`.
- `core/ai/AiClient.kt` — OpenAI-compatible client: `stream(messages): Flow<String>` (SSE, non-stream fallback), `complete()`, `testConnection()`, helpers `summarize/explain/translate/quizQuestions/define/chat` (Flow) and `outline(text): List<OutlineEntry>`; `AiClient.trimDocument(pages)` adds `[صفحة N]` markers and trims to 12k chars; `parseOutline(json)`.
- `core/ai/AnswerPdf.kt` — `AnswerPdf.write(context, title, text, out)` wraps lines with the Arabic font + ArabicShaper, RTL alignment for Arabic lines.

UI
- `ui/signature/SignaturesScreen.kt` — `SignaturesScreen(nav)` grid + filter + delete + "sign a document" (pdf picker → `Routes.reader`), create sheet.
- `ui/signature/SignatureCreateSheet.kt` — `SignatureCreateSheet(onDismiss, onSaved)`: Draw (bezier, pressure when `settings.pressureSensitivity`, colour chips, thickness, undo, clear), Type (4 Android typefaces), `ColorDot` (internal, reused by stamps).
- `ui/signature/SignatureImageTab.kt` — Image / Camera tab (TakePicture via `app.documents.shareUri`), white-background removal with threshold slider, crop to content.
- `ui/signature/SignatureBitmaps.kt` — `InkPoint/InkStroke/InkRenderer` (shared Canvas renderer for preview + PNG export), `SignatureImage` (typefaces, renderText, removeWhiteBackground, cropToContent, limit, savePng).
- `ui/signature/DigitalSignScreen.kt` — `DigitalSignScreen(nav, uri)`: tabs sign / verify / certificate; visible signature (corner, text, saved signature image) stamped first then signed; verify list with valid/invalid icons.
- `ui/signature/CertificateTab.kt` — pick `.p12/.pfx` (OpenDocument `*/*`) + password, use on-device certificate, generate self-signed (RSA-2048, 5 years) into `filesDir/own_certificate.p12`.
- `ui/signature/FillProfileScreen.kt` — `FillProfileScreen(nav)`; `defaultProfileKeys` (full_name … id_number), add/edit/delete, up/down reorder, "add defaults".
- `ui/stamps/StampsScreen.kt` — `StampsScreen(nav)` + `CustomStamps` helper object (`render(stamp)`, `stampsDir`, colour/border packing).
- `ui/stamps/StampPickerSheet.kt` — `StampPickerSheet(onPicked: (Bitmap) -> Unit, onDismiss)`: tabs جاهزة / ديناميكية / مخصصة; `presetStamps` list (12 bilingual stamps).
- `ui/ai/AiChatUi.kt` — `AiChatState` (`rememberAiChatState()`), `QuickAction`, `QuickActionChips`, `MessageList`, `ChatInput`.
- `ui/ai/AiSheet.kt` — `AiSheet(docText: suspend () -> String, selectedText: String?, onDismiss)`.
- `ui/ai/AiScreen.kt` — `AiScreen(nav, uri)`: file pick, PdfSession.allText, chat/actions, generate outline → `PdfOps.setOutline` → ResultSheet, export last answer as PDF.
- `ui/ai/AiSettingsScreen.kt` — `AiSettingsScreen(nav)`: endpoint/model/key, presets (OpenAI, Groq, OpenRouter, Ollama), test connection.

## Wiring needed by the lead (ToolScreens ids used in this module)
- `Routes.tool("digital_sign")` → `DigitalSignScreen(nav, uri)` (used from SignaturesScreen top bar).
- `Routes.tool("ai_settings")` → `AiSettingsScreen(nav)` (used from AiScreen).
- Suggested: `"sign"` → `SignaturesScreen`, `"ai"` → `AiScreen`, `"stamps"` → `StampsScreen`, `"fill_profile"` → `FillProfileScreen`.
- The reader's stamp tool can call `StampPickerSheet { bmp -> ... PdfOps.stampImage(...) }` and the AI button `AiSheet(docText = { AiClient.trimDocument(session.allText()) }, selectedText, onDismiss)`.

## Notes / limitations
- `res/xml/file_paths.xml` does not exist yet; the camera tab writes to `cacheDir/tmp` through `app.documents.shareUri` (FileProvider) — file_paths must include `<cache-path name="tmp" path="tmp/"/>` (or `path="."`).
- `CustomStamp` has no border column: the border style is packed into bits 32–35 of `color` (`CustomStamps.pack/argb/border`).
- Digital signature: no timestamp authority (TSA) and no LTV; self-signed certificates show as "unknown issuer" in Acrobat (expected). Encrypted PDFs have security removed before signing. Verification checks the CMS signature and ByteRange coverage; certificate chain trust is not evaluated.
- `AiClient` treats a non-`openai.com` endpoint as usable without an API key (local Ollama); for `openai.com` the UI shows `ai_no_key` until a key is set.
- No gradle run was performed (per brief); PdfBox-Android `PDSignature.getContents(byte[])`/`getSignedContent(byte[])`, `SignatureOptions.DEFAULT_SIGNATURE_SIZE` and BouncyCastle `JcaSignerInfoGeneratorBuilder` APIs are the ones expected in pdfbox 2.0.27 / bcpkix 1.80.
