# pdf-3c: Nitro §9 Protect + §11 Sign (+ Foxit §28/§30) — row-by-row

Status key:
- **done**: built and green on CI.
- **present**: it was already in the code.
- **NF**: not feasible, with the reason.

Nothing below has been tested on a device; every UI row needs a device test.

Branches, all merged to main: nitro-protect-sign, nitro-quicksign, nitro-protect, nitro-sign2, nitro-fillsign2, nitro-protect2, nitro-esign2.

## §9 Protect
| Row | Status | Where |
|---|---|---|
| 40-bit ARC4 | done (with warning) | ProtectAdvanced RC4_40 |
| Certificate security: recipients from Trusted Contacts | done | SecurityTools picker |
| Security Profiles | done (no passwords stored) | core/redact/SecurityProfiles |
| Trusted Contacts: request certificate by e-mail | done | TrustedCertsScreen, OwnPublicCert |
| Trusted Contacts: export .cer/.pem/.p7b/.p7c | done | core/sign/CertExport |
| My Digital IDs: create (OU, country, usage, RSA 2048/3072/4096, EC P-256/384, Ed25519, validity), several IDs | done | core/sign/DigitalIds, CertificateTab |
| Android KeyChain IDs (Windows-store equivalent) | done | DigitalIds.chooseKeyChain / loadKeyChain |
| Digital ID export (.p12 / .cer, share) | done | CertificateTab |
| Digital ID details (SHA-256 / SHA-1 / MD5, serial, usage…) | done | CertDetailsBlock |
| Microsoft RMS / AIP / .ppdf | NF (MIP SDK and tenant); detection and hand-off are present | EnterpriseProtection |
| Manage Internet Access | done | core/security/DocSecurity |
| File-launch warnings / block executables | done | ReaderLinks |
| Disable JavaScript, plus run levels and Action Inspector | done | JsPolicy, TrustManager, ActionInspector |
| Foxit §28: show permissions; reader honours /P | done | ReaderRestrictions, ReaderGuard |
| Foxit §28: Change Settings pre-filled | done | ProtectPrefill |
| Foxit §28: certificate "don't encrypt metadata"; change/remove in place | done | PubSecCrypto |
| Foxit §28: Safe Reading Mode, privileged locations, Protected View | done | TrustManager |
| Foxit §28: attachment open policy | done | AttachmentPolicy |
| Foxit §28: EU trusted list (LOTL) | done (HTTPS plus pinned URL; the lists' XAdES signature is NOT verified) | TrustLists |
| AATL / Notarius bundled roots | NF (licensing); covered by the system CA store and import | SystemTrust |
| Security watermark profiles | done | SecurityWatermarkSet |
| AES-GCM (ISO 32003) | NF (PdfBox 2.0 cannot write AESV4) | — |

## §11 Sign
| Row | Status | Where |
|---|---|---|
| Sign INTO an existing empty field, plus Sign / Clear / Validate / View signed version / Properties on the field | done | core/sign/SigFields, ui/signature/SigFieldActions |
| Visible signature anywhere (drag / resize / draw; rotation-aware widget appearance) | done | SigPlacementDialog |
| Same signature on several pages | done | SigFields.splitWidgets |
| Add / move / resize / delete empty signature fields | done | SigFields.addEmpty/move/delete |
| Reason presets | done | DigitalSignScreen |
| Contact info (was CODE-ONLY) | done | SignOptions.contactInfo |
| Lock after signing (FieldMDP all / include / exclude, read-only fields, P=1 lock) | done; SignedSave honours it | SigFields.applyLock |
| Signature profiles | done | core/sign/SignProfiles |
| Certify: ask for file name | done | SignPrefs.askFileName |
| Appearance: font size, font family, text direction, named profiles | done | SigAppearanceSpec |
| QuickSign black & white | done | SignatureMono |
| Time-stamp servers: list, login, trusted-only ("attestable") | done | TsaServers, SecretBox, TsaTrust |
| LTV: OCSP/CRL revocation check during validation | done | core/sign/Revocation |
| System trust roots (Android CA store) | done (toggle) | SystemTrust |
| Signer details: location / contact | done | VerifyCard |
| Revision X of Y, View Signed Version, compare with current | done | SigRevisions, SignedCompare |
| Counter-signed state (CMS countersignature and later signatures) | done | VerifyResult.counterSigners / signedLaterBy |
| Signature status icons on pages + hide preference | done | InPageFormLayer badge, SignPrefs |
| Signed / certified bar on open | done | SigFieldSheet SignedBar |
| QuickSign from a PDF page; password-protected signature; recently used; enlarge canvas | done | PdfPageCropDialog, SignatureLock, SignatureMeta |
| Locked signatures in the data bundle (encrypted with their lock) | done | LockedSignatureFiles |
| Foxit §30: batch signing (several files) | done | DigitalSignScreen signBatch |
| Foxit §30: add LTV to a signed file; Time-stamp document (was CODE-ONLY) | done | Verify tab |
| Foxit §30: verification time (secure / signing / now) | done | DigitalSignature.verificationTime |
| Foxit §30: trust levels; trust TSA from validation | done | TrustedCerts.Level |
| Foxit §30: SHA-384/512/SHA3 + EdDSA | done | DigitalSignature.Digest |
| Foxit §30 Fill & Sign: rounded rect, comb, clipboard, keep size, temporary signature, default size, copy/paste, multi-page, apply all + flatten, reorder/edit, paging seal | done | core/fillsign2/* |
| Foxit §30 eSign: bulk send (+ cancel), online form, branding (+ in-app signer) | done; form and branding need the worker redeployed | core/esign/* |
| Signatures panel in the reader | owner pdf-ad (reuses DigitalSignature.verify and SigFields.list) | — |
| Topaz pad / Windows store / activate seats | N/A on Android | — |

## Owner / device follow-ups
- Redeploy `ai-proxy/esign/worker.js` (online forms, branding).
- Set the forum / Labs URLs in `strings_nav_prefs.xml`.
- Device tests:
  - signed-PDF validity in Acrobat after field signing, multi-page, clear and FieldMDP;
  - KeyChain signing;
  - restricted-PDF matrix (user vs owner password);
  - EU list download;
  - WebDAV against a real server.
