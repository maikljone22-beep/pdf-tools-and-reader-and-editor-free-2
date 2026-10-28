# pdf-39: video annotation properties and playback (FINAL_CHECK_2 #15, §19)

| Row | Status | Where |
|---|---|---|
| §19 Video poster (none / from file) | DONE | `VideoPdf.Options.poster` (AUTO / NONE / FILE); Properties tab image picker (`posterFromImage`, baseline JPEG ≤ 1280 px); `AnnotationWriter.writeVideo` writes no poster for NONE |
| §19 Video properties: appearance, locked, title, alt text | DONE | Properties tab: title (`/T`), alt text (`/Contents` + media clip `/Alt`), locked (`/F` Locked, now also written for videos), poster |
| §19 Playback settings (volume, repeat N, keep open) | DONE | Playback tab: controls, mute, volume 0–100 (`/BE /V`), repeat 1–99 or loop (`/RC`), keep player open, play full screen (`/SP /BE /W 1`). In-app player honours all of them (`VideoHost`, `VideoPlayback.plays`) |
| §19 Media Actions tab | DONE | Actions tab: start on page visible (`/AA /PV`) or page open (`/PO`); stop when the page is hidden or closed (`/AA /PI` + `/PC` rendition actions, `/OP 1`). Read back from other viewers' files too |
| §19 Renditions | STILL MISSING | The reader follows selector renditions (`/S /SR`) and plays the first media rendition. There's no editor for adding alternative clips: the pending annotation holds a single video file |

- Saved in our `/SDVideo` keys, and also in the standard MediaPlayParams / MediaScreenParams / additional actions, so other viewers get the same settings. A foreign file without our keys is read from the standard ones.
- In this app a video always stops when it leaves the screen (no surface to draw on). The stop option only decides what's written for other viewers, and the dialog says so.
- Tests: `VideoPdfTest.playbackSettingsAndMediaActionsRoundTrip`; `defaultOptionsHaveNoAutoplayAction` and the foreign Screen test updated for the stop actions and the start trigger.
