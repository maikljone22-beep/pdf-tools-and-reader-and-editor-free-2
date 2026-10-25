# MODULE annotedit — edit existing annotations on the page

## Files
- `core/pdf/Annotations.kt`
  - **new** `AnnotationWriter.extract(src, page, index, password): Annot?`. It reads a stored annotation back into an `Annot`. Supported kinds: Highlight/Underline/StrikeOut/Squiggly (QuadPoints → rects), Ink (InkList, /BS or /Border width), Line (/L, plus ARROW when /LE has OpenArrow/ClosedArrow; a start-only arrow swaps the endpoints), Square/Circle (Rect minus /RD, width), FreeText (Rect, Contents, font size and colour from /DA; `/IT FreeTextStamp` gives STAMP_TEXT), Text note. It also reads colour (/C, including gray and CMYK), opacity (/CA), author (/T) and contents. Stamps and other kinds return null.
  - FreeText now gets a `/DA` (font size and colour) on write, so it reads back the same way it was written. Text stamps also get `/IT FreeTextStamp`.
  - **bug fix**: the text-markup appearance stream drew each rect above its own bbox, using the larger y as the lower edge. Saved highlights, underlines and strikeouts were clipped out (invisible) in the AP.
  - **bug fix**: a sticky note was saved 20 pt below the spot where it was previewed.
- `ui/reader/AnnotEdit.kt` (new): `AnnotTarget` (Stored or Pending), `AnnotEditor` (selection, conversion, edit operations, backup and restore), plus geometry helpers (`annotBounds`, `mapAnnot`, `frameRect`, `hitFrame`, `dragFrame`, `drawAnnotFrame`).
- `ui/reader/AnnotEditUi.kt` (new): the floating action bar `AnnotActionBar` (لون / شفافية / سماكة / تعديل النص / نسخ / حذف) and the text dialog.
- `ui/reader/ReaderState.kt`: adds `val edit = AnnotEditor(this)`.
  - `adoptConverted()` adds the converted annotation to every undo and redo state.
  - `save()` deletes the backup after a successful save. It also writes back when only a conversion and delete happened.
  - `close()` restores the backup when the user discarded.
  - `selectTool` clears the selection, and so does `deleteExisting` when it removes the selected annotation.
  - `saveMutex` and `swapFile` are now `internal`.
- `ui/reader/PageCanvas.kt`: the page is now `Box { Canvas; AnnotActionBar }`. Changes:
  - tap and long-press hit-testing
  - frame drag and resize in `pageDrag`
  - live preview while dragging
  - drawing of the frame and handles
- `ui/reader/PageGestures.kt`: adds `GestureDraft.annotFrame`.
- `ui/reader/ReaderScreen.kt`: Back clears the annotation selection, and "discard" restores the backup.
- `res/values/strings_annotedit.xml` and `res/values-en/strings_annotedit.xml` (`ae_*`).

## Behaviour
- **Selecting.** With no tool active, a tap or long-press on an annotation selects it. Pending annotations are checked first; stored ones are hit-tested against the `state.existing` rects, which are re-listed after every swap or reopen. When several rects overlap, the smallest one wins. A long-press on text with no annotation under it still selects text. A tap outside the frame clears the selection, and the next tap toggles the chrome as before.
- **Frame and bar.** The selection shows a dashed frame. Kinds that can be resized (rect, oval, ink, line, free text, stamps) also get corner handles. Text markup and notes can only be moved. The action bar floats above the frame, or below it when there is no room, and stays inside the page.
- **Moving and resizing.** Dragging inside the frame moves the annotation; dragging a corner resizes it. All coordinates map proportionally from the old frame to the new one: rects, every ink point, and the line endpoints. A drag is only claimed when it starts on the selected frame; every other drag still scrolls the page (`accept` in `pageDrag`).
- **First change to a stored annotation.**
  1. Re-list the file and find the annotation again (indices shift).
  2. `extract` it.
  3. On the first conversion only, copy the file to `.<name>.annotedit.pdf` as a backup.
  4. `delete` the annotation into a temp file and `swapFile`, which reopens the document and re-lists.
  5. Add the extracted annotation as a pending annot, record (page, index) in `replaced`, and mark the document dirty.

  The rendered page then no longer shows the stored copy. Later edits only change the pending annot, each one through `replaceAnnot` (one undo step). Saving commits pending annots as before.
- **Undo** reverts edits to a converted annotation but never removes it, because it is in every undo state.
- **Duplicate** adds an offset copy as a pending annot and selects it. **Delete** on a stored annotation uses `deleteExisting` (immediate, like the list panel); on a pending one it is an undoable remove.
- **Discard** (the dialog when leaving without saving) restores the backup after the session closes, so stored annotations taken out for editing come back.

## Limitations
- Stamps (image appearance), polygons, carets, file attachments and other unsupported kinds can be selected and deleted, but they cannot be moved or edited (`extract` returns null).
- While a stored annotation is being dragged, the original stays in the rendered bitmap and a preview is drawn on top. It disappears once the conversion swap finishes.
- A converted annotation is rewritten with this app's own appearance streams. Custom appearances, rich text (/RC), replies, popups, /RD on shapes and dash styles from other apps are not kept.
- The working file is changed at the first edit. If the process dies before a save or discard, a file opened through a `file://` path (no external origin) loses the annotation being edited; the backup `.annotedit.pdf` stays next to it. Documents opened from a content URI are not affected, because writeBack only happens on save.
- Text markup can be moved but not reflowed to the text under it, and the rotate handle is not offered for annotations.
- With rotated views, the frame and handles follow the rotation through `PageGeometry`. The action bar is laid out in layout space.
