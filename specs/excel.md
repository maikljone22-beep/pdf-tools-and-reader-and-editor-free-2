# Excel-class Spreadsheet Editor for Android: Feature Specification

Target: a native Android (Kotlin + Jetpack Compose) spreadsheet editor that opens, edits and saves `.xlsx` (OOXML/SpreadsheetML) files offline, with feature parity to **Microsoft Excel for Android (Microsoft 365 mobile app / standalone Excel app, 2025-2026)**, plus selected Excel desktop features that fit on a phone or tablet, plus the useful extras from **Google Sheets mobile** and **WPS Spreadsheets mobile**.

Version: 1.0 (2026-10-24). Scope: research and specification only. No code.

---

## 0. How to read this document

### 0.1 Columns used in the feature tables

| Column | Meaning |
|---|---|
| **Feature (EN / AR)** | English name as in Excel, then a suggested Arabic UI label (Modern Standard Arabic, matching the Arabic Office UI where one exists). |
| **Where** | Where it lives in Excel Android: tab > group/menu. On phones the ribbon is a bottom sheet opened with the "A-pencil" (Show ribbon) icon, and the tab is chosen from a drop-down at the top of that sheet (Home, Insert, Draw, Formulas, Data, Review, View). On tablets the ribbon is shown across the top, as on desktop. |
| **Behaviour / options** | What it does, with all options. |
| **OOXML / implementation** | Which parts and elements are read and written, plus notes for Kotlin/Compose. |
| **Diff.** | Effort: **S** = days, **M** = 1-3 weeks, **L** = a month or more, or research-heavy. |
| **Off.** | Works offline: **Y** = yes, **P** = partly (core offline, extras online), **N** = needs a network or cloud service. |
| **Plat.** | **M** = in Excel Android today. **M\*** = Android has it as view-only or partly. **D** = desktop or web only (a candidate to bring to mobile). **G** = from Google Sheets mobile. **W** = from WPS mobile. |

### 0.2 Sources and caveats

- Microsoft Support: "What's new in Excel on mobile platforms", "Show or hide the ribbon in Excel for mobile devices", "Insert data from picture", plus the function reference (support.microsoft.com/excel). Some status tables on Microsoft's mobile pages are old. For example, they list conditional formatting and data validation as "viewing only" on Android, but current Android builds can apply the preset conditional-formatting rules from Home. Where sources conflict, this spec marks the feature **M\*** and gives the safer behaviour.
- In Excel Android, PivotTables can be viewed, and their cached values can be refreshed in some builds, but they cannot be created or edited. Power Query, macros (VBA) and the Data Model are not supported on mobile.
- Copilot on Android: Microsoft 365 Copilot (paid licence) works through the Copilot pane and through the Microsoft 365 Copilot app, which can preview a workbook and chat about it. It needs a network connection.

### 0.3 Product principles for our app

1. **Lossless round-trip.** Every OOXML part we do not understand is kept byte-for-byte: pivot caches, VBA project (`.xlsm`), slicers, `extLst`, custom XML, theme. Unknown elements inside a part we do edit are kept as raw XML nodes ("opaque islands") and written back in place.
2. **Offline first.** Everything works without a network except AI, cloud co-authoring, stock/geography data types and web-based OCR. On-device ML Kit text recognition covers "Data from picture" offline.
3. **Phone first, tablet enhanced.** The phone uses a bottom-sheet ribbon. The tablet uses a top ribbon and a side panel. Both share one command registry.
4. **RTL first.** Arabic users need a right-to-left sheet direction (column A on the right), bidirectional cell text, Arabic-Indic digit display, Hijri date formats and Arabic function-name localisation as an option.

---

## 1. Application shell and file handling

| # | Feature (EN / AR) | Where | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|---|
| 1.1 | New blank workbook / مصنف فارغ جديد | Home screen > + New | Creates Book1.xlsx with Sheet1, the default theme (Office 2023+ Aptos 11 pt) and 100% zoom. | Minimal package: `[Content_Types].xml`, `_rels/.rels`, `xl/workbook.xml`, `xl/_rels/workbook.xml.rels`, `xl/worksheets/sheet1.xml`, `xl/styles.xml`, `xl/theme/theme1.xml`, `docProps/core.xml`, `docProps/app.xml`. Keep a template in assets. | S | Y | M |
| 1.2 | Templates / القوالب | + New > template gallery | Built-in templates: budget, invoice, calendar, to-do, loan calculator, attendance, grade book (useful for schools). | Bundle the `.xltx` files and copy one as a new `.xlsx`, changing the content type from template to workbook (`sheet.main+xml`). | S | Y | M |
| 1.3 | Open .xlsx / .xlsm / .xltx / .xls / .csv / .ods | File picker, SAF intent `ACTION_VIEW` | Excel Android opens xlsx, xlsm (macros are kept but never run), xltx, xls (BIFF8, may convert), csv and ods (view). | Use SAX/StAX streaming (`XmlPullParser`) for sheets, never DOM for large files. For `.xls`, use Apache POI HSSF (works on Android with care) or convert to xlsx on import. For `.ods`, write our own reader for the ODF `content.xml`. Large files: memory-map the ZIP and parse sheets lazily on first view. | L | Y | M |
| 1.4 | Save / AutoSave / حفظ / حفظ تلقائي | Top bar | AutoSave is on for cloud files. Local files save on exit or on a timer. "Save a copy", "Save as" (xlsx, csv, pdf, ods). | Write to a temp ZIP, `fsync`, then atomically rename over the original or write through the SAF `ParcelFileDescriptor`. Rewrite only the dirty parts and stream-copy every other ZIP entry unchanged. | M | Y | M |
| 1.5 | Version history / سجل الإصدارات | File > History | Cloud versions only (OneDrive). | Local option: keep N snapshots in app storage. | M | Y | M\* |
| 1.6 | Recent / Pinned / Shared files / الأخيرة والمثبتة | Home screen | Recent list, pin, search, show location. | Room DB of URIs plus persisted SAF permissions. | S | Y | M |
| 1.7 | Share as link / file / PDF / مشاركة | Top bar Share | Send a copy as xlsx or PDF, or invite people (cloud). | `FileProvider` + `ACTION_SEND`. PDF goes through the print engine (section 14). | S | Y | M |
| 1.8 | Properties / الخصائص | File > Properties | Title, author, created and modified dates, size, location. | `docProps/core.xml` (dc:title, dc:creator, dcterms:modified). | S | Y | M |
| 1.9 | Undo / Redo / تراجع / إعادة | Top bar | Multi-level undo for all edits. | Command pattern: every edit is an inverse-able `Op`, with batched ops grouped into one transaction. Keep about 100 levels, with a memory cap. | M | Y | M |
| 1.10 | Tell me (Search commands) / أخبرني | Lightbulb or search icon | The user types an intent ("merge", "freeze") and gets a list of matching commands. | Fuzzy search over the command registry with Arabic and English keywords. | S | Y | M |
| 1.11 | Dark mode with "dark cells" / الوضع الداكن | Settings / View | The app UI goes dark. Optionally the grid inverts too (Excel's dark canvas), while cell colours keep their saved values. | Render-time colour transform only. Never write the inverted colours to the file. | S | Y | M |
| 1.12 | Password-protected (encrypted) files / ملفات محمية بكلمة مرور | Open | Excel Android can open "Encrypt with password" files (Agile encryption) and asks for the password. | ECMA-376 Agile Encryption: a CFB container with `EncryptionInfo` and `EncryptedPackage`, AES-256 and SHA-512 key derivation (POI's `Decryptor` approach). Writing an encrypted file is a desktop-level feature. | L | Y | M |
| 1.13 | Read-only / Mark as final / Protected View banner | Open | A banner for read-only files, with "Edit anyway" for files from the internet. | `fileSharing readOnlyRecommended`, `workbookProtection`. | S | Y | M |
| 1.14 | Sensitivity labels / تصنيف الحساسية | File | Enterprise feature. | Keep `docProps/custom.xml` MSIP properties unchanged. | S | Y | M |
| 1.15 | Print / طباعة | File > Print | See section 14. | Android `PrintManager` + `PrintDocumentAdapter` rendering our own PDF pages. | M | Y | M |

---

## 2. Grid, navigation and selection (core canvas)

| # | Feature (EN / AR) | Where | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|---|
| 2.1 | Virtualised grid rendering / شبكة الخلايا | Canvas | Smooth pan and pinch-zoom (10%-400%). Row and column headers stay pinned. Hidden rows and columns are skipped. Up to 1,048,576 rows x 16,384 columns (XFD). | Custom Compose `Canvas` (not LazyColumn) with tile-based rendering. Cache 256x256-px bitmap tiles per zoom bucket and invalidate tiles on change. Prefix-sum arrays (Fenwick tree) of row heights and column widths give O(log n) lookup between position and index. | L | Y | M |
| 2.2 | Cell selection, single tap / تحديد خلية | Canvas | A tap selects a cell and shows the selection rectangle with two circular **selection handles** (top-left and bottom-right). Dragging a handle extends the range. | Selection model: active cell + list of ranges (multi-area on tablet with Ctrl). Draw the handles as 24 dp touch targets. | M | Y | M |
| 2.3 | Select row / column / all / تحديد صف أو عمود أو الكل | Tap a header; tap the corner box | A tap on a row or column header selects it, and dragging across headers extends the selection. The corner box selects everything. | Same model, with full-row and full-column flags so we never materialise 1M cells. | S | Y | M |
| 2.4 | Context menu (floating toolbar) / القائمة السياقية | Tap a selected cell again | Cut, Copy, Paste, Paste Special (Values, Formulas, Formatting, Transpose), Clear, Fill, Insert, Delete, Hide, Unhide, Wrap, Comment, AutoFit, Sort, Link, Copy as picture (tablet). For rows/columns: Insert, Delete, Hide, Unhide, Row height / Column width, AutoFit, Freeze. | A Compose `Popup` anchored to the selection with horizontal scrolling and context-dependent items. | M | Y | M |
| 2.5 | Edit in cell / in formula bar / تحرير الخلية | Double tap, or tap the formula bar | Double tap enters edit mode. Text can be edited in the cell or in the formula bar. An expand chevron grows the formula bar to several lines. The Enter key commits and moves down. A checkmark commits, an X cancels. | `BasicTextField` overlay positioned over the cell, mirrored into the formula bar. Commit parses the input (section 9.1 input parsing). | M | Y | M |
| 2.6 | Smart keyboard switching / لوحة مفاتيح ذكية | While editing | Excel Android has its own **numeric keypad and formula keyboard** (digits, operators, parentheses, `$` absolute reference toggle, arrow keys, Enter, Tab, "abc" switch), plus "Fx" for the function list. | Custom in-app keyboard composable in place of the IME when the cell type is numeric or a formula, with a toggle back to the system IME. Keys: 0-9 . , + - * / ^ ( ) = : $ % & < > " ; ! and arrows. | M | Y | M |
| 2.7 | Fill handle / AutoFill / تعبئة تلقائية | Context menu > Fill, then drag | Tap Fill, then drag the handle down or right. Copies the value or extends a series: numbers 1,2,3; dates by day, weekday, month or year; Mon/Tue; Jan/Feb; Q1/Q2; Item1, Item2; custom lists. Formulas are copied with relative references adjusted. Options: Copy cells, Fill series, Fill formatting only, Fill without formatting. | Series detection: linear trend from the selected seed cells (step = difference), date units, text with a trailing number, built-in day/month lists in both EN and AR (السبت، الأحد…). Formula copy uses R1C1 translation of relative references (section 9.4). | M | Y | M |
| 2.8 | Flash Fill / التعبئة السريعة | Data > Flash Fill (desktop: Ctrl+E) | Learns a pattern from examples, such as splitting first and last names. | Program synthesis over a small DSL: substring by token or delimiter, concatenation, upper/lower case. Start rule-based (split by delimiter, take the token at position n). | L | Y | D |
| 2.9 | Go To / Name Box / مربع الاسم | Formula bar left / Find | Type a reference or name to jump there (A1, Sheet2!B5, MyRange). | Resolve through the name table and A1 parser. | S | Y | M |
| 2.10 | Scroll to edge, jump to data region | Flick on headers; hardware keyboard Ctrl+Arrow | Jumps to the last non-empty cell in the direction of the flick. | Per-row and per-column sparse index (a TreeMap of filled cells). | S | Y | M |
| 2.11 | Hardware keyboard and mouse | Tablets, DeX, ChromeOS | Desktop shortcuts: Ctrl+C/V/X/Z/Y/B/I/U/F/H, F2, Ctrl+;, Ctrl+Shift+:, Alt+=, Ctrl+Arrow, Shift+Arrow, Tab, Ctrl+1 (format), right-click menu, mouse-wheel scroll. | `onKeyEvent` + `pointerInput` with a mouse-button check. | M | Y | M |
| 2.12 | Status bar aggregates / شريط الحالة | Bottom bar when a range is selected | Shows SUM, AVERAGE, COUNT, MIN, MAX and Numerical Count of the selection. Tap to cycle through them or show all. | Compute on a background coroutine with a cap, cancelled when the selection changes. | S | Y | M |
| 2.13 | Sheet direction RTL / اتجاه الورقة من اليمين لليسار | View / Page Layout > Sheet Right-to-Left | Column A sits on the right and the grid is mirrored. | `<sheetView rightToLeft="1">` in sheetN.xml. The renderer mirrors x-coordinates. | M | Y | M |
| 2.14 | Pinch zoom / Zoom % | Pinch; View > Zoom | 10-400%. Saved per sheet. | `<sheetView zoomScale="85">`. | S | Y | M |
| 2.15 | Show formulas / إظهار الصيغ | Formulas tab (desktop) | Shows formula text in the cells instead of results. | `<sheetView showFormulas="1">`. | S | Y | D |
| 2.16 | Touch / Reading mode | View | Larger rows for touch, read-only scrolling. | UI-only. | S | Y | M |
| 2.17 | Data-entry form view (card view) / عرض البطاقات | Data or Table > Card view (Excel iOS/Android tables) | Each table row shows as a card (one field per line) for editing on a phone. This is a strength of Excel mobile. | A Compose list of cards bound to the table's columns. Edits write to the cells. | M | Y | M |

---

## 3. Home tab / الصفحة الرئيسية

### 3.1 Clipboard

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.1.1 | Cut / Copy / Paste (قص / نسخ / لصق) | Marching ants. A cut moves cells and adjusts the references that point to them. Paste into a range repeats the copied block when the target is an integer multiple of it. | Internal clipboard with a full cell model (value, formula, style id, comments, merges, validations). The system clipboard gets TSV + HTML (`text/html` table) so pasting into other apps works. On paste from outside, parse HTML or TSV. | M | Y | M |
| 3.1.2 | Paste Special / لصق خاص | Paste Values, Formulas, Formatting, Formulas & Number formatting, Transpose, Column widths, Comments, Validation, Skip blanks. Operations: Add, Subtract, Multiply, Divide. Paste Link (desktop). | Options are a bitmask applied per component. Operations go through the engine's arithmetic. | M | Y | M (Values/Formulas/Formats/Transpose), D (the rest) |
| 3.1.3 | Format Painter / نسخ التنسيق | Tap, then tap the target. Double tap keeps it active. | Copy the `s` style index plus conditional formatting and validation if desired. | S | Y | M |
| 3.1.4 | Copy as picture | Copies the range as an image. | Render the range to a bitmap with the grid renderer. | S | Y | D/W |

### 3.2 Font group / الخط

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.2.1 | Font name / نوع الخط | Picker with theme fonts (Headings, Body) at the top, then the device and installed fonts. Missing fonts get a fallback with an indicator. | `styles.xml <fonts><font><name val="Aptos"/><scheme val="minor"/>`. Theme fonts come from `theme1.xml a:fontScheme`. Arabic fallback: Noto Naskh/Kufi Arabic, or the system fonts Calibri / Arial / Segoe UI substitutes. | M | Y | M |
| 3.2.2 | Font size / الحجم | 1-409 pt, with an increase/decrease step list (8,9,10,11,12,14,16,18,20,24,28,36,48,72). | `<sz val="11"/>`. | S | Y | M |
| 3.2.3 | Bold / Italic / Underline / Double underline / Strikethrough (غامق، مائل، تسطير، تسطير مزدوج، يتوسطه خط) | Toggles. Underline types: single, double, single accounting, double accounting. | `<b/> <i/> <u val="double"/> <strike/>`. | S | Y | M |
| 3.2.4 | Superscript / Subscript (مرتفع / منخفض) | Whole cell or rich-text runs. | `<vertAlign val="superscript"/>`. | S | Y | M (via Format Cells) |
| 3.2.5 | Font colour / لون الخط | Theme colours (6 accents + 4 base, each with 5 tints and shades), standard colours, recent colours, custom (hex or HSL picker), Automatic. | `<color theme="4" tint="-0.25"/>` or `rgb="FFFF0000"`. Theme colour resolution: `theme1.xml a:clrScheme` with the tint algorithm (HLS luminance change per ECMA-376 18.8.19). | M | Y | M |
| 3.2.6 | Rich text inside a cell / تنسيق جزء من النص | Selecting part of the text while editing and formatting it. | Shared string `<si><r><rPr>…</rPr><t>…</t></r></si>`. | M | Y | D (Android is limited) |
| 3.2.7 | Fill colour / لون التعبئة | Same palette, plus No Fill. Desktop also has pattern fills and gradient fills (Format Cells > Fill Effects). | `<fills><fill><patternFill patternType="solid"><fgColor …/></patternFill>`. The first two fills (none, gray125) are reserved. `gradientFill` is rendered with a linear or path gradient. | S | Y | M (solid), D (pattern/gradient) |
| 3.2.8 | Borders / الحدود | Presets: Bottom, Top, Left, Right, No border, All borders, Outside borders, Thick outside, Bottom double, Thick bottom, Top and bottom, Top and thick bottom, Top and double bottom. Line styles: thin, medium, thick, dashed, dotted, double, hair, mediumDashed, dashDot, mediumDashDot, dashDotDot, mediumDashDotDot, slantDashDot. Line colour. Diagonal up and down. | `<borders><border><left style="thin"><color …/></left>…<diagonal/></border>` with `diagonalUp/diagonalDown` attributes. The renderer resolves conflicts between neighbouring cells (the thicker or later border wins, like Excel). | M | Y | M |
| 3.2.9 | Cell Styles / أنماط الخلايا | Good/Bad/Neutral, Data & Model (Calculation, Check Cell, Explanatory, Input, Linked Cell, Note, Output, Warning), Titles & Headings (Title, Heading 1-4, Total), Themed (20%, 40%, 60% Accent1-6, Accent1-6), Number formats (Comma, Comma [0], Currency, Currency [0], Percent). Custom styles on desktop. | `<cellStyleXfs>`, `<cellStyles><cellStyle name="Good" xfId="1" builtinId="26"/>`, cell xf `xfId` links to its parent. Ship the built-in style definitions (builtinId table). | M | Y | M |
| 3.2.10 | Increase / decrease font size | Step through the size list. | Same as 3.2.2. | S | Y | M |

### 3.3 Alignment / المحاذاة

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.3.1 | Horizontal align / المحاذاة الأفقية | General (numbers right, text left; mirrored in RTL), Left, Center, Right, Fill, Justify, Center Across Selection, Distributed. Indent for Left/Right/Distributed. | `<alignment horizontal="center" indent="2"/>`. | S | Y | M (L/C/R), D (others) |
| 3.3.2 | Vertical align / المحاذاة العمودية | Top, Middle, Bottom (default), Justify, Distributed. | `vertical="center"`. | S | Y | M |
| 3.3.3 | Wrap Text / التفاف النص | Text wraps inside the column width, and the row auto-heights unless its height was set by hand. | `wrapText="1"`. Text layout uses Android `StaticLayout` (bidi aware). Row auto-height recalculation runs when `<row customHeight>` is absent. | M | Y | M |
| 3.3.4 | Shrink to fit / تصغير للاحتواء | Scales the font down so the text fits. | `shrinkToFit="1"`. | S | Y | D |
| 3.3.5 | Orientation / الاتجاه | Angle counterclockwise, angle clockwise, vertical text, rotate text up or down, custom -90..90 degrees. | `textRotation="45"` (0-90 counterclockwise, 91-180 means clockwise 1-90, 255 means vertical stacked). | M | Y | M |
| 3.3.6 | Text direction / اتجاه النص | Context, Left-to-right, Right-to-left. | `readingOrder="2"` (RTL). | S | Y | D (useful for Arabic) |
| 3.3.7 | Merge & Center / دمج وتوسيط | Merge & Center, Merge Across (each row on its own), Merge Cells, Unmerge. Warns that only the top-left value is kept. | `<mergeCells><mergeCell ref="A1:D1"/>`. Keep a merge index (an interval tree) for hit-testing and for selection snapping. | M | Y | M |
| 3.3.8 | Indent increase / decrease | Steps of 1 (each about 3 character widths). | `indent`. | S | Y | M |

### 3.4 Number format / تنسيق الأرقام

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.4.1 | Number format categories / فئات التنسيق | **General (عام), Number (رقم), Currency (عملة), Accounting (محاسبة), Short Date (تاريخ قصير), Long Date (تاريخ طويل), Time (وقت), Percentage (نسبة مئوية), Fraction (كسر), Scientific (علمي), Text (نص), Special (Zip, Phone, SSN by locale), Custom (مخصص).** Android shows these in Home > Number Formatting with an options sub-page (decimal places, use 1000 separator, negative number style, currency symbol, date type). | `<numFmts><numFmt numFmtId="164" formatCode="#,##0.00"/>`. Built-in ids 0-49 are implicit (0 General, 1 "0", 2 "0.00", 3 "#,##0", 4 "#,##0.00", 9 "0%", 10 "0.00%", 11 "0.00E+00", 12 "# ?/?", 14 short date (locale), 18-21 times, 22 date-time, 37-40 accounting, 44 currency accounting, 49 "@"). Write custom ids starting at 164. | M | Y | M |
| 3.4.2 | Currency symbols / رموز العملات | $, €, £, ¥, and Arabic: ر.ي.‏ (YER), ر.س.‏ (SAR), د.إ.‏ (AED), ج.م.‏ (EGP), etc. Position (before or after), negatives in red or parentheses. | Format code such as `[$ر.ي.‏-2401] #,##0.00`. Locale id (LCID) inside the `[$-xxxx]` tag. | S | Y | M |
| 3.4.3 | Increase / Decrease decimal (زيادة / إنقاص المنازل العشرية) | Changes the format code to add or remove decimal places. | Rewrite the format code: derive the current display, then rewrite the pattern. | S | Y | M |
| 3.4.4 | Comma style / Percent style | Shortcut buttons. | Ids 43/44 and 9. | S | Y | M |
| 3.4.5 | Custom format codes / تنسيقات مخصصة | Full Excel grammar: up to 4 sections `pos;neg;zero;text`. Conditions `[>=1000]`. Colours `[Red]`, `[Color10]`. Literals `"text"` and `\x`. `_` (space the width of a char), `*` (repeat fill), `@`, `0 # ?`, `.` `,` (thousands, scale by 1000 when trailing), `%`, `E+`, fractions `# ??/??` and `# ?/8`. Dates and times `d dd ddd dddd m mm mmm mmmm mmmmm yy yyyy h hh m mm s ss AM/PM [h] [mm] [ss] .000`. Locale and calendar `[$-x-sysdate]`, `[$-1010000]` / `B2` Hijri. Digit shapes `[DBNum1]` and the Arabic-Indic digits LCID `[$-2000000]`. | Write a **number-format engine**: tokenizer, then section selection, then a formatter. Test it against a reference corpus (export a test sheet from desktop Excel with the expected `TEXT()` results). The same engine drives the `TEXT()` function and cell display. | L | Y | M (list), D (editor) |
| 3.4.6 | Date systems | 1900 (with the fake 1900-02-29 leap day) and 1904. | `workbookPr date1904="1"`. Serial-to-date conversion keeps the Lotus bug. | S | Y | M |
| 3.4.7 | Hijri calendar formats / التاريخ الهجري | Display a Hijri date (Umm al-Qura or Kuwaiti algorithm). | `[$-1180000]` or `B2` prefix. Use `android.icu.util.IslamicCalendar` (Umm al-Qura variant). | M | Y | D |
| 3.4.8 | Auto-detect input type | Typing `12/5` gives a date, `10%` a percent, `$5` currency, `1e3` scientific, `'001` text, `=` a formula, `1 1/2` a fraction, `TRUE` a boolean. A leading apostrophe forces text. Sets the matching number format automatically. | Input parser by locale (decimal separator `.` or `,`, Arabic decimal separator `٫`, date order). | M | Y | M |

### 3.5 Styles group: Conditional Formatting, Format as Table / التنسيق الشرطي والتنسيق كجدول

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.5.1 | Conditional formatting: Highlight Cells Rules / تمييز قواعد الخلايا | Greater Than, Less Than, Between, Equal To, Text that Contains, A Date Occurring (Yesterday, Today, Tomorrow, Last 7 days, Last/This/Next week, Last/This/Next month), Duplicate Values / Unique. Preset formats: Light Red Fill with Dark Red Text, Yellow Fill with Dark Yellow Text, Green Fill with Dark Green Text, Light Red Fill, Red Text, Red Border, Custom Format. | `<conditionalFormatting sqref="A1:A10"><cfRule type="cellIs" operator="greaterThan" dxfId="0" priority="1"><formula>5</formula></cfRule>`. Format in `styles.xml <dxfs>`. Other types: `containsText` (with formula `NOT(ISERROR(SEARCH("x",A1)))`), `timePeriod`, `duplicateValues`, `uniqueValues`. | M | Y | M (presets) |
| 3.5.2 | Top/Bottom Rules / قواعد الأعلى والأدنى | Top 10 Items, Top 10%, Bottom 10 Items, Bottom 10%, Above Average, Below Average (with N editable). | `type="top10" rank="10" percent="1" bottom="1"`, `type="aboveAverage" aboveAverage="0" stdDev="1"`. | M | Y | M |
| 3.5.3 | Data Bars / أشرطة البيانات | Gradient or solid fill, 6 colours each. Options: min/max type (lowest/highest value, number, percent, formula, percentile, automatic), negative bar colour and axis, bar direction, show bar only. | `<cfRule type="dataBar"><dataBar><cfvo type="min"/><cfvo type="max"/><color rgb=…/></dataBar>`. Excel 2010+ extensions live in `extLst` `x14:dataBar` (negative fill, axis, gradient off). Read both and write both. | M | Y | M |
| 3.5.4 | Color Scales / مقاييس الألوان | 2-colour and 3-colour scales (12 presets). Min / mid (percentile 50) / max types. | `type="colorScale"` with `<colorScale><cfvo/>…<color/>…`. Interpolate in RGB like Excel. | M | Y | M |
| 3.5.5 | Icon Sets / مجموعات الأيقونات | Directional (3/4/5 arrows, colored or gray, triangles), Shapes (traffic lights, signs, red-to-black), Indicators (symbols, flags), Ratings (stars, quarters, bars, boxes). Reverse order, show icon only, thresholds (>=, >) with number, percent, formula or percentile. | `type="iconSet"` with `<iconSet iconSet="3Arrows" reverse="1" showValue="0">`. Custom icon mixes go in `x14:iconSet` with `x14:cfIcon`. Ship vector icons for all 52 Excel icons. | M | Y | M |
| 3.5.6 | New Rule: Use a formula / قاعدة جديدة باستخدام صيغة | Any formula that returns TRUE; references are relative to the top-left cell of the range. | `type="expression"` with `<formula>$B2>100</formula>`. Evaluate per cell with an offset. Cache the results and recompute on dependency changes (CF formulas are registered in the dependency graph as volatile-per-range). | M | Y | D (Android: presets only) |
| 3.5.7 | Manage Rules / إدارة القواعد | List of rules for the selection or sheet: edit, delete, reorder priority, Stop If True, change Applies To. | `priority`, `stopIfTrue`. | M | Y | D (Android: Clear Rules only) |
| 3.5.8 | Clear Rules / مسح القواعد | From selected cells or from the entire sheet. | Remove or trim `sqref`. | S | Y | M |
| 3.5.9 | Format as Table / تنسيق كجدول | Pick a table style (Light 1-21, Medium 1-28, Dark 1-11) and "My table has headers". Converts the range to a Table (section 6). | `xl/tables/tableN.xml` with `<tableStyleInfo name="TableStyleMedium2" showRowStripes="1"/>`. Built-in table style definitions must be shipped as data (no XML for them exists in the file). Custom styles go in `styles.xml <tableStyles>` + dxfs. | L | Y | M |

### 3.6 Cells group: Insert / Delete / Format / إدراج / حذف / تنسيق

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.6.1 | Insert cells / rows / columns / sheet (إدراج خلايا / صفوف / أعمدة / ورقة) | Insert Cells (shift right or down), Insert Sheet Rows, Insert Sheet Columns (above or left; count = selection size). | **Reference adjustment** across all formulas in every sheet, defined names, CF sqrefs, DV sqrefs, merges, tables, charts' series refs, hyperlinks, print areas, autofilter, drawing anchors (`xdr:from row`). Do it through one central `ShiftTransform` applied to every reference holder. | L | Y | M |
| 3.6.2 | Delete cells / rows / columns / sheet (حذف) | Shift left or up. References to deleted cells become `#REF!`. | Same transform with tombstones that turn references into `#REF!`. | L | Y | M |
| 3.6.3 | Row height / Column width (ارتفاع الصف / عرض العمود) | Dialog in points (row) or characters (column). Drag the header border. Double-tap the border to AutoFit. | `<row ht="20" customHeight="1">`, `<cols><col min="1" max="1" width="12.7" customWidth="1"/>`. Width unit = (pixels − 5)/MDW, where MDW is the maximum digit width of the default font. | M | Y | M |
| 3.6.4 | AutoFit Row Height / Column Width (احتواء تلقائي) | Fits to content. | Measure text with the cell font, wrap and rotation. | M | Y | M |
| 3.6.5 | Hide & Unhide rows, columns, sheets (إخفاء / إظهار) | | `hidden="1"` on row or col; sheet `state="hidden"` or `veryHidden` (veryHidden cannot be shown from the UI, which keeps the file safe). | S | Y | M |
| 3.6.6 | Default width / standard height | | `<sheetFormatPr defaultRowHeight="15" defaultColWidth=…/>`. | S | Y | D |
| 3.6.7 | Format Cells dialog (desktop Ctrl+1) / تنسيق الخلايا | Number, Alignment, Font, Border, Fill, Protection (Locked, Hidden) tabs. | `<protection locked="0" hidden="1"/>` on the xf. | M | Y | D (Android spreads these across Home) |
| 3.6.8 | Style deduplication | Not visible to the user. | Keep `styles.xml` xf records interned: `(fontId, fillId, borderId, numFmtId, alignment, protection)` mapped to xfIndex through a HashMap so repeated edits do not bloat the file. Garbage-collect unused xfs on save, optionally. | M | Y | — |

### 3.7 Editing group / التحرير

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 3.7.1 | AutoSum / جمع تلقائي | Sum, Average, Count Numbers, Max, Min, More Functions. Guesses the range: contiguous numbers above, otherwise to the left. Inserts `=SUM(B2:B9)` and selects the argument for adjustment. | Range detection heuristic like Excel's (stops at text or empty, skips one header). | S | Y | M |
| 3.7.2 | Fill / تعبئة | Down, Right, Up, Left, Series (Linear, Growth, Date units, Step, Stop value), Justify, Flash Fill. | Same as 2.7. | M | Y | M (Down/Right/Up/Left), D (Series dialog) |
| 3.7.3 | Clear / مسح | Clear All, Clear Formats, Clear Contents, Clear Comments and Notes, Clear Hyperlinks. | | S | Y | M |
| 3.7.4 | Sort & Filter / فرز وتصفية | Sort A to Z / Z to A (smallest to largest, oldest to newest), Custom Sort, Filter (toggles AutoFilter), Clear, Reapply. See section 7. | | M | Y | M |
| 3.7.5 | Find / Find & Replace / بحث واستبدال | Magnifier icon on the top bar. Options: Match case, Match entire cell, Search within Sheet or Workbook, Look in Formulas, Values, Notes or Comments, By rows or columns. Replace, Replace All. Wildcards `*`, `?`, `~`. | Search through the sparse cell store. Results are listed with next/previous navigation. Replace All runs as one undo transaction. | M | Y | M |
| 3.7.6 | Go To Special (desktop) | Blanks, Constants, Formulas (numbers, text, logicals, errors), Comments, Current region, Last cell, Visible cells only, Conditional formats, Data validation, Precedents, Dependents. | Filters over the cell store and dependency graph. | M | Y | D |

---
## 4. Insert tab / إدراج

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 4.1 | Table / جدول | Same as Format as Table, but with the default style. Asks whether the table has headers. | Section 6. | L | Y | M |
| 4.2 | PivotTable / جدول محوري | Excel Android can open, view and sometimes refresh pivots. It cannot create them or change their fields. On desktop: Rows, Columns, Values (Sum, Count, Average, Max, Min, Product, StdDev, Var), Filters, grouping (dates, number bins), subtotals, grand totals, layouts (Compact, Outline, Tabular), PivotTable styles, Show values as (% of total, running total…), calculated field, slicers, timelines. | `xl/pivotTables/pivotTableN.xml` + `xl/pivotCache/pivotCacheDefinitionN.xml` + `pivotCacheRecordsN.xml`. **Phase 1:** render the cached cell values (they are ordinary cells in the sheet) and keep all pivot parts unchanged. **Phase 2:** our own pivot builder that writes a valid cache, definition and rendered cells. | L | Y | M\* (view) / D |
| 4.3 | Recommended PivotTables / Recommended Charts / مخططات موصى بها | Suggests charts that suit the selected data: column for categories x values, line for dates, pie for one series under 7 points, scatter for two numeric columns. | Heuristic classifier based on the column types in the selection. | M | Y | M (Recommended charts on tablet), D |
| 4.4 | Pictures / صور | From Photos (gallery), Camera, Stock images (online). Inserted floating over cells. Picture tab: crop, rotate, styles (frames, shadows, reflection), Bring Forward / Send Backward, Alt text, Reset, Size, "Place in cell" (desktop 2023+, `IMAGE` behaviour). | `xl/media/imageN.png`, `xl/drawings/drawingN.xml` with `<xdr:twoCellAnchor editAs="oneCell"><xdr:from><xdr:col>…</xdr:from><xdr:to>…<xdr:pic>` and `a:blip r:embed="rId1"`. Relationships live in `drawings/_rels/drawingN.xml.rels`, and the sheet points to them with `<drawing r:id/>`. Cropping uses `a:srcRect`. Pictures placed in a cell use `xl/richData/*` (rich value), so keep those parts. Compress pictures to about 1600 px on insert. | M | Y | M |
| 4.5 | Shapes / أشكال | Lines, Rectangles, Basic shapes, Block arrows, Equation shapes, Flowchart, Stars & banners, Callouts (about 170 preset geometries). Shape tab: Fill, Line, Effects (shadow, glow, soft edges), Shape Styles, Text inside the shape, Arrange (Bring to Front…), Rotate/Flip, Size, Alt text. | `<xdr:sp><xdr:spPr><a:prstGeom prst="rect"/>`. Preset geometry formulas come from ECMA-376 `presetShapeDefinitions.xml`. Implement a guide-formula interpreter (`avLst`/`gdLst`/`pathLst`) to draw every preset. | L | Y | M |
| 4.6 | Text Box / مربع نص | A floating box of rich text. | `<xdr:sp txBox="1">` with `<xdr:txBody>` (DrawingML paragraphs). | M | Y | M |
| 4.7 | Icons, 3D Models, SmartArt / أيقونات، رسومات SmartArt | Icons are SVG (online library). SmartArt is shown on Android but only partly editable. | SmartArt: `xl/diagrams/data*.xml`, `drawing*.xml` (pre-rendered shapes). Render the pre-rendered `dsp:drawing` and keep everything else. | M | P | M\* |
| 4.8 | Charts / مخططات | See section 11. | `xl/charts/chartN.xml` (+ `style`, `colors` parts, chartEx for the new chart types). | L | Y | M |
| 4.9 | Sparklines / خطوط المؤشر | Line, Column, Win/Loss mini-charts inside a cell. Options: high/low/first/last/negative points and markers, colours, axis min/max (same for all or per sparkline), show hidden data, empty cells as gaps, zero or connect. Excel Android shows them; you create them on desktop or on iPad. | Inside sheetN.xml `extLst` > `x14:sparklineGroups` > `x14:sparklineGroup type="column"` > `x14:sparklines/x14:sparkline/xm:f + xm:sqref`. Render them as part of the cell layer. | M | Y | M\* (view), D (create) |
| 4.10 | Hyperlink / ارتباط تشعبي | Address (web URL, mailto:), place in this document (sheet + cell or a name), text to display, screen tip. Tap opens it; long-press edits it. | `<hyperlinks><hyperlink ref="A1" r:id="rId3" display="…" tooltip="…"/>` with an External rel for the URL. An internal link uses `location="Sheet2!A1"`. Also the `HYPERLINK()` function. | S | Y | M |
| 4.11 | Comments (threaded) / تعليقات | Modern threaded comments: post, reply, @mention (cloud), resolve, delete, edit. Purple corner marker. | `xl/threadedComments/threadedCommentN.xml` + `xl/persons/person.xml`, **plus** a legacy `xl/commentsN.xml` + VML `xl/drawings/vmlDrawingN.vml` fallback with the text "[Threaded comment] …" so older readers still show it. | M | Y | M |
| 4.12 | Notes (legacy comments) / ملاحظات | Yellow sticky note with a red corner. New, Edit, Delete, Show/Hide, Show all. Converting notes to comments. | `commentsN.xml <comment ref="B2" authorId="0"><text><r>…` + the VML shape for size and position (`x:ClientData ObjectType="Note"` with `x:Anchor`, `x:Row`, `x:Column`, `x:Visible`). VML is old XML. Write a minimal, known-good VML template. | M | Y | M |
| 4.13 | Symbols & Equations / رموز ومعادلات | Insert a symbol into the cell text (Unicode picker). Equations are drawing objects (OMML). | Unicode picker: S. Equations: keep and render the cached image or text. | S/L | Y | D |
| 4.14 | Header & Footer | See Page Layout, section 14. | | M | Y | D |
| 4.15 | Data from Picture / Insert Data from Picture / إدراج بيانات من صورة | Insert (or Data) > Data from Picture. The camera captures a printed or handwritten table, AI converts it, a **review pane** highlights cells with low confidence ("Review" / "Ignore" for each, or edit), then **Insert**. The result is placed at the active cell. | Offline OCR: Google ML Kit Text Recognition v2 covers Latin, Chinese, Devanagari, Japanese and Korean scripts, but **not Arabic**. For Arabic, use Tesseract (`tesseract4android`) with `ara.traineddata`, and pick the engine by detected script. Table structure: cluster text blocks by y (rows) and x (column alignment via gap histogram). Confidence comes from OCR scores, and cells below 0.8 are flagged. Online option: a cloud vision API for higher accuracy. | L | P | M |
| 4.16 | Insert from scanned PDF / Data from PDF | WPS and desktop Excel import tables from a PDF (desktop Power Query). | Parse the PDF text layer with PdfBox-Android, then use the same table clustering. | L | Y | D/W |
| 4.17 | Checkbox in cell / خانة اختيار | Excel 2024 / 365: Insert > Checkbox formats the cells as a checkbox (the value is TRUE/FALSE). Google Sheets has had this for a long time. | Excel writes this as a cell feature in `xl/featurePropertyBag/featurePropertyBag.xml` + a cell `vm` / xf extension. Keep the file working with older Excel by keeping the boolean values. Render a tickbox when the cell's xf references the checkbox feature property bag. | M | Y | D/G |
| 4.18 | Drop-down list (insert) | Google Sheets and newer Excel: Insert > Drop-down chips. Excel does the same via data validation (section 8.4). | DV list. | S | Y | G |
| 4.19 | Form controls / ActiveX | Buttons, combo box, checkbox controls. Excel Android shows them but they do not work. | Keep `ctrlProps`, `activeX`, and VML parts. | S (keep) | Y | D |
| 4.20 | Camera tool / Linked picture | A live picture of a range. | Keep it (formula in `xdr:pic`). | M | Y | D |
| 4.21 | IMAGE function, picture in cell | `=IMAGE(url, alt, sizing)` online. | Download to the cache, then render as a cell picture. Error `#CONNECT!` when offline. | M | P | D (365) |
| 4.22 | Stock / Geography data types | Linked data types (online). | Keep the rich value parts and show the cached text. | S (keep) | P | D |

---

## 5. Draw tab / رسم

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 5.1 | Draw with touch / pen / الرسم باللمس | Toggle between drawing and selecting. In Excel Android: a Draw tab with Select, Lasso Select, Eraser (stroke eraser), Pens (pen, pencil, highlighter), colour, thickness, and "Draw with Touch". With an S Pen the stylus draws and the finger scrolls. | Ink is stored as `xl/ink/inkN.xml` (InkML) and referenced from the drawing via `xdr:contentPart` / `mc:AlternateContent` with a fallback picture. For simple writing, also store a PNG fallback inside an `mc:Fallback` so other readers display it. Use `MotionEvent` pressure and tilt with low-latency rendering (`androidx.graphics:graphics-core` front-buffer rendering). | L | Y | M |
| 5.2 | Ink to Shape / Ink to Math | Turns strokes into shapes (tablet). | Shape recognition (approximate as a polygon, fit a circle/ellipse/rectangle). Ink to Math needs an ML model. | L | Y | M (tablet) |
| 5.3 | Ruler / Replay | | | M | Y | D |
| 5.4 | Lasso select ink / تحديد حر | Select strokes and move or resize them. | Point-in-polygon on the stroke bounding boxes. | M | Y | M |

---

## 6. Tables (ListObjects) / الجداول

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 6.1 | Create / convert to range / إنشاء جدول / تحويل إلى نطاق | Header row required. Unique column names (duplicates get "Column2"). Convert to range keeps the formatting. | `xl/tables/table1.xml`: `<table id="1" name="Table1" displayName="Table1" ref="A1:D10" headerRowCount="1" totalsRowShown="0"><autoFilter ref="A1:D10"/><tableColumns count="4"><tableColumn id="1" name="Name"/>…</tableColumns><tableStyleInfo …/></table>`. The sheet has `<tableParts><tablePart r:id="rId2"/></tableParts>`. Table names are unique across the workbook and live in the same namespace as defined names. | L | Y | M |
| 6.2 | Table Style Options / خيارات نمط الجدول | Header Row, Total Row, Banded Rows, Banded Columns, First Column, Last Column, Filter Button. | Attributes on `tableStyleInfo` + `headerRowCount`, `totalsRowCount`. | M | Y | M |
| 6.3 | Total Row / صف الإجمالي | A drop-down per column: None, Average, Count, Count Numbers, Max, Min, Sum, StdDev, Var, More functions. | Writes `=SUBTOTAL(109,[Sales])` into the cell and `totalsRowFunction="sum"` on the `tableColumn`. | M | Y | M |
| 6.4 | Auto-expand / المد التلقائي | Typing directly below or to the right of the table extends it (AutoCorrect option). | Update `ref` and the autofilter ref, and copy the calculated column formula. | M | Y | M |
| 6.5 | Calculated columns / أعمدة محسوبة | A formula entered in one cell fills the whole column. | `<tableColumn><calculatedColumnFormula>[@Qty]*[@Price]</calculatedColumnFormula>`. | M | Y | M |
| 6.6 | Structured references / المراجع المنظمة | `Table1[Sales]`, `Table1[[#Headers],[Sales]]`, `[@Sales]` / `Table1[@Sales]`, `Table1[#All]`, `[#Data]`, `[#Totals]`, `[#This Row]`, column ranges `Table1[[Q1]:[Q4]]`, escaping with `'` for special characters (`[`, `]`, `#`, `'`). | The parser recognises `TableName[...]`. Bind references to the table **by id and column id** so renaming updates the formulas. In the file, `[@Col]` is saved as `Table1[[#This Row],[Col]]`. | L | Y | M |
| 6.7 | Resize table / تغيير حجم الجدول | Drag the resize handle at the bottom-right, or use a dialog. | Update `ref`. | S | Y | M |
| 6.8 | Remove duplicates | See section 8.3. | | M | Y | M |
| 6.9 | Slicers (desktop) | Buttons that filter a table. | Keep `xl/slicers`, `slicerCaches`. Implementing them is a later phase. | L | Y | D |
| 6.10 | Card view on phone | See 2.17. | | M | Y | M |

---

## 7. Sort & Filter / الفرز والتصفية

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 7.1 | Quick sort ascending / descending (فرز تصاعدي / تنازلي) | Sorts the current region by the active column. Detects a header row. Asks "expand the selection?" when neighbouring columns hold data. | A stable sort. Order: numbers < text (case-insensitive, locale collation via `java.text.Collator`/ICU for Arabic) < FALSE < TRUE < errors, then blanks last whatever the direction. Formulas are moved and their relative references re-evaluated (Excel moves the formula text adjusted for the row change). | M | Y | M |
| 7.2 | Custom sort / فرز مخصص | Multiple levels (Sort by, Then by). Sort On: Cell Values, Cell Color, Font Color, Conditional Formatting Icon. Order: A-Z, Z-A, custom list (days, months). Options: case-sensitive, orientation (sort left to right). | Persisted in `<sortState ref=…><sortCondition ref="B2:B10" descending="1" sortBy="cellColor" dxfId=…/>`. | M | Y | M (levels), D (by colour) |
| 7.3 | AutoFilter / عامل التصفية التلقائي | Drop-down arrows in the header. Shows a values checklist with Select All and search, Sort A-Z / Z-A, Sort by colour, Filter by colour, Text Filters (Equals, Does Not Equal, Begins With, Ends With, Contains, Does Not Contain, Custom with AND/OR), Number Filters (=, ≠, >, ≥, <, ≤, Between, Top 10, Above/Below Average), Date Filters (Today, Yesterday, This week… Year to Date, All dates in period, months/quarters, a tree grouped by year > month > day), Clear Filter. | `<autoFilter ref="A1:F100"><filterColumn colId="2"><filters><filter val="Sana'a"/></filters></filterColumn>` / `<customFilters and="1"><customFilter operator="greaterThan" val="5"/>` / `<top10 val="10" percent="0"/>` / `<dynamicFilter type="today"/>` / `<colorFilter dxfId=…/>` / `dateGroupItem`. **Rows that are filtered out are saved with `hidden="1"`**, and Excel trusts this when the file opens. Defined name `_xlnm._FilterDatabase` (hidden) is written. | L | Y | M |
| 7.4 | Reapply / Clear all filters | | Re-evaluate the criteria and update hidden rows. | S | Y | M |
| 7.5 | Advanced Filter (desktop) | A criteria range, copy to another location, unique records only. | `_xlnm.Criteria`, `_xlnm.Extract`. | M | Y | D |
| 7.6 | Filter views (Google Sheets) / عروض التصفية | A personal filter that other collaborators do not see. Excel's "Sheet View" is similar (desktop and web, co-authoring). | Keep Sheet View parts (`xl/namedSheetViews`). Our own: per-user filter state outside the file. | M | Y | G/D |
| 7.7 | SORT/FILTER/UNIQUE functions | Dynamic arrays, section 9. | | L | Y | M |

---

## 8. Data tab / بيانات

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 8.1 | Sort / Filter | Section 7. | | | | M |
| 8.2 | Refresh All / تحديث الكل | Refreshes external data connections and pivots. Android: limited to cloud workbooks. | Keep `xl/connections.xml`, `queryTables`. Offline: recalculate only. | S (keep) | P | M\* |
| 8.3 | Remove Duplicates / إزالة التكرارات | Pick the columns to compare and say whether the data has headers. Reports "X duplicate values found and removed; Y unique values remain". | Hash the tuple of the chosen columns' **displayed or raw values** (Excel compares the underlying values with case-insensitive text). Delete rows inside the range only, shifting the cells below up within the range. | M | Y | M |
| 8.4 | Data Validation / التحقق من صحة البيانات | **Allow:** Any value, Whole number, Decimal, List (a comma list or a range/name; in-cell drop-down), Date, Time, Text length, Custom (formula). **Data:** between, not between, equal, not equal, >, <, >=, <=. Ignore blank. **Input message** (title and text). **Error alert:** Stop, Warning, Information, with title and message. Circle invalid data (desktop). Excel Android shows the list drop-down and enforces rules; creating rules is not available on phones in some builds (tablet and desktop can). | `<dataValidations count="1"><dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" errorStyle="stop" sqref="B2:B50" promptTitle=… prompt=… errorTitle=… error=…><formula1>"Yes,No"</formula1></dataValidation>`. A list over 255 characters must be a range. Validation that points to another sheet in old files uses `x14:dataValidations` in extLst, so read both. A list drop-down in the grid shows a chevron button on the active cell. | M | Y | M\* / D |
| 8.5 | Text to Columns / تحويل النص إلى أعمدة | Delimited (Tab, Semicolon, Comma, Space, Other, treat consecutive delimiters as one, text qualifier `"`) or Fixed width (break lines). Column data format per column: General, Text, Date (DMY…), Do not import. Destination. | A local parser with a preview grid. | M | Y | D (also G: "Split text to columns") |
| 8.6 | TEXTSPLIT / TEXTBEFORE / TEXTAFTER functions | The formula version of text to columns. | Section 9. | M | Y | M |
| 8.7 | Flash Fill | 2.8. | | L | Y | D |
| 8.8 | Group / Ungroup / Subtotal / Outline (تجميع / إلغاء التجميع / إجمالي فرعي) | Group rows or columns (outline levels 1-7) with +/- buttons in the margin. The Subtotal wizard inserts SUBTOTAL rows at each change in a key. Excel Android shows the outline and lets you expand or collapse it. | `<row outlineLevel="1" hidden="1" collapsed="1">`, `<sheetFormatPr outlineLevelRow="2">`, `<sheetPr><outlinePr summaryBelow="0"/>`. | M | Y | M\* (view), D |
| 8.9 | What-If Analysis: Goal Seek / Scenario Manager / Data Table (تحليل ماذا لو) | Goal Seek: set cell X to value V by changing cell Y (secant or Newton iteration). Data Table: one- or two-variable `{=TABLE(,B1)}`. | Goal Seek: S/M (iterate the engine). Data Table: `<f t="dataTable" ref=… dt2D="1" r1="B1"/>` needs the engine to substitute values into the input cell many times. | M | Y | D |
| 8.10 | Forecast Sheet | ETS forecast plus a chart. | `FORECAST.ETS` (AAA exponential smoothing). | L | Y | D |
| 8.11 | Solver | A Lp/NLP add-in. | Out of scope; keep the hidden names `solver_*`. | L | Y | D |
| 8.12 | Get & Transform (Power Query) / From Text/CSV, Web, Table | Desktop only. | Keep the `customXml` DataMashup part. Our version: "Import CSV/TXT with options" (encoding UTF-8 or Windows-1256 for Arabic, delimiter, header). | M | Y | D |
| 8.13 | Data types (Stocks, Geography) | Online. | 4.22. | — | N | D |
| 8.14 | Consolidate | Combines ranges with a function. | | M | Y | D |
| 8.15 | Analyze Data (formerly Ideas) / تحليل البيانات | Automatic insights: trends, outliers, top-N, charts and pivots made for you, and natural-language questions. Cloud based. In 2025 Microsoft is moving this into Copilot. | Offline version: a rule-based insight generator (biggest values, month-over-month change, correlation between numeric columns, outliers > 2σ) that produces charts and summary text. AI version: an LLM with the table sample as context. | L | P | M (online, 365) |
| 8.16 | Clean data / Data cleanup suggestions (Google Sheets) | Google Sheets: "Cleanup suggestions" for inconsistent values, extra whitespace, duplicates; "Column stats" (distribution, counts, frequencies). | Rules: TRIM, PROPER, near-duplicate clustering (Levenshtein), number-as-text detection with a "Convert to number" action, Excel's "Number stored as text" green triangle. | M | Y | G |
| 8.17 | Error checking (green triangle) / التحقق من الأخطاء | Number stored as text, formula inconsistent with nearby formulas, formula omits adjacent cells, unlocked cells containing formulas, 2-digit years, empty-cell references, evaluation errors. The quick menu has "Convert to Number", "Ignore error", "Help". | `<ignoredErrors><ignoredError sqref="A1" numberStoredAsText="1"/>`. | M | Y | M (some) |

---

## 9. Formulas tab / الصيغ

### 9.1 Formula entry, formula bar and AutoComplete

| # | Feature (EN / AR) | Behaviour / options | Implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 9.1.1 | Formula bar / شريط الصيغة | Shows the formula of the active cell. "fx" opens the function picker. Can be expanded to several lines. On phones it sits above the grid. A checkmark commits. Long formulas scroll. | A `BasicTextField` with syntax colouring through `AnnotatedString`: each range reference gets a colour, and the matching coloured rectangle is drawn on the grid. | M | Y | M |
| 9.1.2 | Point-and-tap references / الإشارة إلى الخلايا | While editing a formula, tapping a cell inserts its reference, and dragging inserts a range. Tapping another sheet's tab inserts `Sheet2!A1`. Coloured outlines of references can be dragged to move or resize them (desktop and tablet). | Editing state machine: "point mode" is on when the caret follows an operator, `(` or `,`. | M | Y | M |
| 9.1.3 | Formula AutoComplete / الإكمال التلقائي | Typing `=su` lists the functions, defined names and table names that contain the text (partial and fuzzy matches since 2023), each with a one-line description. Tapping one inserts `SUM(`. After `Table1[`, column names are listed. | Prefix trie plus contains-search over the localised function names. | M | Y | M |
| 9.1.4 | Function ScreenTip (argument hints) / تلميح الوسائط | Shows `SUM(number1, [number2], …)` with the current argument in bold. | Parse the formula up to the caret to find the function and the argument index. | M | Y | M |
| 9.1.5 | Insert Function (fx) / إدراج دالة | Browse by category: Recently Used, Financial, Logical, Text, Date & Time, Lookup & Reference, Math & Trig, Statistical, Engineering, Cube, Information, Compatibility, Web. Each function has a description and help. Android: Formulas tab > category buttons > a list of functions. | Function metadata table (name, AR name, category, argument specs, description in EN and AR). | M | Y | M |
| 9.1.6 | F4 / `$` absolute toggle | Cycles A1 > $A$1 > A$1 > $A1 > A1. On the custom keyboard this is a `$` key. | Token-level rewrite. | S | Y | M |
| 9.1.7 | Array entry (CSE) | Legacy array formulas `{=…}`. | `<f t="array" ref="A1:A3">`. | M | Y | M |
| 9.1.8 | Formula error helper | An error indicator with the menu "Show calculation steps" (Evaluate Formula), "Trace error", "Ignore". | 9.3 Evaluate Formula. | M | Y | M\* |
| 9.1.9 | Localised function names / أسماء دوال عربية | Excel can show Arabic function names in an Arabic UI. **Note:** Arabic Excel actually keeps English function names, unlike German or French. Recommendation: English names by default, with Arabic descriptions; the separator is `,`, or `;` in locales with a comma decimal. | Always store the en-US invariant form in the file (`<f>SUM(A1:A3)</f>`, no `=`, `,` separator). Convert only for display. | M | Y | M |
| 9.1.10 | Formula text future-function prefix | Newer functions are stored with prefixes: `_xlfn.XLOOKUP`, `_xlfn._xlws.FILTER`, `_xlfn._xlws.SORT`, `_xlfn.LET`, `_xlpm.x` (LET/LAMBDA parameter names), `_xlfn.ANCHORARRAY` (`A1#` spill reference), `_xlfn.SINGLE` (`@`). | Map them on read and write. Missing prefixes make Excel show `#NAME?`. | M | Y | — |

### 9.2 Defined names / الأسماء المعرّفة

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 9.2.1 | Define Name / تعريف اسم | Name, Scope (Workbook or a sheet), Comment, Refers to (a range, constant or formula). Rules: starts with a letter, `_` or `\`; no spaces; cannot look like a cell reference; up to 255 characters; not case sensitive. Arabic letters are allowed. | `xl/workbook.xml <definedNames><definedName name="Rate" localSheetId="0" comment=…>Sheet1!$B$1</definedName>`. | M | Y | M (Android: Formulas > Name Manager on tablet; Name Box on phone) |
| 9.2.2 | Name Manager / إدارة الأسماء | List, edit, delete and filter names (scoped, with errors, table names). | | M | Y | M\* |
| 9.2.3 | Create from Selection | Names from the top row or left column labels. | | S | Y | D |
| 9.2.4 | Use in Formula | A list of names that can be inserted. | | S | Y | D |
| 9.2.5 | Built-in names | `_xlnm.Print_Area`, `_xlnm.Print_Titles`, `_xlnm._FilterDatabase`, `_xlnm.Criteria`, `_xlnm.Extract`, `_xlnm.Consolidate_Area`, `_xlnm.Sheet_Title`. | Handle them specially and hide them from Name Manager where Excel does. | S | Y | — |
| 9.2.6 | LAMBDA names (custom functions) | `=LAMBDA(x,y, x+y)` saved as the name `ADD` can be called as `=ADD(1,2)`. | The engine treats a name that evaluates to a lambda as callable. | L | Y | D (365) |

### 9.3 Formula auditing / تدقيق الصيغ (desktop, worth bringing to tablet)

| # | Feature | Behaviour | Implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 9.3.1 | Trace Precedents / Dependents (تتبع السابقات / التابعات) | Blue arrows on the grid. Dashed arrows with a sheet icon for references to other sheets. | Query the dependency graph, then draw arrows on an overlay layer. | M | Y | D |
| 9.3.2 | Remove Arrows | | | S | Y | D |
| 9.3.3 | Show Formulas | 2.15. | | S | Y | D |
| 9.3.4 | Error Checking | 8.17. | | M | Y | D |
| 9.3.5 | Evaluate Formula (تقييم الصيغة) | Step-by-step evaluation with the next sub-expression underlined. | The AST interpreter with a debugging hook that yields after each node. | M | Y | D |
| 9.3.6 | Watch Window | Pins cells to watch their values. | | S | Y | D |

### 9.4 Calculation options / خيارات الحساب

| # | Feature | Behaviour | OOXML | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 9.4.1 | Calculation mode / وضع الحساب | Automatic, Automatic except for data tables, Manual. **Calculate Now** (F9: the whole workbook), **Calculate Sheet** (Shift+F9). Android: Formulas > Calculation Options. | `xl/workbook.xml <calcPr calcId="191029" calcMode="manual" fullCalcOnLoad="1"/>`. Set `fullCalcOnLoad="1"` when we are unsure our cached values are right, so desktop Excel recalculates when it opens the file. | S | Y | M |
| 9.4.2 | Iterative calculation | Allow circular references with Max iterations (100) and Max change (0.001). | `<calcPr iterate="1" iterateCount="100" iterateDelta="0.001"/>`. | M | Y | D |
| 9.4.3 | Precision as displayed | | `<calcPr fullPrecision="0"/>`. | S | Y | D |
| 9.4.4 | Circular reference warning | A warning dialog plus "Circular References: B5" in the status bar. | Cycle detection with Tarjan's strongly connected components. | M | Y | M |
| 9.4.5 | Calculation chain | | `xl/calcChain.xml` is optional. **Delete it on save** when the formulas have changed (a wrong calcChain makes Excel show a repair prompt). Excel rebuilds it. | S | Y | — |

### 9.5 Function library: categories and the first ~170 functions to implement

The full Excel catalogue has about 500 functions. The list below covers more than 98% of real use (school, office, finance, data cleanup). Priority **P1** = MVP (needed to open common files correctly), **P2** = next release, **P3** = later.

#### 9.5.1 Math & Trig / الرياضيات والمثلثات (36)

| Function | Pri. | Notes |
|---|---|---|
| SUM, SUMIF, SUMIFS, SUMPRODUCT | P1 | SUMIF criteria grammar: `">5"`, `"<>x"`, wildcards `*?~`, `""` (blank), `"="` (empty). Implement **one shared criteria matcher** and reuse it in COUNTIF(S)/AVERAGEIF(S)/MAXIFS/MINIFS/DSUM. |
| ROUND, ROUNDUP, ROUNDDOWN, MROUND, INT, TRUNC, CEILING, CEILING.MATH, FLOOR, FLOOR.MATH, EVEN, ODD | P1/P2 | ROUND rounds half away from zero. Use decimal-aware rounding (correct with `BigDecimal` for halves, e.g. 2.675). |
| ABS, SIGN, MOD, QUOTIENT, POWER, SQRT, EXP, LN, LOG, LOG10, PI, PRODUCT | P1 | MOD(n,d) = n − d·INT(n/d) (the sign follows the divisor). |
| RAND, RANDBETWEEN, RANDARRAY | P1/P2 | Volatile. |
| SEQUENCE | P2 | Dynamic array. |
| SUBTOTAL, AGGREGATE | P1/P2 | Codes 1-11 and 101-111 (ignore hidden rows). Ignore nested SUBTOTALs. |
| GCD, LCM, FACT, COMBIN, PERMUT | P2 | |
| SIN, COS, TAN, ASIN, ACOS, ATAN, ATAN2, RADIANS, DEGREES | P2 | |
| MMULT, MINVERSE, MDETERM, TRANSPOSE | P3 | Arrays. |
| ROMAN, ARABIC, BASE, DECIMAL | P3 | |

#### 9.5.2 Statistical / الإحصائية (30)

| Function | Pri. | Notes |
|---|---|---|
| AVERAGE, AVERAGEA, AVERAGEIF, AVERAGEIFS, MEDIAN, MODE, MODE.SNGL, MODE.MULT | P1/P2 | AVERAGE ignores text and logicals in ranges but counts them when typed directly as arguments (the general rule for "reference vs direct argument"). |
| COUNT, COUNTA, COUNTBLANK, COUNTIF, COUNTIFS | P1 | COUNTBLANK counts `""` results. |
| MAX, MIN, MAXA, MINA, MAXIFS, MINIFS, LARGE, SMALL | P1 | |
| RANK, RANK.EQ, RANK.AVG, PERCENTRANK, PERCENTILE(.INC/.EXC), QUARTILE(.INC/.EXC) | P1/P2 | Important for school grade books. |
| STDEV, STDEV.S, STDEV.P, STDEVA, VAR, VAR.S, VAR.P | P2 | Two-pass (Welford) algorithm for accuracy. |
| CORREL, PEARSON, SLOPE, INTERCEPT, RSQ, FORECAST, FORECAST.LINEAR, TREND, GROWTH, LINEST | P2/P3 | LINEST: least squares via QR decomposition. |
| NORM.DIST, NORM.S.DIST, NORM.INV, NORM.S.INV, T.DIST, T.TEST, CHISQ.TEST, BINOM.DIST, POISSON.DIST, CONFIDENCE.NORM | P3 | Use Apache Commons Math distributions or port them. |
| FREQUENCY | P2 | An array result. |

#### 9.5.3 Logical / المنطقية (12)

| Function | Pri. | Notes |
|---|---|---|
| IF, IFS, AND, OR, NOT, XOR, IFERROR, IFNA, SWITCH, TRUE, FALSE | P1 | IF is lazy: only the branch taken is evaluated, which matters for performance and for errors. |
| LET, LAMBDA, MAP, REDUCE, SCAN, BYROW, BYCOL, MAKEARRAY, ISOMITTED | P3 | Needs closures in the evaluator. |

#### 9.5.4 Text / النصية (33)

| Function | Pri. | Notes |
|---|---|---|
| CONCATENATE, CONCAT, TEXTJOIN, `&` | P1 | |
| LEFT, RIGHT, MID, LEN, LEFTB/RIGHTB/MIDB/LENB | P1 | Count UTF-16 code units the way Excel does (Arabic fits in BMP, so this is fine). |
| FIND, SEARCH, REPLACE, SUBSTITUTE | P1 | SEARCH is case-insensitive and supports wildcards; FIND is case-sensitive. |
| UPPER, LOWER, PROPER, TRIM, CLEAN | P1 | TRIM also collapses inner spaces to one (ASCII 32 only, not NBSP 160). |
| TEXT, VALUE, NUMBERVALUE, FIXED, DOLLAR | P1 | TEXT uses the number-format engine (3.4.5). |
| REPT, EXACT, CHAR, CODE, UNICHAR, UNICODE, T | P2 | |
| TEXTBEFORE, TEXTAFTER, TEXTSPLIT, VALUETOTEXT, ARRAYTOTEXT | P2 | |
| REGEXTEST, REGEXEXTRACT, REGEXREPLACE (365, 2024) | P3 | PCRE2-flavoured; Java regex is close enough with a translation layer. |

#### 9.5.5 Date & Time / التاريخ والوقت (24)

| Function | Pri. | Notes |
|---|---|---|
| TODAY, NOW | P1 | Volatile. |
| DATE, TIME, YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, WEEKDAY, WEEKNUM, ISOWEEKNUM | P1 | DATE allows month or day overflow (DATE(2026,14,1) = Feb 2027). WEEKDAY return types 1, 2, 3, 11-17. |
| DATEVALUE, TIMEVALUE | P1 | Locale-aware parsing. |
| EDATE, EOMONTH, DATEDIF, DAYS, DAYS360, YEARFRAC | P1/P2 | DATEDIF units "Y","M","D","MD","YM","YD" (MD has a known Excel bug; copy Excel's behaviour). |
| NETWORKDAYS, NETWORKDAYS.INTL, WORKDAY, WORKDAY.INTL | P2 | Weekend codes 1-17 and 7-character strings ("0000110" = Friday/Saturday weekend, common in Yemen and the Gulf). |

#### 9.5.6 Lookup & Reference / البحث والمراجع (26)

| Function | Pri. | Notes |
|---|---|---|
| VLOOKUP, HLOOKUP, LOOKUP | P1 | Approximate match (TRUE) uses binary search on sorted data, matching Excel's results even when the data is not sorted. Exact match is a linear scan; build a hash index on demand for repeated calls on the same column (a cache keyed by range and version). |
| INDEX, MATCH | P1 | MATCH types 1, 0, -1. INDEX(range, 0, col) returns a whole column (a reference). |
| XLOOKUP, XMATCH | P1 | match_mode 0, -1, 1, 2 (wildcard). search_mode 1, -1, 2, -2 (binary). if_not_found. Returns a reference, so `XLOOKUP(...):XLOOKUP(...)` works. |
| CHOOSE, CHOOSECOLS, CHOOSEROWS | P1/P2 | |
| OFFSET, INDIRECT | P1 | Volatile. INDIRECT parses A1 or R1C1 text at runtime, so dependencies are dynamic: mark the cell volatile. |
| ROW, ROWS, COLUMN, COLUMNS, ADDRESS, AREAS | P1 | |
| HYPERLINK | P1 | |
| TRANSPOSE | P2 | |
| FILTER, SORT, SORTBY, UNIQUE | P2 | Dynamic arrays, `_xlfn._xlws.` prefix. |
| VSTACK, HSTACK, TAKE, DROP, EXPAND, TOCOL, TOROW, WRAPROWS, WRAPCOLS | P3 | |
| GETPIVOTDATA | P3 | Reads from rendered pivot cells. |
| FORMULATEXT | P2 | |

#### 9.5.7 Financial / المالية (16)

| Function | Pri. | Notes |
|---|---|---|
| PMT, IPMT, PPMT, PV, FV, NPER, RATE | P1/P2 | RATE uses Newton-Raphson with Excel's default guess (0.1) and a 20-iteration cap, returning `#NUM!` when it does not converge. |
| NPV, XNPV, IRR, XIRR, MIRR | P2 | |
| SLN, DB, DDB, SYD | P3 | Depreciation. |
| CUMIPMT, CUMPRINC, EFFECT, NOMINAL | P3 | |

#### 9.5.8 Information / المعلومات (14)

| Function | Pri. | Notes |
|---|---|---|
| ISBLANK, ISNUMBER, ISTEXT, ISNONTEXT, ISLOGICAL, ISERROR, ISERR, ISNA, ISREF, ISFORMULA, ISEVEN, ISODD | P1 | |
| N, NA, TYPE, ERROR.TYPE, CELL, INFO, SHEET, SHEETS | P2 | CELL("filename") and similar need workbook metadata. |

#### 9.5.9 Engineering, Database, Web, Cube, Compatibility

| Category | Functions | Pri. |
|---|---|---|
| Engineering / الهندسية | CONVERT, DEC2BIN, BIN2DEC, DEC2HEX, HEX2DEC, DEC2OCT, BITAND, DELTA, GESTEP, COMPLEX/IM* (later) | P3 |
| Database / قاعدة البيانات | DSUM, DAVERAGE, DCOUNT, DCOUNTA, DMAX, DMIN, DGET | P3 |
| Web | ENCODEURL (P3), WEBSERVICE and FILTERXML (online, Windows only: return `#N/A` or `#BLOCKED!`) | P3 |
| Cube | CUBEVALUE etc.: **keep the cached values**, evaluating to `#N/A` with no OLAP | — |
| Compatibility | The old forms (STDEV, PERCENTILE, RANK, NORMDIST…) map to the new ones. | P2 |

**Count:** 36 + 30 + 12 (+9 lambda) + 33 + 24 + 26 + 16 + 14 + about 20 = **about 220 listed**, of which about **150 are P1/P2**.

---

## 10. Formula engine design / تصميم محرك الصيغ

### 10.1 Value model

```
sealed interface XValue
  Num(Double)            // all numbers are IEEE 754 double, with Excel's 15-significant-digit display
  Str(String)
  Bool(Boolean)
  Err(ErrorCode)         // #NULL! #DIV/0! #VALUE! #REF! #NAME? #NUM! #N/A #GETTING_DATA #SPILL! #CALC! #FIELD! #BLOCKED! #CONNECT! #BUSY! #UNKNOWN!
  Blank                  // an empty cell: 0 in arithmetic and "" in text context
  Arr(rows, cols, Array<XValue>)   // an array value (inline constant, function result)
  Ref(RefArea list)      // a reference result: OFFSET, INDEX, XLOOKUP, A1:B2, union (A1,B2), intersection (A1:C3 B2:D4)
  Lambda(params, body, closureEnv)
```

- Number precision: Excel computes with doubles and shows 15 significant digits. When values are compared or subtracted, Excel snaps results to zero in some cases (e.g. `=0.1+0.2-0.3` shows 5.55E-17, but `=1*(0.1+0.2)=0.3` is TRUE). Copy that: compare with a 15-digit tolerance and round to 15 digits before turning a number into text.
- Error codes in the file: `<c t="e"><v>#N/A</v></c>`. Codes for dynamic arrays: `#SPILL!` and `#CALC!`.

### 10.2 Lexer and parser

- **Grammar** (precedence from low to high): comparison `= <> < > <= >=` < concatenation `&` < `+ -` < `* /` < `^` (left-associative in Excel: `2^3^2` = 64) < percent `%` (postfix) < unary minus (binds tighter than `^`: `-2^2` = 4 in Excel) < range operators: `:` (range) > ` ` (space, intersection) > `,` (union, only inside parentheses or as a reference argument).
- **Tokens:** numbers (`1`, `1.5`, `1E3`, `.5`), strings `"a""b"`, booleans, errors, A1 references with `$`, ranges `A:A`, `1:1`, sheet prefixes `Sheet1!`, quoted sheet names `'My Sheet'!A1` and `'Sheet 1:Sheet 3'!A1` (3D references), external `[1]Sheet1!A1` (`externalLinkN.xml`), defined names, structured references `Table1[[#This Row],[Col]]`, function calls, array constants `{1,2;3,4}`, the spill operator `A1#`, the implicit-intersection operator `@`, R1C1 for INDIRECT.
- **Parser:** a hand-written Pratt (precedence-climbing) parser in Kotlin that produces an AST. Do not use a parser generator: hand-written code gives better error recovery for AutoComplete.
- **Storage:** keep a compact AST per **unique formula shape**. Shared formulas (`<f t="shared" si="0" ref="B2:B100">`) and copied formulas share one AST in **R1C1-relative form**, and each cell stores only its anchor offset. This cuts memory 10-100x on large sheets.
- **Reading shared formulas:** the master cell has the text and the other cells only `si`. Translate references by the cell offset. On write, either re-share them or write each formula in full (simpler, larger file).

### 10.3 Dependency graph and recalculation

- **Nodes:** formula cells (with a sheet id and packed `row<<14|col` key), defined names, CF/DV formulas, and chart series.
- **Edges:** from precedent ranges to the dependent formula. Large ranges (`A:A`, `A1:A100000`) are **not expanded** to cells. Keep a **range index** (an R-tree or interval tree per sheet per column band) so "cell X changed" finds its dependents by range overlap in O(log n + k).
- **Dirty marking:** on an edit, mark the transitive dependents dirty (BFS), then recompute in **topological order** (Kahn's algorithm). Volatile functions (NOW, TODAY, RAND, RANDBETWEEN, RANDARRAY, OFFSET, INDIRECT, CELL, INFO) mark their cells dirty on every recalc.
- **Dynamic dependencies:** INDIRECT, OFFSET and INDEX that return references are resolved at runtime. Treat them as volatile and record the references actually read as runtime dependencies.
- **Cycles:** when Tarjan's algorithm finds an SCC with more than one node, or a self-loop: if iteration is off, report a circular reference and leave the old value (0 for new cells). If iteration is on, use Gauss-Seidel iteration up to `iterateCount` or until the change is below `iterateDelta`.
- **On load:** trust the cached `<v>` values (instant open) and build the graph lazily on a background thread. Recalculate fully only when `fullCalcOnLoad="1"`, when we wrote the file ourselves and the versions differ, or when the user asks.
- **Threading:** do the calculation on `Dispatchers.Default` with a single-writer model. The UI reads an immutable snapshot (persistent data structures or copy-on-write cell pages). Recalculation can be cancelled by the next edit. Optional later: parallelise independent topological levels.
- **Incremental UI:** emit "cells changed" events to invalidate only the affected tiles.

### 10.4 Reference semantics and implicit intersection

- A function parameter is typed as Value, Reference or Array. Example: SUM accepts references and arrays, and in references it ignores text and booleans. ROW wants a reference. INDEX returns a reference.
- **Implicit intersection (`@`)**: in pre-dynamic-array Excel, `=A1:A10` in row 5 returned A5. In dynamic-array Excel it spills. On reading a file: formulas without `t="array"` that come from an old Excel and have the `cm` attribute missing are evaluated as legacy, using implicit intersection. Write `_xlfn.SINGLE()` where the user typed `@`.
- **3D references** `Sheet1:Sheet3!A1` work in SUM/AVERAGE/COUNT/MAX/MIN and similar.

### 10.5 Dynamic arrays and spilling / المصفوفات الديناميكية

- When a formula returns an `Arr` larger than 1x1, it **spills** into the neighbouring cells. If any of those cells is not empty, a merged cell, or inside a table, the result is `#SPILL!` (with a reason: Obstructed, Table, Merged, Indeterminate size, Out of grid).
- The spill range is not stored as formulas. In the file, Excel writes the anchor as `<c r="A1" cm="1"><f t="array" ref="A1:A5">_xlfn._xlws.SORT(B1:B5)</f><v>…</v></c>` and the other cells as plain `<c><v>` values. `cm="1"` points to `xl/metadata.xml` `<cellMetadata>` with the `XLDAPR` dynamic-array property. **Without metadata.xml + cm, Excel treats the formula as a legacy CSE array.** Write metadata.xml exactly in the form Excel expects (copy it from a reference file).
- Spill-range references `A1#` are resolved to the anchor's current spill extent (`_xlfn.ANCHORARRAY(A1)` in the file).
- Spill cells are read-only. The UI draws a thin blue border around the spill range, and typing in a spill cell shows a ghosted formula in the formula bar.
- Lifting (broadcasting): scalar functions applied to arrays work per element (`=A1:A3*2`). Size mismatches pad with `#N/A`.

### 10.6 Error semantics (match Excel exactly)

| Error | When |
|---|---|
| `#DIV/0!` | Division by zero; AVERAGE of no numbers. |
| `#VALUE!` | Wrong type: text where a number is needed (`="a"+1`). Numeric text is converted (`="1"+1` = 2). |
| `#REF!` | Deleted reference, OFFSET outside the grid, INDEX out of range. |
| `#NAME?` | Unknown function or name, unquoted text. |
| `#NUM!` | No convergence (IRR, RATE), invalid number (SQRT(-1)), result too large (>1.79E308). |
| `#N/A` | Lookup not found, NA(), array size padding. |
| `#NULL!` | Empty intersection (A1:A3 C1:C3). |
| `#SPILL!`, `#CALC!` | Spill blocked; empty array result (e.g. FILTER with no matches and no if_empty), nested arrays. |
| Propagation | Errors pass through most operators and functions. The first error in argument order wins. IFERROR, IFNA, ISERROR, AGGREGATE (option 6) and COUNT ignore errors. |

### 10.7 Coercion rules (summary)

- Text to number: `"1,234"`, `"$5"`, `"50%"`, `"1/2/2026"` (a date in the current locale) and `" 5 "` all convert to numbers. Anything else gives `#VALUE!`.
- Booleans in arithmetic: TRUE = 1. In a **range** given to SUM or AVERAGE they are ignored, but typed as a **direct argument** they count.
- Comparison: text compared with a number: text is always greater. Comparisons ignore case (`"a"="A"` is TRUE). Use EXACT for case-sensitive comparison.
- Blank: 0 or "". `=A1=""` and `=A1=0` are both TRUE for an empty A1.

### 10.8 Testing strategy for the engine

- A **golden corpus**: about 5,000 formulas across all supported functions, evaluated in desktop Excel and saved in an `.xlsx` (formula + cached value). Our engine opens the file, recalculates and diffs against the cached values with a 1e-9 relative tolerance. Run it in CI.
- Fuzz the parser against round-trip (parse, print, parse again: same AST).
- Performance targets: open a 100k-row, 20-column file with 50k formulas in under 3 s on a mid-range phone (2023, 6 GB RAM). Recalculate after a single-cell edit in under 50 ms for typical dependency fan-out.

### 10.9 Options to save effort

- **Reuse Apache POI's formula evaluator?** POI-ooxml is heavy on Android (StAX, XMLBeans, about 15 MB, method-count limits) and slow, and it lacks dynamic arrays. Possible as a reference, not as the runtime.
- **Port a JavaScript engine** (HyperFormula, GPLv3/commercial; formula.js MIT) to Kotlin: HyperFormula's architecture (dependency graph with ranges, an address mapping strategy, matrix detection) is a good model, but its licence matters.
- **Recommended:** a Kotlin engine written for this app, with function implementations checked against the formula.js and LibreOffice Calc (MPL) sources for edge cases.

---

## 11. Charts / المخططات

### 11.1 Chart types

Excel Android can insert and edit (Insert > Chart): Column, Bar, Line, Pie, Area, Scatter (XY), Other (Doughnut, Radar, Stock, Surface, Bubble), Combo (tablet), and the newer types Waterfall, Histogram, Pareto, Box & Whisker, Treemap, Sunburst, Funnel. Map charts need the internet.

| Type (EN / AR) | Subtypes | OOXML | Pri. | Diff. |
|---|---|---|---|---|
| Column / عمودي | Clustered, Stacked, 100% Stacked (3D variants are shown flat) | `c:barChart` + `c:barDir val="col"` + `c:grouping clustered/stacked/percentStacked` + `c:overlap val="100"` | P1 | M |
| Bar / شريطي | Same | `c:barDir val="bar"` | P1 | S |
| Line / خطي | Line, Stacked, 100%, with Markers, smooth | `c:lineChart`, `c:marker`, `c:smooth` | P1 | M |
| Pie / دائري, Doughnut / كعكة | Pie, Pie of Pie, Bar of Pie, Exploded, Doughnut (hole size) | `c:pieChart`, `c:ofPieChart`, `c:doughnutChart`, `c:holeSize`, `c:explosion` | P1 | M |
| Area / مساحي | Area, Stacked, 100% | `c:areaChart` | P2 | S |
| Scatter (XY) / مبعثر | Markers only, smooth lines, straight lines | `c:scatterChart` + `c:scatterStyle` | P1 | M |
| Bubble / فقاعي | Bubble size from a third series | `c:bubbleChart` | P3 | M |
| Radar / راداري | Radar, with markers, filled | `c:radarChart` | P3 | M |
| Stock / الأسهم | High-Low-Close, Open-High-Low-Close, with volume | `c:stockChart` + `c:hiLowLines` + `c:upDownBars` | P3 | M |
| Surface / سطحي | | `c:surfaceChart` | P3 | L |
| Combo / مركب | Column + Line on a secondary axis | Several chart elements in one `c:plotArea` + two `c:valAx` | P2 | M |
| Waterfall, Funnel, Histogram, Pareto, Box & Whisker, Treemap, Sunburst, Map | | **chartEx**: `xl/charts/chartEx1.xml` (namespace `http://schemas.microsoft.com/office/drawing/2014/chartex`), referenced from the drawing through `mc:AlternateContent` with `cx:chart`. Data in `cx:chartData` and `cx:numDim`/`cx:strDim`. | P2/P3 | L |

### 11.2 Chart tools (contextual Chart tab on Android) / أدوات المخطط

| Feature | Behaviour | OOXML / implementation | Diff. | Plat. |
|---|---|---|---|---|
| Move / resize the chart | Drag and handles. Snaps to cells (desktop: Alt). | `xdr:twoCellAnchor` from/to + EMU offsets (1 px at 96 dpi = 9525 EMU). | S | M |
| Change Chart Type / تغيير نوع المخطط | | Rebuild the plot element and keep the series. | M | M |
| Quick Layout / Layouts / تخطيطات | 10-12 presets (title positions, legend, data labels, axis titles). | Set element flags. | M | M |
| Chart Styles / Colors / الأنماط والألوان | Styles 1-16 per type. Colour palettes: Colorful 1-4, Monochromatic 1-13. | `xl/charts/styleN.xml` (cs:chartStyle) + `colorsN.xml` (cs:colorStyle). Apply styles by writing explicit `c:spPr` per series. Copy from Excel's style definitions. | M | M |
| Chart elements / عناصر المخطط | Chart Title, Axis Titles, Axes (primary, secondary), Legend (Right, Top, Left, Bottom, None), Data Labels (Center, Inside End, Inside Base, Outside End, Data callout; content: value, category, series name, percentage, from cells), Data Table, Error Bars (standard error, %, standard deviation), Gridlines (major/minor, horizontal/vertical), Trendline (Linear, Exponential, Logarithmic, Polynomial order 2-6, Power, Moving Average period N; show equation, R²; forecast forward/backward), Up/Down Bars, Drop Lines, High-Low Lines. | `c:title`, `c:legend/c:legendPos`, `c:dLbls`, `c:dTable`, `c:errBars`, `c:majorGridlines`, `c:trendline` (`c:trendlineType`, `c:order`, `c:period`, `c:dispEq`, `c:dispRSqr`). Trendlines are fitted with least squares at render time. | L | M |
| Select Data / Switch Row-Column / تبديل الصف/العمود | Edit series name, values and categories; add or remove series; hidden and empty cell settings (gaps, zero, connect with line; show data in hidden rows). | `c:ser/c:tx/c:strRef/c:f`, `c:cat`, `c:val/c:numRef/c:f` + `c:numCache` (**the cached points must be updated** so other readers show the right data without recalculating). `c:dispBlanksAs`, `c:plotVisOnly`. | M | M |
| Format axis | Min, max, major/minor unit, log scale (base), reverse order, crosses at, number format, label position, display units (thousands, millions), date axis (base unit days/months/years). | `c:scaling/c:min/max/c:logBase/c:orientation`, `c:majorUnit`, `c:numFmt`, `c:dateAx`, `c:dispUnits`. | M | M\* |
| Format series / data point | Fill, border, marker style and size, gap width, overlap, secondary axis, explode a point, invert if negative, vary colours by point. | `c:spPr`, `c:dPt`, `c:gapWidth`, `c:varyColors`. | M | M\* |
| Chart filters | Show or hide series and categories without changing the data. | `c15:filteredSeriesTitle`… (extLst). | M | D |
| Chart renderer | | Write our own renderer on Compose Canvas. A charting library (MPAndroidChart, Vico) does not match OOXML semantics or styles well enough. Separate layers: layout engine (plot area, axis label size, legend) > scales > marks > labels with collision avoidance. Reuse the same renderer for the PDF export (Android `PdfDocument` Canvas). | L | — |
| Dynamic chart ranges | Charts update when cells change. A series pointing to a table column grows with the table. | Chart series formulas are registered in the dependency graph. | M | M |
| Copy chart as image / Save chart as picture | | Bitmap export. | S | M/W |

### 11.3 Sparklines

See 4.9. Rendering: per cell, a line path / bars / win-loss blocks, plus markers (high, low, first, last, negative) and an axis.

---

## 12. Review tab / مراجعة

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 12.1 | New Comment, Reply, Resolve, Delete, Previous / Next, Show Comments pane (تعليق جديد، رد، حل، حذف) | 4.11. The comments pane on tablets; a comment card on phones. | | M | Y | M |
| 12.2 | Notes: New / Edit / Show all / Convert to comments (ملاحظات) | 4.12. | | M | Y | M |
| 12.3 | Protect Sheet / حماية الورقة | Optional password. Allow users to: select locked cells, select unlocked cells, format cells/columns/rows, insert columns/rows/hyperlinks, delete columns/rows, sort, use AutoFilter, use PivotTable, edit objects, edit scenarios. Unprotect Sheet. Excel Android can turn protection on and off (with a password) in current builds. | `<sheetProtection algorithmName="SHA-512" hashValue="…" saltValue="…" spinCount="100000" sheet="1" objects="1" scenarios="1" formatCells="0" …/>` (note the inverted logic: `formatCells="0"` means **allowed**). Hash: SHA-512 over salt + UTF-16LE password, iterated spinCount times (ECMA-376 Part 1, 18.2.29). The legacy 16-bit `password="CC3D"` XOR hash must still be read. Our UI must block edits on locked cells (the default xf `locked=1`). | M | Y | M |
| 12.4 | Allow Edit Ranges (desktop) | Ranges with their own passwords. | `<protectedRanges>`. | M | Y | D |
| 12.5 | Protect Workbook (structure) / حماية المصنف | Prevents adding, deleting, renaming, moving, hiding or unhiding sheets. | `<workbookProtection lockStructure="1" workbookAlgorithmName=… workbookHashValue=…/>`. | S | Y | M\* |
| 12.6 | Encrypt with password (file-level) / التشفير بكلمة مرور | Agile encryption on save. | 1.12 (writing is L). | L | Y | D |
| 12.7 | Check Accessibility / التحقق من إمكانية الوصول | Missing alt text, default sheet names, merged cells, colour contrast, table without headers, blank cells used for formatting. | Rule engine over the model. | M | Y | D (M via Tell Me in some builds) |
| 12.8 | Alt text / نص بديل | For pictures, charts, shapes. | `xdr:cNvPr descr="…" title="…"`. | S | Y | M |
| 12.9 | Spelling / التدقيق الإملائي | Spell-check cell text. | Android `SpellCheckerSession` (system), or Hunspell with an Arabic dictionary. | M | Y | D (Android uses keyboard spelling) |
| 12.10 | Translate / Smart Lookup | Online. | | M | N | D |
| 12.11 | Track Changes (legacy shared workbook) / Show Changes | Show Changes (365): who changed which cell, when, and the old value (cloud). | Keep `revisions/` parts. Local edit history could show "Show changes" for this device only. | M | Y | D |
| 12.12 | Workbook Statistics | Counts of sheets, cells with data, tables, formulas, charts, pictures, comments. | | S | Y | D |

---

## 13. View tab / عرض

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 13.1 | Freeze Panes / تجميد الأجزاء | Freeze Panes (at the active cell: rows above and columns to the left), Freeze Top Row, Freeze First Column, Unfreeze. Android: View > Freeze Panes > Freeze Panes / Top Row / First Column. | `<sheetView><pane xSplit="1" ySplit="1" topLeftCell="B2" activePane="bottomRight" state="frozen"/><selection pane="bottomRight" …/></sheetView>`. The renderer has 4 quadrants with independent scrolling. | M | Y | M |
| 13.2 | Split / تقسيم | Splits the window into 2 or 4 panes that scroll on their own (no freezing). | `state="split"` with `xSplit` in twips (1/20 pt) instead of cell count. | M | Y | D (tablet candidate) |
| 13.3 | Gridlines / خطوط الشبكة | Show or hide on screen (separate from printing). | `<sheetView showGridLines="0">`. | S | Y | M |
| 13.4 | Headings / العناوين | Show or hide row and column headers. | `showRowColHeaders="0"`. | S | Y | M |
| 13.5 | Formula bar show/hide | | App setting. | S | Y | M |
| 13.6 | Zoom / تكبير | Zoom to selection, 100%. | `zoomScale`, `zoomScaleNormal`, `zoomScalePageLayoutView`. | S | Y | M |
| 13.7 | Workbook views: Normal, Page Break Preview, Page Layout (عادي، معاينة فواصل الصفحات، تخطيط الصفحة) | Page Break Preview: blue page borders that can be dragged to move breaks. Page Layout: shows the paper with header and footer. | `<sheetView view="pageBreakPreview">`, `<rowBreaks><brk id="20" max="16383" man="1"/>`. | M | Y | D |
| 13.8 | Sheet View (co-authoring) | Temporary personal views (sort and filter). | Keep `xl/namedSheetViews`. | S (keep) | Y | D |
| 13.9 | New Window / Arrange / Side-by-side | Multiple windows of one workbook. Android tablets: split screen with two app instances. | `FLAG_ACTIVITY_LAUNCH_ADJACENT`. | M | Y | D |
| 13.10 | Hide / Unhide workbook windows | | `<workbookView visibility="hidden">`. | S | Y | D |
| 13.11 | Macros (view / run) | Not on mobile. Keep `vbaProject.bin` in `.xlsm`. Show a "This file contains macros that cannot run on this device" banner. | Keep the binary part and its content type. | S | Y | D |
| 13.12 | Show page breaks in Normal view | | | S | Y | D |
| 13.13 | Focus cell (365, 2024) | Highlights the active row and column (a crosshair) to make reading easier. | UI only. | S | Y | D |
| 13.14 | Navigation pane (365) | A tree of sheets, tables, charts, named ranges and pictures. | Mobile equivalent: a "Workbook items" bottom sheet. | M | Y | D |

---

## 14. Page Layout, Print and Export to PDF / تخطيط الصفحة والطباعة والتصدير

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 14.1 | Print / Export dialog (Excel Android) / طباعة | File > Print (Android) gives the Layout Options page: **What to print**: Active Sheet, Entire Workbook, Selection. **Paper size**: Letter, Legal, A3, A4, A5, B4, B5… **Orientation**: Portrait or Landscape. **Scaling**: No scaling, Fit sheet on one page, Fit all columns on one page, Fit all rows on one page. **Gridlines**: on or off. **Headings**: on or off (some builds). Then **Print** (system dialog) or **Save as PDF**. | Write the PDF ourselves with `android.graphics.pdf.PdfDocument` (vector text, embedded fonts) and send it to `PrintManager`. Paging algorithm: a list of columns per page width and rows per page height, respecting manual breaks, print titles, scaling `fitToWidth`/`fitToHeight`. The page order (down then over, or over then down) follows `pageSetup pageOrder`. | L | Y | M |
| 14.2 | Margins / الهوامش | Normal, Wide, Narrow, Custom (top, bottom, left, right, header, footer). Centre horizontally or vertically. | `<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>` (inches), `<printOptions horizontalCentered="1"/>`. | S | Y | M\* |
| 14.3 | Page Setup (page) | Orientation, paper size, scale %, Fit to W pages wide by H tall, first page number, print quality. | `<pageSetup paperSize="9" orientation="landscape" scale="85" fitToWidth="1" fitToHeight="0" firstPageNumber="1" useFirstPageNumber="1"/>` + `<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>`. paperSize ids: 1 Letter, 5 Legal, 8 A3, 9 A4, 11 A5. | M | Y | M |
| 14.4 | Print Area / ناحية الطباعة | Set, Clear, Add to print area (several areas are printed on separate pages). | Defined name `_xlnm.Print_Area` with `localSheetId`. | S | Y | M\* |
| 14.5 | Print Titles / طباعة العناوين | Rows to repeat at top, columns to repeat at left. | `_xlnm.Print_Titles` = `Sheet1!$1:$2,Sheet1!$A:$A`. | S | Y | D |
| 14.6 | Breaks / فواصل الصفحات | Insert or remove a page break; reset all. | `<rowBreaks>`, `<colBreaks>`. | S | Y | D |
| 14.7 | Header & Footer / الرأس والتذييل | Left, centre and right sections. Codes: `&P` page, `&N` pages, `&D` date, `&T` time, `&F` file, `&A` sheet, `&Z` path, `&G` picture, `&"font,Bold"`, `&12` size. Different first page, different odd and even, scale with document, align with margins. Presets ("Page 1 of ?"). | `<headerFooter differentFirst="1"><oddHeader>&amp;C&amp;"-,Bold"Report&amp;R&amp;D</oddHeader>`. Picture headers use `legacyDrawingHF`. | M | Y | D |
| 14.8 | Print options | Gridlines, Row and column headings, Black and white, Draft quality, Comments (None, At end of sheet, As displayed), Cell errors as (displayed, blank, `--`, `#N/A`). | `<printOptions gridLines="1" headings="1"/>`, `pageSetup blackAndWhite cellComments errors`. | S | Y | M\* |
| 14.9 | Themes / السمات | Change the theme (colours, fonts, effects). Changes every cell that uses theme colours. | Replace `xl/theme/theme1.xml`. The renderer resolves theme indices dynamically. | M | Y | D |
| 14.10 | Background picture | Sheet background image (screen only, not printed). | `<picture r:id/>`. | S | Y | D |
| 14.11 | Export formats / تصدير | PDF, CSV (UTF-8 with BOM; Windows-1256 option for Arabic legacy), ODS, XLSX, HTML (WPS), image (PNG of a range or sheet: WPS "Share as picture"). | Writers: CSV is trivial. ODS is M. PNG of a sheet uses the tile renderer at print DPI. | M | Y | M (PDF, CSV), W (image) |
| 14.12 | Print preview / معاينة الطباعة | Page thumbnails before printing, with page navigation. | Render the PDF pages with `PdfRenderer`. | S | Y | M |

---

## 15. Sheet tabs / علامات تبويب الأوراق

| # | Feature (EN / AR) | Behaviour / options | OOXML / implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 15.1 | Add sheet (+) / إضافة ورقة | Adds "Sheet2" (the next free number). In Arabic UI: "ورقة2". | New `xl/worksheets/sheetN.xml`, a rel, `<sheet name="Sheet2" sheetId="2" r:id="rId5"/>`, the content type override, and `docProps/app.xml` TitlesOfParts (optional, keep it consistent). | S | Y | M |
| 15.2 | Rename / إعادة تسمية | Long-press the tab > Rename. Rules: 1-31 characters; not `\ / ? * [ ] :`; cannot start or end with `'`; unique (case-insensitive); not "History". **All formulas referencing the sheet are updated**, with quoting when needed (`'My Sheet'!A1`). | Rewrite references in formulas, names, charts, DV, CF and hyperlinks. | M | Y | M |
| 15.3 | Duplicate / تكرار | Creates "Sheet1 (2)" with everything copied: formulas, formatting, CF, DV, comments, drawings, charts (charts are deep-copied with their series pointing to the new sheet), tables (renamed Table1_2), sheet-scoped names. | Deep copy of the part tree with new relationship ids. | L | Y | M |
| 15.4 | Move / reorder / نقل | Drag the tab, or use "Move left / right". | Reorder `<sheets>`. Note: `localSheetId` in defined names is an **index** and must be renumbered. 3D references move with the sheets. | M | Y | M |
| 15.5 | Delete / حذف | Confirmation. References become `#REF!`. Cannot delete the last visible sheet. | Remove the parts and rels. Turn references to `#REF!`. | M | Y | M |
| 15.6 | Hide / Unhide / إخفاء / إظهار | Unhide lists the hidden sheets. | `state="hidden"`. | S | Y | M |
| 15.7 | Tab colour / لون علامة التبويب | Palette. | `<sheetPr><tabColor rgb="FFFF0000"/></sheetPr>`. | S | Y | M |
| 15.8 | Select multiple sheets (group editing) | Desktop: Ctrl+click tabs so edits apply to all of them. | | M | Y | D |
| 15.9 | Sheet list (navigator) | On phones: a button that lists all sheets for fast switching (WPS and Sheets have this). | | S | Y | M/W |
| 15.10 | Protect sheet from tab menu | 12.3. | | | | M |

---

## 16. AI: Copilot, Analyze Data and equivalents / الذكاء الاصطناعي

| # | Feature (EN / AR) | Behaviour (Excel 365) | Implementation for our app | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 16.1 | Copilot chat about the workbook / مساعد الذكاء الاصطناعي | Microsoft 365 Copilot (with a licence): summarise the data, answer questions, suggest formulas and formula columns, highlight data (CF), sort and filter, make charts and PivotTables, "Explain this formula", Think Deeper / Agent Mode on desktop (multi-step edits). On Android: a Copilot button in the app or the M365 Copilot app opening the file. | An LLM function-calling agent with tools such as `get_range`, `set_formula`, `add_cf_rule`, `create_chart`, `sort`, `filter`, `create_table`. **Every AI edit goes through the same undo-able command layer and shows a preview before applying.** Send only a schema plus a sample of rows, never the whole file, unless the user agrees. | L | N | M (licensed) |
| 16.2 | Formula suggestions / Explain formula / شرح الصيغة | Natural language to a formula ("sum of sales for Sana'a in March") and a formula to a plain-language explanation. | A small on-device model could explain simple formulas; generating formulas needs a cloud LLM. **Always validate the AI formula with our parser before inserting it.** | M | P | M |
| 16.3 | COPILOT() function (2025, 365 Insider) | `=COPILOT("classify sentiment", A2:A50)`: AI inside a formula, recalculated on demand, not volatile. | Optional: an `AI()` function with results cached in the file as values. | M | N | D |
| 16.4 | Analyze Data / Ideas / تحليل البيانات | 8.15. | | L | P | M |
| 16.5 | Google Sheets "Help me organize", Gemini in Sheets, "Smart Fill", "Smart Cleanup" | Smart Fill: autocompletes a column from detected patterns (the same as Flash Fill). Cleanup suggestions: 8.16. | | | | G |
| 16.6 | WPS AI | Table generation, formula help, data summaries. | | | N | W |
| 16.7 | Data from Picture (AI OCR) | 4.15. | | L | P | M |

---

## 17. Collaboration / التعاون

| # | Feature | Behaviour | Implementation | Diff. | Off. | Plat. |
|---|---|---|---|---|---|---|
| 17.1 | Real-time co-authoring | Several people edit a OneDrive/SharePoint workbook at once, with coloured cell flags for each person. | Needs a sync service (OT or CRDT over cell operations). Out of scope for offline v1. A possible later design: a Yjs-like CRDT keyed by cell id. | L | N | M |
| 17.2 | Share / invite / permissions | Can edit / Can view, link settings. | Cloud backend. Offline: share the file. | M | N | M |
| 17.3 | @mentions in comments | Mention a person, who gets an email notification. | Cloud only. | M | N | M |
| 17.4 | Activity / Show Changes | | 12.11. | | | D |
| 17.5 | Sync with SAF cloud providers | Edit a file from Google Drive or OneDrive through Storage Access Framework, with the provider handling upload. | `ACTION_OPEN_DOCUMENT` + persisted permissions + write-back. | S | P | — |

---

## 18. Accessibility and localisation / إمكانية الوصول والتعريب

| # | Feature | Implementation | Diff. |
|---|---|---|---|
| 18.1 | TalkBack on the grid: reads the cell address, value, formula, comment presence and header labels. | Compose `semantics` on a virtual node tree for the visible cells (`AccessibilityNodeProvider`-like via `Modifier.semantics` with collectionInfo / collectionItemInfo). | L |
| 18.2 | Large-text scaling of UI chrome (not the grid). | `sp` units in the chrome. The grid keeps its own zoom. | S |
| 18.3 | High-contrast cell selection colours. | Theme tokens. | S |
| 18.4 | Full Arabic UI + RTL layout mirroring. | `LayoutDirection.Rtl`. Translate all strings (the Arabic labels in this spec). | M |
| 18.5 | Arabic-Indic digits (٠١٢٣) in display as an option. | Number formatter digit substitution (`[$-2000000]`, or an app-level option that never changes the file). | S |
| 18.6 | Locale-aware list separator and decimal separator in formulas. | Display-layer conversion (9.1.9). | M |

---

## 19. Features from Google Sheets mobile and WPS Spreadsheets mobile that Excel Android lacks

### 19.1 Google Sheets (Android) / جداول بيانات Google

| # | Feature (EN / AR) | What it does | Fit for our app | Diff. | Off. |
|---|---|---|---|---|---|
| G1 | Checkboxes in cells / خانات اختيار | Insert > Checkbox, tap to toggle. TRUE/FALSE value with custom checked/unchecked values. | High (excellent for to-do lists, attendance). 4.17. | M | Y |
| G2 | Dropdown chips with coloured options / قوائم منسدلة ملونة | Each option gets a colour, shown as a chip. Multi-select dropdowns (2024). | High. Store as DV list + CF rules per option so Excel shows the colours too. | M | Y |
| G3 | Smart chips (people, dates, files, places, finance) | Rich linked entities in cells. | Low for offline; a date chip with a calendar picker is useful. | M | P |
| G4 | Date picker on date cells / منتقي التاريخ | Double-tap a date cell to open a calendar picker. | High. Show it for cells with a date format or a date DV rule. Include a Hijri calendar option. | S | Y |
| G5 | Explore / column stats / إحصائيات العمود | Distribution histogram, frequency, sum/avg/min/max, unique count per column. | High (a "Column summary" bottom sheet). | M | Y |
| G6 | Filter views / عروض التصفية | Personal filters. | Medium. 7.6. | M | Y |
| G7 | Conditional formatting with a custom formula on mobile | Sheets mobile allows custom-formula CF. Excel Android does not. | High. 3.5.6. | M | Y |
| G8 | Data validation authoring on phone | Full DV editor on mobile. | High. 8.4. | M | Y |
| G9 | Offline mode with automatic sync | | Our app is offline-first by design. | — | Y |
| G10 | Version history with named versions | Named versions, restore, see who edited. | Medium (local snapshots, 1.5). | M | Y |
| G11 | Split text to columns on mobile; Trim whitespace; Remove duplicates | | High. 8.5. | M | Y |
| G12 | Functions Excel lacks: GOOGLEFINANCE, GOOGLETRANSLATE, IMPORTRANGE, IMPORTHTML, QUERY, SPARKLINE (function), REGEXMATCH, ARRAYFORMULA, SPLIT, JOIN, UNIQUE (older), DETECTLANGUAGE | `QUERY` (an SQL-like language over a range) is the most valuable. `SPARKLINE()` as a function. | Optional "compat" functions. When saving to xlsx, they would be `#NAME?` in Excel, so warn the user, or store the results as values. | L | P |
| G13 | Tables (2024) with column types (dropdown, date, checkbox, people) and group-by views | | Medium (typed columns in tables + card view). | M | Y |
| G14 | Timeline view | Data shown as a timeline. | Low. | L | Y |
| G15 | Pivot tables on mobile (create and edit) | Sheets mobile can create pivots; Excel Android cannot. | High value, hard. 4.2 phase 2. | L | Y |
| G16 | Gemini side panel | AI help, formula generation, table creation. | 16.x. | L | N |

### 19.2 WPS Spreadsheets (Android) / WPS جداول البيانات

| # | Feature (EN / AR) | What it does | Fit for our app | Diff. | Off. |
|---|---|---|---|---|---|
| W1 | Share as long image / مشاركة كصورة | Exports a sheet or a range as a long PNG to share in messaging apps. | **Very high** (WhatsApp sharing is common in Yemen). 14.11. | M | Y |
| W2 | Built-in PDF tools: export to PDF, PDF to Excel conversion, OCR | | High (4.16). | L | P |
| W3 | Reading mode / eye-protection mode / وضع القراءة / حماية العين | Highlights the row and column of the tapped cell (a crosshair) + a warm-tint night mode. | High (13.13). | S | Y |
| W4 | Mobile-optimised input panel | A big data-entry panel with keyboard shortcuts, a "next cell" direction toggle (down or right), a dedicated formula keyboard with common functions (SUM, AVERAGE, COUNT, MAX, MIN, IF) on one row. | High. Improves 2.6. | M | Y |
| W5 | Tools menu: Highlight duplicates, Remove duplicates, Reject duplicate entries, Fill blank cells, Convert text to number, Split column, Merge columns (with separator), Insert multiple rows at once, Freeze shortcuts, Batch rename sheets | Several one-tap data cleanup tools. | High (a "Tools" / أدوات section). | M | Y |
| W6 | Merge / split workbooks and sheets | Combine several files or sheets into one; split one sheet into files by a column value. | Medium. | M | Y |
| W7 | Templates store (online) | Many templates. | Medium (local templates, 1.2). | S | P |
| W8 | Document encryption and file-level password | WPS sets a file password on mobile; Excel Android cannot. | Medium (12.6). | L | Y |
| W9 | Projection / presentation of a sheet; Wi-Fi share | | Low. | M | Y |
| W10 | Pivot table creation on mobile | | 4.2. | L | Y |
| W11 | Chart types on mobile with full editing of data labels and axis | | 11.2. | | Y |
| W12 | Mobile "Screenshot of selected area" with watermark | Range screenshot, option to add a watermark. | Medium (combined with W1). | S | Y |
| W13 | Paste as rows from an external text list (paste into multiple cells by line) | | High (2.4 clipboard parsing). | S | Y |
| W14 | ID-number tools for China (age from ID, gender from ID) | Region-specific formula helpers. | Idea for us: a local "Common formulas" helper: grade from score (IF/IFS), age from birth date (DATEDIF), rank (RANK.EQ), attendance %. Useful for schools. | S | Y |

---

## 20. OOXML package architecture for the native editor (summary)

| Part | Purpose | Reader / writer notes |
|---|---|---|
| `[Content_Types].xml` | MIME type for every part | Must list each new part (Override) or extension (Default). |
| `_rels/.rels`, `xl/_rels/workbook.xml.rels`, `xl/worksheets/_rels/sheetN.xml.rels`, `xl/drawings/_rels/…` | Relationship graph | Keep the rIds stable. Create new ids as `rId{max+1}`. |
| `xl/workbook.xml` | Sheets list, defined names, calcPr, workbookPr (date1904), bookViews (activeTab), workbookProtection | |
| `xl/worksheets/sheetN.xml` | sheetPr (tabColor, outlinePr, pageSetUpPr), dimension, sheetViews (pane, selection, zoom, RTL, gridlines), sheetFormatPr, cols, **sheetData** (rows > cells: `r`, `s` style, `t` type: n, s (shared string), str (formula string), inlineStr, b, e; `<f>`, `<v>`, `<is>`), sheetProtection, autoFilter, sortState, mergeCells, conditionalFormatting, dataValidations, hyperlinks, printOptions, pageMargins, pageSetup, headerFooter, rowBreaks, colBreaks, drawing, legacyDrawing, tableParts, extLst | **Element order is fixed by the schema (CT_Worksheet sequence).** Writing them out of order makes Excel report a corrupt file. Keep a writer that emits in the schema order. Stream rows with a SAX writer. |
| `xl/sharedStrings.xml` | Unique strings (`<sst count uniqueCount><si>`) | Intern on load and rebuild on save (drop unused strings). Rich text runs in `<r>`. `xml:space="preserve"` for leading and trailing spaces. Escape control characters `_x000D_`. |
| `xl/styles.xml` | numFmts, fonts, fills, borders, cellStyleXfs, cellXfs, cellStyles, dxfs, tableStyles, colors (indexed palette override), extLst | Indexed colour palette (64 legacy colours) must be supported for old files. |
| `xl/theme/theme1.xml` | Colours and fonts | Read-only in v1. |
| `xl/calcChain.xml` | Calc order | Drop it on save after formula edits. |
| `xl/metadata.xml` | Dynamic-array cell metadata | Needed for spills (10.5). |
| `xl/tables/tableN.xml` | Tables | Section 6. |
| `xl/drawings/drawingN.xml` + `xl/media/*` + `xl/charts/chartN.xml` (+ style, colors) + `chartExN.xml` | Floating objects | Anchors: twoCellAnchor (editAs = twoCell, oneCell, absolute), oneCellAnchor, absoluteAnchor. EMU units. |
| `xl/comments/commentsN.xml` (`xl/commentsN.xml`), `xl/drawings/vmlDrawingN.vml`, `xl/threadedComments/*`, `xl/persons/person.xml` | Notes and comments | 4.11-4.12. |
| `xl/pivotTables`, `xl/pivotCache`, `xl/slicers`, `xl/slicerCaches`, `xl/timelines`, `xl/connections.xml`, `xl/queryTables`, `xl/externalLinks`, `xl/vbaProject.bin`, `xl/richData`, `xl/featurePropertyBag`, `customXml/*` | Advanced / unsupported | **Keep them byte-for-byte** and keep their relationships. If a sheet that a pivot points at is renamed, rewrite the reference in the pivotCacheDefinition `worksheetSource sheet=` (simple text update). |
| `docProps/core.xml`, `app.xml`, `custom.xml` | Metadata | Update modified date and lastModifiedBy. |

**Memory model for cells (Kotlin):** per sheet, a map from row index to row object, where each row is a sorted array of column indexes plus parallel arrays (or packed `LongArray`) holding the value type, value payload index, style index and formula id. Strings are interned. Expected cost: about 16-24 bytes per filled cell, so a 1M-cell workbook needs about 24 MB.

**Large files:** parse sheet XML with `XmlPullParser` (Android's KXml is fast), in a streaming pass on a background thread with progress. Show the first screen as soon as the first ~200 rows are parsed.

**Validation:** after saving, re-open our own output in a test harness and compare it against the OOXML schema (XSD validation in CI with the ECMA-376 transitional schemas) plus an "Excel opens without repair" manual test matrix.

---

## 21. Suggested delivery phases

| Phase | Content |
|---|---|
| **P1: MVP (viewer plus core editing)** | 1.1-1.4, 1.6-1.9, 2.1-2.7, 2.12-2.14, Home: 3.1-3.4 (+ number formats), 3.3.7 merge, 3.6.1-3.6.5, 3.7.1, 3.7.3-3.7.5, sheet tabs 15.1-15.7, freeze 13.1, gridlines and headings 13.3-13.4, formula engine with the P1 functions (about 110), AutoComplete, comments and notes (view/add), hyperlinks, print / PDF 14.1-14.4 and 14.12, CSV import and export, keeping every unknown part. |
| **P2: Excel Android parity** | Conditional formatting (all presets + formula), tables + structured references, sort/filter full, data validation (author + enforce), remove duplicates, charts (P1 types + elements), pictures, shapes, text boxes, protect sheet and workbook, dynamic arrays and the P2 functions, Draw tab (ink), Data from Picture (OCR), find/replace across the workbook, card view, sparklines (render). |
| **P3: Beyond Excel Android** | Pivot creation, Text to Columns, Flash Fill, Goal Seek, chartEx types, header and footer editor, Page Break Preview, Split view, trace precedents, Evaluate Formula, WPS tools (W1, W3, W4, W5), Google extras (G1, G2, G4, G5, G7), AI assistant (16.1-16.2), Hijri formats, encryption on write. |

---

## 22. Counts

- Feature rows in sections 1-18: about **260** (238 numbered rows + 23 chart type/tool rows) (Shell 15, Grid 17, Home 41, Insert 22, Draw 4, Tables 10, Sort/Filter 7, Data 17, Formulas UI and auditing 27, Charts 26 (types + tools), Review 12, View 14, Page Layout/Print 12, Sheet tabs 10, AI 7, Collaboration 5, Accessibility 6).
- Functions listed: about **220**, of which about **150** are P1/P2.
- Competitor-only features: **16** Google Sheets + **14** WPS = **30**.
- Platform marks: about 60% M/M\* (in Excel Android today), about 35% D (desktop to bring to mobile), the rest G/W.
