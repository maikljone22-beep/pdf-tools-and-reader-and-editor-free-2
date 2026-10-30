# Microsoft PowerPoint for Android: Feature Specification and Native .pptx Implementation Guide

> Scope: PowerPoint inside the Microsoft 365 (Office) Android app, current as of October 2026, plus desktop PowerPoint features that would work on a phone or tablet, plus features from Google Slides (Android) and WPS Presentation (Android) that PowerPoint mobile lacks.
> Audience: engineers building a native Android Kotlin/Jetpack Compose slides editor that reads and writes `.pptx` (OOXML / ECMA-376, PresentationML) directly.
> Language: English, with an Arabic UI label for every feature (RTL-first product).

---

## 0. Legend, conventions and sources

### 0.1 Column legend

| Column | Meaning |
|---|---|
| **ID** | Stable feature ID, `AREA-NN`, for backlog tracking |
| **Feature (EN / AR)** | English name / suggested Arabic UI label |
| **Location** | Where it lives in PowerPoint Android (phone: bottom sheet ribbon opened with the `^` / pencil button; tablet: top ribbon) |
| **Behaviour & options** | What it does and which options it exposes |
| **Impl. note** | OOXML parts/elements touched and the Compose rendering/editing approach |
| **Diff** | S = under 3 dev-days, M = 1 to 3 weeks, L = more than 3 weeks or research-heavy |
| **Off** | Feasible fully offline: Y / P (partial; e.g. needs on-device model or cached assets) / N (needs cloud) |
| **Plat** | M = available in PowerPoint Android; D = desktop-only (Windows/Mac/Web) and proposed for mobile; M* = mobile, but limited vs desktop; GS = Google Slides; WPS = WPS Office |

### 0.2 Phone vs tablet UI model in PowerPoint Android

- **Phone**: View mode by default (read-only, swipe slides). Tap the **Edit (pencil)** icon to enter edit mode. A bottom **command bar** shows contextual quick actions; the `^` (Show commands) button opens a bottom sheet holding the ribbon. A dropdown at the top of the sheet switches tabs (Home, Insert, Draw, Design, Transitions, Animations, Slide Show, Review, View). Contextual tabs (Shape, Picture, Table, Video, Audio, Ink, Chart) appear automatically when an object is selected.
- **Tablet (7 in. and larger)**: a desktop-like ribbon with tab headers across the top, a thumbnail rail on the left, and the notes pane below the slide.
- **Floating menu on selection**: Cut, Copy, Paste, Delete, Edit Text, Duplicate (slides), Hide, Comment, Link, Alt Text, Add Animation, and similar commands, depending on the object type.
- **Slide thumbnails**: on phones, a horizontal filmstrip under the slide; on tablets, a vertical rail on the left. Long-press a thumbnail to Cut, Copy, Paste, Duplicate, Delete, Hide, Add Section, Comment or Move. Drag a thumbnail to reorder.

### 0.3 Sources consulted (Microsoft support and press)

- Add animation effects in PowerPoint on a mobile device: https://support.microsoft.com/en-us/office/add-animation-effects-in-powerpoint-on-a-mobile-device-f8bbbb00-06dc-4b3c-8c0a-7e7f17df6e42 (confirms entrance/exit, effect options for direction and sequence, multiple animations per object; **no sound effects and no motion paths on mobile**; mobile can play every desktop animation but cannot insert all of them)
- Use the Morph transition in PowerPoint for Android: https://support.microsoft.com/en-gb/office/use-the-morph-transition-in-powerpoint-for-android-63633bfd-071d-41ef-bb97-d5ce7293f375 (Transitions > Transition Effects > Morph; Effect Options: Objects / Words / Characters)
- Add, change, or remove transitions between slides: https://support.microsoft.com/en-us/office/add-transitions-between-slides-e89a076e-ed81-404e-9598-021a918fa1ba
- Copilot in PowerPoint for mobile devices: https://support.microsoft.com/en-us/office/copilot-in-powerpoint-for-mobile-devices-7b3ce1ab-cfe4-47e8-a157-cecadbf0fefb (on Android: "Show key slides", suggested Q&As and free questions about a shared deck; creation and Get Summary are documented for iPad, Mac, Windows and Web)
- Present a slide show from a smartphone and use a laser pointer: https://support.microsoft.com/en-US/PowerPoint/present-a-slide-show-from-a-smartphone-and-use-a-laser-pointer-for-emphasis (hold finger for laser dot, drag in the notes area in presenter mode, gyroscope pointer on some devices)
- Rehearse your slide show with Speaker Coach: https://support.microsoft.com/en-gb/office/rehearse-your-slide-show-with-speaker-coach-cd7fc941-5c3b-498c-a225-83ef3f64f07b ; Presenter Coach on Android (Android Police, June 2020): https://www.androidpolice.com/2020/06/17/microsoft-brings-powerpoint-presenter-coach-to-android/
- The difference between animations and transitions: https://support.microsoft.com/en-us/office/the-difference-between-animations-and-transitions-bae174a4-dad3-4268-bf9c-c201a70995f1
- ECMA-376 Part 1 (PresentationML, DrawingML), 5th edition, and the MS-PPTX extensions (p14/p15/p159 namespaces for newer transitions, Morph `p159:morph`, and so on).

> Uncertainty: Microsoft ships PowerPoint mobile as a monthly build, and some galleries differ by device size, account type (personal, work or school, unsigned) and region. Items that could not be confirmed against a support article are tagged **[verify on device]**.

### 0.4 Account and licensing gates in PowerPoint Android (for competitive context)

| Tier | What it unlocks |
|---|---|
| Unsigned | View only (on large screens) or limited editing on phones under about 10.1 in. |
| Free Microsoft account (screens up to 10.1 in.) | Core editing: text, shapes, pictures, transitions, basic animations, themes |
| Microsoft 365 Personal/Family/Business | Premium items: some Designer ideas, Stock images/icons/3D, advanced ink, Copilot (where licensed), Presenter Coach extras, larger cloud storage, editing on tablets over 10.1 in. |

**Opportunity for our app:** everything in sections 1 to 14 can be built offline with no account wall. That is the main way to beat PowerPoint for students in Yemen, where bandwidth is constrained.

---

## 1. App shell, file handling and document lifecycle

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| SHELL-01 | Open .pptx / فتح عرض تقديمي | Home list, Open, OS share intent, file manager | Opens .pptx, .ppt (legacy, converted), .ppsx (opens directly into the show), .potx (template, opens as a new copy), .odp (read and edit, with fidelity warning) | Zip (OPC) reader: `[Content_Types].xml`, `_rels/.rels`, `ppt/presentation.xml` and its rels. Lazy-parse slides with StAX/XmlPullParser; parse only the visible slide plus its neighbours. Legacy .ppt needs a binary (MS-PPT) converter; consider Apache POI HSLF on a background thread, or "view only" | L | Y | M |
| SHELL-02 | View mode vs Edit mode / وضع العرض / وضع التحرير | Pencil icon in the top bar | Read-only reading view with swipe paging; Edit unlocks the ribbon and selection. A "Read-only recommended" flag prompts the user | `p:modifyVerifier` and `ReadOnlyRecommended` in `docProps/app.xml` / `presProps`. Track mode in the ViewModel; disable hit-testing for editing in view mode | S | Y | M |
| SHELL-03 | AutoSave / حفظ تلقائي | Toggle in File | Continuous save for cloud files; for local files, save on pause or exit | Write to a temp zip, fsync, then atomic rename. Keep untouched parts byte-identical (copy raw zip entries) to preserve unknown XML and extensions | M | Y | M |
| SHELL-04 | Save As / Save a Copy / حفظ باسم / حفظ نسخة | File > Save a Copy | Choose location (device, OneDrive, SharePoint, other providers through SAF), name and format | Android Storage Access Framework (`ACTION_CREATE_DOCUMENT`). Keep format conversion separate | S | Y | M |
| SHELL-05 | Recent, Pinned, Shared with me / الأخيرة، المثبّتة، المشتركة معي | Home | MRU list with thumbnails; pin files; shared list needs cloud | Room DB of MRU entries; thumbnail from `docProps/thumbnail.jpeg` or a render of slide 1 | S | P | M |
| SHELL-06 | Version history / سجل الإصدارات | File > Version History (cloud files) | Restore earlier versions | Local: keep N snapshots (diff or full zip) in app storage. Cloud: provider API | M | P | M |
| SHELL-07 | Password-protected files / ملفات محمية بكلمة مرور | On open | Prompts for password; cannot set an open password on mobile **[verify on device]** | MS-OFFCRYPTO Agile Encryption (`EncryptionInfo` and `EncryptedPackage` in a CFB container). Decrypt with AES and SHA-512 key derivation. Setting a password is a desktop feature; propose it for mobile | L | Y | M*/D |
| SHELL-08 | Mark as Final / Read-only / وضع علامة كنهائي | Banner when opened | Shows "Marked as Final" with Edit Anyway | `docProps/custom.xml` property `_MarkAsFinal` | S | Y | M |
| SHELL-09 | Recover unsaved / استعادة | On crash or relaunch | Restores last session edits | Periodic serialisation of the edit journal (command log) to app storage | M | Y | D→M |
| SHELL-10 | Undo / Redo / تراجع / إعادة | Top bar | Multi-level undo of all edits | Command pattern over an immutable document model (persistent data structures); undo stack of around 100 entries; group gestures (one drag = one command) | M | Y | M |
| SHELL-11 | Dark mode canvas / الوضع الداكن | Settings, Theme | App chrome goes dark; slide content keeps its original colours (optional "dark canvas" for notes) | Material 3 dynamic theme; never alter slide colours | S | Y | M |
| SHELL-12 | Search in app (Tell me) / بحث عن الأوامر | Lightbulb or search icon | Search for commands by name and run them | Static index of command IDs to Arabic and English synonyms | S | Y | M |
| SHELL-13 | Fidelity warnings / تحذيرات التوافق | Banner | "Some features aren't supported in this version" for unsupported content (for example, embedded OLE edit, VBA macros, ActiveX) | Preserve unknown parts (`vbaProject.bin`, `oleObject`, `activeX`) untouched in round-trip; render the OLE fallback image (`p:oleObj/p:pic`) | M | Y | M |
| SHELL-14 | Open from ZIP/email attachment / فتح من المرفقات | Intent filters | Opens from Gmail, WhatsApp, Telegram, and other apps | Intent filters for MIME types `application/vnd.openxmlformats-officedocument.presentationml.*` and `application/vnd.ms-powerpoint` | S | Y | M |

---

## 2. Home tab (الصفحة الرئيسية)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| HOME-01 | Clipboard: Cut / Copy / Paste / قص / نسخ / لصق | Floating menu; Home on tablet | Works on objects, text and slides. Paste options include Keep Source Formatting, Use Destination Theme and Picture (tablet and desktop) | Internal clipboard holds the DrawingML XML fragment and its related parts (images, media). System clipboard gets plain text and a PNG render. Remap rIds on paste; dedupe media by SHA-1 | M | Y | M |
| HOME-02 | Format Painter / نسخ التنسيق | Home (tablet) **[verify on device]**; desktop Home | Copy formatting from one object or text run to another; double-tap to keep it active | Copy `a:rPr` / `a:pPr` (text) or `p:spPr` + `p:style` (shape) | S | Y | D/M* |
| HOME-03 | New Slide (layout picker) / شريحة جديدة | Home > New Slide; `+` on the filmstrip | Gallery of the master's layouts: Title Slide, Title and Content, Section Header, Two Content, Comparison, Title Only, Blank, Content with Caption, Picture with Caption, plus custom layouts. The new slide is inserted after the current one | Create `ppt/slides/slideN.xml` with `p:sld` → `p:cSld/p:spTree` containing placeholder `p:sp` with `p:nvPr/p:ph type=… idx=…` (no spPr, so it inherits). Add a rel to `slideLayoutM.xml`, a `p:sldId` (id ≥ 256, unique) in `presentation.xml` `p:sldIdLst` with an r:id, a content-type override and a presentation rel | M | Y | M |
| HOME-04 | Change Layout / تخطيط الشريحة | Home > Layout | Reapply another layout to the selected slide(s); content moves into matching placeholders | Change the slide's layout rel target; remap placeholders by `type`/`idx`; content without a match stays as-is | M | Y | M |
| HOME-05 | Reset slide / إعادة تعيين الشريحة | Home > Reset (desktop; tablet **[verify on device]**) | Resets placeholder position, size and formatting to the layout | Strip `p:spPr/a:xfrm` and local formatting from placeholder shapes | S | Y | D/M* |
| HOME-06 | Duplicate Slide / تكرار الشريحة | Long-press thumbnail > Duplicate | Copies the slide after the current one, including notes and comments **[verify on device]** | Deep-copy the slide part and its rels (media shared by rel, not by file); new `sldId`; copy `notesSlide` too | S | Y | M |
| HOME-07 | Delete Slide / حذف الشريحة | Thumbnail menu | Removes the slide or slides | Remove from `sldIdLst`; garbage-collect orphaned parts and media on save | S | Y | M |
| HOME-08 | Hide Slide / إخفاء الشريحة | Thumbnail menu; Slide Show tab | Skipped during the show; thumbnail greyed out with a slashed number | `<p:sld show="0">` | S | Y | M |
| HOME-09 | Reorder slides (drag) / إعادة ترتيب الشرائح | Filmstrip drag; Slide Sorter | Drag to move; multi-select move | Reorder `p:sldId` entries only (no file renaming needed) | S | Y | M |
| HOME-10 | Sections / المقاطع | Thumbnail menu > Add Section (tablet); desktop Home > Section | Add, rename, collapse, move and remove sections | `p:extLst/p:ext uri="{521415D9-36F7-43E2-AB2F-B90AF26B5E84}"` → `p14:sectionLst/p14:section name id` → `p14:sldIdLst/p14:sldId id` | M | Y | M*/D |
| HOME-11 | Font family / نوع الخط | Home > Font | Theme fonts (+Headings / +Body) shown first, then installed and cloud fonts; recently used | `a:rPr/a:latin typeface`, `a:ea`, `a:cs` (**Arabic uses `a:cs`**), theme references `+mj-lt`, `+mn-lt`, `+mj-cs`. Text shaping with HarfBuzz (via Android `TextLayout` / `Paint` / Compose `TextMeasurer`). Bundle Arabic fonts: Noto Naskh/Kufi, Cairo, Tajawal, Amiri | M | Y | M |
| HOME-12 | Font size, grow, shrink / حجم الخط / تكبير / تصغير | Home | Size list from 8 to 96 plus free entry; A+ / A- steps | `a:rPr sz` in hundredths of a point (1800 = 18 pt). Respect `a:bodyPr/a:normAutofit fontScale lnSpcReduction` when rendering | S | Y | M |
| HOME-13 | Bold / Italic / Underline / Strikethrough / عريض / مائل / تسطير / يتوسطه خط | Home | Toggle buttons; underline styles (single, double, heavy, dotted, dashed, wavy) | `b="1" i="1" u="sng|dbl|heavy|dotted|dash|wavy…" strike="sngStrike|dblStrike"` | S | Y | M |
| HOME-14 | Superscript / Subscript / مرتفع / منخفض | Home > More font | X² and X₂ | `baseline="30000"` / `"-25000"` | S | Y | M |
| HOME-15 | Font colour / Highlight / لون الخط / تمييز النص | Home | Theme palette (10 theme colours × tints/shades), standard colours, more colours, eyedropper (desktop); highlight colours | `a:solidFill/a:schemeClr val="accent1"` + `a:lumMod/a:lumOff`, `a:srgbClr`; highlight `a:highlight` (a newer PowerPoint feature, so check the namespace) | S | Y | M |
| HOME-16 | Change Case / تغيير حالة الأحرف | Home (desktop; mobile **[verify on device]**) | Sentence case, lowercase, UPPERCASE, Capitalize Each Word, tOGGLE | Transform the text, or use `cap="all|small"` for All Caps / Small Caps | S | Y | D/M* |
| HOME-17 | Clear Formatting / مسح التنسيق | Home | Removes run-level formatting | Drop `a:rPr` attributes except `lang`/`dirty` | S | Y | M |
| HOME-18 | Character spacing / تباعد الأحرف | Desktop Font dialog | Tight, Normal, Loose, custom | `a:rPr spc` (hundredths of a point); kerning `kern`. **Must be disabled for Arabic runs**, because tracking breaks joining | S | Y | D |
| HOME-19 | Bullets / Numbering / تعداد نقطي / رقمي | Home | Bullet glyph gallery, numbering schemes (1. a. i. A. I. (1) and **Arabic-Indic ١. أ.**), picture bullets (desktop), colour and size % | `a:pPr/a:buChar char="•"` + `a:buFont`, `a:buAutoNum type="arabicPeriod|arabicParenR|alphaLcPeriod|romanUcPeriod|arabic1Minus|hindiNumPeriod…" startAt`, `a:buClr`, `a:buSzPct`, `a:buNone` | M | Y | M |
| HOME-20 | Increase / Decrease list level / زيادة / إنقاص المستوى | Home | Indent levels 1 to 9 | `a:pPr lvl="0..8"`; level styles from `p:txBody/a:lstStyle`, then the layout and master `p:txStyles` (`p:titleStyle`, `p:bodyStyle`, `p:otherStyle`) | S | Y | M |
| HOME-21 | Paragraph alignment / محاذاة الفقرة | Home | Left, Centre, Right, Justify; desktop adds Distributed and Thai Distributed | `a:pPr algn="l|ctr|r|just|dist"`. In RTL, `l`/`r` stay logical; render per `rtl="1"` | S | Y | M |
| HOME-22 | Text direction RTL/LTR / اتجاه النص من اليمين لليسار | Home (shown when an RTL keyboard or language is enabled) | Sets paragraph direction | `a:pPr rtl="1"`; also `a:rPr lang="ar-SA"` + `altLang`. Bidi with ICU `Bidi` / Android `TextDirectionHeuristics` | M | Y | M |
| HOME-23 | Line spacing / تباعد الأسطر | Home > Line Spacing | 1.0, 1.15, 1.5, 2.0, 2.5, 3.0; Line Spacing Options (before/after spacing in pt, exactly / at least / multiple) | `a:lnSpc/a:spcPct val="150000"` or `a:spcPts`; `a:spcBef`, `a:spcAft` | S | Y | M |
| HOME-24 | Columns / الأعمدة | Desktop Home > Columns | 1 to 3 columns, spacing | `a:bodyPr numCol="2" spcCol="…"`. Renderer must flow text across columns | M | Y | D |
| HOME-25 | Vertical text alignment / المحاذاة العمودية | Home > Align Text (tablet/desktop) | Top, Middle, Bottom | `a:bodyPr anchor="t|ctr|b"` + `anchorCtr` | S | Y | M*/D |
| HOME-26 | Text direction (rotate) / اتجاه النص (تدوير) | Desktop Home > Text Direction | Horizontal, Rotate 90°, Rotate 270°, Stacked | `a:bodyPr vert="vert|vert270|wordArtVert|eaVert"` | M | Y | D |
| HOME-27 | Text box options: autofit, margins, wrap / خيارات مربع النص | Format Shape > Text Box (desktop); mobile limited | Do not Autofit, Shrink text on overflow, Resize shape to fit text; inner margins; wrap text in shape | `a:noAutofit` / `a:normAutofit` / `a:spAutoFit`; `lIns tIns rIns bIns` in EMU; `wrap="square|none"`. Implement shrink-on-overflow as a binary search on fontScale at layout time | M | Y | M*/D |
| HOME-28 | Shapes gallery / الأشكال | Home > Shapes and Insert > Shapes | Lines, Rectangles, Basic Shapes, Block Arrows, Equation Shapes, Flowchart, Stars and Banners, Callouts, Action Buttons: about 180 presets | `p:sp` with `a:prstGeom prst="rect|roundRect|ellipse|triangle|rightArrow|…"` + `a:avLst` adjust values. **Implement all presets from `presetShapeDefinitions.xml` (ECMA-376 Part 1 annex D) as a geometry engine**: evaluate guides (`*/`, `+-`, `cos`, `at2` and so on) and build a Compose `Path`. This is the largest single item for rendering fidelity | L | Y | M |
| HOME-29 | Shape quick styles / أنماط سريعة للشكل | Home > Shape Styles (also the Shape tab) | Theme style matrix: 7 colours × 6 intensities (Colored Outline, Colored Fill, Light 1 Outline… Intense Effect), plus Presets | `p:style` with `a:lnRef idx`, `a:fillRef idx`, `a:effectRef idx`, `a:fontRef` resolved against `a:fmtScheme` in `theme1.xml` (`a:fillStyleLst`, `a:lnStyleLst`, `a:effectStyleLst`, `a:bgFillStyleLst`) | M | Y | M |
| HOME-30 | Arrange: Bring Forward / Send Backward / To Front / To Back / ترتيب: إحضار للأمام / إرسال للخلف | Home > Arrange; Shape tab | Z-order changes | Reorder children in `p:spTree` (document order equals z-order) | S | Y | M |
| HOME-31 | Align objects / محاذاة الكائنات | Arrange > Align | Left, Centre, Right, Top, Middle, Bottom; Align to Slide or to Selected Objects | Compute bounding boxes in EMU and rewrite `a:off`. Take rotation into account (use the rotated AABB the way PowerPoint does) | S | Y | M |
| HOME-32 | Distribute / توزيع | Arrange > Align | Distribute Horizontally / Vertically (3 or more objects) | Equal gaps between sorted AABBs | S | Y | M |
| HOME-33 | Group / Ungroup / Regroup / تجميع / فك التجميع | Arrange; floating menu with multi-select | Group selected shapes; ungroup | `p:grpSp` with `p:grpSpPr/a:xfrm` (`a:off`, `a:ext`, `a:chOff`, `a:chExt`): children live in a child coordinate space. Transform maths: child → group scale/offset → rotation and flip. Recalculate chOff/chExt on ungroup | M | Y | M |
| HOME-34 | Rotate / Flip / تدوير / قلب | Arrange > Rotate; rotate handle | Rotate right/left 90°, Flip Vertical / Horizontal; free rotate handle with 15° snapping | `a:xfrm rot="5400000"` (60000ths of a degree), `flipH="1" flipV="1"`. Text in flipped shapes is not mirrored; implement that rule | S | Y | M |
| HOME-35 | Selection Pane / جزء التحديد | Desktop Home > Arrange > Selection Pane; mobile: none | List of objects; rename, show/hide, reorder, select hidden or behind objects | `p:cNvPr name`, `hidden="1"`. **Very useful on touch screens, where small or stacked objects are hard to tap. Build it** | M | Y | D→M |
| HOME-36 | Find / بحث | Home > Find (magnifier) | Find text across slides (and notes); options Match Case, Whole Word | Index runs per slide; highlight matches with an overlay; scroll to the slide | S | Y | M |
| HOME-37 | Replace / Replace Fonts / استبدال / استبدال الخطوط | Desktop Home > Replace; mobile Find has Replace **[verify on device]** | Replace text; replace one font everywhere | Text: run-level replace across runs (handle matches that span runs). Fonts: rewrite `typeface` attributes | M | Y | M*/D |
| HOME-38 | Select All / Select tool / تحديد الكل | Floating menu; Home > Select | Selects all objects on the slide | UI only | S | Y | M |
| HOME-39 | Dictate / إملاء صوتي | Home > Dictate (mic) | Speech-to-text into the text box; voice commands for punctuation | Android `SpeechRecognizer` (on-device recognition on Android 13+; offline Arabic model availability varies) | M | P | M |
| HOME-40 | Shape fill / outline / effects shortcuts / تعبئة / مخطط / تأثيرات | Home (tablet) and the Shape tab | See SHAPE-xx | See section 12 | M | Y | M |

---

## 3. Insert tab (إدراج)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| INS-01 | New Slide / شريحة جديدة | Insert > New Slide | Same as HOME-03 | — | M | Y | M |
| INS-02 | Pictures (device) / صور من الجهاز | Insert > Pictures | Multi-select from the gallery; inserted centred and fitted, or into a picture placeholder if one is selected | `p:pic` → `p:nvPicPr`, `p:blipFill/a:blip r:embed` + `a:stretch/a:fillRect`, `p:spPr/a:xfrm` + `prstGeom rect`. Media in `ppt/media/imageN.(png|jpeg)`; relationship type `.../image`. Read EXIF orientation and bake it in; downscale images over 4096 px (desktop "compress pictures" defaults to 220 ppi) | M | Y | M |
| INS-03 | Camera / الكاميرا | Insert > Camera | Take a photo and insert it; Office Lens-style document/whiteboard cropping **[verify on device]** | CameraX + ML Kit Document Scanner (on-device) for perspective-corrected crops | M | Y | M |
| INS-04 | Stock Images / Icons / Cutout People / Stickers / Illustrations / صور مخزنة / أيقونات | Insert > Pictures > Stock Images (M365) ; Insert > Icons | Searchable library of licensed photos, icons (SVG), cutout people and stickers | Icons insert as SVG with a PNG fallback: `a:blip` + `a:extLst/a:ext uri="{96DAC541-7B7A-43D3-8B79-37D633B846F1}"/asvg:svgBlip r:embed`. **Ship our own bundled icon pack** (Material Symbols, Tabler, Phosphor, all permissive licences) as offline SVGs; render SVG with AndroidSVG or a vector path parser. Recolour by rewriting SVG fills or `a:duotone`/`a:clrChange` | M | P | M |
| INS-05 | Online pictures (Bing) / صور عبر الإنترنت | Desktop Insert > Online Pictures | Creative Commons search | Optional; needs a network and licence warnings | M | N | D |
| INS-06 | Shapes / الأشكال | Insert > Shapes | See HOME-28. Tap to place at a default size, or drag to size it (tablet). Lines/connectors snap to connection sites | Connectors: `p:cxnSp` with `a:stCxn id idx`, `a:endCxn`, `prstGeom bentConnector3|curvedConnector3|straightConnector1`. Reroute on shape move (find the connection-site points from the preset `a:cxnLst`) | L | Y | M |
| INS-07 | Text Box / مربع نص | Insert > Text Box | Inserts an auto-size text box and opens the keyboard | `p:sp` with `p:nvSpPr/p:cNvSpPr txBox="1"`, `a:bodyPr wrap="none|square"` + `a:spAutoFit` | S | Y | M |
| INS-08 | Table / جدول | Insert > Table | Default 3×3 (phone) with a grid picker on tablet; applies the theme's Medium Style 2 Accent 1 | `p:graphicFrame` → `a:graphic/a:graphicData uri=".../table"` → `a:tbl` (`a:tblPr firstRow bandRow` + `a:tableStyleId {5C22544A-7EE6-4342-B048-85BDC9FD1C3A}`), `a:tblGrid/a:gridCol w`, `a:tr h`/`a:tc`/`a:txBody`/`a:tcPr`. Table styles from `ppt/tableStyles.xml` (we must bundle the 74 built-in style GUID definitions, because they are *implied* and not stored when unused). See TBL-xx | L | Y | M |
| INS-09 | Chart / مخطط | Desktop Insert > Chart; mobile: edit-data and insert support limited (tablet) **[verify on device]** | Column, Bar, Line, Pie, Doughnut, Area, Scatter, Combo, and so on, with an embedded workbook | `p:graphicFrame` → `c:chart r:id` → `ppt/charts/chartN.xml` (ChartML) + `ppt/embeddings/Microsoft_Excel_Worksheet.xlsx` for data. Rendering: build a ChartML-to-Compose renderer for the top 8 chart types; fall back to the cached values in `c:numCache`/`c:strCache`. Data editing: a simple grid editor that writes both the cache and the embedded xlsx | L | Y | M*/D |
| INS-10 | SmartArt / رسم SmartArt | Desktop Insert > SmartArt; mobile renders it and allows text editing only **[verify on device]** | 200+ diagram layouts (list, process, cycle, hierarchy, relationship, matrix, pyramid, picture) | `dgm` parts: `diagrams/data1.xml`, `layout1.xml`, `quickStyle1.xml`, `colors1.xml`, `drawing1.xml` (the `dsp:` pre-rendered drawing; **render from drawing1.xml**). A full layout engine is L+; plan a phase-2 "basic SmartArt" with about 10 layouts generated as grouped shapes | L | Y | D/M* |
| INS-11 | Video (device) / فيديو | Insert > Video (Android supports inserting a video from the device **[verify on device]**) | Embedded video plays in the show; poster frame | `p:pic` with `p:nvPicPr/p:nvPr/a:videoFile r:link` + `p:extLst/p14:media r:embed` (rel type `http://schemas.microsoft.com/office/2007/relationships/media`) and a `.../video` rel; poster image in `p:blipFill`. Timing entry in `p:timing` (`p:video/p:cMediaNode`) for auto or on-click playback. Playback with Media3 ExoPlayer in a SurfaceView overlay on the canvas | L | Y | M* |
| INS-12 | Online Video (YouTube, Vimeo, Stream) / فيديو عبر الإنترنت | Desktop Insert > Video > Online | Embeds an online player | `a:videoFile r:link` to a URL (external rel, `TargetMode="External"`) | M | N | D |
| INS-13 | Audio / صوت | Insert > Audio (record on mobile **[verify on device]**); desktop Audio on My PC / Record Audio | Audio icon on the slide; play across slides; loop; hide during the show | `a:audioFile` + `p14:media`; timing `p:audio` with `p:cMediaNode vol`, `numSld` for play across slides | M | Y | M*/D |
| INS-14 | Screen recording / تسجيل الشاشة | Desktop Insert > Screen Recording | Records the screen into the slide | Android MediaProjection; save MP4 and insert as INS-11 | M | Y | D→M |
| INS-15 | Link (hyperlink) / ارتباط | Insert > Link; floating menu | Web URL, email, link to a slide in this deck (desktop: First/Last/Next/Previous slide, other file); display text and ScreenTip | Text: `a:rPr/a:hlinkClick r:id` (rel type hyperlink, External). Slide link: `r:id` → slide part + `action="ppaction://hlinksldjump"`; first/last/next: `action="ppaction://hlinkshowjump?jump=firstslide|lastslide|nextslide|previousslide"`. Shapes: `p:cNvPr/a:hlinkClick` | M | Y | M |
| INS-16 | Action settings / إعدادات الإجراء | Desktop Insert > Action | On click or mouse over: go to slide, run program, play sound | `a:hlinkClick`/`a:hlinkHover` with `ppaction://…`, `a:snd` | S | Y | D |
| INS-17 | Zoom (Summary / Section / Slide) / تكبير الملخص / المقطع / الشريحة | Desktop Insert > Zoom | Interactive thumbnails that zoom to a slide or section and back | `p:graphicFrame` with `a:graphicData uri="http://schemas.microsoft.com/office/powerpoint/2016/slidezoom"` → `pslz:sldZm` (`pslz:sldZmObj sldId cId`, `pslz:zmPr returnToParent imageType transitionDur`), `p166:summaryZm`, `psez:sectionZm`. A non-linear navigation that is great for lessons; **recommend for mobile** | L | Y | D→M |
| INS-18 | Comment / تعليق | Insert > Comment; Review | See REV-01 | — | M | Y | M |
| INS-19 | Header & Footer / رأس وتذييل | Insert > Header & Footer (tablet/desktop; phone **[verify on device]**) | Slide: Date & time (update automatically in a chosen format, or fixed), Slide number, Footer text, "Don't show on title slide", Apply / Apply to All. Notes and handouts: Header, Date, Page number, Footer | Turns placeholders on in slides: `p:sp` with `p:ph type="dt|ftr|sldNum"`. Field `a:fld type="slidenum"` / `type="datetime1..13"` with `a:t` cached text. Apply to All also sets `p:hf sldNum="1" hdr="0" ftr="1" dt="1"` in the master/notes master. "Don't show on title slide" means omitting the placeholders from slides on the title layout | M | Y | M* |
| INS-20 | Slide Number / رقم الشريحة | Insert > Slide Number | Same as INS-19, with only slide numbers ticked; also inserts a field in a text box at the cursor | `a:fld id="{GUID}" type="slidenum"`; first slide number in `presentation.xml firstSlideNum` | S | Y | M |
| INS-21 | Date & Time / التاريخ والوقت | Insert > Date & Time | 13 formats (locale dependent, including the Hijri calendar for Arabic locales on desktop), update automatically | `a:fld type="datetime1".."datetime13"` + `a:rPr lang="ar-SA"`. Use `java.time` `HijrahDate` for Hijri | S | Y | M* |
| INS-22 | Symbol / Equation / رمز / معادلة | Desktop Insert > Symbols; mobile: Equation **[verify on device]** | Symbol picker; equation editor (Unicode/LaTeX linear input and professional format) | Equations are stored as `a14:m` inside `mc:AlternateContent` with an `m:oMathPara` (OMML) choice and a **fallback picture** that we must generate. Implement OMML → layout renderer (L), or use LaTeX input → convert to OMML (via MathML) + render the fallback PNG. **Important for an education platform (chemistry/physics)** | L | Y | D/M* |
| INS-23 | WordArt / وورد آرت | Desktop Insert > WordArt | Text effects presets (fill, outline, shadow, glow, reflection, 3D) and Transform (arch, wave and so on) | Text box with `a:rPr` effects (`a:effectLst/a:outerShdw`, `a:glow`, `a:reflection`), `a:ln` for outline, `a:bodyPr/a:prstTxWarp prst="textArchUp|textWave1|…"`. Warping text along a path: render glyphs as paths with Android `Paint.getTextPath` and apply the warp. Arabic still needs shaping first | L | Y | D |
| INS-24 | 3D Models / نماذج ثلاثية الأبعاد | Desktop Insert > 3D Models (renders on mobile) | Rotatable .glb models | `am3d:model3d` with an image fallback; render the fallback only, or use Filament | L | P | D/M* |
| INS-25 | Object (OLE) / كائن | Desktop | Embed a workbook or document | Render only the `p:oleObj` fallback image; preserve the part | S | Y | D |
| INS-26 | Photo Album / ألبوم صور | Desktop Insert > Photo Album | Builds a deck with 1, 2 or 4 photos per slide, captions and frame shapes | Generator: easy to build and useful on mobile (camera roll to deck) | M | Y | D→M |
| INS-27 | Cameo (live camera feed) / كاميو | Desktop Insert > Cameo | Live webcam feed inside the slide during the show | `p:graphicFrame` with the camera extension; in our show use a CameraX preview in the shape bounds | M | Y | D→M |
| INS-28 | Forms (Microsoft Forms quiz) / نموذج / اختبار | Desktop Insert > Forms (online) | Embeds a live quiz or poll | Web add-in; out of scope. Alternative: our own native quiz slide object (see section 18) | L | N | D |
| INS-29 | Add-ins / Visio / Live polls / الوظائف الإضافية | Desktop | Office add-ins | Out of scope | — | N | D |

---

## 4. Draw tab (رسم)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| DRAW-01 | Pens / أقلام | Draw > Pen | Several pen slots; each has a colour (30+ plus custom), thickness (0.25 mm to 3.5 mm), and on M365 special inks (Galaxy, Rainbow, Gold, Silver, Lava, Ocean and so on) **[verify on device]** | Ink is stored as `p:contentPart r:id` → `ppt/ink/inkN.xml` (InkML: `inkml:trace` with x,y[,pressure] points, `inkml:brush` with `inkml:brushProperty` colour, width and tip), wrapped in `mc:AlternateContent` with a fallback picture **[generate PNG fallback for old readers]**. Capture with Compose `pointerInteropFilter` / `MotionEvent` including historical points, pressure and tilt (stylus). Smooth with Catmull-Rom or quadratic Bezier; render as variable-width paths. Use Android `androidx.ink` (Jetpack Ink API) for low latency | L | Y | M |
| DRAW-02 | Pencil / قلم رصاص | Draw | Textured pencil stroke | InkML brush `tip="ellipse"` + texture via `inkml:brushProperty name="pencil"` (MS extension); render with a noise shader (RuntimeShader on API 33+) | M | Y | M |
| DRAW-03 | Highlighter / قلم تمييز | Draw | Semi-transparent, chisel tip, drawn under the other ink | `transparency` brush property + `tip="rectangle"`; blend mode Multiply | S | Y | M |
| DRAW-04 | Eraser (stroke, point, segment) / ممحاة | Draw > Eraser | Stroke eraser (whole stroke), point eraser (splits strokes), segment eraser (to intersections) | Hit-test strokes with a spatial index (R-tree); point eraser splits the trace | M | Y | M |
| DRAW-05 | Lasso Select / تحديد حر | Draw > Lasso | Free-form select of ink and objects; move, resize, delete | Point-in-polygon on stroke points and object AABBs | M | Y | M |
| DRAW-06 | Draw with Touch toggle / الرسم باللمس | Draw > Draw with Touch / Finger | When off, only the stylus draws and a finger scrolls | Check `MotionEvent.getToolType` (TOOL_TYPE_STYLUS vs FINGER) | S | Y | M |
| DRAW-07 | Ink to Shape / تحويل الحبر إلى شكل | Draw > Ink to Shape (toggle) | Strokes that look like a shape become real shapes (rectangle, ellipse, triangle, arrow, line, star, pentagon, hexagon) with the pen colour | On-device recogniser: ML Kit Digital Ink Recognition supports a "shapes" model (`zxx-Zsym-x-shapes`) offline after a one-time download; or a custom $1/$P recogniser plus corner detection. Output `p:sp prstGeom` with `a:ln` in the pen colour | M | P | M |
| DRAW-08 | Ink to Text / تحويل الحبر إلى نص | Desktop/iPad Draw > Ink to Text; Android **[verify on device]** | Handwriting becomes a text box | ML Kit Digital Ink (supports Arabic `ar`, offline after model download) | M | P | D/M* |
| DRAW-09 | Ink to Math / تحويل الحبر إلى معادلة | Desktop Draw > Ink to Math | Handwritten equation becomes an OMML equation | Hard; needs a math handwriting recogniser (for example, MyScript, which is commercial). Phase 3 | L | P | D |
| DRAW-10 | Ink Replay / إعادة تشغيل الحبر | Draw > Ink Replay (Rewind/Forward, scrub) | Replays strokes in drawing order; also usable as an animation effect, "Replay" / "Rewind" (desktop) | Store a timestamp per point (`inkml:timestamp` / channel `T`) and per-trace order. Animation version: `p:animEffect`-like custom `p:cTn presetClass="entr" presetID="?"` for "Replay ink" (MS-specific) **[preserve; render as Wipe if unknown]** | M | Y | M |
| DRAW-11 | Ruler / المسطرة | Draw > Ruler | Two-finger rotatable ruler with angle readout; strokes snap to its edge | Overlay composable; project stroke points onto the ruler edge while within N px | M | Y | M |
| DRAW-12 | Convert ink to shape after the fact / تحويل الحبر المحدد | Select ink > Convert to Shape | Same as DRAW-07, on demand | — | S | P | M* |
| DRAW-13 | Action pen / Rotate ink | Desktop | Rotate, resize and recolour ink objects | Ink objects transform via the `p:contentPart` `p14:xfrm` | S | Y | M |
| DRAW-14 | Drawing canvas / Ink annotation layer | Desktop | Ink can be saved from the slide show as annotations ("Keep ink annotations?") | Show-time ink becomes `p:contentPart` items placed on the slide when kept | M | Y | M |

---

## 5. Design tab (تصميم)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| DES-01 | Themes / السمات | Design > Themes | Gallery of about 30 Office themes (Office Theme, Facet, Ion, Retrospect, Wisp, Integral, Slice, Organic, Gallery, Dividend, Parallax, Savon, Berlin, Circuit, Damask, Depth, Droplet, Frame, Main Event, Mesh, Quotable, Vapor Trail, View, Badge, Celestial, Basis, Banded, Madison, Atlas, Office Theme 2013–2022, and so on) | A theme is a `slideMaster` + its layouts + `theme/themeN.xml` (`a:clrScheme` 12 colours: dk1, lt1, dk2, lt2, accent1–6, hlink, folHlink; `a:fontScheme` major/minor with `a:font script="Arab" typeface="…"`; `a:fmtScheme`). Applying it means replacing the master and layouts and remapping each slide's layout by type/name. **Ship our own theme pack as .thmx/.potx assets (designed in-house with Arabic-first fonts)**; we cannot redistribute Microsoft theme assets | L | Y | M |
| DES-02 | Variants / المتغيرات | Design > Variants | 4 alternates per theme (colour/background) | Variants are additional themes in the .thmx (`theme/themeVariants/variant1…`). Apply = swap `clrScheme`, background and fonts | M | Y | M |
| DES-03 | Colours / Fonts / Effects / Background Styles / الألوان / الخطوط / التأثيرات / أنماط الخلفية | Design > Variants dropdown (desktop; mobile Variants only **[verify on device]**) | Built-in colour schemes (Office, Office 2007–2010, Grayscale, Blue Warm, Blue, Blue II, Blue Green, Green, Green Yellow, Yellow, Yellow Orange, Orange, Orange Red, Red Orange, Red, Red Violet, Violet, Violet II, Median, Paper, Marquee, Slipstream, Aspect); Customize Colors; font pairs; effects; 12 background styles | Edit `theme1.xml` `a:clrScheme`/`a:fontScheme`/`a:fmtScheme`; background styles mean `p:bg/p:bgRef idx="1001..1003"` referencing `a:bgFillStyleLst` | M | Y | D→M |
| DES-04 | Slide Size / حجم الشريحة | Design > Slide Size | Standard 4:3, Widescreen 16:9; desktop: Custom (Letter, A4, 35 mm, Overhead, Banner, 16:10, A3, B4, B5, On-screen 16:9/16:10, Custom width/height, Portrait/Landscape, Maximize vs Ensure Fit) | `presentation.xml` `p:sldSz cx="12192000" cy="6858000" type="custom|screen4x3|…"` and `p:notesSz`. Rescaling content: scale `a:xfrm` on every slide, layout and master (Ensure Fit uses uniform scale + centre; Maximize scales to fill). **Add portrait 9:16 (stories/phone lessons)** | M | Y | M |
| DES-05 | Format Background / تنسيق الخلفية | Design > Format Background | Fill: Solid, Gradient (presets, type linear/radial/rectangular/path, angle, stops), Picture or texture (from file, tile, offset, transparency), Pattern (48 patterns, desktop); Hide background graphics; Apply to All; Reset Background | Slide `p:cSld/p:bg/p:bgPr` with `a:solidFill`/`a:gradFill`(`a:gsLst`, `a:lin ang scaled` / `a:path path="circle|rect|shape"`)/`a:blipFill`(`a:tile` or `a:stretch`)/`a:pattFill prst`. Hide background graphics: `<p:cSld>` + `showMasterSp="0"` on `p:sld`. Apply to All writes `p:bg` into the master and removes it from the slides | M | Y | M |
| DES-06 | Designer (Design Ideas) / مصمم الشرائح (أفكار التصميم) | Design > Designer; it also appears when you insert pictures | Suggests professional layouts for the current slide's content: photos (1 to 10), title slide, lists to SmartArt, timelines, icons for keywords, dates to timeline. Tap to apply. M365 for most ideas; the service is cloud-based | Cloud in Office. **Our offline approach**: rule-based layout generator. Inputs are slide content features (title length, bullet count, image count and aspect ratios, keywords); outputs are N candidate arrangements from parametric templates (grid, hero image, split, mosaic, numbered steps, timeline, icon list), each scored for fit (no overlap, margins, contrast). Render candidate thumbnails with the same canvas renderer at low resolution. Optional on-device LLM (Gemini Nano / AICore, where available) for keyword → icon picks | L | Y (rule-based) | M (cloud) |
| DES-07 | Customize: slide master access / تخصيص | Desktop Design > Customize | Slide Size, Format Background | — | — | — | D |
| DES-08 | Apply theme to selected slides / تطبيق السمة على الشرائح المحددة | Desktop right-click theme | Multiple masters per deck | Add a second `p:sldMasterId` in `sldMasterIdLst`; the slide's layout rel points to the new master's layouts | M | Y | D→M |
| DES-09 | Theme fonts for Arabic / خطوط السمة للعربية | (Hidden in Office) | Theme defines `a:font script="Arab"` per major/minor font | Edit `a:majorFont/a:font script="Arab" typeface="…"`. **First-class Arabic font pairs in our UI** | S | Y | Our |

---

## 6. Transitions tab (انتقالات)

### 6.1 Commands

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| TRN-01 | Transition Effects gallery / تأثيرات الانتقال | Transitions > Transition Effects | Pick a transition for the selected slide(s); a preview plays on tap. Groups: Subtle, Exciting, Dynamic Content | Write `<p:transition spd="fast|med|slow" p14:dur="700" advClick="1" advTm="3000">` + child effect element (see 6.2) inside the slide **after `p:clrMapOvr`, before `p:timing`**. Newer effects need `mc:AlternateContent` with `mc:Choice Requires="p14"` (or `p15`, `p159`) and an `mc:Fallback` holding a classic `p:fade` | M | Y | M |
| TRN-02 | Effect Options / خيارات التأثير | Transitions > Effect Options | Direction or variant depending on the effect (see table 6.2) | Attributes on the child element (`dir`, `orient`, `thruBlk`, `pattern`, `invX`, and so on) | M | Y | M |
| TRN-03 | Duration / المدة | Transitions > Duration (tablet; phone **[verify on device]**) | Seconds, for example 00.01 to 59.00 | `p14:dur` in ms (with `spd` as a fallback: fast 500, med 750, slow 1000) | S | Y | M*/D |
| TRN-04 | Apply To All / تطبيق على الكل | Transitions > Apply To All | Copies transition, options and timing to every slide | Clone the `p:transition` node into every slide | S | Y | M |
| TRN-05 | Advance slide: On Mouse Click / After N sec / التقدم: عند النقر / بعد | Desktop Transitions > Timing (mobile **[verify on device]**) | Both can be combined | `advClick="0|1"`, `advTm="ms"` | S | Y | D→M |
| TRN-06 | Transition sound / صوت الانتقال | Desktop Transitions > Sound | Built-in sounds (Applause, Arrow, Bomb, Breeze, Camera, Cash Register, Chime, Click, Coin, Drum Roll, Explosion, Hammer, Laser, Push, Suction, Typewriter, Voltage, Whoosh, Wind), Other Sound (.wav), loop until next sound | `p:transition/p:sndAc/p:stSnd/p:snd r:embed name` (wav in media), `p:endSnd` | S | Y | D |
| TRN-07 | Preview / معاينة | Transitions > Preview; automatic on selection | Plays the transition on the canvas | Use the same engine as the slide show | — | Y | M |
| TRN-08 | Remove (None) / بلا | Gallery > None | Removes the transition | Delete the `p:transition` element | S | Y | M |

### 6.2 Complete transition catalogue (desktop PowerPoint for Microsoft 365) and OOXML mapping

The Android gallery shows these same three groups. It includes Morph (confirmed by Microsoft) and most of the effects below; a few are 3D-heavy and less certain **[verify on device]**. Every effect renders on Android, because playback supports all of them.

**Rendering approach for all transitions:** render the outgoing slide and the incoming slide into two `GraphicsLayer`/`ImageBitmap` textures at screen resolution, then composite them per frame with a progress `t∈[0,1]` (Compose `Animatable`, easing per effect) using `Canvas` clipping (wipes, shapes, blinds, checkerboard), alpha (fade), translation (push, cover, uncover) or AGSL `RuntimeShader` (API 33+; dissolve, glitter, ripple, vortex, shred, fracture, honeycomb) and `graphicsLayer { rotationY / cameraDistance }` for the 3D ones (cube, flip, doors, box, gallery, ferris wheel, page curl approximation). On API 26–32, fall back to Fade for shader effects.

| # | Effect (EN / AR) | Group | Effect Options (Android/desktop) | OOXML element | Render technique | Diff |
|---|---|---|---|---|---|---|
| 1 | Morph / تحويل | Subtle | Objects, Words, Characters | `p159:morph option="byObject|byWord|byChar"` (inside `mc:AlternateContent Requires="p159"`, fallback `p:fade`) | Match shapes between slides by name (`!!name` forcing), type and geometry; tween xfrm, fill colour, geometry (path morph via point resampling) and text (by word or char: match tokens, tween positions). The hardest transition | L |
| 2 | Fade / تلاشٍ | Subtle | Smoothly, Through Black | `p:fade thruBlk="0|1"` | Alpha crossfade | S |
| 3 | Push / دفع | Subtle | From Bottom, Left, Right, Top | `p:push dir="u|l|r|d"` | Translate both | S |
| 4 | Wipe / مسح | Subtle | Right, Top, Left, Bottom, Top-Right, Bottom-Right, Top-Left, Bottom-Left | `p:wipe dir="l|u|r|d"`; diagonal directions are stored as `p:strips dir="lu|ru|ld|rd"` | Soft-edged gradient mask | S |
| 5 | Split / انقسام | Subtle | Vertical Out/In, Horizontal Out/In | `p:split orient="horz|vert" dir="out|in"` | Clip rect expand/contract | S |
| 6 | Reveal / كشف | Subtle | Smoothly or Through Black, from Right or Left | `p14:reveal thruBlk dir="l|r"` | Translate + fade | S |
| 7 | Cut / قطع | Subtle | Through Black | `p:cut thruBlk` | Instant (one black frame) | S |
| 8 | Random Bars / أشرطة عشوائية | Subtle | Vertical, Horizontal | `p:randomBar dir="horz|vert"` | Random 1-px line mask | S |
| 9 | Shape / شكل | Subtle | Circle, Diamond, Plus, In, Out | `p:circle`, `p:diamond`, `p:plus` ; `p:zoom dir="in|out"` | Clip path scaling | S |
| 10 | Uncover / كشف الغطاء | Subtle | 8 directions | `p:pull dir="l|u|r|d|lu|ru|ld|rd"` | Translate old | S |
| 11 | Cover / تغطية | Subtle | 8 directions | `p:cover dir` | Translate new | S |
| 12 | Flash / وميض | Subtle | — | `p14:flash` | White flash + crossfade | S |
| 13 | Fall Over / سقوط | Exciting | Left, Right | `p15:prstTrans prst="fallOver" invX` | 3D rotateX around bottom edge | M |
| 14 | Drape / ستارة منسدلة | Exciting | Left, Right | `p15:prstTrans prst="drape"` | Mesh warp (approximate with strip rotation) | M |
| 15 | Curtains / ستائر | Exciting | — | `p15:prstTrans prst="curtains"` | Two-halves mesh warp | M |
| 16 | Wind / رياح | Exciting | Right, Left | `p15:prstTrans prst="wind"` | Strips flying with skew | M |
| 17 | Prestige / مرموق | Exciting | — | `p15:prstTrans prst="prestige"` | 3D tilt-away + fade | M |
| 18 | Fracture / تكسّر | Exciting | — | `p15:prstTrans prst="fracture"` | Voronoi shards (precomputed), rotate + fall | L |
| 19 | Crush / سحق | Exciting | — | `p15:prstTrans prst="crush"` | Crumple approximation (scale + shards) | L |
| 20 | Peel Off / تقشير | Exciting | Left, Right | `p15:prstTrans prst="peelOff"` | Page-curl shader | L |
| 21 | Page Curl / لف الصفحة | Exciting | Double Left/Right, Single Left/Right | `p15:prstTrans prst="pageCurlDouble|pageCurlSingle"` | Page-curl shader (AGSL) | L |
| 22 | Airplane / طائرة | Exciting | Right, Left | `p15:prstTrans prst="airplane"` | Fold to paper plane (approximate: shrink + fly) | M |
| 23 | Origami / أوريغامي | Exciting | Right, Left | `p15:prstTrans prst="origami"` | Fold animation (approximate) | M |
| 24 | Dissolve / تحلّل | Exciting | — | `p:dissolve` | Noise threshold shader | S |
| 25 | Checkerboard / رقعة الشطرنج | Exciting | From Left, From Top | `p:checker dir="horz|vert"` | Cell mask | S |
| 26 | Blinds / ستائر معدنية | Exciting | Vertical, Horizontal | `p:blinds dir` | Stripe mask | S |
| 27 | Clock / ساعة | Exciting | Clockwise, Counterclockwise, Wedge | `p:wheel spokes="1"` / `p14:wheelReverse` / `p:wedge` | Angular sweep mask | S |
| 28 | Ripple / تموّج | Exciting | Centre, From Top-Left, Top-Right, Bottom-Left, Bottom-Right | `p14:ripple dir="center|lu|ru|ld|rd"` | Radial displacement shader | M |
| 29 | Honeycomb / خلية النحل | Exciting | — | `p14:honeycomb` | Hex-cell mask + scale | M |
| 30 | Glitter / بريق | Exciting | Hexagons/Diamonds × 4 directions | `p14:glitter pattern="hexagon|diamond" dir` | Cell flip shader | M |
| 31 | Vortex / دوامة | Exciting | Left, Up, Right, Down | `p14:vortex dir` | Swirling particles shader | M |
| 32 | Shred / تمزيق | Exciting | Strips In/Out, Particles In/Out | `p14:shred pattern="strip|rectangle" dir="in|out"` | Strip/particle displacement | M |
| 33 | Switch / تبديل | Exciting | Right, Left | `p14:switch dir` | 3D swap | M |
| 34 | Flip / قلب | Exciting | Right, Left | `p14:flip dir` | rotationY 180 | S |
| 35 | Gallery / معرض | Exciting | Right, Left | `p14:gallery dir` | Perspective slide | M |
| 36 | Cube / مكعب | Exciting | Right, Left, Top, Bottom | `p14:prism isContent="0" dir` (cube) | Two faces rotationY | M |
| 37 | Doors / أبواب | Exciting | Vertical, Horizontal | `p14:doors dir="vert|horz"` | Two halves rotating | M |
| 38 | Box / صندوق | Exciting | Right, Left, Top, Bottom | `p14:prism isInverted="1" dir` | Inverted cube | M |
| 39 | Comb / مشط | Exciting | Horizontal, Vertical | `p:comb dir` | Interleaved strip translate | S |
| 40 | Zoom (Exciting) / تكبير/تصغير | Exciting | In, Out, Zoom and Rotate | `p:zoom dir` / `p:newsflash` (zoom and rotate) | Scale + rotate | S |
| 41 | Random / عشوائي | Exciting | — | `p:random` | Pick one at random each time | S |
| 42 | Pan / تحريك أفقي | Dynamic | Bottom, Left, Right, Top | `p14:pan dir` | Content only moves (background static) | M |
| 43 | Ferris Wheel / عجلة دوارة | Dynamic | Right, Left | `p14:ferris dir` | Content rotates on arc | M |
| 44 | Conveyor / ناقل | Dynamic | Right, Left | `p14:conveyor dir` | Content-only 3D conveyor | M |
| 45 | Rotate / تدوير | Dynamic | Right, Left, Top, Bottom | `p14:prism isContent="1" dir` | Content-layer cube | M |
| 46 | Window / نافذة | Dynamic | Vertical, Horizontal | `p14:window dir` | Content split-swing | M |
| 47 | Orbit / مدار | Dynamic | Right, Left, Top, Bottom | `p14:prism isContent="1" isInverted="1" dir` | Content orbit | M |
| 48 | Fly Through / طيران عبر | Dynamic | In, In with Bounce, Out, Out with Bounce | `p14:flythrough dir="in|out" hasBounce` | Scale + alpha | S |
| 49 | Legacy: Strips, Wedge, Newsflash, Push/Pull variants | Legacy (opening old decks) | — | `p:strips`, `p:wedge`, `p:newsflash`, `p:wheel spokes=1..8` | — | S |
| 50 | Vortex/other p14 3D: Conveyor/Ripple already listed; also `p14:warp` (Warp in/out) | Legacy | In/Out | `p14:warp dir` | Scale + blur | S |

> **Dynamic Content** transitions animate only content, not the background or master graphics. The renderer must render the background layer and the content layer as two separate bitmaps per slide.
> Exact `p15:prstTrans` preset names follow MS-PPTX §2.3; keep a mapping table and always write an `mc:Fallback` with `p:fade`, so older readers still work.


---

## 7. Animations tab (حركات)

### 7.1 What PowerPoint Android supports (confirmed by Microsoft)

- You can add **Entrance**, **Emphasis** and **Exit** effects to objects (text, shapes, pictures). Each item can carry several animations, for example an entrance and an exit.
- **Effect Options** let you set direction and sequence where the effect allows it (for example, Fly In: From Bottom/Left/Right/Top...; Sequence: As One Object / By Paragraph).
- Numbered markers on the canvas show the order. Tap a marker to change or delete the animation; reorder **[verify on device; available on tablet]**.
- **Not supported on mobile:** inserting **Motion Paths** and **sound effects**, and some desktop effects. **Playback supports every animation**, including desktop-only ones, so our player must handle the full `p:timing` model even where the editor offers only a subset.

### 7.2 Commands

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| ANI-01 | Entrance Effects / تأثيرات الدخول | Animations > Entrance Effects | See catalogue 7.3 | `p:timing/p:tnLst/p:par/p:cTn id=1 dur="indefinite" restart="never" nodeType="tmRoot"` → `p:childTnLst/p:seq concurrent="1" nextAc="seek"` → `p:cTn nodeType="mainSeq"` → click groups `p:par` → `p:cTn fill="hold"` → `p:par` (with/after) → effect `p:cTn presetID="2" presetClass="entr" presetSubtype="4" grpId="0" nodeType="clickEffect"` → `p:childTnLst` with `p:set` (visibility) + `p:anim`/`p:animEffect filter="wipe(down)"`/`p:animScale`/`p:animRot`. Plus `p:bldLst/p:bldP spid grpId build="p|whole"` for text builds. **Generate the XML from preset templates**: keep a JSON table of presetID → template XML captured from desktop PowerPoint output | L | Y | M |
| ANI-02 | Emphasis Effects / تأثيرات التوكيد | Animations > Emphasis Effects | See 7.3 | `presetClass="emph"`; `p:animClr` (colour), `p:animScale`, `p:animRot`, `p:set` of `style.opacity` and so on | L | Y | M |
| ANI-03 | Exit Effects / تأثيرات الخروج | Animations > Exit Effects | Mirrors of entrance | `presetClass="exit"`; final `p:set style.visibility=hidden` | M | Y | M |
| ANI-04 | Motion Paths / مسارات الحركة | Desktop Animations > Motion Paths | Lines, Arcs, Turns, Shapes, Loops, Custom Path (draw); Edit Points; Reverse Path Direction; Locked/Unlocked | `presetClass="path"` + `p:animMotion origin="layout" path="M 0 0 L 0.25 0.33 E" pathEditMode="relative" rAng ptsTypes`. Coordinates are a fraction of the slide size. **Recommend for mobile** (draw a path with a finger, which is a natural touch gesture) | M | Y | D→M |
| ANI-05 | Effect Options / خيارات التأثير | Animations > Effect Options | Direction (8), Amount (emphasis: Grow/Shrink 25%/50%/150%/200%/custom; Spin 360/180/90/45, clockwise or counter-clockwise), Colour (emphasis colour effects), Shape (Circle, Box, Diamond, Plus for the Shape effect), Sequence (As One Object, All at Once, By Paragraph), Vanishing Point (Zoom) | `presetSubtype` + inner `p:anim` values; Sequence means `p:bldP build="whole|p|allAtOnce"` and per-paragraph `p:spTgt/p:txEl/p:pRg st end` targets | M | Y | M |
| ANI-06 | Start: On Click / With Previous / After Previous / البدء: عند النقر / مع السابق / بعد السابق | Animations > Timing (tablet) **[verify on phone]** | — | `nodeType="clickEffect|withEffect|afterEffect"` and the nesting of `p:par` groups (click group > after-group > with-effects) | M | Y | M*/D |
| ANI-07 | Duration / Delay / المدة / التأخير | Animations > Timing (desktop; mobile **[verify on device]**) | Seconds; preset speeds Very Fast 0.5, Fast 1, Medium 2, Slow 3, Very Slow 5 | `p:cTn dur="500"`, `p:stCondLst/p:cond delay="250"` | S | Y | M*/D |
| ANI-08 | Reorder (Move Earlier / Later) / تحريك للأمام / للخلف | Animation Pane; mobile marker menu | Changes sequence | Move `p:par` nodes within `mainSeq`; rewrite `cTn id`s | M | Y | M*/D |
| ANI-09 | Animation Pane / جزء الحركات | Desktop | List of all animations with a timeline bar; drag to reorder; play from here | **Build a touch-friendly animation list sheet** (essential for usability) | M | Y | D→M |
| ANI-10 | Triggers / المشغِّلات | Desktop Animations > Trigger | Start the effect when a specific shape is clicked, or at a bookmark in media | `p:seq concurrent="1" nextAc="seek"` with `nodeType="interactiveSeq"` + `p:stCondLst/p:cond evt="onClick"`/`p:tgtEl/p:spTgt spid`. Great for quizzes ("tap the answer to reveal it"). **Recommend** | M | Y | D→M |
| ANI-11 | Animation Painter / ناسخ الحركة | Desktop | Copy animations to other objects | Clone the effect subtree with a retargeted `spid` | S | Y | D |
| ANI-12 | Effect enhancements: Sound, After animation (dim to colour / hide), Animate text by letter/word, Smooth start/end, Bounce end, Auto-reverse, Repeat, Rewind when done / تحسينات | Desktop Effect Options dialog | — | `p:cTn accel decel autoRev repeatCount="indefinite|n" restart`; `p:iterate type="lt|wd" p:tmPct`; after-anim dim = trailing `p:animClr`/`p:set`; sound `p:subTnLst/p:audio` | M | Y | D |
| ANI-13 | Preview / معاينة | Animations > Preview (auto on add) | Plays the slide's animations in place | Same engine as the show | — | Y | M |
| ANI-14 | Remove animation / إزالة | Tap the marker > Delete; Effect gallery > None | — | Remove the effect `p:par` (and empty parents), plus the `p:bldP` if it is no longer used | S | Y | M |
| ANI-15 | Media animations: Play / Pause / Stop / Seek (bookmarks) / تشغيل الوسائط | Auto-generated when inserting media | Start automatically or on click; trim; fade in/out | `presetClass="mediacall"` `p:cmd type="call" cmd="playFrom(0.0)|togglePause|stop"`; bookmarks `p14:bmkLst` | M | Y | M |

### 7.3 Animation effect catalogue (desktop PowerPoint; star = believed available in the Android gallery **[verify on device]**)

Every effect needs a renderer in our show engine. presetIDs come from PowerPoint output (`presetClass` + `presetID`); confirm each against a sample deck before you ship.

**Entrance (`entr`)**: Basic: Appear\* (1), Blinds (3), Box (4), Checkerboard (5), Circle (6), Crawl In (7), Diamond (8), Dissolve In (9), Fly In\* (2), Flash Once (11), Peek In (12), Plus (13), Random Bars\* (14), Spiral In (15), Split\* (16), Stretch (17), Strips (18), Swivel\* (19), Wedge (20), Wheel\* (21), Wipe\* (22), Zoom\* (23), Random Effects (24), Fade\* (10). Subtle: Expand (55), Fade (10), Faded Swivel (45), Faded Zoom (53), Zoom. Moderate: Basic Zoom, Center Revolve (43), Compress (50), Float In\* (42 Ascend / 47 Descend), Grow & Turn\* (31), Rise Up (37), Spinner (49), Stretch (17), Shape\* (Circle/Box/Diamond/Plus), Bounce\* (26). Exciting: Boomerang (25), Bounce (26), Credits (28), Curve Up (52), Drop (38), Flip (56), Float (30), Pinwheel (35), Spiral In (15), Basic Swivel (19), Thread (46), Whip (41).

**Emphasis (`emph`)**: Basic: Fill Color\* (1), Font Color\* (3), Grow/Shrink\* (6), Line Color\* (7), Spin\* (8), Transparency\* (9). Subtle: Bold Flash (10), Brush Color (16), Complementary Color\* (21), Complementary Color 2, Contrasting Color, Darken\* (24), Desaturate\* (25), Lighten\* (30), Object Color\* (19), Pulse\* (26), Color Pulse\* (27), Underline\* (18), Bold Reveal (15). Moderate: Color Blend, Color Wave, Grow with Color, Shimmer, Teeter\* (32). Exciting: Blink (35), Bold Reveal, Wave\* (34).

**Exit (`exit`)**: the mirror of every entrance effect (Disappear, Fade, Fly Out, Float Out, Split, Wipe, Shape, Wheel, Random Bars, Shrink & Turn, Zoom, Swivel, Bounce, Collapse, Sink Down, and so on).

**Motion Paths (`path`)** (desktop only): Basic: Lines, Arcs, Turns, Shapes (Circle, Diamond, Hexagon, Octagon, Parallelogram, Pentagon, Right Triangle, Square, Star 4/5/6/8 pt, Teardrop, Trapezoid, Crescent Moon, Football, Heart), Loops, Custom Path. Lines & Curves: Arc Down/Left/Right/Up, Bounce Left/Right, Curvy Left/Right, Decaying Wave, Diagonal Down/Up Right, Funnel, Heartbeat, Sine Wave, Spiral Left/Right, Spring, Stairs Down, S-curve 1/2, Turn Down/Up, Wave 1/2, Zigzag. Special: Bean, Curved Square/X, Curvy Star, Figure 8 Four/Horizontal/Vertical, Horizontal Figure 8, Inverted Square/Triangle, Loop de Loop, Neutron, Peanut, Point Star, Swoosh, Vertical Figure 8.

**Engine design (Kotlin):**
1. Parse `p:timing` into a time-node tree (`par`/`seq`/`excl` containers; `anim`, `animClr`, `animEffect`, `animMotion`, `animRot`, `animScale`, `set`, `cmd`, `audio`, `video` behaviours) following the SMIL-like semantics in ECMA-376 §19.5.
2. Build a timeline per click step: the main sequence advances on tap/next; interactive sequences start on target taps.
3. Each shape renders with a per-frame `ShapeAnimState` (visibility, opacity, offset, scale, rotation, colour overrides, clip-mask filter for `animEffect filter="wipe(down)|circle(in)|barn(inVertical)|blinds|box|checkerboard|diamond|dissolve|fade|plus|randombar|slide(fromTop)|strips|wedge|wheel(4)"`).
4. Text builds by paragraph/word/letter split the text layout into fragments, each with its own state.
5. Property formulas (`#ppt_x`, `#ppt_y`, `#ppt_w`, `#ppt_h`, `ppt_r`, `$`, `sin()`) need a small expression evaluator for `p:tav/p:val/p:strVal` and `fmla`.

---

## 8. Slide Show tab (عرض الشرائح)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| SHOW-01 | From Beginning / From Current Slide / من البداية / من الشريحة الحالية | Slide Show tab; play icon in the top bar | Full-screen show; landscape lock; swipe left = next, swipe right = previous; tap = next | Full-screen Activity/Composable (immersive mode, keep screen on via `FLAG_KEEP_SCREEN_ON`). Pre-render the next slide's first state to a bitmap for instant advance. **For RTL decks, mirror swipe directions (option)** | M | Y | M |
| SHOW-02 | Presenter View (phone/tablet) / عرض المقدِّم | Automatic when an external display is connected (HDMI/USB-C, Miracast/Chromecast); toggle on device | The device shows the current slide, next slide, notes, a timer and slide thumbnails; the external display shows the slide only | Android `DisplayManager` + `Presentation` class (secondary display) rendering the show composable; the main device renders the presenter UI | M | Y | M |
| SHOW-03 | Laser Pointer / مؤشر الليزر | During the show: long-press; or the pen menu | Red dot follows the finger; on some devices the gyroscope moves it; in presenter mode, drag in the notes area | Overlay layer with a glow dot; gyroscope via `SensorManager` `TYPE_GAME_ROTATION_VECTOR` mapped to screen coordinates; mirror to the external display | S | Y | M |
| SHOW-04 | Pen / Highlighter / Eraser during the show / القلم والتمييز أثناء العرض | Show toolbar (pen icon) | Ink over slides; colours; on exit, "Keep ink annotations?" | Transient ink layer per slide; when kept, convert to `p:contentPart` (DRAW-01) | M | Y | M |
| SHOW-05 | Black / White screen / شاشة سوداء / بيضاء | Show menu (desktop B/W keys) | Blanks the screen | Overlay | S | Y | M*/D |
| SHOW-06 | See all slides / Go to slide / عرض كل الشرائح | Show menu | Grid of slides; jump to one | Reuse the sorter thumbnails | S | Y | M |
| SHOW-07 | Zoom into slide (pinch) / تكبير أثناء العرض | Pinch during the show | Zoom and pan into slide content | `graphicsLayer` scale; render at higher resolution on zoom (re-rasterise vector content) | S | Y | M |
| SHOW-08 | Rehearse with Coach (Presenter/Speaker Coach) / التدرب مع المدرب | Slide Show > Rehearse with Coach (Android has had it since 2020; uses the cloud) | Real-time feedback while you rehearse: pace (words/min; ideal 100–165), filler words ("um", "basically"), inclusive language, reading slide text, profanity, pitch (monotone), repetitive language, body language (camera; Web); end report with metrics | Cloud speech service in Office. **Ours**: on-device ASR (Android `SpeechRecognizer` with EXTRA_PREFER_OFFLINE, or Whisper.cpp tiny/base with Arabic support) → transcript with timestamps → compute WPM, filler-word list (Arabic: "يعني"، "اممم"، "طيب"، "بصراحة"), pitch variance via `AudioRecord` + YIN pitch tracker, slide-text overlap (n-gram match against slide text). Report screen | L | P | M (cloud) |
| SHOW-09 | Live subtitles / Always Use Subtitles / الترجمة المصاحبة | Desktop/Web Slide Show > Subtitle Settings; Android **[verify on device]** | Live captions in the spoken language or translated into 60+ languages; position (bottom/top, overlaid) | On-device ASR (as SHOW-08) rendering captions as an overlay; translation with ML Kit Translate (on-device models, Arabic ↔ English supported) | M | P | D/M* |
| SHOW-10 | Cast / Present to external display / البث / العرض على شاشة خارجية | Show menu > Cast; or system Cast | Chromecast/Miracast/wired | Chromecast through `MediaRouter` + Cast SDK remote display (or rely on system screen mirroring plus a `Presentation` display) | M | P | M |
| SHOW-11 | Present Online (Teams/Live) / العرض عبر الإنترنت | Desktop/Web | Audience follows in a browser; live presentation with audience QR, reactions and subtitles | Out of scope (needs a server). Lightweight alternative: a local Wi-Fi web server serving the current slide PNG to students' browsers (fully offline on a LAN). **Interesting for classrooms** | L | P | D |
| SHOW-12 | Custom Slide Show / عرض مخصص | Desktop Slide Show > Custom Slide Show | Named subsets of slides | `presentation.xml` `p:custShowLst/p:custShow name id` → `p:sldLst/p:sld r:id` | S | Y | D→M |
| SHOW-13 | Set Up Slide Show / إعداد العرض | Desktop | Presented by a speaker / browsed individually / browsed at a kiosk (loop); loop until Esc; show without narration/animation; pen and laser colour; slide range; advance manually or with timings | `ppt/presProps.xml` `p:showPr loop="1" showNarration useTimings` + `p:penClr`, `p:sldRg st end`, `p:kiosk`, `p:browse` | S | Y | D→M |
| SHOW-14 | Rehearse Timings / التمرين على التوقيت | Desktop | Records time per slide and saves it as advance timings | Write `advTm` on each slide's `p:transition` | S | Y | D→M |
| SHOW-15 | Record Slide Show (narration, ink, camera) / تسجيل عرض الشرائح | Desktop Record | Records audio, video and ink per slide; exports to video | Per slide: audio as `p:audio` media + timing auto-start; ink replay timing. Phase 2. **High value for teachers** (record a lesson on the phone) | L | Y | D→M |
| SHOW-16 | Slide show navigation keyboard/remote / التحكم بلوحة المفاتيح | During the show | Bluetooth clickers send PageUp/PageDown/arrows/B/W/Esc | `onKeyEvent` handling | S | Y | M |
| SHOW-17 | Hide Slide / إخفاء شريحة | Slide Show tab | Same as HOME-08 | — | S | Y | M |
| SHOW-18 | Use presenter view / Monitor selection / استخدام عرض المقدم | Desktop | Choose which monitor shows the slides | Display picker | S | Y | D |
| SHOW-19 | Show timer / clock in presenter view / مؤقت | Presenter view | Elapsed time; pause/reset | UI | S | Y | M |
| SHOW-20 | Play media in the show / تشغيل الوسائط | Show | Video/audio controls, auto-start | ExoPlayer overlays positioned by shape xfrm; respect `p:timing` media nodes | M | Y | M |
| SHOW-21 | Hyperlinks & action buttons in the show / الارتباطات أثناء العرض | Show | Tap links to jump slides or open URLs | Hit-test link regions (text run bounds and shapes) during the show | S | Y | M |
| SHOW-22 | Remote control from phone (desktop show) / التحكم عن بُعد | PowerPoint mobile acts as a remote for a desktop show via "Present in Teams" / Office Remote (retired) | — | Ours: phone-to-phone remote over Wi-Fi Direct / Nearby Connections (offline) | M | Y | — |

---

## 9. Review tab (مراجعة)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| REV-01 | New Comment / تعليق جديد | Review > New Comment; floating menu | Modern threaded comments anchored to a slide or object; @mention (cloud); Reply; Resolve/Reopen; Like (reaction); Edit; Delete | **Modern**: `ppt/comments/modernComment_*.xml` (`p188:cmLst/p188:cm id authorId created` + `p188:txBody`, `p188:replyLst`, `status="resolved"`, anchor `ac:deMkLst`/`pc:sldMkLst`), authors in `ppt/authors.xml` (`p188:authorLst`). Slide rel type `http://schemas.microsoft.com/office/2018/10/relationships/comments`. **Legacy**: `ppt/comments/commentN.xml` (`p:cmLst/p:cm authorId dt idx` + `p:pos x y` + `p:text`) and `ppt/commentAuthors.xml`. Read both and write modern + legacy fallback | M | Y | M |
| REV-02 | Show Comments pane / Previous / Next / إظهار التعليقات / السابق / التالي | Review | Comments pane; navigation across slides | UI | S | Y | M |
| REV-03 | Delete All Comments on Slide / in Presentation / حذف كل التعليقات | Review > Delete | — | Remove comment parts and rels | S | Y | M*/D |
| REV-04 | Spelling / تدقيق إملائي | Review > Spelling (Android uses the system/keyboard spellcheck; red squiggles) **[verify on device]**; desktop Spelling pane with suggestions, Ignore, Add to Dictionary | Underlines misspellings; suggestions | Android `SpellCheckerSession` (TextServicesManager) per run language; Hunspell dictionaries for Arabic (ar) and English (en_US) bundled for offline use. Respect `a:rPr lang` and `noProof="1"` | M | Y | M |
| REV-05 | Proofing language / لغة التدقيق | Review > Language | Set the language for selected text; "Do not check spelling" | `a:rPr lang="ar-YE"`, `noProof="1"` | S | Y | M*/D |
| REV-06 | Thesaurus / المرادفات | Desktop Review | Synonyms | Offline thesaurus (WordNet for English; an Arabic synonym DB from open sources) | M | Y | D |
| REV-07 | Check Accessibility / تدقيق إمكانية الوصول | Review > Check Accessibility (Android has it) | Errors: missing alt text, missing slide titles, tables without header rows, low colour contrast, reading order, duplicate slide titles, empty table cells, hyperlink text not meaningful; each comes with a fix action | Rule engine over the model: `p:cNvPr descr` empty, no `ph type="title"`, `a:tblPr firstRow="0"`, contrast ratio < 4.5 computed from resolved fill vs text colour (WCAG formula), and so on. Results list with "Fix" deep-links | M | Y | M |
| REV-08 | Reading order pane / ترتيب القراءة | Desktop Review > Accessibility > Reading Order | Reorder how screen readers read objects (= z-order / spTree order) | Same as HOME-35 | S | Y | D→M |
| REV-09 | Translate / ترجمة | Review > Translate (cloud) | Translate selection or the whole deck | ML Kit on-device Translation (Arabic ↔ English and 50+ more; models about 30 MB each) | M | P | M (cloud) |
| REV-10 | Compare presentations / مقارنة | Desktop Review > Compare | Merge changes from another version, with revision marks | Diff the two models by shape id/name; show a change list | L | Y | D |
| REV-11 | Smart Lookup / Researcher / بحث ذكي | Desktop | Web definitions | Optional | S | N | D |
| REV-12 | Protect / Restrict editing / حماية | Desktop File > Info | Encrypt with password; Restrict Access (IRM) | See SHELL-07 | L | Y | D |
| REV-13 | Track ink & comments authorship / المؤلف | Settings > Author name | Name used in comments | User profile setting | S | Y | M |

---

## 10. View tab (عرض)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| VIEW-01 | Normal / عادي | View > Normal | Slide plus thumbnails plus notes | — | M | Y | M |
| VIEW-02 | Outline View / عرض المخطط التفصيلي | View > Outline (phone and tablet) | Edits titles and body text of all slides as one hierarchical text document; Enter = new bullet; Tab/Shift-Tab promotes or demotes; promoting to level 0 creates a new slide | Maps to title and body placeholders (`ph type="title|ctrTitle"`, `ph type="body" idx=1`). Uses a Compose `BasicTextField` per slide with a structural editor | M | Y | M |
| VIEW-03 | Notes / ملاحظات المتحدث | View > Notes (toggle; also the button under the slide) | Speaker notes per slide; formatting | `ppt/notesSlides/notesSlideN.xml` (`p:notes` with `ph type="sldImg"` and `ph type="body" idx="1"`), rel to the slide and to `notesMasters/notesMaster1.xml`. Create the notes master on first use if it is missing | M | Y | M |
| VIEW-04 | Slide Sorter / فارز الشرائح | View > Slide Sorter (tablet) **[phone: thumbnail grid on pinch-out]** | Grid of thumbnails; drag to reorder; multi-select; sections collapsible | `LazyVerticalGrid` with drag-and-drop reordering; thumbnails cached as WebP keyed by slide-content hash | M | Y | M |
| VIEW-05 | Zoom / Fit to window / تكبير / ملاءمة | Pinch; View > Zoom (tablet) | 10%–400%; Fit Slide | Canvas transform; re-rasterise at the new scale with debounce | S | Y | M |
| VIEW-06 | Ruler / Gridlines / Guides / المسطرة / خطوط الشبكة / الأدلة | Desktop View > Show; mobile: smart guides only | Gridlines (spacing), drawing guides (draggable), smart guides when moving (align to edges/centres) | Smart guides: on drag, compute candidate snap lines from other shapes' edges and centres plus the slide centre/edges; threshold 6 dp. Guides stored in `presProps.xml`/`viewProps.xml` `p:guideLst/p:guide orient pos` | M | Y | M |
| VIEW-07 | Slide Master view / عرض الشريحة الرئيسية | Desktop View > Slide Master; mobile: none | See section 13 | — | L | Y | D→M |
| VIEW-08 | Handout Master / Notes Master / النشرة / الملاحظات الرئيسية | Desktop | Layout of printed handouts/notes | `handoutMasters/handoutMaster1.xml`, `notesMaster1.xml` | M | Y | D |
| VIEW-09 | Reading View / عرض القراءة | Desktop View | Windowed show | Same as show | S | Y | D |
| VIEW-10 | Colour / Grayscale / Black & White view / رمادي | Desktop View | Preview in greyscale for printing | Colour-matrix filter on the canvas (`ColorMatrix.setSaturation(0)`) | S | Y | D |
| VIEW-11 | Immersive Reader / Read Aloud / القارئ الشامل / القراءة بصوت عالٍ | Desktop/Web View; Review > Read Aloud | TTS of slide text with highlighting | Android `TextToSpeech` (Arabic voices available offline on many devices) | S | Y | D→M |
| VIEW-12 | Focus / hide ribbon / التركيز | Phone: collapse ribbon | — | UI | S | Y | M |

---

## 11. File menu (ملف) and sharing

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| FILE-01 | New blank / New from template / جديد فارغ / من قالب | Home "+" > Blank presentation / templates (Pitch deck, Business, Education, Photo album, ...) | Template gallery (online in M365; a few cached) | Bundle about 30 offline templates as .potx/.pptx assets (Arabic-first, education-focused: lesson, quiz, lab report, revision summary, exam tips); copy on creation, then convert the potx content type to presentation (`application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml`) | M | Y | M |
| FILE-02 | Create from outline/document (Copilot) / إنشاء من مستند | See AI-01/AI-02 | — | — | L | P | M*/D |
| FILE-03 | Export to PDF / تصدير PDF | File > Print > Save as PDF / Export > PDF (Android) | Options: slides, notes pages, handouts (desktop), include hidden slides, page range | Use Android `PdfDocument` with our renderer drawing into its `Canvas` (vector output for shapes and text; fonts embedded by the platform). Notes page = slide image + notes text. Handouts (1, 2, 3, 4, 6, 9 per page) = composed page layouts | M | Y | M |
| FILE-04 | Export to Video (MP4) / تصدير فيديو | Desktop File > Export > Create a Video (quality 480p–4K, seconds per slide, use recorded timings and narrations); **not available on Android** | — | Render show frames offscreen (transitions + animations + media) and encode H.264 via `MediaCodec` + `MediaMuxer` (Media3 Transformer). Mix narration audio. **High-value differentiator for a video-lesson platform** | L | Y | D→M |
| FILE-05 | Export images (PNG/JPG per slide) / تصدير صور | Desktop Export > Change File Type; Android Share > as image of the current slide **[verify on device]** | — | Render each slide at chosen DPI → MediaStore | S | Y | D→M |
| FILE-06 | Export as GIF / Animated GIF | Desktop Export > Animated GIF | — | Frame render + GIF encoder | M | Y | D |
| FILE-07 | Save as other formats / حفظ بتنسيقات أخرى | Desktop | .ppsx (show), .potx (template), .odp, .ppt (legacy), outline .rtf | .ppsx/.potx: same package with a different main content type. .odp: separate writer (L) | M | Y | D→M |
| FILE-08 | Package for CD / Embed fonts / تضمين الخطوط | Desktop Options > Save > Embed fonts | Embeds used fonts (all characters or subset) | `p:embeddedFontLst/p:embeddedFont/p:font typeface` + `p:regular r:id` → `ppt/fonts/fontN.fntdata` (obfuscated TTF; XOR with GUID-key per ECMA-376 Part 2 §… font obfuscation). Reading: load embedded fonts so decks look right. **Important for Arabic fonts missing on recipient devices** | M | Y | D→M |
| FILE-09 | Share (link / copy / attachment / PDF) / مشاركة | Top bar Share | Invite people (cloud), copy link, send a copy as .pptx or PDF | Android Sharesheet with `FileProvider` URIs; "Send as PDF" renders first | S | Y | M |
| FILE-10 | Print / طباعة | File > Print | Android Print framework: slides, layout (full page slides, notes, handouts 1–9), colour/greyscale, range, frame slides, scale to fit | `PrintDocumentAdapter` producing PDF pages from the renderer | M | Y | M |
| FILE-11 | Properties / Info / خصائص | File > Info | Title, author, size, last modified, slide count, compress media | `docProps/core.xml` (`dc:title`, `dc:creator`, `cp:lastModifiedBy`, `dcterms:modified`), `docProps/app.xml` (`Slides`, `Notes`, `HiddenSlides`, `TitlesOfParts`). **Keep app.xml stats in sync on save** | S | Y | M |
| FILE-12 | Compress media / Reduce file size / ضغط الوسائط | Desktop File > Info > Compress Media; Picture Format > Compress Pictures | Video: Full HD/HD/Standard; pictures: 330/220/150/96 ppi; delete cropped areas | Re-encode via Media3 Transformer; downsample images to target ppi based on displayed size; apply `a:srcRect` crops permanently | M | Y | D→M |
| FILE-13 | Inspect Document / فحص المستند | Desktop | Remove comments, notes, hidden slides, personal info, off-slide content, custom XML | Rule-based cleaner | S | Y | D→M |
| FILE-14 | Settings: autosave, author, fonts download, file location, privacy / الإعدادات | App settings | — | DataStore prefs | S | Y | M |
| FILE-15 | Open recovered / Version history | See SHELL-06/09 | — | — | — | — | M |
| FILE-16 | Reuse Slides / إعادة استخدام الشرائح | Desktop Home > New Slide > Reuse Slides | Insert slides from another deck (keep source formatting or use the destination theme) | Copy slide part + layout/master (dedupe by hashing master XML) + media; remap rIds and ids | L | Y | D→M |
| FILE-17 | Duplicate presentation / Rename / Move / نسخ / إعادة تسمية | Home list long-press | — | SAF operations | S | Y | M |


---

## 12. Contextual tabs (object tools)

### 12.1 Shape tab (شكل), shown when a shape or text box is selected

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| SHP-01 | Shape Styles gallery / أنماط الأشكال | Shape > Shape Styles | See HOME-29 | `p:style` refs | M | Y | M |
| SHP-02 | Fill / تعبئة | Shape > Fill | No fill, theme/standard colours, More colours, Gradient presets (light/dark variations), Picture fill (desktop), Texture (desktop), Transparency slider (desktop Format pane) | `a:noFill`, `a:solidFill` (+ `a:alpha val="50000"`), `a:gradFill`, `a:blipFill`, `a:pattFill`, `a:grpFill` | M | Y | M |
| SHP-03 | Line / Outline / مخطط | Shape > Line | Colour, weight (¼ to 6 pt), dashes (solid, round dot, square dot, dash, dash dot, long dash, long dash dot, long dash dot dot), arrows (begin/end type and size), compound type, cap, join | `a:ln w="12700" cap="rnd" cmpd="sng|dbl|thickThin|thinThick|tri"` + `a:prstDash val`, `a:headEnd type="triangle|stealth|diamond|oval|arrow" w len`, `a:tailEnd`, `a:round|a:bevel|a:miter` | M | Y | M |
| SHP-04 | Shape Effects / تأثيرات الشكل | Desktop Shape Format > Shape Effects (mobile: Shadow and Reflection presets **[verify on device]**) | Preset, Shadow (outer 9, inner 9, perspective 5), Reflection (9), Glow (colour × size), Soft Edges (1–50 pt), Bevel (12), 3-D Rotation (parallel, perspective, oblique presets) | `a:effectLst/a:outerShdw blurRad dist dir algn rotWithShape`, `a:innerShdw`, `a:reflection`, `a:glow rad`, `a:softEdge rad`; 3D: `a:scene3d/a:camera prst` + `a:sp3d/a:bevelT`. Render shadow with `Paint.setShadowLayer` or a RenderEffect blur (API 31+); reflection by drawing a flipped, gradient-masked copy; glow = blurred dilated silhouette. 3D bevel: approximate with lighting gradients (L) | L | Y | M*/D |
| SHP-05 | Edit Shape: Change Shape / تغيير الشكل | Desktop Shape Format > Edit Shape; mobile: Change Shape **[verify on device]** | Swap the preset but keep its formatting and text | Replace `a:prstGeom prst` (drop avLst) | S | Y | M*/D |
| SHP-06 | Edit Points / تحرير النقاط | Desktop | Convert to freeform; drag vertices; smooth/corner/straight points | Convert the preset to `a:custGeom` (pathLst with moveTo/lnTo/cubicBezTo/arcTo/close) | L | Y | D→M |
| SHP-07 | Adjustment handles (yellow) / مقابض الضبط | On-canvas | E.g. rounded corner radius, arrow head size | `a:avLst/a:gd name="adj" fmla="val 16667"`; handle definitions from the preset `a:ahLst` (`ahXY gdRefX minX maxX`, `ahPolar`) | M | Y | M |
| SHP-08 | Merge Shapes / دمج الأشكال | Desktop Shape Format > Merge Shapes | Union, Combine, Fragment, Intersect, Subtract | Path boolean ops (Android `Path.op` supports UNION, INTERSECT, DIFFERENCE, XOR) → `a:custGeom` | M | Y | D→M |
| SHP-09 | Size & Position / الحجم والموضع | Shape > Size (height/width), Lock Aspect Ratio; Format pane Position | Numeric entry; units cm/in by locale | `a:xfrm a:ext cx cy` (EMU: 914400/in, 360000/cm, 12700/pt); lock = `a:spLocks noChangeAspect="1"` | S | Y | M |
| SHP-10 | Text inside shape / نص داخل الشكل | Tap the shape and type | Text formatting as Home; text box options (HOME-27) | `p:txBody` on `p:sp`; the text rect is defined by the preset `a:rect l t r b` | M | Y | M |
| SHP-11 | Quick Styles for text (WordArt styles) / أنماط WordArt | Shape > Text Styles (tablet) **[verify on device]** | Text fill, outline and effects | See INS-23 | M | Y | M*/D |
| SHP-12 | Alt Text / النص البديل | Shape > Alt Text; floating menu | Description; "Mark as decorative" | `p:cNvPr descr="…"`; decorative = `a:extLst/a:ext uri="{C183D7F6-B498-43B3-948B-1728B52AA6E4}"/adec:decorative val="1"` | S | Y | M |
| SHP-13 | Lock object / قفل | Desktop (2022+) right-click > Lock | Prevents moving or editing | `p:cNvSpPr/a:spLocks noMove noResize noRot noSelect…` (or the `a16:` lock extension) | S | Y | D→M |
| SHP-14 | Eyedropper / القطارة | Desktop colour menus | Pick a colour from any slide pixel | Sample the rendered bitmap at the touch point | S | Y | D→M |
| SHP-15 | Save as Picture / حفظ كصورة | Desktop right-click | Export a shape as PNG/SVG | Render the shape alone to a bitmap; SVG export = path serialisation (M) | S | Y | D→M |

### 12.2 Picture tab (صورة)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| PIC-01 | Picture Styles / أنماط الصور | Picture > Picture Styles | 28 presets (Simple Frame White, Beveled Matte, Drop Shadow Rectangle, Rounded Diagonal Corner, Soft Edge, Reflected, Metal Frame, Perspective Shadow, Rotated White, Oval, Bevel Perspective, and so on) | Each preset = `prstGeom` + `a:ln` + effects in spPr. Capture the 28 XML templates from desktop output | M | Y | M |
| PIC-02 | Crop / اقتصاص | Picture > Crop | Free crop with handles; Crop to Shape; Aspect ratio (1:1, 2:3, 3:4, 3:5, 4:5, 16:9, and so on); Fill / Fit | `p:blipFill/a:srcRect l t r b` (in 1/1000 %); crop to shape = change `prstGeom`. Interactive crop UI shows the outside region dimmed | M | Y | M |
| PIC-03 | Rotate / Flip / تدوير / قلب | Picture tab | — | `a:xfrm rot flipH flipV` | S | Y | M |
| PIC-04 | Reset Picture / إعادة تعيين | Picture tab | Remove all formatting and crops | Clear spPr effects and srcRect; restore the native size | S | Y | M |
| PIC-05 | Picture Corrections / تصحيحات | Desktop; mobile **[verify on device]** | Sharpen/Soften (±100 %), Brightness/Contrast presets (-40 % to +40 %) | `a:blip/a:extLst/a14:imgProps/a14:imgLayer/a14:imgEffect/a14:sharpenSoften amount`, `a14:brightnessContrast bright contrast` (legacy `a:lum bright contrast`). Render with `ColorMatrix` (brightness/contrast) and a convolution kernel or RenderEffect for sharpen | M | Y | D/M* |
| PIC-06 | Picture Color / لون الصورة | Desktop | Saturation (0 %–400 %), Tone (temperature 4700K–11200K), Recolor (Grayscale, Sepia, Washout, Black & White 25/50/75 %, accent duotones), Set Transparent Color | `a14:saturation sat`, `a14:colorTemperature colorTemp`, `a:grayscl`, `a:duotone`, `a:biLevel thresh`, `a:clrChange` (transparent colour). All map to ColorMatrix/AGSL shaders | M | Y | D→M |
| PIC-07 | Artistic Effects / تأثيرات فنية | Desktop | Marker, Pencil Grayscale, Pencil Sketch, Line Drawing, Chalk Sketch, Paint Strokes, Paint Brush, Glow Diffused, Blur, Light Screen, Watercolor Sponge, Film Grain, Mosaic Bubbles, Glass, Cement, Texturizer, Crisscross Etching, Pastels Smooth, Plastic Wrap, Cutout, Photocopy, Glow Edges | `a14:artisticPencilSketch` and so on. Office also stores a pre-rendered `a14:imgLayer r:embed` (HD photo), so **render from the cached processed image when present**; apply locally with shaders for new effects | L | Y | D |
| PIC-08 | Remove Background / إزالة الخلفية | Desktop (and newer mobile builds **[verify on device]**) | Automatic subject detection; mark areas to keep/remove | ML Kit Subject Segmentation (on-device, Android) → write a new PNG with alpha (or `a14:imgEffect/a14:backgroundRemoval` with marks). Writing a new PNG is the simplest path and stays compatible | M | Y | D/M* |
| PIC-09 | Transparency / الشفافية | Desktop Picture Format > Transparency | 0–100 % | `a:blip/a:alphaModFix amt="50000"` | S | Y | D→M |
| PIC-10 | Change Picture / تغيير الصورة | Desktop | Replace the image but keep size and format | Swap the media rel target | S | Y | D→M |
| PIC-11 | Compress Pictures / ضغط الصور | Desktop | See FILE-12 | — | M | Y | D |
| PIC-12 | Alt Text (auto-generated) / نص بديل تلقائي | Picture > Alt Text | Office auto-generates descriptions (cloud vision) | ML Kit Image Labeling (on-device) for keywords; on-device multimodal LLM (Gemini Nano with multimodality on supported Pixels/Galaxy) for captions | M | P | M |
| PIC-13 | Convert to SmartArt / Picture layout | Desktop | — | Skip | — | — | D |
| PIC-14 | Picture placeholder fill / ملء عنصر نائب للصورة | Tap the icon in an empty picture placeholder | Inserts and crops to fill the placeholder | `p:pic` with `p:nvPr/p:ph type="pic" idx`; compute `srcRect` for a "fill" crop | S | Y | M |

### 12.3 Table tab (جدول) and Table Design/Layout

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| TBL-01 | Insert rows/columns / إدراج صفوف / أعمدة | Table > Insert | Above, Below, Left, Right | Add `a:tr` / `a:gridCol` + a `a:tc` in each row; keep the total width (scale columns) or grow the frame | M | Y | M |
| TBL-02 | Delete rows/columns/table / حذف | Table > Delete | — | Inverse; fix merges that span the deleted range | M | Y | M |
| TBL-03 | Select row/column/table / تحديد | Table > Select | — | UI | S | Y | M |
| TBL-04 | Merge / Split cells / دمج / تقسيم الخلايا | Table > Merge (tablet/desktop; phone **[verify on device]**) | Merge a selection; split into R×C | Merges: top-left `a:tc gridSpan="2" rowSpan="2"`, covered cells `hMerge="1"`/`vMerge="1"` (keep their `a:tc`). Split = inverse or insert grid lines | M | Y | M*/D |
| TBL-05 | Table Styles / أنماط الجدول | Table > Table Styles | 74 built-in styles: Themed, Light 1–3, Medium 1–4, Dark 1–2 × (no accent + accent 1–6) | `a:tableStyleId` GUID → definition in `tableStyles.xml` or **bundled built-in definitions**. Style parts: wholeTbl, band1H, band2H, band1V, band2V, firstRow, lastRow, firstCol, lastCol, seCell, swCell, neCell, nwCell, each with tcTxStyle and tcStyle (fill, borders). Cell formatting precedence: direct tcPr > style part (resolve in this order: wholeTbl → bands → first/last col → first/last row → corner cells) | L | Y | M |
| TBL-06 | Style Options / خيارات النمط | Table > Style Options | Header Row, Total Row, Banded Rows, First Column, Last Column, Banded Columns | `a:tblPr firstRow lastRow bandRow firstCol lastCol bandCol` | S | Y | M |
| TBL-07 | Shading / Borders / Effects / تظليل / حدود | Table > Shading/Borders (tablet) | Cell fill; border preset (all, outside, inside, top...), pen style/weight/colour; cell bevel; table shadow/reflection | `a:tcPr/a:solidFill`, `a:lnL a:lnR a:lnT a:lnB a:lnTlToBr a:lnBlToTr` | M | Y | M*/D |
| TBL-08 | Cell alignment & margins & text direction / محاذاة الخلية / الهوامش | Table > Alignment | Horizontal (via paragraph), vertical (top/centre/bottom), cell margins, text direction | `a:tcPr anchor="t|ctr|b" marL marR marT marB vert` | S | Y | M |
| TBL-09 | Row height / Column width / Distribute / الارتفاع / العرض / توزيع | Drag borders; Table > Size | Distribute rows/columns evenly | `a:tr h`, `a:gridCol w`. Note: actual row height grows to fit text, so layout must compute it | M | Y | M |
| TBL-10 | RTL table / جدول من اليمين لليسار | (Office: `rtl` on tblPr) | Column order mirrored | `a:tblPr rtl="1"`. **Must support for Arabic** | S | Y | M |
| TBL-11 | Draw/erase table borders / رسم الجدول | Desktop | — | Skip | — | — | D |
| TBL-12 | Paste table from Excel/Sheets / لصق جدول | Clipboard | HTML table → a:tbl | Parse `text/html` clipboard | M | Y | M |

**Table rendering:** compute column x-positions from gridCol, lay out each cell's text (wrapping in cell width minus margins) to find row heights = max(h, content), then draw fills, then borders (shared-edge conflict rules: last-drawn wins, as in PowerPoint), then text. Edit cell text with an inline `BasicTextField` overlay.

### 12.4 Video / Audio tabs (فيديو / صوت)

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| MED-01 | Play / Pause preview / تشغيل | Video tab; on-object controls | — | ExoPlayer | S | Y | M |
| MED-02 | Start: Automatically / On Click / In Click Sequence / البدء | Video/Audio tab > Start **[verify on device]** | — | `p:timing` media node (ANI-15) | M | Y | M*/D |
| MED-03 | Play full screen, Loop until stopped, Rewind after playing, Hide while not playing, Volume / تشغيل بملء الشاشة / تكرار | Desktop Playback; mobile partial | — | `p:video fullScrn="1"`, `p:cMediaNode vol="80000" mute showWhenStopped="0"`, `p:cTn repeatCount="indefinite"` | S | Y | M*/D |
| MED-04 | Trim Video/Audio / قص الفيديو | Desktop Playback > Trim | Start/end time | `p14:media/p14:trim st="1000" end="5000"` (ms) | S | Y | D→M |
| MED-05 | Fade in / Fade out / تلاشي | Desktop | Seconds | `p14:media/p14:fade in out` | S | Y | D→M |
| MED-06 | Bookmarks / إشارات مرجعية | Desktop | Points in media that trigger animations | `p14:bmkLst/p14:bmk name time` | M | Y | D |
| MED-07 | Poster Frame / إطار الملصق | Desktop Video Format > Poster Frame | Current frame or image | Replace `p:blipFill` image with `MediaMetadataRetriever.getFrameAtTime` | S | Y | D→M |
| MED-08 | Video Styles / shape / border / effects / أنماط الفيديو | Desktop | Same as picture styles | Same spPr | M | Y | D |
| MED-09 | Play across slides (audio) / تشغيل عبر الشرائح | Desktop Audio > Play in Background | — | `p:cMediaNode numSld="999"` + `showWhenStopped="0"` | S | Y | D→M |
| MED-10 | Captions (insert .vtt) / التسميات التوضيحية | Desktop Playback > Insert Captions | WebVTT captions for video | `p14:media`, caption track part (MS extension) | M | Y | D |
| MED-11 | Record audio / تسجيل صوت | Insert > Audio > Record (mobile **[verify on device]**) | — | `MediaRecorder` AAC/M4A → `p:pic` audio icon + media rels | S | Y | M* |

### 12.5 Chart tools (مخطط)

| ID | Feature (EN / AR) | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|
| CHT-01 | Render charts / عرض المخططات | All types render on Android | ChartML renderer (c:barChart, c:lineChart, c:pieChart, c:doughnutChart, c:areaChart, c:scatterChart, c:radarChart, c:bubbleChart, combos; chartex `cx:` for Waterfall/Treemap/Sunburst/Histogram/Box/Funnel = preserve and render the fallback image) | L | Y | M |
| CHT-02 | Chart Styles & Colors / أنماط المخطط | Gallery | `c:style`, `cs:chartStyle` parts (chartStyle1.xml, colors1.xml) | M | Y | M*/D |
| CHT-03 | Chart Elements / عناصر المخطط | Title, axes, axis titles, legend (position), data labels, gridlines, trendline, error bars | `c:title`, `c:legend/c:legendPos`, `c:dLbls`, `c:majorGridlines`, `c:trendline` | M | Y | M*/D |
| CHT-04 | Edit Data / تحرير البيانات | Grid editor | Update `c:numCache`/`c:strCache` + rewrite the embedded xlsx `sheet1.xml` (minimal SpreadsheetML writer) | L | Y | M*/D |
| CHT-05 | Change Chart Type / تغيير نوع المخطط | Switch between types | Rewrite plotArea | M | Y | D→M |

### 12.6 Ink object tab

| ID | Feature | Behaviour | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|
| INK-01 | Ink colour/thickness change after drawing / تغيير لون الحبر | Select an ink object > Pen colour | Rewrite InkML brush | S | Y | M |
| INK-02 | Convert ink to shape/text | See DRAW-07/08 | — | — | — | M |

---

## 13. Slide master, layouts and theme editing (الشريحة الرئيسية والتخطيطات)

**PowerPoint Android status:** there is **no Slide Master view on Android**. The master and layouts are fully *rendered* and *used*: New Slide/Layout lists the layouts, and placeholders inherit formatting. Applying a theme or variant replaces the master. Editing a master or layout requires desktop or Web (Web has only limited master editing **[verify]**).

**Proposal:** a touch "Master editor" mode in our app (desktop-parity feature, D→M).

| ID | Feature (EN / AR) | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|
| MST-01 | Inheritance renderer / الوراثة | Slide → layout → master → theme cascade for placeholder geometry, text styles, background, colour map | Placeholder match: slide `ph` (type, idx) → layout `ph` with same idx (or type) → master `ph` of the same type. Text style cascade (lowest priority first): presentation `p:defaultTextStyle` → master `p:txStyles` (titleStyle/bodyStyle/otherStyle) → master placeholder `lstStyle` → layout placeholder `lstStyle` → slide shape `lstStyle` → paragraph `pPr` → run `rPr`. Colour map: master `p:clrMap bg1="lt1" tx1="dk1"…`, overridable by `p:clrMapOvr` on layout/slide. **This cascade is the core of correct rendering; build and unit-test it first** | L | Y | M |
| MST-02 | Slide Master view / عرض الشريحة الرئيسية | Opens a list: master on top, layouts below | Edit the master/layout parts with the same canvas editor | M | Y | D→M |
| MST-03 | Insert / delete / rename / duplicate layout / تخطيط جديد | — | New `slideLayoutN.xml` (`p:sldLayout type="cust"` + `p:cSld name`), add to master `p:sldLayoutIdLst` (id ≥ 2147483648), rels + content types | M | Y | D→M |
| MST-04 | Insert placeholder / إدراج عنصر نائب | Content, Content (vertical), Text, Picture, Chart, Table, SmartArt, Media, Online Image | `p:sp` with `p:ph type="body|pic|chart|tbl|dgm|media|clipArt" idx="n" sz orient` | M | Y | D→M |
| MST-05 | Title / footers toggles on layout / العنوان والتذييلات | Toggle title and footer placeholders | Add/remove those `p:sp`s | S | Y | D→M |
| MST-06 | Master text styles / أنماط نص الرئيسية | Edit level 1–9 fonts, bullets, indents | `p:txStyles/p:bodyStyle/a:lvl1pPr…a:lvl9pPr` | M | Y | D→M |
| MST-07 | Background on master/layout / خلفية الرئيسية | Same as DES-05; Hide background graphics on a layout | `p:bg`; `showMasterSp="0"` on the layout | S | Y | D→M |
| MST-08 | Preserve master / multiple masters / الحفاظ على الرئيسية | Keep an unused master | `p:sldMaster preserve="1"`; multiple `sldMasterId` | S | Y | D |
| MST-09 | Edit theme colours/fonts (custom) / ألوان وخطوط مخصصة | Create a colour scheme and font pair | Rewrite `theme1.xml`; optionally save as .thmx | M | Y | D→M |
| MST-10 | Save as template (.potx) / حفظ كقالب | — | Change the main content type; strip slides optionally | S | Y | D→M |

---

## 14. Notes, sections and organisation

| ID | Feature (EN / AR) | Location | Behaviour & options | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|---|
| NOTE-01 | Speaker notes editor / ملاحظات المتحدث | Notes button (phone: below the slide; tablet: pane) | Rich text: bold/italic/underline, bullets, colour **[verify on device]** | VIEW-03 | M | Y | M |
| NOTE-02 | Notes in presenter view / الملاحظات في عرض المقدم | Show | Scrollable notes; font size +/- | Presenter UI | S | Y | M |
| NOTE-03 | Notes Page print/export / صفحة الملاحظات | Print/PDF | Slide image + notes | FILE-03 | M | Y | M |
| NOTE-04 | Auto-scroll teleprompter / ملقّن | **Not in PowerPoint mobile** (desktop has a Teleprompter view in Record mode) | Scrolls notes at speed during the show/recording | Simple auto-scroll + speed control; optional voice-following via ASR | M | Y | D→M |
| SEC-01 | Add / Rename / Remove / Move section / مقطع | Thumbnail menu (tablet); Slide Sorter | Collapse/expand in the thumbnail rail | HOME-10 | M | Y | M*/D |
| SEC-02 | Section Zoom / تكبير المقطع | Desktop Insert > Zoom | — | INS-17 | L | Y | D→M |

---

## 15. Copilot and AI features (الذكاء الاصطناعي)

**Status on Android (Microsoft support, 2026):** Copilot in PowerPoint mobile on Android can **show key slides** (a summary of a presentation's important slides) and **answer questions about the deck**, with suggested Q&As, typed questions or voice input. This works for decks shared with the user. Microsoft documents full Copilot authoring (create a deck from a prompt or file, add slides, rewrite, organise, generate images) for **iPad, Windows, Mac and Web**. The Android page notes that for eligible Microsoft 365 work subscriptions, Copilot in PowerPoint is "currently only available on iPad devices, along with Mac and Windows". The Designer, Presenter Coach and Translate features use the cloud as well.

Our app should provide these with a hybrid design: **on-device first** (Gemini Nano via Android AICore / ML Kit GenAI APIs on supported devices, or a small local LLM such as Gemma 3 1B/Qwen 2.5 1.5B through MediaPipe LLM Inference / llama.cpp), with an **optional cloud LLM** for large generation. Arabic quality: verify each model's Arabic support. Small local models are weak in Arabic, so treat cloud as the quality path and local as the fallback.

| ID | Feature (EN / AR) | PowerPoint behaviour | Our implementation | Diff | Off | Plat |
|---|---|---|---|---|---|---|
| AI-01 | Create presentation from prompt / إنشاء عرض من وصف | Copilot > "Create a presentation about…": outline → slides with titles, bullets, speaker notes, images (stock/generated) and a theme | LLM → **strict JSON outline schema** (`{slides:[{layout, title, bullets[], notes, imageQuery, iconKeywords[]}]}`) → deterministic deck builder (our layouts + themes + offline icon pack) → Designer pass (DES-06). Arabic prompts produce RTL slides | L | P | M*/D |
| AI-02 | Create from file (Word/PDF) / إنشاء من ملف | Desktop/Web/iPad: "Create presentation from file" (.docx/.pdf, with headings preserved) | Extract text (docx: `word/document.xml` headings; PDF: PdfRenderer + text extraction via PDFBox-Android) → chunk → LLM outline → builder. **Very relevant: textbook chapter → lesson deck** | L | P | D/M* |
| AI-03 | Summarize presentation / Show key slides / تلخيص العرض / الشرائح الرئيسية | Android: "Show key slides", summary with slide citations | Concatenate slide titles/text/notes → LLM summary with slide references (tap to jump) | M | P | M |
| AI-04 | Ask questions about the deck (Q&A) / اسأل عن العرض | Android: suggested Q&As + free questions + voice | RAG over slide texts (small, fits in context); voice input via SpeechRecognizer | M | P | M |
| AI-05 | Add a slide about… / إضافة شريحة عن | Desktop/iPad Copilot | LLM → single-slide JSON → builder | M | P | D/M* |
| AI-06 | Rewrite / Make concise / Change tone / إعادة صياغة / اختصار | Desktop Copilot "Rewrite" on a text box | LLM text transform; keep run formatting by mapping paragraphs | M | P | D |
| AI-07 | Organize presentation / Add agenda / sections / تنظيم العرض | Desktop Copilot | LLM proposes sections + agenda slide; apply sections (HOME-10) | M | P | D |
| AI-08 | Generate image (Designer / DALL·E) / توليد صورة | Copilot "Add an image of…" | Cloud image API (optional); offline: icon/illustration pack search | M | N | D |
| AI-09 | Speaker notes generation / توليد ملاحظات المتحدث | Copilot "Add speaker notes" | LLM per slide → notesSlide | S | P | D |
| AI-10 | Designer suggestions / اقتراحات التصميم | DES-06 | Rule-based + optional LLM ranking | L | Y | M |
| AI-11 | Alt text generation / توليد نص بديل | PIC-12 | On-device vision | M | P | M |
| AI-12 | Translate presentation / ترجمة العرض | Copilot/Translator | ML Kit Translate, slide by slide, keeping runs; flip `rtl` and alignment when the target is Arabic | M | P | M (cloud) |
| AI-13 | Presenter Coach / المدرب | SHOW-08 | On-device ASR + metrics | L | P | M |
| AI-14 | Narrated video from deck (Clipchamp-like) / فيديو بصوت معلّق | Not in PowerPoint (desktop narrations are manual) | Notes → TTS (Android TTS or Gemini TTS cloud) → per-slide audio → FILE-04 video export. **Perfect fit with the existing lesson-video pipeline** | L | P | Our |
| AI-15 | Quiz generation from slides / توليد أسئلة من الشرائح | Not in PowerPoint (Forms Copilot only) | LLM → MCQ JSON → quiz slides with triggers (ANI-10) | M | P | Our |

---

## 16. Accessibility and alt text (إمكانية الوصول)

| ID | Feature (EN / AR) | Behaviour | Impl. note | Diff | Off | Plat |
|---|---|---|---|---|---|---|
| ACC-01 | Alt text for pictures, shapes, charts, tables, groups, media / نص بديل | Description field + Mark as decorative | `p:cNvPr descr` (+ `title`), decorative extension | S | Y | M |
| ACC-02 | Accessibility Checker / مدقق إمكانية الوصول | REV-07 | — | M | Y | M |
| ACC-03 | TalkBack support in editor and show / دعم TalkBack | Screen reader reads slides in reading order | Compose `semantics {}` per shape in spTree order; slide title as heading | M | Y | M |
| ACC-04 | Captions/subtitles for media / تسميات | MED-10, SHOW-09 | — | M | P | D/M* |
| ACC-05 | High-contrast UI & large text / تباين عالٍ | Follows system font scale in chrome | Material 3 + `fontScale` | S | Y | M |
| ACC-06 | Slide titles check / عناوين الشرائح | Every slide needs a unique title (can be hidden off-slide) | Rule + "Add hidden title" fix (title placed off-canvas) | S | Y | D→M |

---

## 17. Desktop PowerPoint features worth bringing to mobile (summary, marked D→M above)

Ranked by value for an education-focused Arabic slides editor:

1. **Export to video (FILE-04)** with narration/TTS (AI-14): direct fit with lesson-video production.
2. **Record slide show with narration + ink (SHOW-15)** and a teleprompter (NOTE-04).
3. **Equations (INS-22)**: chemistry/physics/maths; LaTeX input → OMML.
4. **Motion paths drawn by finger (ANI-04)**, **triggers (ANI-10)** and an **animation pane (ANI-09)**.
5. **Zoom: Summary/Section/Slide (INS-17)** for non-linear lessons.
6. **Slide master editor (section 13)** and **theme colour/font customisation (DES-03)**.
7. **Selection pane (HOME-35)**, lock objects (SHP-13), merge shapes (SHP-08), edit points (SHP-06).
8. **Embed fonts (FILE-08)**: Arabic fonts render on any device.
9. **Reuse slides (FILE-16)**, photo album (INS-26), custom shows (SHOW-12), kiosk loop (SHOW-13), rehearse timings (SHOW-14).
10. **Picture tools**: transparency, recolour, remove background (PIC-06/08/09), compress media (FILE-12).
11. **Charts with data editing (CHT-04)** and basic **SmartArt (INS-10)**.
12. **Cameo (INS-27)** and **screen recording (INS-14)**.

---

## 18. Google Slides (Android) features that PowerPoint Android lacks or does differently

| ID | Feature (EN / AR) | Google Slides behaviour | Value / Impl. note | Diff | Off |
|---|---|---|---|---|---|
| GS-01 | Real-time co-editing with presence / التحرير المشترك الفوري | Live cursors, selection colours, instant sync (PowerPoint also co-authors, but more slowly, with save/merge semantics) | Needs a server with CRDT/OT over the slide model (Yjs-like). Phase 3 | L | N |
| GS-02 | Offline mode per file toggle / متاح بلا اتصال | "Make available offline" per file, with sync on reconnect | Ours is local-first by design; add a sync queue | M | Y |
| GS-03 | Q&A audience tool / أسئلة الجمهور | Audience submits questions at a short URL during the show; presenter sees them in presenter view | LAN web server (SHOW-11) + question board | M | P |
| GS-04 | Present to a meeting (Meet) / العرض في اجتماع | Present the deck directly into Google Meet | Out of scope | — | N |
| GS-05 | Explore / "Help me visualize" / Gemini in Slides / ساعدني في التصميم | Gemini side panel: generate slides, images, rewrite, summarise (Workspace plans; mobile has a Gemini panel in newer builds **[verify]**) | AI section 15 | L | P |
| GS-06 | Version history with named versions / تسمية الإصدارات | Full revision timeline per editor | SHELL-06 + "Name this version" | M | Y |
| GS-07 | Link slides / linked objects from Sheets/Docs (sync) / ربط | Charts and slides linked to source files, with an "Update" button | Our equivalent: linked chart to an xlsx in the same folder (refresh) | M | Y |
| GS-08 | Simpler theme builder / Theme panel | Change theme + import theme from another deck | FILE-16 variant (import master only) | M | Y |
| GS-09 | Slide transitions/animations simple panel / لوحة الحركات المبسطة | One "Motion" panel with transitions + per-object animations in a list (reorder by drag), playable preview | **Adopt this UX** for our ANI-09 | M | Y |
| GS-10 | Laser pointer with trail; "Present on another screen" via Chromecast first-class | Cast | SHOW-10 | M | P |
| GS-11 | Comment assignment (action items) / تعيين مهمة | @mention assigns a task in a comment | Comments extension (store in our own ext or custom XML) | M | P |
| GS-12 | Web publishing / Embed / نشر على الويب | Publish as a web page with auto-advance | Static HTML export (each slide as SVG/PNG + JS player). **Useful for sharing lessons on the platform website** | M | Y |
| GS-13 | Smart chips / Linked previews | — | Low priority | — | — |
| GS-14 | Speaker notes dictation / Voice typing in notes | Voice typing | HOME-39 | S | P |

---

## 19. WPS Presentation (WPS Office for Android) features that PowerPoint Android lacks

| ID | Feature (EN / AR) | WPS behaviour | Value / Impl. note | Diff | Off |
|---|---|---|---|---|---|
| WPS-01 | WPS AI: generate PPT from topic/outline/document / توليد عرض بالذكاء الاصطناعي | Prompt → outline (editable) → pick a template → full deck; AI beautify one slide; AI rewrite; AI speaker notes | Same as AI-01/AI-02 with an **editable outline step** (adopt this UX: the user approves the outline before generation) | L | P |
| WPS-02 | One-tap "Beautify" / Smart layout / تجميل بلمسة | Restyle the whole deck or a slide with templates | DES-06 applied deck-wide | L | Y |
| WPS-03 | Huge template & graphic library (Docer) / مكتبة قوالب ضخمة | Thousands of templates, infographics, charts (many premium) | Our curated offline packs + optional online catalogue | M | P |
| WPS-04 | Play mode tools: laser, pen, spotlight/magnifier, timer, autoplay, loop / أدوات العرض | Built-in timer, autoplay interval, spotlight | Add a **spotlight** (dim everything but a circle under the finger) and a **magnifier lens** in the show | S | Y |
| WPS-05 | Shared Play (remote presenting to others' devices) / العرض المشترك | Presenter generates an access code; others follow the slides live on their devices | LAN/Nearby (SHOW-11, SHOW-22). **Classroom killer feature** | L | P |
| WPS-06 | Record presentation as video (screen + voice) / تسجيل العرض كفيديو | Records the show with audio to an MP4 on the phone | SHOW-15 + FILE-04 | L | Y |
| WPS-07 | Export to long image / pictures / تصدير كصورة طويلة | Exports all slides stitched as one long image, or each slide as an image; watermark options | Easy: render + stitch. **Shareable on WhatsApp for students** | S | Y |
| WPS-08 | PPT to PDF / PDF to PPT conversion / تحويل PDF إلى عرض | PDF → editable slides | PDF text/image extraction → shapes (approximate layout). Phase 3 | L | Y |
| WPS-09 | Mobile View / reflow reading of slides / عرض الجوال | Reflows slide text for reading on a small screen | Outline-based reader mode | S | Y |
| WPS-10 | Eye-protection / night reading modes / وضع حماية العين | Warm filter for reading | UI filter | S | Y |
| WPS-11 | Free on large tablets, no account wall / مجاني على الأجهزة اللوحية | Edits on tablets for free (ads/premium tiers) | Positioning | — | — |
| WPS-12 | File compression / reduce size / ضغط الملف | One-tap compress | FILE-12 | M | Y |
| WPS-13 | Speaker timer with vibration cues / مؤقت باهتزاز | Vibrates at set intervals during the show | `Vibrator` cues at configured times | S | Y |
| WPS-14 | Slide thumbnails as a story / Share as images to social | Share selected slides as images | FILE-05 | S | Y |
| WPS-15 | Watermark / علامة مائية | Add a text/image watermark to slides or exports | Master-level shape or export-time overlay (**brand "أفتهم لي" on exported lessons**) | S | Y |
| WPS-16 | Text recognition (OCR) from images into slides / التعرف على النص | Picture to text | ML Kit Text Recognition v2 covers Latin, Chinese, Devanagari, Japanese and Korean on-device, **but not Arabic**, so use Tesseract with `ara` traineddata offline | M | Y |

---

## 20. Architecture blueprint for a native Compose .pptx editor

### 20.1 Package parts our app must read and write

```
[Content_Types].xml                 Defaults (png, jpeg, xml, rels, wav, mp4, m4a, svg, emf, wmf, fntdata, bin) + Overrides per part
_rels/.rels                         officeDocument → ppt/presentation.xml; core/app props; thumbnail
docProps/core.xml, app.xml, custom.xml, thumbnail.jpeg
ppt/presentation.xml                sldMasterIdLst, notesMasterIdLst, handoutMasterIdLst, sldIdLst, sldSz, notesSz, embeddedFontLst, custShowLst, defaultTextStyle, extLst (sections p14:sectionLst)
ppt/_rels/presentation.xml.rels
ppt/presProps.xml (showPr, penClr, extLst)   ppt/viewProps.xml   ppt/tableStyles.xml
ppt/theme/theme1.xml (+ theme per notes/handout master)
ppt/slideMasters/slideMaster1.xml (+ rels to layouts + theme)
ppt/slideLayouts/slideLayout1..11.xml (+ rel to master)
ppt/slides/slideN.xml (+ rels: layout, notesSlide, media, charts, comments, hyperlinks, ink)
ppt/notesSlides/notesSlideN.xml   ppt/notesMasters/notesMaster1.xml   ppt/handoutMasters/handoutMaster1.xml
ppt/media/*   ppt/charts/chartN.xml + style/colors + embeddings/*.xlsx   ppt/diagrams/*   ppt/ink/inkN.xml
ppt/comments/modernComment_*.xml   ppt/authors.xml   ppt/commentAuthors.xml (legacy)
ppt/fonts/*.fntdata   ppt/embeddings/*   ppt/vbaProject.bin (preserve only)
```

### 20.2 Model and round-trip strategy
- **Lossless round-trip principle:** parse into a typed model for the elements we understand, and keep a **raw XML DOM node per element** for everything else (unknown attributes, `mc:AlternateContent`, `extLst`). Serialise by patching the original DOM. Copy unchanged zip entries byte-for-byte. This is the main defence against corrupting files made by Office.
- Handle `mc:AlternateContent` with a `Requires` check: render the Choice if we support its namespace (p14, p15, p159, a14, asvg), otherwise render the Fallback. Never drop either branch.
- IDs: shape ids (`p:cNvPr id`) must be unique per slide; slide ids ≥ 256; layout/master ids ≥ 2^31; and rIds must be unique per rels file.
- Units: EMU (914400 per inch, 12700 per point). The renderer scales EMU → px by `viewWidthPx / sldSz.cx`.

### 20.3 Rendering pipeline (custom Compose Canvas)
1. **Resolve** (pure Kotlin, cached per slide): inheritance (MST-01) → a flat render tree of `ResolvedShape` (geometry path, fill, line, effects, text frames, transforms, z-order).
2. **Geometry engine:** preset shape guide formulas → `Path` (cached by prst + avLst + size).
3. **Text engine:** paragraphs → runs → `TextMeasurer`/`StaticLayout` with spans (font, size, colour, baseline, underline, highlight); bidi + Arabic shaping via the platform's HarfBuzz; autofit; bullets/numbering (Arabic-Indic digits); columns; vertical text. Handle font fallback chains for Latin/Arabic/symbol fonts and embedded fonts.
4. **Paint:** `DrawScope` draws background, then master shapes (if showMasterSp), then layout shapes, then slide shapes. Effects use `RenderEffect`/`BlurMaskFilter`; images use `ImageBitmap` decoded at the target size (Coil with a custom zip fetcher; LRU cache); SVG uses AndroidSVG; EMF/WMF (common in old decks) need a converter, so render the PNG fallback if present or use a minimal EMF player (L).
5. **Thumbnails:** the same pipeline at low resolution on `Dispatchers.Default` into `Bitmap`s, cached on disk by content hash.
6. **Editor overlay:** a separate composable layer for selection handles, rotation handle, adjustment handles, smart guides, crop UI, text cursor/IME (`BasicTextField` overlaid at the text frame, synced back to runs), and multi-touch gestures (drag, pinch-resize, two-finger rotate).
7. **Show engine:** the timing tree (section 7.3) + transition compositor (section 6.2), with full-screen rendering at the display refresh rate and pre-rasterised layers.

### 20.4 Suggested build order (MVP → v3)
- **MVP (about 3 months):** SHELL-01/03/04/10, MST-01 renderer, HOME-03/04/06–09/11–23/28–34/36, INS-02/03/07/08/15/19/20, DES-01/02/04/05, TRN-01/02/04 (Subtle group + Morph-lite), ANI-01/03/05/14 (top 10 entrance/exit), SHOW-01/03/04/06, VIEW-02/03/04, FILE-01/03/09/10, REV-01/04/07, ACC-01, full RTL/Arabic.
- **v2:** tables complete, charts render, notes/presenter view, external display, ink suite + ink to shape, Designer (rule-based), animation pane + triggers + motion paths, export video, record show, embedded fonts, equations.
- **v3:** AI suite (create from prompt or textbook), Presenter Coach, live captions/translate, master editor, SmartArt-basic, classroom shared play, co-editing.


---

## 21. Counts

| Metric | Count |
|---|---|
| Feature rows with IDs (all sections) | 319 |
| of which PowerPoint/our-app features (sections 1–17) | 289 |
| of which Google Slides extras (GS) | 14 |
| of which WPS extras (WPS) | 16 |
| Transitions catalogued with OOXML mapping (6.2) | 50 rows (about 48 effects + legacy) |
| Animation effects listed (7.3: entrance, emphasis, exit mirrors, motion paths) | about 150 |
| Desktop-only features proposed for mobile (marked D→M) | 50 |
| Difficulty split across all rows (S / M / L) | 117 / 143 / 48 |
| Items flagged **[verify on device]** | about 35 |
| Per-area rows | SHELL 14, HOME 40, INS 29, DRAW 14, DES 9, TRN 8, ANI 15, SHOW 22, REV 13, VIEW 12, FILE 17, SHP 15, PIC 14, TBL 12, MED 11, CHT 5, INK 2, MST 10, NOTE 4, SEC 2, AI 15, ACC 6 |
