# pdf-3c nav-settings gaps: what was done

Scope: Nitro §16, §17, §21, §22, §23, §24 (`cmp_16-24.md`), Foxit §15, §16, §18, §21 (`fcmp_14-21_23.md`), Foxit §31, §36, §37, §40 (`fcmp_28-42.md`), and the old-plan items in `OLD_PLAN_AUDIT.md`. Protect/Sign (Nitro §9/§11, Foxit §28/§30) is in `DONE_pdf3c_protect_sign.md`.

Branches, all merged to main:
- `feature/nav-bookmarks` (069a401)
- `feature/nav-attach` (8c3bcf7)
- `feature/nav-batch` (256a5c9)
- `feature/nav-prefs` (2efe9d6)

**Nothing was tested on a device.** Every row was compiled and unit-tested on CI only, and every UI row needs a device test.

**Status words:**
- **DONE:** built.
- **PRESENT:** already in the code.
- **PARTIAL:** part of the row is done; the note says what is missing.
- **NF:** not feasible; the note gives the reason.
- **OWNER:** the row belongs to another session.

Paths are under `app/src/main/java/com/smartdocs/editor/`.

## Nitro §16 / Foxit §15 / Foxit §16: bookmarks, links, destinations, actions (feature/nav-bookmarks)
| Row | Status | Where |
|---|---|---|
| Delete all bookmarks; custom colour; expand / collapse all | DONE | `ui/edit5/BookmarksScreen.kt`, `ui/edit5/NavUi.kt`, reader `OutlinePanel` |
| Bookmark actions (web, file, named, JS, print / extract pages) and /Next chains | DONE | `core/edit5/NavActions.kt` |
| Wrap, text size; panel search; find current bookmark | DONE | `ui/reader/ReaderPanels.kt` (OutlinePanel) |
| Auto bookmarks: 5 levels; per-level font / size / colour / indent / mask / keywords; leading numbers; zoom; Before; collapse; exact text; page range; settings import / export | DONE (JSON; Nitro's `.inb` format is undocumented) | `core/edit5/AutoBookmarks.kt`, `OutlineTools.kt` |
| Bookmark XML export / import; add a bookmark from selected text; sections extract / delete (old-plan item 9) | DONE | `core/edit5/OutlineNav.kt`, `ui/edit5/ReaderNav.kt` |
| TOC title / content styles and orientation | DONE | `OutlineTools.kt` (TocStyle) |
| Links: multi-select, align, distribute, move, resize; FitV / FitB / FitBH / FitR; named destination in another file; action lists; default appearance; border thickness; URL-to-link page scope | DONE | `core/edit5/LinkGeom.kt`, `LinkOps`, `ui/edit5/LinkEditor.kt`, `ui/edit5/LinksScreen.kt` |
| Password for the other file in a link | NF (a PDF action cannot carry one; the reader asks) | — |
| Auto Page Links, Auto Keyword Links, named actions | DONE | `core/edit5/AutoLinks.kt` |
| Document JavaScript editor + console, doc-level functions, document actions WC / WS / DS / WP / DP | DONE (respects JsPolicy) | `ui/edit5/DocScriptsScreen.kt`, `core/edit5/DocJs.kt` |
| Destinations panel, Cross-Reference panel | DONE | `ui/edit5/DestinationsScreen.kt`, `CrossRefScreen.kt`, `core/edit5/NamedDests.kt` |
| Signed files | DONE: all link and bookmark writes (including the old LinkOps full rewrite) are incremental | `core/edit5/NavIo.kt` |
| A6 / FILE-2/3 (reader honours /Direction, OpenAction, PageLayout) | OWNER pdf-39 | — |

## Nitro §17 / Foxit §18: attachments and portfolio (feature/nav-attach)
| Row | Status | Where |
|---|---|---|
| Attachments panel in the reader (indicator, chip, command) | DONE | `ui/attachments/ReaderAttachments.kt`, `AttachmentsBrowser.kt` |
| Open in the native app; delete one; edit description; print; created / modified dates; list / details / tiles | DONE | `core/pdf/AttachmentOps.kt`, `NameTreeCos.kt` |
| Add several files / a folder | DONE | `AttachmentsBrowser.kt` |
| Portfolio prompt on open (was CODE-ONLY) | DONE | `PortfolioPrompt` |
| Attach the source file when creating a PDF | DONE | To-PDF+, the convert hub, text→PDF |
| Open policy per type | DONE (protect2's `DocSecurityGate`; one rule set) | — |
| Signed / certified files | DONE: incremental saves; certified files refused | `SignedSave` |
| Drag-and-drop | NF (desktop gesture; Android uses the file picker) | — |

## Foxit §31: share / review (feature/nav-attach)
| Row | Status | Where |
|---|---|---|
| Strip metadata / JS / attachments on share (shared copy only) | DONE | `core/share5/ShareStrip.kt` |
| E-mail all open tabs; auto-save before e-mail | DONE | `ShareOptionsSheet.kt`, `ReaderScreen` |
| Review: PDF with the invitation, check interval, offline / reconnect, archive copy, tracker PDF / folders / search, notifications inbox | DONE | `core/share5/ReviewLocal.kt`, `ui/share5/TrackerExtras.kt` |
| Leave a review | PARTIAL (on this device only; the server has no endpoint) | — |
| Pre-review security (password) | NF (reviewers and the server must read the copy) | — |

## Nitro §22: cloud (feature/nav-attach)
| Row | Status | Where |
|---|---|---|
| Add Place (WebDAV: URL, user, password, label) | DONE (password in SecretBox) | `core/webdav/DavPlaces.kt`, `ui/webdav/WebDavScreens.kt` |
| WebDAV open / save with locking | DONE: LOCK / UNLOCK, check-out / check-in, a conflict copy instead of overwriting a newer file; the sync engine is unchanged | `core/webdav/DavClient.kt`, `DavLinks.kt` |
| Microsoft account / AIP / SharePoint WebDAV | NF (MIP SDK and tenant; SharePoint needs NTLM) | — |

## Nitro §21 / Foxit §21 / Foxit §36: batch, Action Wizard, print (feature/nav-batch)
| Row | Status | Where |
|---|---|---|
| Clone / reorder recipes; nested recipes; notes; prompt at run; stop / resume | DONE | `core/batch/*`, `RecipesScreen`, `RecipeEditor` |
| Sources: folder (+ sub-folders), all open documents | DONE | `SafFolders`, `BatchSources` |
| Destination folder; ask before overwriting | DONE | `OutputOptions`, `Conflict` |
| Steps (22 types): JS, metadata Base URL / custom keys, delete comments, header / footer, Bates, redact, sanitize, convert to Office, print, … | DONE | `Recipes.kt`, `RecipeRunner.kt` |
| Built-ins: Prepare for Distribution, Make Accessible, PDF/UA-1, Publish Sensitive Information | DONE | — |
| Named watermark / header-footer profiles for many files | DONE (E5Profiles in the steps) | — |
| Batch Print | DONE | `BatchPrintScreen` |
| Print: poster + cut marks, N×M, booklet fronts / backs, auto-rotate, scale, content choice, notes, comment summary (with SummaryOptions), greyscale, as image, preview, presets, document print prefs, label ranges; print from Organize, bookmarks, snapshot; virtual printer applies a recipe | DONE | `core/view5/PrintPipeline.kt`, `PrintSettings.kt`, `ui/view5/PrintSheet5.kt` |
| Print the current view | PARTIAL (through Snapshot, then Print) | — |
| Overprint simulation; append in the virtual printer | NF (no PDFium / Android support; one PDF per print job) | — |
| Signed inputs | DONE: every step writes a new file; info-only steps stay incremental | — |

## Nitro §23 / Foxit §37 / Foxit §40 / Nitro §24 / old plan (feature/nav-prefs)
New preferences: `core/data/NpfPrefs.kt`. Screen: `ui/settings/NpfSettingsScreen.kt`, with search.

| Row | Status | Where |
|---|---|---|
| Default save folder (a SAF copy of outputs) | DONE | `core/files/NpfSaveFolder.kt` |
| Recent list size; auto-save and crash-recovery interval | DONE | `ui/reader/NpfReaderHooks.kt` |
| Default layout / zoom; panel on open / width; document title | DONE | `NpfReader` |
| Ignore rotation for header / footer / watermark; units; grid colour / offset / subdivisions; guides | DONE | `ui/view5/NpfGuides.kt` |
| Grid snap; measure snapping; form colours; comment summary prefs; compare colours | PRESENT (comments-forms / tools); Settings rows added | — |
| Note pop-up font / size / opacity; retain markup tools; identity (organisation / unit / e-mail) | DONE | — |
| XFA prefs; XFA barcode drawing (QR, Code 128, Code 39) | DONE | `core/forms/NpfBarcode.kt` |
| Arabic-Indic digits when typing (PREF-2); remove / import bookmarks on page delete / replace | DONE | `NpfText`, `core/pdf/NpfOutline.kt` |
| Conversion and OCR defaults; notification toggles; search prefs + hit colours; accessibility colour override; reading order; font mapping editor | DONE | — |
| Offline mode (PREF-7): all OkHttp clients + WebView | DONE | `core/data/NpfNet.kt` |
| Accent colour; tab menu / limit; presentation background; Help → forum / Labs | DONE (the forum / Labs URLs are placeholders for the owner) | — |
| §40 panels: layers, security, fields, destinations, tags, articles, standards, accessibility, OCR report, ConnectedPDF | DONE | `core/pdf/NpfDocPanels.kt`, `ui/reader/NpfPanels.kt` |
| Enhance thin lines; "only black text" filter; 3D model tree; linearize; ads switch | NF | PDFium wrapper has no flag; raster filter; no 3D; PdfBox 2.0; paid benefit |
| AI-off leaks (all ai6 ids incl. ai_side_by_side / ai_chats / ai_skills, ToolScreens, Commands5, reader action) | DONE | `AiGate`, `NpfAiOffScreen` |
| Sync notification on Android 12+ (setForeground dataSync + manifest) | DONE | `SyncWorker`, `CloudUploadWorker` |
| Home "search all files" → GlobalSearch; indexSoon caller; searchScopes / hit colour / search in attachments | DONE | `ui/search/NpfScopeHits.kt`, `DocParts.attachmentText` |

## Owner actions
- Set the forum / Labs URLs in `res/values/strings_nav_prefs.xml`.
- Device tests: the lists above, especially WebDAV against a real server, the sync notification on Android 12 and 14, offline mode, and signed-file edits.
- Possible duplicates to check:
  - comments-forms added zxing. The XFA barcodes in `NpfBarcode` could switch to it for more symbologies.
  - view-edit's Settings search, accent colour, tab menu, grid / guides and Arabic-Indic digits overlap with NpfPrefs.
