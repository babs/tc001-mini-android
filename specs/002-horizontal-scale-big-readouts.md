# 002 — Horizontal scale, big readouts, volume-key photo, photo metadata

**Status**: shipped
**Requested by**: babs
**Date**: 2026-11-19

## Problem

In portrait the thermal image does not use the screen width: the vertical scale bar takes a column on its right and leaves large empty bands above and below. The temperatures (centre, hot and cold spots) are small labels on the image, hard to read when the phone is held at arm's length to scan a wall or a radiator. Taking a photo needs a tap on a small button while aiming.

## Solution

The thermal image fills the width of the screen, at the top. Under it, a horizontal colour scale (cold on the left, hot on the right) with its low / middle / high temperatures. Photos and videos carry exactly that picture; the coldest, centre and hottest temperatures are already on the image (marker and crosshair labels). Photos also embed those values as a small JSON. The same three temperatures are also shown in large type in the app itself under the picture, readable at arm's length (not in the captures). Either volume key takes a photo.

## Scope

- Composed picture 768 x 832: thermal image 768 x 768 on top (full width), then a 64 px band with the horizontal scale bar (cold left, hot right) and its low / middle / high labels with °C. The vertical scale panel and any readout line in the picture go away. Colour code: cold marker cyan, hot marker pink-red, centre crosshair badge white. Crosshair and marker boxes twice as thick as in spec 001, on a thin semi-transparent black outline of even width.
- The live view sits at the top of the screen, leaving the space below it to the large readouts.
- Large native readouts (Android text views, outside the composed picture) for Min and Max side by side with Center below, refreshed about 5 times per second, hidden when no source runs.
- Volume up and volume down take a photo (same as the Photo button) while a source runs; the volume does not change then.

## Out of scope

- A layout specific to landscape (the picture is fitted above the readout band, which keeps at most half the height).
- Recording or other actions on the volume keys, burst shots, configurable key mapping.
- Changing the spot tracker, smoothing or range rules of spec 001.

## Acceptance criteria

- [x] **AC1** (sideways layout and size superseded by 003-AC8/AC9/AC10) The composed picture is 768 x 832 (both multiples of 16): the 768 x 768 thermal image on top, below it a horizontal scale whose left end is the coldest palette colour and right end the hottest, labelled with the colour range's low / middle / high values; no readout line; the cold marker is cyan, the hot marker pink-red, the centre badge white; photo PNGs and video frames are this picture.
- [x] **AC2** With "lock range" on, the horizontal scale's labels and colours stop following the scene (spec 001 AC3 in the new layout).
- [x] **AC3** Center is the mean of the raw 2x2 centre pixels; Max and Min are the hot and cold marker values; all one decimal; for a given frame the large native readouts show the same numbers as the picture's cold marker, centre badge and hot marker labels.
- [x] **AC4** While a source runs, the app shows, outside the composed picture, Min (left) and Max (right) side by side in the same large type, and Center below them across the full width in a larger type, each with a small label above; the band below the picture is sized for 56 sp values, values grow to fill it (up to about 110 sp for Min/Max, 140 sp for Center) and shrink below that only when a wide value would not fit a narrow screen (down to 32 sp) or the band is capped (split screen, landscape, large font scale); updated about 5 times per second; absent from photos and videos and hidden when no source runs.
- [x] **AC5** While a source runs, pressing volume up or volume down takes exactly one photo (holding the key does not take more) and leaves the volume unchanged; with no source the keys change the volume as usual.
- [x] **AC6** (sideways size superseded by 003-AC10) Recording still produces a playable H.264 MP4 at 768 x 832, about 25 fps, duration matching the recording ± 1 s.
- [x] **AC7** Each photo PNG carries a `tEXt` chunk with keyword `tc001` holding one JSON object: `min`, `center`, `max`, `lo`, `hi` (°C, one decimal), `palette`, `rotation`, `mirror`, `smoothing`, `locked`, `time` (ISO 8601); the PNG still decodes as before and `pngcheck`/`exiftool` shows the chunk.

## Phases

### Phase 1 — Horizontal scale and readout line in the composed picture
- Work: FrameComposer layout 768 x 896 (thermal image, crosshair and markers unchanged, horizontal scale bar with labels, readout line), recorder and photo at the new size, live view fit.
- **Covers**: AC1, AC2, AC3, AC6
- **Data model impact**: none
- **DoD**: `./gradlew clean test check assembleDebug assembleRelease` green; on the replay emulator: screenshot shows the full-width image, horizontal scale and readout line; photo PNG is 768 x 896 and matches the screen; a ~10 s recording probed by `ffprobe` is h264 768 x 896, ~25 fps, duration within 1 s.

### Phase 2 — Large native readouts and volume-key photo
- Work: picture band reduced to the scale only (768 x 832), hot marker pink-red, live view at the top, PNG `tEXt` JSON; Min | Max and Center text views in the free band between the picture and the buttons, throttled updates on the UI thread; volume keys mapped to the photo action while a source runs.
- **Covers**: AC1, AC3, AC4, AC5, AC6, AC7
- **Data model impact**: none
- **DoD**: gates green; on the replay emulator: screenshot shows the large readouts equal to the cold marker, centre badge and hot marker labels; `adb shell input keyevent KEYCODE_VOLUME_UP` and `..._DOWN` each save one photo pair and `dumpsys audio` shows the music volume unchanged; a long press (`input keyevent --longpress`) saves one photo; with no source the key changes the volume.

## Data model impact (summary)

None.

## Open questions

- (none)

## Decisions

- Max / Min show the hot and cold marker values (3x3-smoothed, steadied), not the raw frame extremes — the numbers then match the markers on the image; the scale labels keep showing the colour range.
- Native readouts: Min under the cold end of the scale, Max under the hot end, Center below (user choice).
- No readout line in the picture: the marker and crosshair labels already show the values (user choice); photos carry them as JSON instead.
- Photo metadata as a PNG `tEXt` chunk rather than EXIF: framework EXIF writing on PNG is unreliable across versions. MP4 gets no JSON (MediaMuxer has no free-form metadata).
- Colour code: cold = cyan (cold marker, Min), hot = pink-red (hot marker box and label, Max), centre = white, on the picture and in the native readouts (user choice).
- Native layout Min | Max on one row, Center below in larger type (user choice): Center is the value aimed at; two columns give Min/Max twice the width. Band sized for 56 sp, values sized 32 sp to ~110 / ~140 sp to fill it; Min and Max share one size (user asked for bigger than 40 sp).
- Crosshair and marker boxes drawn twice as thick as in 001, on a thin semi-transparent black outline of even width (ends included) so they stay visible on any palette colour (user choice: readable at arm's length).
- Large readouts refresh at ~5 Hz — fast enough to follow a scan, slow enough to read (same cadence as `scripts/live_view.py`).
- The picture changes from 864 x 768 to 768 x 832 (narrower, taller; phase 1 shipped 768 x 896 with a readout line, dropped in phase 2 because the marker labels already show the values): photos and videos change aspect ratio from this version on.
