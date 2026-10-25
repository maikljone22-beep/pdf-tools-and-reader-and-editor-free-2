# SmartDocs — shared brief for module agents

Project: `/home/claude/smartdocs` (Android, Kotlin 2.1, Jetpack Compose Material3, minSdk 26, package `com.smartdocs.editor`).
Goal: an Arabic-first PDF editor matching a commercial PDF editor feature-for-feature. UI language is Arabic (RTL); every user-visible string goes through `stringResource(R.string.xxx)`.

## Rules
- DO NOT run gradle/build (another process owns the build; the lead will compile and fix). Write code carefully so it compiles: correct imports, no undefined symbols, Compose M3 APIs only.
- Do not edit files outside your module except: you MAY add NEW string resources in your own file `app/src/main/res/values/strings_<module>.xml` (never edit `strings.xml`). Use unique prefixes for your string names (e.g. `rd_`, `tl_`, `sc_`, `sg_`).
- Do not add Gradle dependencies. Available: Compose BOM 2025.03, material-icons-extended, navigation-compose 2.8, lifecycle-compose, Room 2.6 (KSP), DataStore, WorkManager, coil-compose 2.7, okhttp 4.12, kotlinx-serialization-json, PdfBox-Android 2.0.27 (`com.tom_roush.pdfbox`), PDFium wrapper `io.legere:pdfiumandroid:2.0.3` (use through `PdfSession` only), ML Kit document scanner `play-services-mlkit-document-scanner:16.0.0`, Tesseract4Android 4.8 (`com.googlecode.tesseract.android.TessBaseAPI`), BouncyCastle bcpkix-jdk15to18 1.72 (org.bouncycastle, same as PdfBox), exifinterface, core-splashscreen, androidx.browser.
- Existing core APIs (read these files before coding): 
  - `core/pdf/PdfSession.kt` — open(context, file, password) [throws PdfSession.Companion.PasswordRequiredException], pageCount, pageSize(i) -> (w,h) points, render(i, wPx, hPx, nightMode, annotations) -> Bitmap, renderRegion, text(i), allText(), outline(), links(i), meta(), charBoxes(i) (PDF space, origin bottom-left), textInRect, search(query, matchCase, wholeWord), close().
  - `core/pdf/PdfOps.kt` — merge, keepPages, deletePages, rotatePages, reorder, split, createBlank(PageStyle), insertBlankPage, insertPagesFrom, imagesToPdf, decodeScaled, stampImage, stampText, watermark, addPageNumbers, protect, removeSecurity, compress, flatten, flattenForms, redact(regions map page->rects in PDF points), setInfo, setOutline, extractText, parseRange, arabicFont(context, doc).
  - `core/pdf/Annotations.kt` — `Annot` data class + `AnnotKind`; `AnnotationWriter.commit(context, src, annots, out, password)`, `.list(src)`, `.delete(src,page,index,out)`.
  - `core/pdf/ArabicShaper.kt` — `ArabicShaper.shape(text)` for drawing Arabic with PdfBox.
  - `core/files/DocumentRepository.kt` — `app.documents`: docsDir, scansDir, tempDir, signaturesDir, newTemp(prefix, ext), uniqueFile(dir, base, ext), localFile(uri) (suspend; copies content:// into docsDir), displayName, touchRecent(file, pageCount, lastPage), setThumb, scanDevicePdfs, shareIntent(files, mime), exportTo(file, uri), formatSize/formatDate.
  - `core/data/AppDatabase.kt` — `app.db.docs()/tags()/signatures()/bookmarks()/fillProfile()/stamps()` DAOs; entities RecentDoc, Tag, DocTag, SavedSignature(path,isInitials), ReadingBookmark(uri,page,title), FillProfileField(key,label,value,order), CustomStamp(text,imagePath,color).
  - `core/data/SettingsStore.kt` — `app.settings.settings: Flow<AppSettings>`, `app.settings.update { it.copy(...) }`. Fields: theme, language, authorName, autoSave, restoreSession, volumeKeysFlip, tapEdgesFlip, keepZoom, keepScreenOn, highlightFields, highlightLinks, pressureSensitivity, continuousScroll, nightMode, pageColor, ttsRate, ttsPitch, aiEndpoint, aiModel, aiApiKey, ocrLanguages, lastOpenedUri.
  - `MainActivity.kt` — `LocalApp.current` (SmartDocsApp: db, settings, documents) and `LocalSettings.current` (AppSettings).
  - `ui/Navigation.kt` — `Routes.reader(uriString, page)`, `Routes.tool(id, uriString?)`, `Routes.SETTINGS`, tabs HOME/FILES/TOOLS/CLOUD/ACCOUNT.
  - `ui/common/Components.kt` — AppTopBar, EmptyState, ToolCard, SectionTitle, ProgressDialog, ToolRunner/rememberToolRunner (run/runMany with result/results/error/running/message), ResultSheet(file, nav, onDismiss, extraInfo), rememberPdfPicker(multiple){files}, rememberImagePicker{files}, FilePickRow, PageRangeField, LabeledSlider.
  - `res/values/strings.xml` — read it; reuse existing names (tool_*, annot_*, sig_*, stamp_*, page_*, ocr_*, ai_*, tts_*, etc.).
- Files passed between screens are `file://` URIs (String). A tool screen that receives `uri` (nullable) should preselect that file: `File(Uri.parse(uri).path!!)`.
- Output files: write to `app.documents.docsDir` via `uniqueFile`, then show `ResultSheet`. Long operations go through `ToolRunner` + `ProgressDialog`.
- Tablet-aware layouts: use `BoxWithConstraints`/`maxWidth > 700.dp` to show side panels beside content where it helps.
- Keep each file < 600 lines; split screens into several files.
- When done, write a short `MODULE_<name>.md` in the project root listing files created and any TODO/limitations.

## Round 3 rules (many agents at once — READ)
- Do NOT run gradle at all this round (the machine has 8 GB RAM; parallel builds crash). The lead compiles and fixes. Write carefully: verify every library API you use by inspecting the jar/aar with `javap` (caches under /root/.gradle/caches/modules-2/files-2.1/) or the existing code, rather than guessing.
- Newly available deps: `com.google.mlkit:digital-ink-recognition:19.0.0`, `com.android.billingclient:billing-ktx:9.1.0`, `com.google.android.play:review-ktx:2.0.2`, `com.google.android.gms:play-services-auth:22.0.0`. (Their jars may not be in the gradle cache yet; if you need to inspect one, download the .aar from https://maven.google.com/<group path>/<artifact>/<version>/<artifact>-<version>.aar into /tmp and unzip classes.jar.)
- Prefer NEW files. Shared hot files (`ui/reader/ReaderState.kt`, `ReaderScreen.kt`, `ReaderToolbars.kt`, `PageCanvas.kt`, `ui/tools/ToolScreens.kt`, `ui/Navigation.kt`, `core/pdf/PdfOps.kt`, `core/pdf/Annotations.kt`): only the agent that owns them edits them; others add a single minimal hook ONLY if their brief says so, re-reading the file immediately before each Edit (others are editing it too).
- Strings: your own new files `res/values/strings_<module>.xml` + `res/values-en/strings_<module>.xml` with your prefix.
- Tool routes: to add a screen reachable from the Tools tab, do NOT edit ToolScreens.kt/ToolsScreen.kt; instead list the route id and composable in your MODULE_<name>.md and the lead wires it.
