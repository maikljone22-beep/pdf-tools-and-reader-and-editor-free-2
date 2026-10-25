# MODULE_design — visual refresh (home / files / tools / cloud / account / settings)

Build: `:app:assembleDebug` passes (3 builds used). Screenshots not possible in this environment (no emulator).

## Design system (`core/theme/`)
- `Color.kt` — brand light/dark `ColorScheme`s (primary #1565C0 deep blue, teal secondary, amber tertiary/accent) with the full M3 tonal surface set (`surfaceContainerLowest…Highest`) so elevation = tone, not shadow. `BrandTiles`/`TileColors`: 6 category tile palettes (blue, teal, amber, purple, rose, green) with dark variants.
- `Type.kt` — `AppFontFamily` = Noto Sans Arabic 400/500/700 (bundled in `res/font/noto_sans_arabic_{regular,medium,bold}.ttf`, downloaded from fonts.gstatic.com); `AppTypography` with Arabic-friendly line heights and zero letter-spacing (tracking breaks Arabic joining). Latin glyphs fall back to the system font.
- `Shape.kt` — `AppShapes` (8/12/16/24/28dp), `PillShape`, `CardShape`, `Spacing` tokens.
- `Theme.kt` — `SmartDocsTheme(mode, rtl, dynamicColor = false)`; provides `LocalBrandTiles` / `Brand.tiles`, `LocalIsDark`; sets status/nav-bar icon contrast from the app theme (not only the system one). `ThemeMode` unchanged.
- `res/values/themes.xml` — window background + font for platform views.

## Common components (`ui/common/Components.kt`) — all existing signatures unchanged
- Restyled: `AppTopBar`, `EmptyState` (tonal circle), `ToolCard` (colored icon tile, uniform 2-line labels), `SectionTitle`, `ProgressDialog` (card + linear progress + %), `ResultSheet` (visuals only; logic identical), `FilePickRow`, `PageRangeField`, `LabeledSlider`.
- New (additive): `IconTile`, `EmptyIllustration(@DrawableRes art, title, subtitle, action)`, `LocalToolTile` (lets a screen tint `ToolCard` per category without changing its signature).

## Screens
- **Navigation** (`ui/Navigation.kt`): routes unchanged. M3 `NavigationBar` with filled/outlined icons + primaryContainer indicator, slides away on non-tab routes; width > 700dp → `NavigationRail` (start edge = right in RTL). Faster cross-fade between destinations; `consumeWindowInsets` fixes double status-bar padding inside tabs.
- **Home** (`ui/home/HomeScreen.kt`, `HomeComponents.kt`, `Thumbnails.kt`): greeting header (time-based + author name) with app name, settings and avatar → account; inline search pill (filters recents+favorites, plus "search all files" → Files tab); quick-create cards (Scan, Images→PDF, Blank, Open — one tap each); **Continue reading** card (thumbnail, "صفحة X من Y", progress, one tap resumes at the saved page); favorites row; tag chips; recents with list/grid toggle (persisted in prefs `home_ui`); extended FAB "+" opens a create sheet (blank, images, scan, open, merge, convert, form builder, Wi‑Fi import). Long-press/⋮ sheet keeps every action (favorite toggle, organize, share, remove record, tags attach/detach/create).
- **Thumbnails**: `Thumbnails.render()` renders page 1 at 240px with `PdfSession` on IO (max 2 concurrent), JPG in `cacheDir/thumbs/<hash>_<size>_<mtime>.jpg` (stale ones deleted; failures remembered per process), stored with `app.documents.setThumb`. Files tab reuses the cache for local PDFs; images show themselves.
- **Files** (`ui/files/FilesScreen.kt`, `FilesComponents.kt`): segmented control هذا الجهاز / كل ملفات PDF / المجلدات المختارة (last tab + view mode persisted); search pill; sort menu with check marks; breadcrumb chips; rows/grid with thumbnails, type·size·date, ⋮ menu (select, info, rename, share, zip, move, delete). Long-press → multi-select with contextual top bar (select all, share, zip, move, merge when ≥2 PDFs, delete with confirmation). Move = folder-tree picker under the documents root. Zip writes next to the files and offers Share in a snackbar. Back exits selection / goes to parent folder. "All PDFs" still merges MediaStore + chosen SAF tree; the third tab shows only the chosen folder with pick/forget controls.
- **Tools** (`ui/tools/ToolsScreen.kt`): search field, "most used" chips row (counts in SharedPreferences `tool_usage` via public `ToolUsage`, defaults until history exists), 4 groups with colored category tiles. Added the existing-but-hidden `digital_sign` route as a tool card. `allTools`/`ToolDef` kept public.
- **Cloud**: illustration header + grouped cards (system picker, Wi‑Fi transfer, provider rows with "soon" pill).
- **Account**: gradient header card (avatar initial, name, edit, stats: files/favorites/signatures) + grouped cards (my content, app, support). All previous entries kept.
- **Settings**: grouped like the reference — General (auto save, appearance segmented, language segmented) · PDF view (restore session, highlight fields/links, keep zoom, keep screen on, volume keys, tap edges, continuous, night) · Annotations (author, pressure) · Read aloud (speed) · AI (endpoint/model/key + link to advanced AI settings) · OCR (language segmented) · Notifications (opens system settings) · About (help, version). Every previous option kept. Reusable `GroupCard`, `GroupRow`, `GroupDivider`, `SwitchRow` in `ui/settings/SettingsComponents.kt`.

## Drawables / strings
- `res/drawable/ds_empty_folder.xml`, `ds_empty_docs.xml`, `ds_empty_cloud.xml`, `ds_empty_search.xml` (multi-color illustrations).
- `res/values/strings_design.xml` + `res/values-en/strings_design.xml` (`ds_` prefix).

## Limitations / TODO
- Window background in themes.xml is light; a dark-mode cold start can flash light before Compose draws (a `values-night/themes.xml` would fix it; outside my allowed files).
- Noto Sans Arabic has no Latin glyphs; Latin text uses the system fallback font (looks fine but is a different face).
- Thumbnails are not generated for MediaStore/SAF content URIs (only local files) to avoid copying documents just for previews.
- Tablet: NavigationRail is in place; a list-detail pane for Home/Files was not added.
- Language segmented labels and OCR language labels are still literal strings (as before).
