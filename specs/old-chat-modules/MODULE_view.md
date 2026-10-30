# MODULE view — reader view features (round 3)

Not compiled (round-3 rule). Every M3 / icon API used was checked against the cached jars (material3 1.3.1, icons-extended 1.7.8).

## Files
New (`app/src/main/java/com/smartdocs/editor/ui/reader/view/`):
- `ViewExtras.kt`: `ViewExtras` holds the view state not in ReaderState: facing separator, auto-flip interval/progress, orientation lock, screen timeout, reflow scale, magnifier flag, `crop`. Also does spread-aware `flip()`/`canFlip()` and `handleKeyCode()`. `PageSpread` maps pager items to pages for SINGLE/CONTINUOUS/FACING/COVER. Saved values go in SharedPreferences `"view"`.
- `CropController.kt`: display-only crop. Auto mode renders each page 160 px wide, finds the bounding box of non-white pixels (threshold 236), adds a 2.5 % margin (minimum 25 % of each side) and caches it per page; the cache is cleared when `renderVersion` changes. Manual mode uses one rect for all pages, with an optional mirrored rect on even pages. The setting is saved per document (`crop:<path>` in `"view"`). Also has the rotation and box→full-page helpers.
- `PageTint.kt`: the reference's 8 page-colour presets, each with its text colour. `PageTintDraw.drawPageTint()` maps white→background and black→text colour per channel using blend-mode overlays (Multiply + Plus for light presets, Multiply + Difference for dark presets).
- `MagnifierWindow.kt`: the in-app floating pan & zoom window. It shows a mini-map (the current page, or the current spread in paged modes) with a rectangle for the visible area. Drag or tap the map to pan the main view; drag the header to move the window. It also has a zoom slider, prev/next buttons and close.
- `ScreenControls.kt`:
  - `ReaderViewEffects()`: keep-screen-on with a timeout that restarts on touch; orientation lock; key bus registration; tab bookkeeping; per-document crop restore; restore-session flag.
  - `readerTouchWatcher` modifier, `ReaderKeyBus`, `AutoFlipBar` (+/−, circular progress, stop) and `AutoFlipEffect`.
- `ReaderTabs.kt`: `ReaderTabs` store (max 5, oldest dropped first, prefs `"tabs"`: list/active/interact). `rememberTabSwitcher` saves pending edits first, then replaces the reader route. Also `ReaderTabStrip` (tablet), `ReaderTabsAction` (phone: icon with count and a "recently viewed files" menu) and `RestoreLastSession`.
- `ViewSheet.kt`: the full "View settings" sheet in the reference order, plus crop menu, manual crop dialog (preview, 4 margin sliders, symmetric option, reset to detected box), screen-timeout dialog and `PresetRow`.
- `PageViewParts.kt`: `readerGestures` and `FastScroller`, moved out of PageView.kt unchanged.

Edited:
- `PageView.kt` (owned, rewritten):
  - COVER mode. Pager count and current-page mapping go through `PageSpread`, and the list/pager states are recreated for each mode at the current page.
  - The facing separator sets the gap to 8 dp or 0.
  - `DisplayedPage` does the crop: a clipped box with the full-size PageCanvas shifted inside it, so PageCanvas geometry is unchanged.
  - Visible-clip maths fixed for pages inside a spread.
  - Pan now uses `absoluteOffset`. The old `offset {}` was mirrored in RTL, which flipped the pan and tile maths in Arabic.
  - Hosts the magnifier window, the auto-flip bar and the auto-flip timer.
- `ReaderDialogs.kt` (owned):
  - `ViewModeSheet` now calls `ViewSettingsSheet`.
  - `PageColorSheet` uses the 8 presets.
  - `AutoFlipDialog` covers 20–100 s in 10 s steps, default 60, and is saved.
  - `ReflowView`: font scale ×1.25 / ×0.8 within 1–8 (base 14 sp), colours from the preset or night mode, and a close button.
- `ReaderState.kt`: added `COVER` to `ViewMode` and one line `val vx = ViewExtras(this)`.
- `ReaderScreen.kt`, small hooks:
  - `ReaderViewEffects(...)` replaces the old `view.keepScreenOn` effect.
  - The key handler now calls `state.vx.handleKeyCode`.
  - `.readerTouchWatcher(state)` on the root Box.
  - `switchTab`, plus `ReaderTabsAction` (phone top bar) and `ReaderTabStrip` (tablet, under the top bar).
- `ReaderToolbars.kt`: two chips in ViewToolbar, Crop (auto toggle) and Pan & zoom.
- `PageCanvas.kt` (one line): the page-colour multiply became `with(PageTintDraw) { drawPageTint(...) }`.
- Strings: `res/values/strings_view.xml` and `res/values-en/strings_view.xml` (`vw_`).

## Behaviour notes
- Cover facing: page 1 alone, then (2,3) (4,5)…. Pager items = 1 + n/2. On its own, the cover keeps the same scale as a pair. In RTL the lower page number is on the right.
- Edge taps, volume keys, arrow/PageUp/PageDown keys, auto-flip and the magnifier arrows all move one screen (two pages in facing, cover-aware).
- Crop turns reflow and the magnifier off, and reflow turns crop off (same as the reference).
- Tabs: only the active document has a PdfSession. Switching saves pending edits and then navigates to `Routes.reader(uri, page)` with `popUpTo(Routes.READER){inclusive=true}`, so the old reader is closed. Each tab stores its page and zoom; zoom is restored when keepZoom is on. Closing the active tab opens its neighbour, or leaves the reader if none is left. Every document opened from Home or Files is added to the list.
- Keep screen on: the timeout is read from `"view"` (`screen_on_time`, default 600 s, −1 = never) and restarts on every touch. The system timeout is subtracted, as the reference does. The screen always stays on during auto-flip.
- Orientation lock: stores portrait/landscape when you turn it on and applies PORTRAIT / SENSOR_LANDSCAPE while the reader is shown. It switches back to UNSPECIFIED when you leave the reader.

## Wiring the lead must do
1. **Restore last session.** In `AppNavHost`, after the `NavHost`, add `com.smartdocs.editor.ui.reader.view.RestoreLastSession(nav, enabled = <activity was not started by ACTION_VIEW/SEND>)`. Easiest: expose a flag from `MainActivity.handleIntent`, or pass `enabled = true` and accept that an external open wins anyway (it navigates first, and the restore skips when a reader is already on top).
2. **Volume keys when the page area has no focus** (after dialogs, for example). In `MainActivity` add `override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = com.smartdocs.editor.ui.reader.view.ReaderKeyBus.handle(keyCode) || super.onKeyDown(keyCode, event)`.
3. Optional, matching the reference defaults: `AppSettings.volumeKeysFlip` defaults to `false` in SettingsStore, while the reference flips pages by default. Consider changing it to `true`.
4. No new routes were needed; `Navigation.kt` is untouched.

## Limitations / TODO
- The dark page-colour presets (#2E5073, #644F47) need `BlendMode.Difference`, which requires API 29. On API 26–28 they fall back to a plain multiply, so text stays black. Images are tinted along with text, as in the reference.
- In auto crop, a page shows uncropped until its detection finishes, so the continuous list can shift slightly as heights update. Detection runs for visible pages plus the next page.
- The magnifier mini-map gives pages of a spread equal height, while the main view gives them equal width. For spreads mixing different page sizes the rectangle is approximate. The magnifier is turned off while crop is on (same as the reference).
- No continuous + facing combination; facing and cover are paged modes only.
- Tab "interact" flag is set on ON_STOP and cleared on ON_START or when leaving the reader. It survives process death, but not a crash in the foreground (same as the reference).
- The old `state.reflowFontSize` field is no longer used; the reflow size comes from `vx.reflowScale`.
