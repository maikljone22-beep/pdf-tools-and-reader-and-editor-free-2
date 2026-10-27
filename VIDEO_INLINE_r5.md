# In-page video player (branch feature/inline-video)

User request: "When I add a video to the PDF, open the player INSIDE the PDF, not like an icon. A real video inside
the PDF, not an external app player."

## How the user uses it

1. Comment tab -> **Video** tool. The video picker opens straight away.
   - Files over 200 MB get a warning dialog (size shown; Continue / Cancel).
2. **Drag a rectangle** on the page where the video should play. A plain tap gives a default box: 40 % of the page
   width, using the video's own aspect ratio (16:9 if it cannot be read), centred on the tap.
   - If you tap or drag before picking, the picker opens and the video goes into that box when it returns.
3. The box shows the poster frame (the first frame that is not black) with a play button.
   **Tap it and the video plays inside that box** on the page. It scrolls, zooms and rotates with the page and is
   clipped to the viewport.
   - Controls: play/pause, seek bar, time, mute, loop, full screen, and a stop (X) button.
   - A narrow box keeps only play, seek and full screen.
   - The controls hide after 3 s of playback. A tap brings them back, and a double tap opens full screen.
4. **Full screen** stays inside the app. It can turn to landscape (it follows the sensor, and a rotate button forces
   landscape). Back returns to the page at the same scroll position, and the video keeps the same playback position.
5. **Long-press** the video to select it, then move it or resize it with the frame handles. The action bar has
   **Play**, **Video options** (autoplay when the page is shown, loop, start muted, show controls),
   **Share video**, **Save video to device**, copy, edit text and delete.
6. Saving writes a standard PDF video (structure below). Add, move, resize, options and delete are all undoable with
   the unified undo/redo (see "Undo / redo").

When playback stops:
- Only one video plays at a time.
- Playback stops and the player is released when the video's box scrolls or flips off screen, or when the page
  leaves composition.
- Playback pauses when the app goes to the background (ON_STOP).
- Everything is released when the reader closes. The extracted copies in `cache/video_play` are deleted then; at most
  3 extracted copies are kept while reading.

## PDF structure written (core/annot/VideoPdf.writeScreen)

```
<< /Type /Annot /Subtype /Screen /Rect [...] /F 4 /P <page> /T (author) /Contents (name) /M (date) /NM (sd-...)
   /C [r g b] /MK << /CA (name) >>
   /SDMedia /Video                                   % private: ours
   /SDVideo << /Autoplay b /Loop b /Muted b /Controls b >>   % private: our options
   /A << /Type /Action /S /Rendition /OP 0 /AN <this annot>
         /R << /Type /Rendition /S /MR /N (name)
               /C << /Type /MediaClip /S /MCD /N (name) /CT (video/mp4)
                     /D << /Type /Filespec /F (name) /UF (name) /Desc (name)
                           /EF << /F <stream> /UF <stream> >> >>     % /Type /EmbeddedFile /Subtype /video#2Fmp4 /Params << /Size n >>
                     /P << /Type /MediaPermissions /TF (TEMPACCESS) >>
                     /Alt [() (name)] >>
               /P << /Type /MediaPlayParams /BE << /C controls /V 0|100 /RC 0(loop)|1 /A true >> >>
               /SP << /Type /MediaScreenParams /BE << /W 3 >> >> >> >>   % W 3 = play in the annotation rect
   /AA << /PV <same rendition action> >>             % only when "autoplay" is on
   /AP << /N <form: black box, poster JPEG (/DCTDecode image XObject, aspect kept), 55 % disc + white triangle> >>
>>
```

The video is streamed into the embedded-file stream without loading it into memory and without a filter (it is
already compressed). `PdfOps.loadRaw` now uses `MemoryUsageSetting.setupMixed(48 MB)`, so a large new stream goes
to a temp file instead of the heap. Signed files still go through the incremental save: a unit test confirms the
signed bytes stay a prefix and the signature still verifies. The `fullSave` copy path is unchanged.

## What is read back and played in the page

- **Our new form:** Screen + Rendition with /SDMedia.
- **Our old form:** FileAttachment with `/SDMedia /Video`, and any file attachment whose embedded mime type is
  `video/*`. A small icon plays in an expanded 16:9 box anchored at the icon. Moving or resizing it rewrites it in
  the new Screen form.
- **Foreign Screen + Rendition:**
  - Handles `/A` and every `/AA` trigger, `/Next` chains, selector renditions (`/S /SR`) and clip sections
    (`/MCS`).
  - Options come from MediaPlayParams `/MH` or `/BE`: `/RC 0` means loop, `/V 0` means muted, plus `/C`. A `/PV`
    or `/PO` trigger means autoplay.
  - Audio-only renditions are ignored.
- **Foreign RichMedia:**
  - `/RichMediaContent /Assets` name tree (Names and Kids). The first embedded video asset is used: mp4, m4v, mov,
    3gp, webm, mkv, flv, ts and similar.
  - `/RichMediaSettings /Activation /Condition /PV` or `/PO` means autoplay.
  - **Flash-only** (.swf with no video asset): shown with a black placeholder and the message "Flash (SWF) video is
    not supported on this device...". It is not playable.
- A clip that only points to a URL or an external file is shown as "not embedded" (no crash).

## Behaviour that changed

- `MediaIntents.view` (the external app) is **no longer used for videos**. It is kept for generic file attachments.
  Sound still uses the in-app audio dialog.
- Video annotations are now resizable. Colour and opacity buttons are hidden for videos.
- Selecting a stored video no longer copies the video out of the PDF. It uses `extract(media = false)`; the copy
  happens only when the video is converted for editing, duplicated, shared or played.
- Old FileAttachment extraction now streams (`createInputStream().copyTo`) instead of `toByteArray()`.
- CommentExchange (merge / import) never rebuilds foreign Screen / RichMedia annotations.

## Undo / redo

- **Add:** `ReaderState.addAnnot`, a memory step.
- **Move / resize / options:** `AnnotEditor.transform` / `edit` -> `replaceAnnot`. A stored video is first converted
  to a pending one, which is the existing mechanism.
- **Delete:** a pending video is removed with `removeAnnot`. A stored one goes through `deleteExisting`, a file
  snapshot step.

Everything goes through core/history exactly like the other annotation kinds.

## Files

New:
- `core/annot/VideoPdf.kt`: pure PdfBox. Write, detect, extract (streamed), poster, jpegSize, findIndex,
  annotDicts.
- `core/annot/VideoPoster.kt`: MediaMetadataRetriever. Poster frame (skips black frames), size and rotation,
  duration.
- `ui/reader/annot/VideoHost.kt`: single ExoPlayer, active video, extraction cache, options, share/save, Video
  tool placement.
- `ui/reader/annot/InPageVideoLayer.kt`: per-page boxes, TextureView, controls, visibility tracking, autoplay.
- `ui/reader/annot/VideoDialogs.kt`: picker, large-file warning, options dialog, save launcher, full-screen
  dialog, lifecycle pause/release.
- `res/values/strings_video.xml` (Arabic) and `res/values-en/strings_video.xml` (English).
- `app/src/test/.../core/annot/VideoPdfTest.kt`: 8 JVM tests.

Changed:
- `core/pdf/Annotations.kt`: `Annot.video` and `Annot.posterPath`, `Existing.video`, and the `media` flag.
- `core/pdf/PdfOps.kt`: mixed memory.
- `core/annot/Annot2Writer.kt`: VIDEO -> Screen; read every video form.
- `core/annot/CommentExchange.kt`
- `ui/reader/AnnotEdit.kt`, `ui/reader/AnnotEditUi.kt`, `ui/reader/PageCanvas.kt` (adds the layer),
  `ui/reader/ReaderState.kt` (`state.video`, release on close)
- `ui/reader/annot/Annot2Host.kt` (drag box, VIDEO tool flow, no external player), `ui/reader/annot/Annot2Draw.kt`,
  `ui/reader/annot/Annot2Dialogs.kt`
- `app/build.gradle.kts`: `androidx.media3:media3-exoplayer:1.9.2`, ExoPlayer only, with our own Compose controls.
- `.gitignore`: `.kotlin/`

## Verification

- **Unit tests (VideoPdfTest, 8):**
  - Screen + Rendition round trip: rect, mime, name, options, embedded bytes identical, poster bytes identical,
    /A /S /Rendition /OP 0, /AN, MR, MCD, /CT, TEMPACCESS, MediaPlayParams, /AA /PV, and the AP content.
  - Default options: no /AA.
  - Old FileAttachment form detected, and a plain text attachment not detected.
  - Hand-built RichMedia with mp4 is detected and its bytes extract; Flash-only RichMedia is detected as
    unsupported.
  - Foreign Screen with a selector rendition, /MH loop+mute and an /AA /PO trigger; an audio Screen is not a video;
    findIndex.
  - jpegSize.
  - A video added to a **signed** PDF: incremental update, signed revision untouched, signature valid, bytes
    extract.
- **Full local run:** `:app:compileDebugKotlin :app:testDebugUnitTest`: 566 tests in 88 suites, 0 failures.
- **pypdfium2 5.11 (PDFium, the engine the app renders with):**
  - Sample PDF with a real 4 s H.264/AAC mp4 made with ffmpeg.
  - The annotation is reported as FPDF_ANNOT_SCREEN with the correct rect.
  - The rendered page shows the poster with the play button.
  - All the structure keys are present, and the mp4 bytes are embedded verbatim.
  - Files: `scratchpad/video/sample_video.pdf` and `sample_video_render.png`.

## What plays in Acrobat / others (expected, not tested here)

- **Acrobat / Reader (desktop):** a Screen annotation + Rendition action (OP 0) with an embedded MCD clip, TEMPACCESS
  and MediaScreenParams W 3 is the classic "legacy multimedia" form. Clicking the poster should play it in the rect;
  /AA /PV autoplays it. Acrobat's legacy multimedia needs a system player for the codec: mp4/H.264 works in current
  Acrobat on Windows/macOS. Recent Acrobat versions may first ask to trust the document's multimedia.
- **Foxit / Nitro / PDF-XChange:** these support Screen + Rendition, so the video should play. Viewers without
  multimedia support (browsers, Preview, most mobile viewers) show the poster picture with the play button. They
  never show a paperclip icon.

## Limitations (honest)

- **Not run on a device or emulator** (none available). The in-page player, the TextureView clipping, gestures,
  full screen, the orientation change, the lifecycle pause and the flip/curl modes are compiled but untested at
  runtime.
- **Rotation:** when the page view is rotated (90/180/270), the box rotates with the page but the picture stays
  upright and is letterboxed inside the box.
- **Page-curl / flip mode:** the live player runs when the page is settled. During the curl animation the turning
  page shows the poster from the page snapshot, and playback stops when the page turns away.
- **After a save:** a pending video becomes a stored one with a new identity, so a video that was playing at save
  time stops. Autosave only runs on ON_PAUSE.
- **No volume slider:** mute toggle plus the device volume keys. "Loop" while playing is a session toggle; the
  stored option is changed in Video options.
- **Hint and warning timing:** the "maybe unsupported" hint relies on MediaMetadataRetriever. A codec the device
  cannot decode only shows the in-box error when playback starts (no crash).
- **RichMedia is read only:** it is not written. Editing a foreign Screen or RichMedia video (move, resize, options)
  rewrites it in our Screen form. Its original foreign settings beyond loop, mute, controls and autoplay are lost.
- **Large files:** a huge video (1 GB) is copied into the PDF on save and extracted to the cache on first play,
  both on a background thread with a spinner. The warning only appears above 200 MB.
- **Temp copies:** the copies made when a stored video is converted for editing (`java.io.tmpdir/annot2_extract`)
  are left on disk. This is the existing behaviour for attachments.
