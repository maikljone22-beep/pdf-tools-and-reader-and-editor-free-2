# Selection / long-press / right-click menus — research and per-tab design

Branch `feature/selection-menus` (worktree `D:\pdf\selmenu-wt`, from main `cf819fb`).
Question from the user: *"when I select text or long-press on text it should show the drop list (context menu);
analyse what should appear when text is selected in each tab section; Nitro, Foxit, Word, PowerPoint, Acrobat … don't miss anything."*

Confidence tags used below: **[RES]** read from the installed program's resources (exact), **[DOC]** official
documentation, **[COMM]** forum / third-party page, **[KNOW]** product knowledge not verified in this session (verify on a device).

---

## 1. Research — desktop PDF editors (installed, read-only)

_Nitro PDF Pro 14, Foxit PDF Editor 14 and Acrobat Reader desktop resource extraction: being added (see the next commit of this file)._


## 2. Research — Office, mobile PDF apps and the platforms

### 2.1 Microsoft Word (desktop, Microsoft 365)
- **Selected text, right-click** (≈10–12): Cut, Copy, Paste Options (Keep Source / Merge / Keep Text Only) [KNOW] ·
  Copilot (recent builds) [COMM elevenforum 36620] · Review Selection in Editor (some builds) [COMM learn.microsoft.com answers 5119180] ·
  Font…, Paragraph… [KNOW] · Search "…" (Smart Lookup successor; Smart Lookup retired 2025) [DOC support.microsoft.com smart-lookup] ·
  Synonyms ▸ (single word) [COMM webnots] · Read Aloud [KNOW] · Translate [DOC learn.microsoft.com translate-text-in-word] ·
  Link ▸ [COMM] · New Comment [COMM]. Extras: spelling suggestions on top for a misspelt word, Bullets/Numbering, Styles [KNOW].
- **Mini toolbar** (≈14): Font, Size, Grow, Shrink, Format Painter, Styles, Bold, Italic, Underline, Highlight, Font colour,
  Bullets, Numbering, New Comment [COMM howtogeek, computerhope; New Comment learn.microsoft.com answers 11180476].
- **Hyperlink**: Edit Hyperlink…, Open Hyperlink, Copy Hyperlink, Remove Hyperlink (+ the text items) [DOC support.microsoft.com remove-or-turn-off-hyperlinks].
- **Picture** (≈15): Cut, Copy, Paste Options, Change Picture ▸, Group ▸, Bring to Front ▸, Send to Back ▸, Link ▸, Save as Picture…,
  Insert Caption…, Wrap Text ▸, View Alt Text…, Size and Position…, Format Picture…, New Comment [KNOW]; picture mini toolbar Style / Crop / Wrap.
- **Word Android/iOS selection bar** (≈6–9): Cut, Copy, Paste, overflow: New Comment, Format, Link, Delete, Search, Select All [KNOW, uncertain].

### 2.2 Microsoft PowerPoint (desktop)
- **Text in a text box** (≈16): Cut, Copy, Paste Options, Exit Edit Text, Font…, Paragraph…, Bullets ▸, Numbering ▸,
  Convert to SmartArt ▸, Link ▸, Search, Synonyms ▸, Translate, Format Text Effects…, Format Shape…, New Comment [KNOW].
- **Shape / picture** (≈15): Cut, Copy, Paste Options, Change Picture ▸, Edit Text / Edit Points, Group ▸, Bring to Front ▸,
  Send to Back ▸, Link ▸, Search, Save as Picture…, Edit Alt Text…, Size and Position…, Format Shape/Picture…, New Comment
  [COMM indezine change-picture, dcp.ucla.edu grouping, bakersfieldcollege alt-text; rest KNOW].
- **Empty slide** (≈7): Paste Options, Layout ▸, Reset Slide, Format Background…, Grid and Guides ▸, Ruler, New Comment [COMM indezine reset-slide / format-background].
- **PowerPoint Android**: text: Cut, Copy, Paste, Comment, Delete, Format…; object: Cut, Copy, Paste, Duplicate, Delete, Arrange, Alt text [KNOW].

### 2.3 Adobe Acrobat Reader (Android / iOS)
- **Text selection** (long-press a word, drag handles): Copy (Adobe calls it "Copy text"), Highlight, Underline, Strikethrough
  [COMM community.adobe.com how-to-highlight-text-3319; DOC helpx …/mobile/edit-text-images/copy-text.html]; Define (older) [COMM];
  Comment / Add note [COMM]; Edit Text (premium) [DOC adobe.com devnet android workingwithpdf]; Read aloud, Share, AI Assistant [uncertain].
- **Long-press empty page**: Add image (and comment / text) [DOC devnet android; rest KNOW].
- **Annotation tapped**: Note/Comment, Colour, Opacity, Delete, Copy text, Thickness / Font size [KNOW].
- **Link**: opens, confirm dialog for external URLs [KNOW].

### 2.4 Foxit PDF Editor (Android / iOS)
- **Text selection pop-up**: Speak [DOC help.foxit.com mobile 12.0.0 Read_out_PDF_File] · AI Assistant [DOC foxit.com ai-assistant mobile] ·
  Highlight, Underline, Squiggly, Strikeout, Insert, Replace [DOC help.foxit.com mobile 2026.4.0 Comment] · Copy, Search / Web search,
  Translate, Share, Redact [KNOW]. Likely order: Copy, Highlight, Underline, Squiggly, Strikeout, Replace, Insert text, Speak, AI (≈9+).
- **Annotation pop-up** (≈8): Open, Reply, Appearance, Flatten, Delete, Copy (ink), Group / Ungroup, Recognize Text (handwriting) [DOC 2026.4.0].
- **Long-press blank page**: Pencil [DOC]; Note / Paste [KNOW].

### 2.5 Xodo / Apryse (PDFTron) Android
- Quick-menu resources per context [DOC docs.apryse.com/android/guides/advanced/customize-quick-menu]: `pan` (long-press blank),
  `text_select`, `annot_simple_shape`, `annot_free_text`, `annot_link`, `annot_signature`, `annot_stamper`, `annot_file_attachment`,
  `annot_free_hand`, `annot_general`, `annot_edit_text_markup`, `type`, `annot_edit_thickness`, `sig_field_image`, `sig_field_paths`.
- **LongPressMenu** (text or blank) 5: copy, paste, search, share, read [DOC pdftron-react-native Config.ts].
- **AnnotationMenu** 18: style, note, copy, duplicate, delete, flatten, editText, editInk, search, share, markupType, read,
  screenCapture, playSound, openAttachment, calibrate, group, ungroup [DOC same].
- **Text select** visible: Highlight, Underline, Squiggly, Strikeout [DOC docs.apryse.com xamarin quick-menu] + Copy, Search, Share, Read
  in overflow [DOC feedback.xodo.com]; Link, Redact [KNOW].
- **Blank long-press**: line, arrow, rectangle, oval, freehand, free text, sticky note, signature, image/stamp, link, paste (≈8–10) [KNOW].

### 2.6 Samsung Notes (PDF) and Google Drive PDF viewer
- Samsung Notes: finger text selection; markup highlight / underline / strikethrough [DOC samsung.com notes]; menu Copy, Highlight,
  Underline, Strikethrough, Search/Translate, Share, Galaxy AI items [KNOW]. S Pen lasso did not select PDF text (2024) [COMM].
- Drive viewer: freehand annotation only (pen, highlighter, eraser) [DOC support.google.com/drive 13207179]; selection = Android
  system toolbar: Copy, Select all, Share, Search, Translate [KNOW].

### 2.7 Platforms
- **Android floating text toolbar** order constants (AOSP Editor.java) [DOC]: ASSIST 0 (TextClassifier smart action first), CUT 4,
  COPY 5, PASTE 6, SHARE 7, SELECT_ALL 8, REPLACE 9, AUTOFILL 10, PASTE_AS_PLAIN_TEXT 11, secondary assist ≥ 50,
  PROCESS_TEXT apps ≥ 100 (Translate, dictionaries…). Seen on a Galaxy: Cut, Copy, Translate, Select all | Share, Manage apps [COMM androidauthority].
- **TextClassifier smart actions**: address → Map, date → Add event, phone → Call, e-mail → Email, URL → Open, measurement →
  Convert [COMM; DOC developer.android.com Android 8.0 smart text selection].
- **ACTION_PROCESS_TEXT** (Android 6+): `text/plain`, EXTRA_PROCESS_TEXT / EXTRA_PROCESS_TEXT_READONLY [DOC].
- **iOS edit menu**: Cut, Copy, Paste, Select, Select All, Replace…, Look Up, Translate, Search Web, Share…, Format ▸, Speak,
  Spell, Writing Tools, AutoFill [KNOW].
- **Google Docs** (≈15–17): Cut, Copy, Paste, Paste without formatting, Delete, Comment, Suggest edits, Insert link,
  Smart chips ▸, Define "word", Format options ▸, Clear formatting, Select all matching text, Update style to match,
  Save to Keep, Help me write [KNOW + COMM gcfglobal, techrepublic].

### 2.8 Counts per source

| Source | Context | Items |
|---|---|---|
| Word desktop | text / mini toolbar / hyperlink / picture | ~11 / ~14 / 4 / ~15 |
| PowerPoint desktop | text / object / empty slide | ~16 / ~15 / ~7 |
| Acrobat mobile | text selection | 4 confirmed (≤ 8) |
| Foxit mobile | text / annotation / blank | ≥ 9 / 8 / 1 documented |
| Xodo / Apryse | long-press / annotation / text select | 5 / 18 / 8 |
| Android toolbar | slots | 8 standard + assist + secondary + PROCESS_TEXT |
| iOS edit menu | standard | ~12 |
| Google Docs | text | ~15–17 |
<!-- COUNTS_DESKTOP -->


---

## 3. What SmartDocs had before this branch (main `cf819fb`)

**Text selection — identical in every tab** (bottom-bar chip row, horizontal scroll, `ui/reader/ReaderOverlays.kt:83-118`,
call `ReaderScreen.kt:295-303`): Copy (:92), Highlight (:93), Underline (:94), Strikeout (:95), AI Translate (:96, AI only),
AI Explain (:97, AI only), AI Rewrite (:98, AI only), Read aloud from here (:99), Web search (:100), Share (:101), Link (:103),
PDF bookmark (:106), Redact (:108), then NVE chips (`ui/nve/NveSelection.kt:162-186`): Squiggly (:163), Note (:164), Reading
bookmark (:171), Copy with formatting (:175), Copy as table (:176), Select all (:177), Column select (:178), Export .txt (:179),
Edit text (:180), Rewrite (NVE, :181, AI only); then Word count (`ReaderOverlays.kt:111`). **26 chips**, same order in VIEW,
COMMENT, EDIT, FILL_SIGN, SIGN, TOOLS, CUSTOM (PAGES opens the organiser).

Other menus:
- Long-press on a word → selects the word (`PageCanvas.kt:158-171`); handles drag (`PageCanvas.kt:182-215`, `moveSelectionHandle`);
  tap elsewhere clears (`handleTap`, `PageCanvas.kt:392`).
- Long-press on an annotation → selects it; floating `AnnotActionBar` above/below it (`AnnotEditUi.kt:50-123`): Colour, Opacity,
  Thickness, Edit text, Text format, Copy, Open / Play (attachment, sound, video), video options / share / save, Recognise ink,
  Image properties, Delete, Lock badge, More (`SelectionMoreButton`: properties, lock, clipboard, rotate, flatten …), markup range ±word.
  Multi-selection bar `annot/MultiActionBar.kt`.
- Long-press on an empty spot with a stamp on the clipboard → "Paste here" bar (`FillSign2Reader.kt:233`). Otherwise nothing.
- Long-press on a link → nothing (word selection under it). Tap opens it.
- Right-click (mouse) → NVE page context menu (`NveOverlays.kt:419-446`): with a selection Copy / Copy with formatting /
  Copy as table, then Select all, Paste, Page snapshot, Marquee zoom, Zoom, Go to page, Rotate view, Print, Document info.
- Form fields: in-page form layer (`forms5/InPageFormLayer`) — no context menu.

Problems found:
1. 26 chips in one scrolling row in the **bottom bar**, far from the selection; most-used items are not first per tab; no "More".
2. Same menu in every tab (COMMENT should lead with markup, EDIT with Edit text…).
3. Duplicates: two Rewrite chips (ai6 `ReaderOverlays.kt:98` and NVE `NveSelection.kt:181`), two bookmark chips with nearly the same label.
4. Guard gaps: Copy with formatting / Copy as table bypass the copy permission (`NveSelection.kt:175-176`, `NveOverlays.kt:433-434`);
   items are never shown as disabled with a reason; signed documents are not considered at all.
5. No smart actions (phone / e-mail / URL / address / date), no Arabic copy variants, no "other apps" (PROCESS_TEXT) — so with AI
   off there is no translate at all.
6. No menu for a long-pressed link or an empty area; right-click and touch menus are two different lists; no keyboard menu key.

---

## 4. Design

### 4.1 One registry (`core/selmenu/SelMenu.kt`, pure Kotlin, JVM-tested)
`SelAction` (64 ids) — each with group, contexts, needed permission (`Need` → `DocAction`), `ai`, `changesContent`
(refused on a signed file), smart `entity`. `SelMenus.build(SelEnv)` → `SelMenuModel(bar, more)`:
- **visible**: context matches; AI items hidden when AI is off; smart items only for a detected entity; Arabic copy variants only when
  they change something; paste-stamp only with a stamp on the clipboard; fill/sign insertions only in FILL_SIGN / SIGN.
- **enabled + reason** (`Why`): `AUTHOR` (restricted by the author), `PROTECTED_VIEW`, `SIGNED` (content changes would break the
  signature), `EMPTY_CLIPBOARD`. Disabled items stay in "More" (greyed, tapping says why) and **leave the floating bar**, whose next
  allowed candidate moves up — so a copy-restricted file in VIEW shows Highlight first instead of a dead Copy.
- **bar**: first smart action (like Android's ASSIST slot) + the tab's ordered candidates; 4 on phones, 6 on tablets, then **More**.
- **More**: every visible item grouped; the tab's own group comes right after Smart.

Labels / icons: `ui/selmenu/SelBindings.kt`; handlers: `ui/selmenu/SelHandlers.kt` (`SelRunner`), which **reuses** the existing
handlers (markupFromSelection, annot2.pick(REPLACE/CARET), NVE copy variants / select all / column select / edit request,
ReaderRedact.markSelection, ReaderScreen's TextToolsSheet + ReaderAi6 replace, read aloud, ReaderLinkFromSelection,
ReaderBookmarkFromSelection, WordCount, NveClipboard.paste, pasteStampAt, TextPrompt dialogs, ReaderActions).

### 4.2 Contexts
| Context | Trigger |
|---|---|
| TEXT | selection longer than a word (handles, select all, sentence, column) |
| WORD | long-press on one word (adds **Select sentence**) |
| LINK | long-press / right-click on a link annotation |
| EMPTY | long-press / right-click where there is no character, link or annotation |
| annotation | unchanged: existing `AnnotActionBar` (already Foxit-level: colour, opacity, thickness, edit, format, copy, open/play, ink→text, properties, delete, lock, more, range ±) |
| form field / page image | not wired in this branch (see §6) |

### 4.3 Per-tab floating bar (phone 4 / tablet 6, then More) — before → after
Before: the same 26-chip bottom row in every tab.

| Tab | TEXT / WORD bar (order of candidates; first 4 or 6 enabled are shown) | EMPTY-area bar |
|---|---|---|
| VIEW, PAGES | [smart] · Copy · Highlight · AI Translate · Other apps · Read aloud · Share · Find · Web search | Paste here (stamp) · Select all · Snapshot · Read page · Search · Go to page · Note here |
| COMMENT, CUSTOM | [smart] · Highlight · Underline · Strikeout · Squiggly · Note · Copy · Replace text | Note here · Text here · Paste here · Paste · Insert image · Select all · Snapshot |
| EDIT | [smart] · Edit text · Copy · Link · Redact · AI Rewrite · Highlight | Text here · Insert image · Paste here · Paste · Select all · Snapshot |
| FILL_SIGN | [smart] · Copy · Highlight · Note · Find · Share · Read aloud | Type here · Sign · Paste here · Paste · Note here · Select all |
| SIGN | [smart] · Copy · Highlight · Note · Find · Share · Read aloud | Sign · Paste here · Type here · Paste · Select all · Snapshot |
| TOOLS | [smart] · Copy · Redact · Find · Word count · Export .txt · Copy as table | Snapshot · Select all · Search · Paste here · Paste · Print |
| LINK (all tabs) | Open link · Copy link · Share link (page links: Open only) | — |

### 4.4 "More" sheet groups (order; tab's group promoted)
1. **Smart actions** — phone: Call, WhatsApp (international numbers), SMS, Add to contacts · e-mail: Send e-mail · URL: Open link,
   Copy link · address: Open in Maps · date: Add to calendar · number: Copy number.
2. **Copy** — Copy, Copy with formatting, Copy as plain text (joins soft line breaks / hyphenation), Copy as table.
3. **Arabic copy** — Copy without diacritics (harakat + tatweel), Copy normalised (also أإآٱ→ا, bidi controls removed, spaces),
   Copy with Latin digits (١٢٣→123, ٫→.), Copy with Arabic digits (123→١٢٣).
4. **Select** — Select sentence (WORD), Select all (page), Column select.
5. **Text markup** — Highlight, Underline, Strikeout, Squiggly, Replace text, Insert text.
6. **Comments & bookmarks** — Note, Reading bookmark, PDF bookmark.
7. **Look up & read** — Find in document, Web search, Read aloud from here, Word count, Other apps (Translate…, PROCESS_TEXT).
8. **AI** (hidden when AI is off) — Translate, Explain, Define, Summarise, Rewrite (can replace the text in the page).
9. **Edit** — Edit text, Create link, Redact.
10. **Share & export** — Share, Export .txt.
11. EMPTY only: **Insert here** — Paste, Paste here (stamp), Note here, Text here, Type here (Fill & Sign), Insert image, Sign;
    **Page & view** — Snapshot, Read page aloud, Search, Marquee zoom, Zoom, Go to page, Rotate view, Print, Document info.

### 4.5 Rules
- **ReaderGuard**: Copy-class items (copy variants, share, web search, other apps, AI, smart actions, export, snapshot) need COPY;
  markup / notes / insertions need ANNOTATE; Edit text, Link, Redact, PDF bookmark need MODIFY; Print needs PRINT. Refused → greyed in
  More with the guard's own message, removed from the bar; the handler checks `guard.check` again before running.
- **Signed file** (`SignedSave.isSigned`, probed once per file): MODIFY-class items are disabled with "changes would invalidate the
  signature"; comments stay allowed (they are incremental annotations; a refused save already offers "Save a copy", AN-28).
- **AI switch off**: every AI item hidden; "Other apps (Translate…)" remains.
- **Entities**: detected on the selected text after reading Arabic-Indic / Persian digits and the Arabic decimal separator as ASCII;
  Arabic and Levantine month names; day-first dates (month-first only when the day cannot be a month); `+`/`00` country codes;
  bare domains with common and Arab TLDs; Arabic address words (شارع، حي، طريق، ص.ب).

### 4.6 Placement and input
- Bar floats centred over the selection (union of its line rects), **above** it when there is room, else **below** (room left for the
  drag handles), else inside a very tall selection; clamped 8 dp inside the page area; when the selection scrolled off screen the bar
  stays reachable at the bottom. Coordinates come from the page canvas (`SelAnchor`, `localToRoot`, so zoom / rotation / scroll are
  followed). RTL: the row follows the layout direction; placement is absolute so it never mirrors off screen.
- Phone: 4 items + More (62 dp each); tablet (≥ 600 dp): 6 items + More (76 dp); More = bottom sheet with a grid per group.
- Long-press a word → word selected → bar; drag handles → bar follows; tap elsewhere → closes (existing tap handling); Back closes.
- Long-press a link → link menu; long-press empty → empty menu at the finger (tap outside closes).
- **Mouse right-click** → the same registry as a dropdown at the pointer (all groups, disabled items with their reason), replacing
  NVE's page menu. **Keyboard**: Shift+F10 or the Menu key opens it next to the selection (or the page centre).

---

## 5. Implementation map
- `core/selmenu/SelMenu.kt` registry · `core/selmenu/SelEntities.kt` entities · `core/selmenu/SelText.kt` Arabic copy / sentence.
- `ui/selmenu/SelMenuState.kt` state + hooks · `SelMenuLayer.kt` bar / More / dropdown / dialogs / `SelAnchor` ·
  `SelHandlers.kt` runner · `SelBindings.kt` labels + icons · strings `values/strings_selmenu.xml` (ar), `values-en/strings_selmenu.xml`.
- Marked hooks (`// selmenu`): `PageCanvas.kt` (long-press link / empty, `SelAnchor`), `ReaderScreen.kt` (legacy bottom row off via
  `SelHooks.LEGACY_BAR`, `SelMenuLayer`, key hook, Back), `NveOverlays.kt` (NVE context menu yields to the registry).
- Tests: `app/src/test/java/com/smartdocs/editor/core/selmenu/SelMenuTest.kt`.

## 6. Not done / not feasible here
- **Form field menu** (fill mode: Clear, Copy value, Reset field; form-edit: Properties, Duplicate, Delete, Tab order) — the in-page
  form layer owns those touches; needs its own hook in `forms5` (left for the forms owner to avoid conflicts with the running audit).
- **Page-content image menu** (EDIT tab: Copy image, Save image, Replace, Crop, Rotate, Delete) — lives in `EditModeHost`; not wired.
- **Annotation menu** keeps the existing `AnnotActionBar` (already complete); Reply / Flatten single are reached via its More.
- Thumbnail (PAGES) context menu is the organiser's (separate screen).
- Not tested on a device; CI compiles and runs the JVM tests only.
