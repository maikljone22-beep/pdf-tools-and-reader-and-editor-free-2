# MODULE annot2: advanced annotations, comments and review

No gradle run was made (Round 3 rules). I checked library APIs with `javap` against:
- `pdfbox-android-2.0.27.0`
- `digital-ink-recognition-19.0.0`
- `common-18.11.0`
- `digital-ink-common-16.0.0`
- `material-icons-extended-1.7.8`

## New files

### `core/annot/`
| File | What it does |
|---|---|
| `Annot2Writer.kt` | PDF writing and reading for the new kinds:<br>• Polygon, PolyLine and cloudy Polygon (`/BE /S /C /I 1|2`, `/IC` fill, `/IT PolygonCloud`)<br>• Caret (`/Sy /None`)<br>• Replace: a Caret with the new text (`/IT /Replace`) plus a StrikeOut with `/IRT` caret, `/RT /Group` and `/IT /StrikeOutTextEdit`<br>• FileAttachment for files, sound and video. Each embeds its file. Our own key `/SDMedia /Audio|/Video` and `/Subj` mark sound and video.<br>Every annotation gets an appearance stream (vector icons, caret, cloud arcs).<br>`afterWrite` gives every committed annotation an `/NM`. Pending annotations that share a `groupId` get `/IRT` and `/RT /Group`.<br>`read` turns stored polygons, carets and attachments back into an `Annot`. For attachments it extracts the embedded file to tmp, so moving or editing keeps the file. |
| `CloudPath.kt` | Scalloped-outline geometry. It is shared by the PDF appearance stream and the on-screen preview. |
| `CommentStore.kt` | One-pass listing (`CommentItem`) of each comment's thread replies, its latest `/StateModel Review` status (with author and date) and its group parent.<br>File operations: `group`, `ungroup`, `deleteMany` (also removes replies, states and popups), `addReply`, `setStatus`, `deleteReply`. All use `PdfOps.load`, so encrypted files can be saved. |
| `CommentExchange.kt` | `exportXfdf` writes the XML by hand: page, rect, colour, opacity, title, subject, dates, name, flags, in-reply-to, intent, state, width, coords/QuadPoints, inklist gestures, vertices, line start/end/heads, DA and contents.<br>`exportFdf` uses PdfBox `FDFDocument`. It drops `/P`, `/Popup` and `/AP`, adds `/Page`, and re-links `/IRT` to the clones.<br>`importFile` reads FDF, or XFDF through `FDFDocument.loadXFDF`.<br>`merge` copies comments from another PDF onto the same page indexes.<br>Import and merge skip duplicates by `/NM`, then by subtype + rect + contents. They rebuild the appearance stream with `AnnotationWriter.write` when the source has none, and re-attach replies and groups to the copies. |
| `CommentSummary.kt` | On-device "Summarize Comments": a new PDF where each page that has comments gets a thumbnail with numbered red markers and a numbered list beside it. Each entry shows type, author, date, status, the wrapped text and the replies. Arabic is shaped and right-aligned with the Naskh font, and the list continues on the next page when it runs out of room. |
| `InkRecognizer.kt` | ML Kit Digital Ink (`com.google.mlkit.vision.digitalink.recognition.*`, the 19.0.0 package):<br>• `isDownloaded`, `download` and `delete` through `RemoteModelManager`<br>• `recognize(tag, strokes)`: PDF y-up coordinates are flipped, with synthetic timestamps and a `WritingArea`<br>• languages: ar, en, fr, de, es, tr-TR, fa, ur, zh-Hani-CN, ja, ru, hi |
| `MediaAnnots.kt` | `extractAt(page, index)` for stored attachments, and `Recorder` (MediaRecorder, AAC/MPEG-4 `.m4a`). |
| `StoredOps.kt` | In-place `translate`: shifts `/Rect` plus QuadPoints, InkList, L, Vertices and CL. The appearance stream follows the rect, so stored annotations keep their AP, replies and status when moved. Also `removeOnly` and `namesOf`. |

### `ui/reader/annot/`
| File | What it does |
|---|---|
| `Annot2Host.kt` | Lives in `state.annot2`.<br>• Polygon vertices: tap adds a vertex; double-tap or "تم" finishes; undo point and cancel are available.<br>• Caret by tap: the caret goes at the nearest character edge, handling RTL.<br>• Replace by tap on a word or a drag over text. Caret and Replace also act on an existing text selection.<br>• Attachment, video and sound taps.<br>• Lasso, snapshot, replace and multi-move drags.<br>• Snapshot render (up to 4 px/pt, max 4096 px), then PNG, then the clipboard.<br>• Open or play for attachments, sound and video.<br>• Handwriting gather and apply.<br>• `fileOp`: change the file, swap the session and write back, like `deleteExisting`.<br>• Comments cache. |
| `MultiSelect.kt` | Lasso (or rectangle) selection of pending and stored annotations on one page. Tap with the Select tool adds or removes one. Tapping a grouped annotation selects its whole group.<br>Operations:<br>• move: pending changes are one undo step; stored ones are moved in place in the file<br>• delete<br>• colour, opacity and thickness: stored annotations the editor can rewrite are first converted to pending in one file pass, with the annotedit backup kept<br>• group and ungroup: pending annotations are saved first, then the group is written as `/IRT` + `/RT /Group` |
| `MultiActionBar.kt` | Floating bar for a multi-selection: colour, opacity, thickness, group, ungroup, recognise (when every item is ink), delete. |
| `Annot2Draw.kt` | `drawAnnot2` (live preview of the new kinds) and `drawAnnot2Overlay`. The overlay draws the polygon being drawn (closing edge, cloud and fill preview), the lasso, the snapshot or replace rectangle, and the multi-selection frames with the live move preview. |
| `Annot2Toolbar.kt` | `Annot2CommentChips`: the new tools plus the "المزيد" menu (summary, XFDF, FDF, import, merge, handwriting).<br>`Annot2PropertyRow`: tool hint, polygon progress buttons, thickness, fill (none plus palette), cloud 1/2, multi-selection count. |
| `Toolset.kt` | `ToolCatalog` lists every tool, grouped Comment, Drawing, Edit and Fill & Sign.<br>`ToolsetStore` saves the user's tools as a comma-separated list under key `tools` in SharedPreferences `"toolset"`.<br>`CustomToolbar` is the "أدواتي" tab.<br>`ToolsetEditSheet` lists the added tools (long-press drag to reorder, remove) and the full tool list with add/remove. |
| `CommentsPanel.kt` | Pending and stored comments together.<br>• Filters by **type and author** (multi-choice chips).<br>• Sort by page, date or author.<br>• Each card shows author and date, the group icon, and a review-status chip with author and date. The chip's menu offers None, Accepted, Rejected, Cancelled and Completed.<br>• Expandable reply thread: delete a reply, or type and send a new one.<br>• Delete; tap a card to jump and select.<br>• The "⋮" menu offers the same exchange actions as the toolbar. |
| `Annot2Dialogs.kt` | Every activity-result launcher (attachment, video, import, merge, XFDF and FDF save, snapshot PNG save, mic permission). It also holds the caret/replace text dialog, the recorder, the audio player, the snapshot sheet (copy, share, save PNG), the summary `ResultSheet`, the handwriting dialog and the busy dialog. |
| `HandwritingDialog.kt` | Language chips (Arabic first when the UI is Arabic), then model download with progress, then recognition, then candidate chips and an editable text field. Confirming replaces the ink with a FreeText at the ink's bounds. Font size = stroke height × 0.75; the box is right-anchored for Arabic. |
| `MediaIntents.kt` | FileProvider view intent, image on the clipboard, share, import of a content URI, SAF write. |

Strings are in `res/values/strings_annot2.xml` and `res/values-en/strings_annot2.xml`, all with the `an_` prefix (93 strings).

## Edits to existing files (all small and localized)
- `core/pdf/Annotations.kt`:
  - `AnnotKind` gains POLYGON, POLYLINE, CLOUD, CARET, REPLACE, FILE_ATTACH, SOUND and VIDEO.
  - `Annot` gains `fillColor`, `cloud`, `filePath`, `mime`, `groupId` and `nm`, all with defaults.
  - `write` delegates the new kinds to Annot2Writer.
  - `commit` calls `Annot2Writer.afterWrite` (sets `/NM` and groups).
  - `list` skips hidden replies and review states, so they cannot be picked on the page.
  - `extract` keeps `/NM`, and `toAnnot` falls back to `Annot2Writer.read`.
  - `write` and `toAnnot` are now `internal`; import and merge use them.
- `core/pdf/AnnotReplies.kt`: writes now go through `PdfOps.load`, so encrypted files can be saved.
- `ui/reader/ReaderState.kt`:
  - `ReaderTab.CUSTOM`
  - `ReaderTool` gains CARET, REPLACE, POLYGON, POLYLINE, CLOUD, ATTACH, SOUND, VIDEO, SNAPSHOT and SELECT
  - `val annot2 = Annot2Host(this)`
  - `batchAnnots { }`: several pending changes as one undo step
- `ui/reader/ReaderToolbars.kt`:
  - the "أدواتي" tab is placed after Comment
  - a `ReaderTab.CUSTOM` branch in `BottomToolbar`
  - `Annot2CommentChips` in the Comment row
  - `Annot2PropertyRow` at the end of `PropertyBar`
- `ui/reader/ReaderScreen.kt`:
  - Back first cancels a polygon in progress, then clears a multi-selection
  - `Annot2Dialogs(state, nav, snack)` after `QuickActionHost`
- `ui/reader/PageCanvas.kt`: hooks for double-tap, drag accept/start/move/end/cancel and tap, plus `drawAnnot2Overlay` and `MultiActionBar`.
- `ui/reader/PageGestures.kt`: `annotAt` hit-tests polygons (edges, and the inside of closed shapes).
- `ui/reader/AnnotEdit.kt`:
  - grouped annotations go to the multi-selection when tapped
  - `ensureBackup()`
  - bounds, `resizable`, `hasStroke` and `hasEditableText` know the new kinds
- `ui/reader/AnnotEditUi.kt`: Open/Play button for attachments, sound and video; Recognise button for ink.
- `ui/reader/AnnotationOverlay.kt`: `drawAnnot` sends the new kinds to `drawAnnot2`. I edited this file because it holds the exhaustive `when` over `AnnotKind`.
- `ui/reader/ReaderPanels.kt`: the annotations panel is now `annot.CommentsPanel`. `kindLabel` covers the new kinds. The old private `AnnotationsPanel` is kept but unused.

## Wiring the lead must do / check
1. **Compile.** Watch for:
   - exhaustive `when` over `AnnotKind`, `ReaderTool` or `ReaderTab` added by other agents this round, which now need the new values;
   - smart-cast or label nits.
2. **Gradle.** `com.google.mlkit:digital-ink-recognition:19.0.0` is already declared in `app/build.gradle.kts`.
   - It pulls in `work-runtime 2.7` and `okhttp 3.12.1` transitively. If `okhttp` conflicts with the app's 4.12, gradle resolution picks 4.12, which is fine.
   - Its manifest adds `INTERNET` and `ACCESS_NETWORK_STATE`, which are already declared.
3. **Optional hook in `ReaderOverlays.SelectionMenu`**, owned by another agent. Add two chips:
   - `state.annot2.pick(ReaderTool.REPLACE)`: "استبدال", which strikes out the selection and adds the replacement text;
   - `state.annot2.pick(ReaderTool.CARET)`: "إدراج", which inserts text at the end of the selection.

   Both already work today from the Comment-tab chips while text is selected.
4. **Optional:** `ToolHint` in ReaderScreen shows the generic hint for the new tools. `Annot2PropertyRow` already shows the specific hint above the bar.
5. **FileProvider:** `res/xml/file_paths.xml` already covers `cacheDir` and `filesDir`. Media picked before saving is kept in `filesDir/annot2_media`; snapshots go in `cacheDir/tmp`.

## Behaviour notes
- **Polygon, polyline and cloud.** Tap to add vertices. Double-tap (which also adds that vertex) or "تم" finishes. Near-duplicate vertices are dropped. A polygon or cloud needs at least 3 points, a polyline 2. Fill and cloud intensity come from the property row. Thickness is 1–12 pt.
- **Page attachment.** Tap the page, then pick any file. A pending FILE_ATTACH with a paperclip icon is added, and the file is embedded on save.
  - I did not use `AttachmentOps.add(page=…)`. It writes the file directly with no appearance stream and no undo, and loads with `PDDocument.load`, so encrypted files cannot be saved.
  - Pending annotations are drawn live, can be undone and are committed with our appearance stream. The `/FS` structure is identical, so `AttachmentOps.list` and `extract` still see them.
  - Selecting one, then "فتح", opens it with a chooser.
- **Sound.** Recorded as AAC `.m4a` and embedded as a FileAttachment with a speaker icon. Selecting one, then "تشغيل", plays it in our own MediaPlayer dialog. I avoided `/Sound` (raw samples) and `/Screen` with renditions because viewer support is poor.
- **Video.** Pick a video, and it is embedded as a FileAttachment with a film icon. Opening it extracts the file to cache and sends `ACTION_VIEW` through FileProvider.
- **Snapshot.** Drag a rectangle. The region is rendered by PDFium at up to 4 px/pt, saved as PNG and put on the clipboard as a content URI. A sheet then offers copy, share, or save PNG (SAF).
- **Multi-select.** Use the Select tool: draw a lasso (a short drag counts as a rectangle) or tap to add. Drag inside the frame to move everything. The floating bar does the rest.
  - The selection stays on one page.
  - Stored annotations are moved in place, so their appearance, replies and status are kept, as are stamps from other apps.
  - Colour and opacity changes need a new appearance stream, so stored annotations the editor can rewrite become pending first. Others, such as image stamps, keep their style.
- **Comments panel.** Replies are hidden Text annotations with `/IRT` and `/RT /R`. A review status is a hidden Text annotation with `/State` and `/StateModel Review`; the latest one by date wins. Reply, status and delete write to the file immediately, like the old panel's delete. Pending annotations are listed as "غير محفوظ".
- **Handwriting.** The source can be:
  - the selected ink (pending or stored);
  - a multi-selection that is all ink;
  - every pending ink stroke on the current page (toolbar menu).

  Stored ink is deleted from the file; pending ink is removed in one undo step together with adding the FreeText.

## Limitations
- ML Kit gives no download progress, so the indicator is indeterminate. The first use of each language needs network access. If the model cannot be fetched the dialog shows the error and a retry.
- XFDF export covers the common attributes and elements. Stamp appearances, rich text (`/RC`), embedded files and popups are not exported.
- FDF export drops `/AP`. On import, appearances are rebuilt only for kinds the editor can write; anything else, such as stamps without an AP, is added as-is and depends on the viewer.
- Merge maps by page index only, with no page-content matching. Comments on pages beyond the current page count are skipped.
- Editing (convert) a stored Replace group splits it: only the tapped part is converted. Moving it as a group with multi-select keeps it intact.
- Group and ungroup save pending annotations first, so those edits leave the undo history.
- In-place moves, deletes, replies, statuses, imports and merges are written to the file right away. Like `deleteExisting`, the "discard" backup does not cover them; only annotedit conversions are restored.
- Converting stored annotations (single edit or multi-style) leaves their replies orphaned in the file, same as annotedit today.
- The summary relies on the PDFium render of each page. A page that fails to render is listed without a thumbnail.
