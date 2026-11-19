# 002 — Horizontal scale, big readouts, volume-key photo

**Status**: approved
**Requested by**: babs
**Date**: 2026-11-19

## Problem

In portrait the thermal image does not use the screen width: the vertical scale bar takes a column on its right and leaves large empty bands above and below. The temperatures (centre, hot and cold spots) are small labels on the image, hard to read when the phone is held at arm's length to scan a wall or a radiator. Taking a photo needs a tap on a small button while aiming.

## Solution

The thermal image fills the width of the screen. Under it, a horizontal colour scale (cold on the left, hot on the right) with its low / middle / high temperatures, then a line with the centre, hottest and coldest temperatures. Photos and videos carry exactly that picture. The same three temperatures are also shown in large type in the app itself, readable at arm's length (not in the captures). Either volume key takes a photo.

## Scope

- Composed picture 768 x 896: thermal image 768 x 768 on top (full width), then a band with the horizontal scale bar (cold left, hot right), its low / middle / high labels, and a readout line `Centre 23.1  Max 23.8  Min 22.9`. The vertical scale panel goes away.
- Large native readouts (Android text views, outside the composed picture) for Centre, Max and Min, refreshed about 5 times per second, hidden when no source runs.
- Volume up and volume down take a photo (same as the Photo button) while a source runs; the volume does not change then.

## Out of scope

- A layout specific to landscape (the picture is fitted as today).
- Recording or other actions on the volume keys, burst shots, configurable key mapping.
- Changing the spot tracker, smoothing or range rules of spec 001.

## Acceptance criteria

- [ ] **AC1** The composed picture is 768 x 896 (both multiples of 16): the 768 x 768 thermal image on top, below it a horizontal scale whose left end is the coldest palette colour and right end the hottest, labelled with the colour range's low / middle / high values, and a readout line with Centre, Max and Min; photo PNGs and video frames are this picture.
- [ ] **AC2** With "lock range" on, the horizontal scale's labels and colours stop following the scene (spec 001 AC3 in the new layout).
- [ ] **AC3** Centre is the mean of the raw 2x2 centre pixels; Max and Min are the hot and cold marker values; all one decimal; for a given frame the picture and the large native readouts show the same three numbers.
- [ ] **AC4** While a source runs, the app shows Centre, Max and Min in large type (centre at least 48 sp, max/min at least 32 sp) outside the composed picture, updated about 5 times per second; they are absent from photos and videos and hidden when no source runs.
- [ ] **AC5** While a source runs, pressing volume up or volume down takes exactly one photo (holding the key does not take more) and leaves the volume unchanged; with no source the keys change the volume as usual.
- [ ] **AC6** Recording still produces a playable H.264 MP4 at 768 x 896, about 25 fps, duration matching the recording ± 1 s.

## Phases

### Phase 1 — Horizontal scale and readout line in the composed picture
- Work: FrameComposer layout 768 x 896 (thermal image, crosshair and markers unchanged, horizontal scale bar with labels, readout line), recorder and photo at the new size, live view fit.
- **Covers**: AC1, AC2, AC3, AC6
- **Data model impact**: none
- **DoD**: `./gradlew clean test check assembleDebug assembleRelease` green; on the replay emulator: screenshot shows the full-width image, horizontal scale and readout line; photo PNG is 768 x 896 and matches the screen; a ~10 s recording probed by `ffprobe` is h264 768 x 896, ~25 fps, duration within 1 s.

### Phase 2 — Large native readouts and volume-key photo
- Work: Centre / Max / Min text views in the free band between the picture and the buttons, throttled updates on the UI thread; volume keys mapped to the photo action while a source runs.
- **Covers**: AC3, AC4, AC5
- **Data model impact**: none
- **DoD**: gates green; on the replay emulator: screenshot shows the large readouts matching the picture's readout line; `adb shell input keyevent KEYCODE_VOLUME_UP` and `..._DOWN` each save one photo pair and `dumpsys audio` shows the music volume unchanged; a long press (`input keyevent --longpress`) saves one photo; with no source the key changes the volume.

## Data model impact (summary)

None.

## Open questions

- (none)

## Decisions

- Max / Min show the hot and cold marker values (3x3-smoothed, steadied), not the raw frame extremes — the numbers then match the markers on the image; the scale labels keep showing the colour range.
- Large readouts refresh at ~5 Hz — fast enough to follow a scan, slow enough to read (same cadence as `scripts/live_view.py`).
- The picture shrinks from 864 x 768 to 768 x 896: photos and videos change aspect ratio from this version on.
