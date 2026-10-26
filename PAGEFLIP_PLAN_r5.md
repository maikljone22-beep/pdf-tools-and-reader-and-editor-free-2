# PAGEFLIP_PLAN_r5 — page navigation & transition effects (agent pageflip6)

Stage 0 (read-only inspection) result. Implementation follows in stages 1–4; the final status is in
`MODULE_pageflip6_r5.md`.

## 1. What exists today (reuse, do not rebuild)

| Area | Where | Notes |
|---|---|---|
| Rendering | `core/pdf/PdfSession` | `render(page, w, h, night, annots)` returns a bitmap **owned by the session LRU (64 MB)** and shared with `PageCanvas` (same key -> same object). Callers must never `recycle()` it. `renderRegion` for high zoom tiles. All native calls serialised with a mutex on IO. |
| Page widget | `ui/reader/PageCanvas.kt` | One interactive page: base bitmap (render width quantised to 48 px, max 3200 px), zoom tile, selection, links, annotations, search hits, forms5, tools. Its `onTap` callback is how PageView implements edge taps. Long press = text / annotation selection (detectTapGestures). |
| Page list | `ui/reader/PageView.kt` | `ViewMode.CONTINUOUS` (LazyColumn, vertical continuous), `SINGLE`/`FACING`/`COVER` (HorizontalPager, plain slide). Pinch zoom + pan via `readerGestures` (Initial pass), double-tap zoom, crop mode, magnifier, auto-flip bar. Edge-tap flip uses UI layout direction. `pendingJump` is the single jump channel (toolbar, outline, search, history back/forward, keys, auto flip). |
| Spread mapping | `ui/reader/view/ViewExtras.kt` `PageSpread` | item <-> page mapping for SINGLE / FACING / COVER (cover page alone). `vx.flip(delta)` -> `state.jumpTo`. |
| Keys | `ui/view5/Keys5.kt` (V5Keys: Home/End, Ctrl shortcuts), `ui/gaps6/ReaderHooks.kt` (G6Keys -> `vx.handleKeyCode`: arrows, PgUp/PgDn, volume, mirrored in RTL) | All end in `jumpTo` -> `pendingJump`. Space is not mapped. |
| Auto flip | `ui/reader/view/ScreenControls.kt` | timer -> `vx.flip(1)` -> `pendingJump`. |
| Presentation | `ui/view5/Presentation5.kt` | Separate full-screen dialog with 7 `AnimatedContent` transitions (none, fade, push, cover, zoom, wipe, flip-up). Left untouched (its own surface); the new library covers the same looks for the reader. |
| Position history | `ui/view5` (`V5State.back/forward`) | uses `jumpTo` -> automatically animated by the new host. |
| Scrubber | `FastScroller` (continuous mode only, vertical) | the new host gets its own horizontal scrubber with thumbnails. |
| Prefs | `AppSettings` (DataStore): `continuousScroll`, `tapEdgesFlip`, `volumeKeysFlip`… | New settings go to own SharedPreferences `pageflip` (no edit of the shared `SettingsStore`). |

Already covered requirements (A): vertical continuous scroll, one-page and two-page spread (+cover page), paged horizontal
swipe, edge taps, keyboard, volume keys, RTL mirroring of keys/spread order. Missing: horizontal continuous scrolling,
vertical paging, book layout with margins & spine, document-based RTL, mouse wheel paging, Space keys, animated jumps,
all of B/C/D/E.

## 2. Architecture

```
core/pageflip/   pure Kotlin, unit-tested
  PageFlipPrefs.kt   enums (layout, axis, effect, direction, motion) + prefs data class + key=value codec
  CurlMath.kt        cylinder-projection page curl: fold from finger, vertex deformation, mesh + shading arrays
  FlipMath.kt        spread/page mapping for book layout (RTL, cover alone on the outer side), drag -> delta,
                     edge zones, velocity -> commit & duration, effect timing curves
  BoundedLru.kt      byte-bounded LRU with eviction callback (snapshot cache core)
  ScriptDirection.kt RTL detection from page text (Arabic / Hebrew / Syriac / Thaana ranges)
ui/pageflip/
  PageFlipController.kt   per-reader state (weak map like V5State), prefs (global + per document), reduced motion,
                          haptics, `hosts(state)` decides whether the new host replaces PageView
  PageSnapshotCache.kt    renders page snapshots at display size via PdfSession on IO, current ±1 (±2 when memory allows)
  FlipRenderer.kt         draws one transition frame on android.graphics.Canvas (modern effects via Matrix/Camera)
  CurlRenderer.kt         drawBitmapMesh curl / book / hard cover / page stack
  PageFlipHost.kt         the "page-turn" view mode: interactive PageCanvas when settled, snapshot overlay while moving
  FlipGestures.kt         pointer layer: edge zones, drag/corner drag, fling, long-press peek, riffle, wheel, pinch/pan
  HorizontalScrollHost.kt horizontal continuous layout (LazyRow of PageCanvas)
  PageScrubber.kt         slider with thumbnail bubble + page-stack preview
  PageFlipSettingsSheet.kt + EffectPreviews.kt  settings with live previews
  PageFlipKeys.kt         Space / Shift+Space / arrows in document direction
```

### Snapshot technique (all animated modes)
Transitions never animate live `PageCanvas` composables. When a turn starts, the host draws a full-viewport Canvas with
page **bitmap snapshots** (same size key as PageCanvas, so normally a PdfSession cache hit) of the *from* and *to*
items only (stack turns add up to 3 low-resolution intermediate leaves). When the motion settles the interactive
PageCanvas of the new item is composed again (the overlay stays ~150 ms so the swap is invisible) -> selection, links,
annotations, forms, zoom, search highlights, bookmarks keep working exactly as before. Memory: bounded LRU of
references (default 48 MB budget, 24 MB / 0.6 scale on low-RAM devices); session bitmaps are never recycled by us
(they are shared with PageCanvas — recycling would crash it), evicting only drops the reference.

### Per-effect technique
| Effect | Technique |
|---|---|
| Page curl (curl / corner peel / page fold) | Cylinder projection: fold axis from grabbed corner and finger (perpendicular-bisector + radius correction, page kept attached at the bound edge), every vertex of a 32×24 grid is wrapped around a cylinder of radius r (`d' = r·sinθ`, `z = r(1−cosθ)`, flipped flat beyond πr). Drawn with `Canvas.drawBitmapMesh` (HW accelerated since API 18, incl. per-vertex colours; `drawVertices` is not HW accelerated before API 29, so it is not used). Front and back are two meshes of the same grid; each clamps the other side's vertices onto the cylinder crest (zero-area cells), so no alpha tricks are needed. Per-vertex colours = lighting from the surface normal; back side = same bitmap washed out with a ColorMatrix (paper show-through, mirrored automatically by the geometry); gradient shadow cast on the revealed page; radius shrinks to 0 at both ends so the page lands flat. Curvature setting = radius; a small radius gives a sharp fold. Corner drag = grabbing the corner (diagonal axis); edge drag = vertical axis. |
| Realistic book (double-page flip) | Same mesh, leaf hinged at the spine; leaf back shows the next spread's facing page (texture u mirrored). Spine: binding shadow gradient on both inner edges + centre crease; paper thickness: stacked edge lines on the outer sides proportional to pages left on each side; book margins (setting). Cover open / close (COVER spread, first or last leaf): **hard cover = rigid rotation** with `android.graphics.Camera` (a cover board is rigid, so a rotation is physically right there; it is never used to fake paper). Stack-of-pages turning: jumps of ≥2 screens turn up to 4 leaves staggered (intermediate leaves at low resolution). |
| Slide (H/V) = Push | Both pages translate (`Canvas.translate`). |
| Reveal | Old page moves away on top (with edge shadow), new page static below. |
| Parallax (includes "cover / slide over") | New page slides over, old moves at 30 %·intensity speed and dims. |
| Fade | Cross-fade (paint alpha). |
| Scale + crossfade | Material "fade through": out 1->0.92 + fade, in 0.92->1 + fade. |
| Zoom in/out | Old grows 1->1.25 and fades, new grows 0.8->1. |
| Wipe | New page revealed by a moving clip edge with a soft gradient edge. |
| Rotate (2D) | Old page swings out around its bottom outer corner while translating. |
| 3D rotate (door) | Rigid leaf rotating around its bound edge with perspective (`Camera.rotateY/X`). Labelled as a 3D rotation, not a curl. |
| Cube | Two faces rotating around the shared edge (Camera, perspective distance ~2.5× page). |
| Flip card | 180° turn around the centre axis, back face = new page. |
| None | Instant (also the reduced-motion fallback when the system "remove animations" is on). |
| Motion | Compose `Animatable`: spring (velocity-aware, bouncy option for flat effects only) or eased tween; fling velocity -> commit decision and remaining duration. |

Why not a library: the only Compose curl library known (`oleksandrbalan/pagecurl`) draws a flat fold with clip paths
(no cylinder, no back-texture control, no two-page spine / RTL); GL libraries (`eschao/android-PageFlip`,
`harism/android-pagecurl`) are unmaintained since ~2017 and View/GL based. A custom mesh is ~400 lines, testable and
reuses PdfSession bitmaps directly. **No new dependency.** OpenGL ES (GLSurfaceView in AndroidView) is not needed:
a 33×25 vertex mesh is ~800 vertices per side, trivial for drawBitmapMesh on any GPU from API 26; GL would add a
second texture upload path, EGL context loss handling and z-order issues with Compose overlays.

### De-duplicated effect list (14)
Curl (= corner peel = page fold = single-page realistic flip; they differ only in grab point and radius), Book
(= double-page flip + spine + thickness + cover open/close + stack turning), Slide (= horizontal slide = vertical slide
= push; axis is a setting), Reveal, Parallax (= cover / slide-over with depth), Fade, Scale+crossfade, Zoom, Wipe,
Rotate, 3D rotate, Cube, Flip card, None. "Smooth spring-based motion" is a motion option for all effects, not an effect.

### Layouts / navigation model
- Vertical continuous = existing `ViewMode.CONTINUOUS` (unchanged default path).
- Paged (page-turn host): spread from `state.viewMode` (SINGLE / FACING / COVER — same `PageSpread` as `vx.flip`),
  axis horizontal or vertical (vertical paging), book layout with adjustable margins & spine.
- Horizontal continuous: LazyRow host (RTL = reversed order).
- Direction: AUTO (document text: Arabic/Hebrew majority -> RTL; no text -> UI direction), LTR, RTL.
- Input: edge tap zones (outer strip, configurable width), swipe / drag following the finger, corner drag, fling velocity,
  long-press peek (edge zones only, see limitations), riffle (vertical drag along the outer edge), mouse wheel,
  keyboard (arrows in document direction, PgUp/PgDn, Space/Shift+Space, Home/End), volume keys (existing), pinch zoom.
- Every jump (`pendingJump`) is animated: ±1 screen with the effect, farther with stack turning (book effects) or the
  effect between the two screens.

## 3. Limitations (by design)
- Live text selection / link taps are not available *during* a turn (the overlay shows snapshots); they return as soon
  as it settles. Pending (unsaved) annotations and search highlights are not part of the snapshot (stored annotations are).
- Continuous-scroll modes scroll; page-turn effects apply only to the paged host.
- Zoomed in (zoom > 1): turn gestures are disabled (one finger pans); taps / keys / wheel still turn with the effect
  after resetting zoom to 1.
- Edge zones are navigation zones in page-turn mode: a long press there peeks instead of selecting text; a link that
  lies inside the outer strip is reachable after zooming or with edge zones disabled.
- Crop mode and view rotation fall back to the classic PageView (snapshot geometry assumes unrotated full pages).
- Reduced motion (setting or system animator scale 0) -> fade (or none when animations are removed).
- Very old / weak GPUs: drawBitmapMesh cost is per vertex, not per pixel; on low-RAM devices snapshots are rendered at
  0.6× and only ±1 screens are prefetched.
- Presentation mode keeps its own 7 transitions (separate dialog).
- Device testing (touch, mouse, keyboard, RTL/LTR, portrait/landscape) must be done by the user; this module ships with
  JVM unit tests of the math only.
