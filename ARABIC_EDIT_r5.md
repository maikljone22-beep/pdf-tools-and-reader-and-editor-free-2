# Arabic in the content editor: round 5

Scope: editing existing PDF text (`core/edit`, `ui/edit`), find & replace (`core/search/FindReplace.kt`), and the
edit5 text writer (`core/edit5/E5Text.kt`). Copy/paste (`ArabicTextFix`) was already fixed by the lead and is reused here.

## Root causes found

1. **Lam-alef and other ligatures came out reversed ("السلام" read back as "السالم").** `PageTextModel.Segment`
   joined the glyph texts in visual (x) order, then reversed the whole line. A single glyph that decodes to several
   letters (ﻻ → "لا", ﷲ → "الله") is already in logical order, so the second reversal flipped it. Every Arabic text with
   lam-alef was corrupted in the edit box, and so was every rewrite that kept the text.
2. **Tashkeel ended up before its letter.** Marks are drawn at the letter's x position. Sorting by x put them on the
   wrong side, and the reversal then moved them in front of their letter (or onto the neighbouring letter).
3. **Undecodable glyphs became blanks or private-use garbage.** `PageScanner` used PdfBox's `toUnicode` as it was:
   - null for fonts without `/ToUnicode`, so the glyph was dropped;
   - private-use codes from broken ToUnicode maps, so garbage showed;
   - for TrueType fonts without ToUnicode, the *first* code of the font cmap. In Arial that is Farsi yeh U+06CC
     instead of ي and heh-doachashmee instead of ه, so a search for "عليكم" failed on the text it had extracted.
   No glyph-name or ranked-cmap fallback existed, and nothing told the user the text could not be read.
4. **Reordering bugs.** In `Bidi.visualToLogical` (read side), Latin phrases inside Arabic came back in reversed
   word order ("Smart Docs" became "Docs Smart"), and Extended Arabic-Indic digits were reversed. In an LTR line,
   Arabic words were not reversed at all. On the write side, `TextLayout.visual` used `ArabicShaper.shape`. Its
   `visualOrder` treats Arabic-Indic digits U+0660..U+0669 as RTL letters, so it **reverses them** ("صفحة ١٢"
   is drawn as "٢١"; confirmed by a test). It also reverses the word order of Latin phrases. The same bug affected
   edit5 headers, footers and page numbers (`E5Line`).
5. **Alignment and order.** Every multi-line LTR paragraph was "LEFT", and right-aligned LTR text was never
   detected. Blocks on the same baseline were numbered left to right, also on Arabic pages.
6. **Find & replace on logical text.** `TextMatcher.find` also matches the *reversed* query (meant for raw PDFium
   text). On the edit engine's logical text that gives false hits, and replacing them would overwrite the wrong
   letters.

## What changed

| File | Change |
|---|---|
| `core/edit/GlyphDecoder.kt` (new) | Per-font glyph → Unicode recovery. It uses PdfBox's answer only when it is a real character. Fonts without ToUnicode use a **ranked reverse lookup in the embedded TrueType's own Unicode cmaps** ((3,1), (3,10), (0,x)): basic Arabic before other U+06xx, compared by NFKC base letter. **GSUB single-substitution reverse mapping** (init/medi/fina/isol forms have no cmap entry). **Glyph names** from the `post` table, the CFF charset and the simple-font encoding: `uniXXXX`, `uXXXX[XX]`, `uni06440627`, `lam_alef`, `afii57xxx` (built-in table and AGL), `alefarabic`, `alef`, `alef-ar`, `beh.init`, `hehfinal`, … Bare Arabic names are checked before the AGL, which maps `alef` to *Hebrew* א. |
| `core/edit/EditBidi.kt` (new, replaces the old `Bidi` in PageTextModel.kt) | `logicalToVisual`: a compact UBA (W1, W4–W7, N1/N2, I1/I2, L2, mirroring). `shapeVisual`: shapes Arabic letter runs with `ArabicShaper` in logical order, then reorders with the UBA, so digits and Latin are never reversed and nothing is reversed twice. `visualToLogical(s, rtl)`: inverse for RTL lines (LTR islands = Latin / digits / number punctuation "2.5", "2024/05/01", "50%"; neutrals between islands join when Latin is involved), and for LTR lines (only the Arabic stretches are reversed). |
| `core/edit/PageScanner.kt` | Glyph text now comes from `GlyphDecoder`. |
| `core/edit/PageTextModel.kt` | Segment rebuilt: presentation forms → letters (spacing harakat forms no longer add spaces); per-glyph cp1256 demojibake via `ArabicTextFix.demojibake`; marks attached to the letter under them; multi-letter Arabic glyphs pre-reversed; direction-aware `Bidi.visualToLogical`. New `TextBlock.unreadable` (≥30 % undecodable glyphs, `ArabicTextFix.isGarbage`, or blank). `detectAlign`: centre, right or left from line edges; justified Arabic counts as RIGHT; one-line RTL counts as RIGHT. Blocks on RTL pages are ordered right to left. `PageModel.toDisplay` added (user → display matrix, used to place PDFium char boxes). Raw U+FEFF literals replaced with `\uXXXX`. |
| `core/edit/TextWriter.kt` | `TextLayout.visual` = `Bidi.shapeVisual`. Arabic keeps going to Noto Naskh (`PdfOps.arabicFont`), never to the original subset font. Lines are tagged with `/ActualText` (logical text), as before. |
| `core/edit5/E5Text.kt` | `E5Line` uses `Bidi.shapeVisual` (headers/footers/page numbers with ١٢٣ and Latin names are no longer reversed). |
| `core/search/FindReplace.kt` | `matches()` = `TextMatcher.find` (folding: hamza/alef, tashkeel…) minus reversed-query hits. Unreadable blocks are skipped in find, apply and replaceHit. Replacements are written through `ContentEdit.replaceText`, so they are shaped. |
| `ui/edit/EditController.kt` | Unreadable block: the field starts empty, `unreadable` = true. `startReplaceWhole()` rewrites the whole block with new text, aligned by the new text's direction. **PDFium fallback**: for unreadable/lossy blocks, `state.pageText` + `state.charBoxes` are filtered by position inside the block, passed through `ArabicTextFix.fix`, and used if they are not garbage (`recovered` = true). Commit is a no-op when the shown text and style are unchanged. |
| `ui/edit/EditPanels.kt` | Error card «لا يمكن قراءة نص هذا الخط — استخدم التعرّف الضوئي OCR أو استبدل الكتلة بنص جديد» with the button «استبدال الكتلة بنص جديد». A "recovered from the text layer" note. The font-substitution warning shows when replacing. English variants are used when the UI is not Arabic. |
| `ui/edit/EditPageView.kt` | No inline field for an unreadable block; the direction of a replace-whole field follows what is typed. |
| `app/src/test/.../core/edit/EditBidiTest.kt` (new) | Pure unit tests: bidi, shaping order, glyph names. |

## Tests

Location: `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\arabicedit\`
- `run.sh` compiles `core/edit/*`, ArabicShaper, ArabicTextFix, ArabicNormalizer, TextMatcher, FindReplace and SearchPatterns with kotlinc. It runs on the JVM against the **real PdfBox-Android 2.0.27 classes**, with small `android.graphics`/`android.util`/`AssetManager` stubs (`astub/`) and the AFM/glyph-list assets extracted from the .aar.
- `uicheck.sh` compile-checks the changed app sources, Compose UI included, against the base build classes. Result: clean.

The fixture PDFs are generated by the tests with PdfBox-Android on the JVM. No desktop PdfBox jar is in the gradle
cache; the PDF output is the same. They are kept in `scratchpad\arabicedit\pdfs\`. Fonts: Noto Naskh (app asset)
and Windows Arial.

**Result: 21/21 pass** (`ArabicEditTest` 17, `EditBidiTest` 4).

| Test | What it checks |
|---|---|
| shapedVisualWithToUnicode | Shaped, visual-order presentation forms with ToUnicode read back as logical «السلام عليكم ورحمة الله» (with lam-alef), RTL, RIGHT |
| paragraphRightAlignedMixed | 4 right-aligned lines (Arabic, "2.5 من Smart Docs", «صفحة ١٢ من ٣٤») form 1 paragraph with lines in order and RIGHT alignment |
| logicalGlyphOrderSameText | Glyphs emitted right to left one per Tj (Word style) give the same text |
| noToUnicode | Full embedded font, ToUnicode removed: recovered |
| privateUseToUnicodeRecoveredFromCmap | ToUnicode mapping everything to U+E000+: recovered from the font cmap |
| noToUnicodeNoCmapUsesGlyphNames | ToUnicode removed **and** the cmap table destroyed: recovered from `post` glyph names |
| nothingLeftIsUnreadable / subsetWithoutToUnicodeIsUnreadable | PUA, no cmap, no post (and a PdfBox subset without ToUnicode): `unreadable` = true, no garbage text |
| tashkeelStaysOnItsLetter | «مُحَمَّدٌ رَسُولُ اللَّهِ» exact |
| arialNoToUnicodeAndLtrLineWithArabic | Arial without ToUnicode gives ي/ه (not ی/ھ); an LTR line «Hello عالم world …» gives LTR, LEFT, only the Arabic reversed |
| replaceTextRoundTrip | Rewrite with «السعر ١٢٥ ريال للنسخة Pro 2»: reads back identical, RIGHT, right edge kept |
| replaceInUndecodableSource | Rewriting a block from a font without ToUnicode works (Noto substitute) |
| findReplaceArabic | «عليكم» found; the reversed query «مكيلع» not found; «الاصدار» finds «الإصدار»; replacing with «عليكم جميعاً» reads back exactly |
| visualOrderKeepsDigitsAndLatin / visualToLogicalRoundTrips / shaperAloneReversesArabicDigits / glyphNames | Pure bidi and name tests; `ArabicShaper.shape("صفحة ١٢")` was shown to start with U+0662 U+0661 (the reversal bug) |

Not run: Gradle (by instruction), and on-device testing on the Samsung tablet. The PDFium fallback path in
`EditController` cannot run on the JVM (native PDFium); it is compile-checked only.

## What still can't work

- **Fonts with no ToUnicode, no Unicode cmap and no glyph names cannot be decoded.** PdfBox's own subsets (and
  several other generators) drop `cmap` and `post`. For these the text is not in the file. PDFium usually fails
  the same way, so the editor shows the "use OCR or replace the block" notice. OCR is not wired into the edit
  panel; the user has to run the app's OCR tool.
- **GSUB reverse mapping covers only single substitutions** (fontbox does not parse ligature lookups). A lam-alef or
  other ligature glyph is recovered only when it has a cmap entry, a glyph name or ToUnicode.
- **Visual → logical is ambiguous.** "Hello 123" and "123 Hello" after Arabic draw identically; Latin-first is
  assumed. There is one paragraph level only: no explicit embeddings, and RTL nested inside LTR inside RTL is not
  handled.
- **cp1256 mojibake from simple fonts** is decoded character by character. The glyph order of such old PDFs is still
  taken from their x positions: correct when the producer placed the glyphs right to left, wrong when it drew a
  logical string left to right with a Latin-encoded font.
- **The original subset font is never reused for Arabic.** Rewritten Arabic is always Noto Naskh (with the existing
  substitution warning), so the look changes. Bold is synthetic.
- **No PDFium fallback in find & replace** (`FindReplace` has no PDFium session). Unreadable blocks are skipped
  there.
- **Not fixed here (outside my area): `core/pdf/ArabicShaper.shape`'s `visualOrder` still reverses Arabic-Indic
  digits and Latin phrases** for its other callers (`PdfOps.stampText`, `PdfOps.watermark`, …). Suggested fix: call
  `com.smartdocs.editor.core.edit.Bidi.shapeVisual(text, Bidi.firstStrongRtl(text) ?: true)` there, or move its
  reordering into ArabicShaper.
- **The three new UI strings are hard-coded in `EditPanels.kt`** (`uiText`, Arabic and English) because
  `res/values*` is outside my area. They should move to `strings_edit.xml`.
