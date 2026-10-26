# MODULE_pageflip6_r5 — page navigation & transition effects library

Plan / architecture / technique choice: `PAGEFLIP_PLAN_r5.md` (Stage 0). This file is the result.
No new Gradle dependency. Default behaviour of the reader is unchanged (layout "classic" = existing PageView).

## Files
```
core/pageflip/PageFlipPrefs.kt   enums PfLayout / PfAxis / PfDirection / PfSpread / PfEffect, PageFlipPrefs + key=value codec
core/pageflip/CurlMath.kt        cylinder-projection curl: fold from finger, spine constraint, deform, lighting, mesh arrays
core/pageflip/FlipMath.kt        SpreadMap (item<->page, book sides incl. RTL / cover), drag->delta, edge zones,
                                 commit & velocity->duration, spring stiffness, stack stagger, scrubber/riffle maths
core/pageflip/BoundedLru.kt      byte-bounded LRU with eviction callback
core/pageflip/ScriptDirection.kt RTL detection from page text
ui/pageflip/PageFlipController.kt per-reader state (weak map), prefs (global + per document), direction, haptics, hosts()
ui/pageflip/PageSnapshotCache.kt  snapshots via PdfSession at PageCanvas' render key, ±1/±2 screens, never recycles shared bitmaps
ui/pageflip/FlipScene.kt          frame model (PageSnap / ItemSnap / FlipScene / FlipStyle)
ui/pageflip/FlipRenderer.kt       modern effects (Canvas transforms + android.graphics.Camera), book decorations, page tint
ui/pageflip/CurlRenderer.kt       drawBitmapMesh curl / book leaf / hard cover / stack turning, cast shadow
ui/pageflip/FlipDriver.kt         turn state machine (interactive drag, peek, settle with spring/tween, queue, hold frame)
ui/pageflip/FlipGeometry.kt       page rects of a screen (fit, margins, spine, lone cover page on its side), zoom/pan helpers
ui/pageflip/FlipGestures.kt       pointer layer (Initial pass): edge zones, drag/corner, fling, peek, riffle, pinch, pan, wheel
ui/pageflip/PageFlipHost.kt       the page-turn view mode (interactive PageCanvas when settled, snapshot overlay while moving)
ui/pageflip/HorizontalScrollHost.kt horizontal continuous layout (LazyRow of PageCanvas, RTL order)
ui/pageflip/PageScrubber.kt       page slider (reading direction) with thumbnail bubble + page-stack preview; riffle preview
ui/pageflip/EffectPreviews.kt     live effect previews (document pages or generated sample pages), labels
ui/pageflip/PageFlipSettingsSheet.kt settings sheet + PageFlipSheetHost (restore remembered mode on open)
ui/pageflip/PageFlipKeys.kt       Space / Shift+Space / arrows in document direction / up-down in vertical paging
res/values(-en)/strings_pageflip.xml  (prefix pf_)
test: core/pageflip/CurlMathTest.kt, FlipLogicTest.kt (SpreadMap, FlipMath, BoundedLru, PageFlipPrefs, ScriptDirection)
```

## Integration (applied, minimal; after MODULE_history_r5.md existed)
- `ui/reader/ReaderScreen.kt`
  - key handler: `PageFlipKeys.onKey(state, e.nativeKeyEvent, rtl) ||` before `V5Keys.onKey(...)`.
  - page area: the PageView call now uses a shared `openLink` lambda and
    `if (PageFlipController.of(state).hosts()) PageFlipHost(state, chrome, …) else PageView(…)`.
  - dialogs: `PageFlipSheetHost(state)` after the ViewModeSheet line.
- `ui/reader/view/ViewSheet.kt`: list item "Page turning & effects" under the page-mode cards (opens the sheet).
Nothing else in reader / view5 was touched. `hosts()` is false for layout CLASSIC (default), continuous view mode,
reflow, crop mode and view rotation -> the existing PageView path runs exactly as before.

## Stages
**Stage 1 — engine (DONE).** Navigation model: vertical continuous (existing), horizontal continuous (new), paged
horizontal / vertical (new host), single / two-page spread / two-page with cover (same mapping as `PageSpread`, so
`vx.flip`, auto flip, volume keys agree; unit-tested equal), book layout with adjustable margins, spine and lone cover
page on its side. Direction AUTO (document text: Arabic/Hebrew -> RTL), LTR, RTL; mirrors spread order, drags, edge
zones, scrubber and arrows. Snapshot cache (bounded, display size, off main thread, ±1/±2 screens, low-RAM 0.6×).
Gestures: edge taps, swipe/drag following the finger, corner drag, fling velocity, long-press peek, riffle, wheel,
keyboard, pinch zoom + pan. Haptics (CLOCK_TICK on turns / scrubber, LONG_PRESS on peek) respecting the setting.
Animated jumps for every `pendingJump` (outline, search, go-to, history back/forward, keys, auto flip).

**Stage 2 — effects (DONE).** De-duplicated library of 14 (see below). Realistic paper effects are a real 3D cylinder
mesh (no flat rotation is used to fake a curl); rigid rotation is used only for the hard cover and the explicitly named
"3D rotation" transition.

**Stage 3 — settings (DONE).** `PageFlipSettingsSheet`: large interactive live preview (drag on it), a grid of all
effects each with its own animated mini preview on the open document's pages (generated sample pages when none),
layout / spread / axis / direction (+ detected direction), speed, lighting & depth, curvature, margins, shadows,
thickness & spine, spring / bounce, reduced motion (+ system animator scale 0 -> instant), haptics, edge zones + width,
peek, "remember for this document only". Saved in SharedPreferences `pageflip` (global + `doc_<hash>`), restored on open.
Page scrubber with thumbnail bubble and page-stack preview.

**Stage 4 — integration (DONE)**, see above.

## Effect list (de-duplicated) and technique
| Effect | Covers | Technique |
|---|---|---|
| Page curl | 3D curl, corner peel, page fold (small curvature), single-page flip | cylinder mesh, drawBitmapMesh 32×24, front/back meshes split at the crest, per-vertex lighting, washed show-through back, cast shadow, radius -> 0 at the ends |
| Realistic book | double-page flip, spine + binding shadow, paper thickness/curvature, cover open/close, stack-of-pages turning, fast multi-page flip | same mesh hinged at the spine, back = next spread's facing page; stack edges; hard cover = rigid Camera rotation; jumps ≥2 screens turn up to 4 staggered leaves (low-res intermediates) |
| Slide | horizontal slide, vertical slide, push | translate both |
| Reveal | reveal | old moves off with edge shadow |
| Parallax | parallax, cover/slide-over | new over old, old at 15–40 % speed + dim |
| Fade | fade | alpha |
| Scale & crossfade | scale + crossfade | fade-through |
| Zoom | zoom in/out | scale + alpha |
| Wipe | wipe | moving clip + soft edge |
| Rotate | 2D rotate | swing around the outer bottom corner |
| 3D rotation | 3D rotation | Camera rotateY/X around the bound edge |
| Cube | cube | Camera, faces around the shared edge |
| Flip card | flip-card | Camera 180° around the centre |
| No animation | instant / reduced motion | — |
Spring-based motion is an option for all effects (velocity-aware; bounce only for flat effects).

## Limitations
- During a turn the pages are snapshots: no live text selection / link taps until it settles; pending (unsaved)
  annotations and search highlights are not in the snapshot (stored annotations are).
- Zoomed in: turn gestures off (pan instead); taps on edge zones off; keys / wheel jump (zoom resets).
- Edge zones are navigation zones in the page-turn mode (long press = peek, not text selection). Text/links inside the
  outer strip: zoom in or switch edge zones off.
- Crop mode, view rotation, reflow and the floating magnifier use the classic view.
- Vertical axis drops effects without a vertical variant (curl, book, rotate) -> slide.
- Presentation mode keeps its own transitions (separate dialog, untouched).
- Horizontal continuous: no edge zones (taps toggle the chrome), zoom pans vertically only.

## Testing
JVM unit tests (`app/src/test/java/com/smartdocs/editor/core/pageflip`):
- curl geometry: plain fold = reflection, cylinder projection (crest, half turn, flat back), front/back split, spine never
  lifts (500 random fingers), finger constraint, radius 0 at both ends, automatic path, mesh mapping LTR/RTL, lighting;
- spread/page mapping equals the reader's PageSpread for every mode and page count; book sides LTR/RTL/cover; cover turns;
- drag direction LTR/RTL/vertical, edge zones LTR/RTL, commit decision, velocity -> duration, stiffness, stack stagger,
  scrubber RTL round trip, riffle, stack edge thickness;
- snapshot LRU eviction order / callbacks / budget; settings codec round trip, garbage tolerance, clamping, effective effect
  (reduced motion, system animations off, vertical axis); script direction (Arabic, English, mixed, too short).
Touch, mouse, keyboard, RTL/LTR, portrait/landscape behaviour was checked by reasoning over the code paths only —
**device testing still has to be done by the user** (suggested checklist: curl drag from corner and edge in an Arabic and
an English PDF; two-page cover book in landscape; tap zones; long-press peek; riffle on the edge; scrubber; wheel and
arrow keys with a mouse/keyboard; Space; auto flip; search jump; zoom then pan; text selection and link tap after a turn;
system "remove animations" on).

## State placement (for pdf-39 / ViewModel move)
No field was added to ReaderState. All state lives in `ui/pageflip/PageFlipController` (WeakHashMap keyed by the
ReaderState instance, like V5State) + SharedPreferences "pageflip". The library uses only public ReaderState API:
session, pageCount, pageSizes, currentPage, rememberPage, jumpTo, pendingJump, viewMode, zoom, nightMode, pageColor,
rotation, reflow, renderVersion, tool, placement, selection, vx (crop, facingSeparator, flip), pageText, pageSize,
uriString, context. If ReaderState moves to a ViewModel, the weak-map key still works, and nothing else has to change.
Reader files changed: `ui/reader/ReaderScreen.kt` (3 hooks), `ui/reader/view/ViewSheet.kt` (1 list item + 1 import).
Each hook is commented `MODULE_pageflip6`. PageView.kt, ReaderState.kt and the ui/view5 files are untouched.

## Build
`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain` -> BUILD SUCCESSFUL; 461 tests, 0 failures
(pageflip: CurlMathTest 11, FlipMathTest 6, SpreadMapTest 3, BoundedLruTest 2, PageFlipPrefsTest 3, ScriptDirectionTest 1).
The earlier runs failed only on errors from other agents (convert6 main sources, the OCR test) and a test-results file
lock while another build was running. After they finished, the build passed. I fixed my one test-order bug.
