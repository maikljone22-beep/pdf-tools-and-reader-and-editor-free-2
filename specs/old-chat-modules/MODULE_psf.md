# MODULE psf — templates, split, certificate security, signature upgrades, form calculations, XFA

Strings: `res/values/strings_psf.xml` + `res/values-en/strings_psf.xml` (prefix `ps_`, identical key sets).
No gradle run. Core (non-Compose) files were compiled with a standalone kotlinc against android.jar + PdfBox-Android 2.0.27 +
BouncyCastle 1.72 + OkHttp: no errors in this module's files. The calc/format engine was unit-checked (SUM, SFN, getField script,
AFNumber/AFPercent/AFDate). Compose UI files were not compiled.

## Routes to wire (lead)
| id | composable | from |
|---|---|---|
| `templates` | `com.smartdocs.editor.ui.templates.TemplatesScreen(nav)` | Home "create from template", Tools tab |
| `cert_protect` | `com.smartdocs.editor.ui.tools.CertificateProtectScreen(nav, uri)` | Tools → Security (opens on the "open" tab when the file is certificate-encrypted) |

`split`, `unlock`, `forms`, and `digital_sign` keep their ids. They are extended in place.

**Manifest (needed for TSA/OCSP/CRL over plain http):** Android blocks cleartext HTTP by default. `TsaClient.post` retries
`http://` URLs as `https://`, which covers DigiCert and most TSAs. Many OCSP responders and CRL hosts are http-only, so for full
LTV add `android:networkSecurityConfig` with `<base-config cleartextTrafficPermitted="true"/>`, or set `usesCleartextTraffic="true"`.
TSA, OCSP and CRL payloads are signed. I did not create the XML because the manifest is not mine.

## Files
Core
- `core/templates/TemplateModels.kt`: `TemplateKind` (11 templates, inputs, row ranges), `TemplateInput`, `TemplateDefaults` (Arabic defaults + samples).
- `core/templates/TplCanvas.kt`: PdfBox drawing helper (shaped Arabic text with alignment/measuring/wrap, lines, rects, images) plus a field collector (`FieldScript` = calc/format/keystroke JS).
- `core/templates/TemplateRenderer.kt`: `render(context, kind, input, out)`. It draws the pages, adds the fields with `FormBuilder.addFields`, attaches `/AA` JS actions and `/CO`, and sets calc fields read-only. Also holds the letterhead/footer helpers.
- `core/templates/TemplatePapers.kt`: lined, grid (5 mm), graph (1/5/10 mm), music staves, to-do (check box + task + priority fields), weekly timetable (Sat–Thu × periods).
- `core/templates/TemplateDocs.kt`:
  - Official letter (letterhead, ref/date/subject/body/signature fields)
  - Invoice (qty × price per row via `AFSimple_Calculate("PRD")`, subtotal SUM, tax via SFN, grand total, `AFNumber_Format`)
  - Certificate
  - Meeting minutes
  - Exam sheet (school header, student name/class/section/seat/score fields, instructions, questions with answer lines over N pages)
- `core/sign/TsaClient.kt`: RFC 3161 client (BC `TimeStampRequestGenerator` + OkHttp), presets, http→https retry.
- `core/sign/LtvSupport.kt`:
  - `addDss` (incremental `/DSS` with Certs/OCSPs/CRLs + per-signature `/VRI`; issuers from AIA caIssuers; OCSP from AIA, CRL fallback; `/Extensions /ESIC`)
  - `addDocTimestamp` (`/DocTimeStamp`, `ETSI.RFC3161`)
  - `hasDss`
- `core/sign/PagingSeal.kt`: seal bitmap cut into N vertical strips, one on the right edge of each page (page content, so it is covered by the signature).
- `core/sign/PreSignAnalyzer.kt`: static analysis. It finds:
  - OpenAction, catalog AA, Names/JavaScript, page AA, link JS, field and widget AA (with trigger events)
  - Named page templates
  - XFA and OCG layers
  - Unsigned signature fields and empty fillable fields
  - Existing signatures and an existing DocMDP P level (`canCertify`, `signingLocked`)
- `core/sign/CertEncryption.kt`:
  - `PublicKeyProtectionPolicy` encryption (AES-256 default) with per-recipient `Perms` and the reference app's dependency rules
  - `readCertificates` (.cer/.crt/.der/.pem/.p7b); certificate from a PKCS#12 file
  - `open(file, p12, pw)` via `PDDocument.load(file, pw, keyStoreStream, alias)` (alias = first key entry), `decryptToFile`, `permissionsFor`
  - `isCertificateEncrypted` (raw `Adobe.PubSec` scan)
- `core/forms/AfExpr.kt`: expression evaluator (+ - * / %, parentheses, Math.*, AFMakeNumber; SFN identifiers with dots and `\` escapes) plus `AfNumbers` (AFMakeNumber-like parsing incl. Arabic-Indic digits).
- `core/forms/AfFormat.kt`: parses `AFNumber_Format`, `AFPercent_Format`, `AFDate_FormatEx`/`AFDate_Format`, `AFTime_*` and `AFSpecial_Format`, and the keystroke kind. Also does sepStyle 0–4 and negStyle 0–3 formatting, and the Acrobat→SimpleDateFormat pattern conversion and date parsing.
- `core/forms/FormCalc.kt`:
  - Parses `/AA /C` scripts: `AFSimple_Calculate(SUM|PRD|AVG|MIN|MAX, new Array(...) | [...] | "a, b")`, SFN (`BVCALC … EVCALC`), and `var x = …; event.value = …` with `this.getField("x").value`
  - `FormCalcModel.recalculate(values)` runs in `/CO` order (2 passes); also provides `display`/`displayMap`
  - Parent field names expand to their children
- `core/forms/XfaSupport.kt`:
  - `detect` (NONE/STATIC/DYNAMIC)
  - `readFields`: template walk with subforms, exclGroup, field types, items/save items, and `bind none|global|dataRef`, bound to `xfa:datasets`
  - `saveValues`: rewrites only the datasets packet (array or single-stream XFA), drops `/Perms /UR3`

Modified (owned)
- `core/pdf/DigitalSignature.kt`:
  - `SignOptions` gains `tsaUrl`, `pades`, `certifyLevel`, `ltv`, `docTimestamp`; new `PagingSealOptions` and `SignReport`
  - `sign(...)` now returns `SignReport` and takes `pagingSeal`
  - The CMS signer adds signing-certificate-v2 (PAdES) and the TSA token as `id_aa_signatureTimeStampToken`. DocMDP is applied for certification. Post-steps are DSS, then document time-stamp
  - `VerifyResult` gains timestamp date/validity, doc-timestamp, certify level, LTV flag, subFilter; `verify` checks signature and document time-stamp tokens
  - `ensureProvider`/`pkcs12Store` are now `internal`; the provider is also set for PdfBox's `SecurityProvider`
  - New self-signed certificates include keyEncipherment and dataEncipherment, so they can also serve as encryption recipients
- `core/pdf/FormOps.kt`: `fillFields(..., context, display, dropXfa)` (all new params are defaulted):
  - Formatted appearance with the raw `/V` kept
  - Arabic text fields get embedded Noto Naskh in the DR, shaped right-aligned appearances
  - `NeedAppearances` is set only when generation fails
  - Read-only fields are written only when they are in `display` (calculated fields)
- `ui/tools/MergeSplitTools.kt`: split modes "by bookmarks" (preview list, files named `NN_<title>.pdf`) and "by size (MB)" (greedy grouping by measured page sizes, re-halving oversize parts); `SplitOps` object.
- `ui/tools/SecurityTools.kt`:
  - `CertificateProtectScreen` with an encrypt tab (recipients, per-recipient permission dialog, AES-256/128, add own cert) and an open tab (p12 or own key + password → decrypted copy)
  - Unlock shows a hint for certificate-encrypted files
- `ui/signature/DigitalSignScreen.kt`: advanced options card, pre-sign warning before signing, result info (timestamp, LTV counts, warnings), richer verify cards.
- `ui/forms/FormsScreen.kt`:
  - Live recalculation (`derivedStateOf`); calculated fields shown read-only with formatted value
  - Numeric keyboard and date picker from the keystroke/format actions
  - XFA banner. Static XFA: AcroForm fill, XFA dropped on save. Dynamic XFA: fill-in list saved into datasets, and "flatten" explains why it is impossible

New UI
- `ui/templates/TemplatesScreen.kt` (gallery), `TemplateEditor.kt` (inputs, debounced live preview, tablet side-by-side, org/logo/accent remembered in prefs `psf_templates` + `filesDir/template_logo.png`), `TemplateThumbs.kt` (PdfSession render, PNG cache `cacheDir/tpl_thumbs`).
- `ui/signature/SignAdvancedOptions.kt` (state persisted in prefs `psf_sign`), `ui/signature/PreSignDialog.kt`.
- `ui/forms/FormCalcUi.kt` (`CalculatedValue`, `ScriptedTextField` with date picker), `ui/forms/XfaFormPanel.kt`.

## Limitations
- **Template fields:** template form fields use FormBuilder's Helvetica DA, so Arabic typed in other viewers relies on NeedAppearances. Our FormsScreen embeds the Arabic font when saving. Priority options are 1/2/3 for the same reason.
- **Arabic appearances:** multi-line Arabic appearances wrap on the visual (shaped) string, so a long RTL line that wraps may show its lines in reverse order.
- **Paging seal:** strips are drawn in unrotated page space, so pages with `/Rotate` get the strip on their unrotated right edge.
- **LTV is best effort:**
  - Network or responder failures are reported as warnings and signing still succeeds.
  - OCSP responder certificates' own revocation data is not fetched recursively.
  - Certificate trust is not evaluated on verify.
- **Signature types:**
  - Certification (DocMDP) is only offered as the first signature.
  - No FieldMDP /Lock for approval signatures.
  - A document timestamp requires the TSA option.
- **Form calculations:**
  - No JS engine. Unsupported calculate scripts (if/loops/functions) are ignored, so the field stays editable.
  - Validate (`/V`) actions are not run.
  - The reader has no form-filling path, so recalculation lives in FormsScreen only.
- **XFA:**
  - Dynamic XFA rendering and flattening are not possible on device.
  - The data binding covers normal / global / simple `dataRef` binding only (no scripting, no repeating-subform instance management beyond the index).
  - Saving drops Reader-extension usage rights (`UR3`).
- **Certificate encryption:**
  - Recipients must have RSA keys; expired certificates are blocked.
  - Smart cards and Android KeyChain are not supported.
- **Split by size:** relies on per-page measurement, so shared resources make it approximate (oversize groups are re-split).
