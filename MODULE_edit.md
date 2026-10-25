# MODULE edit — real content editing (text, images, outline)

Owner: edit agent. Packages `core/edit/*`, `ui/edit/*`. Strings: `res/values/strings_edit.xml` + `res/values-en/strings_edit.xml` (prefix `ed_`).
No Gradle dependency added. Every PdfBox-Android 2.0.27 API used was checked with `javap` on `/tmp/pb/classes.jar`.

## Files

| File | What |
|---|---|
| `core/edit/EditGeometry.kt` | `Affine` (row-vector matrix helpers: mul, inverse, rotateAround, box) and `PageFrame` (user space ⇄ display space: crop box offset + /Rotate 0/90/180/270). |
| `core/edit/PageScanner.kt` | `PDFGraphicsStreamEngine` subclass. Records, per **top-level operator index**: glyphs (display-space position/size/font/colour), images (`Do` / inline `BI` with their CTM), and the text-matrix advance of every `Tj TJ ' "`. Nested operators (form XObjects, the `T*` that `'` triggers, …) are not counted, so indexes match `PDFStreamParser(page)` tokens 1:1. Invisible text (Tr 3/7, OCR layers) and rotated glyphs are skipped. |
| `core/edit/PageTextModel.kt` | `PageModel`, `TextBlock`, `ImageItem`; grouping glyphs → lines (baseline) → segments (split at gaps > 1.8 em) → paragraphs (spacing ≤ 1.75 em, same size/colour/direction, horizontal overlap); blocks that share an operator are merged (union-find). Detects RTL lines, converts visual Arabic to logical order (`Bidi.visualToLogical`), maps presentation forms back to base letters (NFKC), guesses bold/italic/family/alignment, flags subset fonts. `PageTextModel.load(file, pw, page)`. |
| `core/edit/ContentRewriter.kt` | Token-level rewrite of a page's content: replace/remove operators and write back with `ContentStreamWriter` (one new Flate stream wrapped in `q … Q`). `removeText` replaces a text operator by an invisible `[n] TJ` of the same advance, so text that follows in the same `BT` does not move. `wrapWithMatrix` turns `/Im Do` into `q m cm /Im Do Q` (optionally with a new name). `EditIo` load/save helpers. |
| `core/edit/TextWriter.kt` | `EditTextStyle`; `FontSet` (reuse original font when not subset + encodable + same style, else standard-14 Helvetica/Times/Courier of the same family with real bold/italic; Arabic → `PdfOps.arabicFont` Noto Naskh, synthetic bold = fill+stroke, synthetic italic = skew); `TextLayout` (word wrap to the box width, per-character font runs, `ArabicShaper.shape` for RTL lines and for Arabic words inside LTR lines, left/center/right alignment, `/ActualText` marked content so copy/search get logical Arabic). |
| `core/edit/ContentEdit.kt` | High-level ops (all `src → out`, never in place): `replaceText`, `addText`, `deleteText`, `deleteImage`, `transformImage`, `replaceImage` (aspect-fit, new XObject name), `addImage`, `imageBitmap`. Throws `StalePageException` when the block/image no longer exists. |
| `core/edit/OutlineModel.kt` | Outline as flat pre-order rows (`OutlineRow(key,title,page,level)`): `load`, `save` (replaces /Outlines), `insert`, `update`, `delete` (with subtree), `indent`, `outdent`, `move`, `moveUp`, `moveDown`, `normalize`. |
| `ui/edit/EditController.kt` | `applyEdit(state, block)` (public, see below) + `EditController` (page, model, selection, style, pending image affine, undo stack of backups) + `rememberEditController`. |
| `ui/edit/EditModeHost.kt` | **`@Composable fun EditModeHost(state: ReaderState, onExit: () -> Unit)`** — full-screen overlay: top bar (exit, undo, zoom ×1/1.5/2/3, outline editor), Text/Images segmented switch, page ‹ n/N ›, hints, snackbar, bottom bars. Back = clear selection, then exit (jumps the reader to the edited page). |
| `ui/edit/EditPageView.kt` | Renders the page itself via `state.session.render` (fit width × zoom, scrollable), dashed outlines of editable blocks / images, the inline RTL-aware text field over the block, image drag-to-move and corner-handle resize. |
| `ui/edit/EditPanels.kt` | `TextFormatBar` (size −/menu/+, bold, italic, family, align L/C/R, 12 colours, font-substitution notice, delete, cancel, done), `ImageActionBar` (replace, save to Pictures/SmartDocs via MediaStore, share PNG, rotate ±90°, delete with confirm, apply/reset move-resize), `InsertImageBar`. |
| `ui/edit/OutlineEditor.kt` | Shared tree editor body (select, drag handle: vertical = reorder, sideways = indent/outdent, RTL aware; menu: rename/move up/down/indent/outdent/delete; add entry dialog with page number), **`OutlineEditorSheet(state, onDismiss)`** (reader; saves in place through `applyEdit`), **`OutlineEditorScreen(nav, uri)`** (tool route id `"outline"`; saves into the file + `writeBack`). |

## Public API for the reader

```kotlin
// ui/edit/EditController.kt
suspend fun applyEdit(state: ReaderState, block: suspend (src: File, out: File) -> Unit): File?
```
Uses `state.applyInPlace` (pending annotations are saved first, file swapped, session reopened, written back to the source). Returns the previous version for undo (`state.restoreFrom(backup, password)`); `EditModeHost` keeps up to 15 of them and deletes them on dispose.

## Hook for the lead (ReaderScreen.kt + ReaderToolbars.kt)

1. `ReaderScreen.kt`, with the other `remember` flags (near `var showAi …`):
   ```kotlin
   var contentEdit by remember { mutableStateOf(false) }
   ```
2. `ReaderScreen.kt`, in `ReaderActions(... navigateTool = { id -> ... })` make the first statement route our id (no new `ReaderActions` field needed):
   ```kotlin
   navigateTool = { id -> if (id == "content_edit") { state.selectTool(null); contentEdit = true } else { /* existing body unchanged */ } },
   ```
3. `ReaderScreen.kt`, inside the root `Box(Modifier.fillMaxSize()) { Scaffold(...) ...` right **after** the Scaffold and the tablet/phone panels (so it covers the reader chrome; it handles status/navigation bar and IME insets itself):
   ```kotlin
   if (contentEdit && state.session != null) com.smartdocs.editor.ui.edit.EditModeHost(state) { contentEdit = false }
   ```
   Toolbar button — `ReaderToolbars.kt`, `EditToolbar`, first chip of the `ToolRow`:
   ```kotlin
   ToolChip(Icons.Default.EditNote, stringResource(R.string.ed_content_edit)) { a.navigateTool("content_edit") }
   ```

Optional:
- Outline editing from the reader panel (`ReaderPanels.kt`, OUTLINE tab): `var editOutline by remember { mutableStateOf(false) }`, a button «تحرير» → `editOutline = true`, and `if (editOutline) com.smartdocs.editor.ui.edit.OutlineEditorSheet(state) { editOutline = false }`. (It is also reachable from the Edit overlay's top bar.)
- Tools tab: route id `"outline"` → `com.smartdocs.editor.ui.edit.OutlineEditorScreen(nav, uri)` (title string `R.string.ed_outline_title`).

## How edits work

- Text: tap block → inline editor prefilled with the logical text and style → Done: the block's text operators are neutralised (`[n] TJ` keeps following text in place), then the new text is appended as page content in a `q <display→user cm> … Q` block at the original first baseline. One-line blocks may extend to the page margin (LTR → right, RTL ← left); paragraphs are re-wrapped to their width with their original line spacing (scaled with the size). Empty text = delete.
- Add text: tap an empty spot; the box runs from the tap to the margin in the text direction (Arabic → right-aligned, ending at the tap).
- Images: select → replace (new XObject, aspect-fit inside the old placement), save to Pictures / share (PNG of `PDImage.getImage()`), delete (operator removed), rotate ±90° / drag / resize then **Apply** (one `q N cm … Q` wrapper, N = P·D·A·D⁻¹·P⁻¹). Tap an empty spot in Images mode to insert a picture as real content.
- Every commit = one `applyEdit` (new file version) → reader re-renders, model reloads; undo restores the previous file.

## Limitations / TODO

- Text inside form XObjects, annotation appearances, Type3 glyph procedures and patterns is not listed (only the page's own content stream is editable). Images inside form XObjects likewise.
- Rotated / vertical text (non-upright text matrix, vertical CJK fonts) is skipped.
- A paragraph is rewritten with one font/size/colour: mixed bold words or colours inside one block are flattened to the block's dominant style.
- Original fonts are reused only when not subset-embedded; otherwise standard-14 (Latin) or Noto Naskh Arabic (bundled; regular weight only → synthetic bold) are used. Metrics differ slightly from the original font; the UI warns when the font is substituted.
- Glyphs without ToUnicode (`lossy`) produce incomplete text; the UI warns.
- Visual-order Arabic is reconstructed by geometry; combining marks (harakat) may be misplaced in the extracted text.
- Clipping paths around removed text remain (harmless). Neutralised operators stay inside their `BT/ET`; the content stream is rewritten as a single stream (byte size may change).
- Outline save rebuilds /Outlines: item colours/bold/italic and XYZ zoom destinations are not preserved (destinations become page fits). Encrypted files: the screen version opens without password (sheet version uses the reader's password).
- Link editing (spec §6) and audio/video (§5) are not in this module.
