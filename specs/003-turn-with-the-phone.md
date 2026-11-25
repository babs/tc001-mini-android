# 003 — Picture and readouts turn with the phone

**Status**: shipped
**Requested by**: babs
**Date**: 2026-11-23

## Problem

When the phone is turned sideways, Android relays the app out in landscape: the picture is squeezed into the short screen height and becomes tiny, and the camera, which turns with the phone, shows the scene on its side. Upside down, the app does not turn at all, so every number is upside down.

Once the picture turns (phases 1 and 2), sideways the turned 768 x 832 picture puts its scale band along the long edge of the screen, so the thermal image is 997 px wide instead of 1080 px on a 1080 px phone (-15 % of area).

## Solution

The app behaves like a camera app. The layout never moves: the picture stays at the top of the phone, the readouts under it, the buttons at the bottom. When the phone is turned a quarter either way or upside down, the content turns in place so it reads upright: the picture keeps its full size, its scale, marker and centre labels face the user, and the large Min / Max / Center readouts turn too. Photos and videos come out upright, as the user saw them. Sideways, the scale moves beside the thermal image and turns vertical, so the thermal image stays as large as in portrait.

## Scope

- The activity stays in portrait; no system relayout on rotation.
- The phone's orientation (0°, 90°, 180°, 270°) is read from the accelerometer, snapped to the nearest quarter with a dead band, kept while the phone lies flat.
- The composed picture is drawn for that orientation (thermal image turned so the scene keeps its orientation on the screen, scale band, labels and badges upright for the user, sized per AC10), and the live view shows it turned back onto the portrait screen.
- The readout band and the status message ("Plug in the camera", "Camera disconnected"…) turn in place, laid out for the turned box.
- Photos: the picture as the user saw it (sized per AC10, upright) and the untouched `.bin`; the PNG JSON gains `orientation`.
- Videos: the orientation is held from Record to Stop, so a file never turns half way.
- The Rotate button (camera mounted another way) still applies, on top of the phone's orientation.
- Sideways (90°, 270°), the picture is the 768 x 768 thermal image with a 64 px band beside it holding a vertical scale (coldest colour at the bottom, hottest at the top) and its low / middle / high labels, upright; the band sits on the side of the phone's portrait bottom edge (the user's left at 90°, right at 270°), so the picture fills the portrait box and the thermal image is as large as in portrait.
- Captures follow the screen: 768 x 832 upright, 832 x 768 sideways (photo PNG and video MP4); a video keeps one size from Record to Stop because its orientation is held.

## Out of scope

- Turning the button labels and the smoothing label (they stay as in portrait).
- A landscape layout (picture on one side, controls on the other).
- Tablets whose natural orientation is landscape.
- The same field in the Linux viewer's photos.
- System toasts (drawn by Android in its own orientation).
- Large screens (sw >= 600 dp: tablets, foldable inner screens), where Android 16 ignores the portrait lock.

## Acceptance criteria

- [x] **AC1** Turning the phone never relays the app out: the picture, the readout band and the buttons keep their places on the screen at 0°, 90°, 180° and 270° (180° superseded by 004-AC1).
- [x] **AC2** At each orientation the picture on screen is upright for the user: the scale band runs along the user's bottom edge of the picture upright and beside it sideways (AC8), its labels and the marker and centre labels read upright, the thermal scene keeps its orientation on the screen as in portrait (its place too at 90° and 270°, AC8; at 180° the thermal image is shifted by the band height, kept as is by AC11), and the picture is as wide as in portrait (no shrink to a landscape height) (180° superseded by 004-AC1).
- [x] **AC3** At each orientation Min, Max and Center stay in the band under the picture (phone frame), turned to read upright (sideways, Min over Max over Center), with the values fitted to the turned box as in 002-AC4 and equal to the picture's labels (180° superseded by 004-AC1).
- [x] **AC4** The orientation changes only once the phone is within 30° of the next quarter (no flicker around 45°), within 0.5 s, and holds while the phone lies flat; the status message turns with it.
- [x] **AC5** A photo taken at any orientation is a PNG (size per AC10) showing exactly what the screen showed, upright (during a recording, the orientation held per AC6); its `.bin` is the untouched frame; its JSON keeps every 002-AC7 field (`rotation` is still the Rotate button setting) and adds `orientation` (0, 90, 180 or 270), the turn the picture was drawn for.
- [x] **AC6** A video keeps the orientation it started with until Stop (the live view holds it too while recording) and is the 002-AC6 MP4, sized per AC10.
- [x] **AC7** The Rotate button still turns the image by 90° steps at every phone orientation, and its setting survives a restart.
- [x] **AC8** At 90° and 270° the thermal image on screen is the same size and place as at 0° (full width), and the scale is beside it, on the side of the phone's portrait bottom edge.
- [x] **AC9** At 90° and 270° the scale reads upright for the user: coldest colour at the bottom, hottest at the top, low / middle / high labels with °C matching the colour range (002-AC1 / 002-AC2 rules, locked range included).
- [x] **AC10** Captures are the picture as drawn for the orientation held at capture: 768 x 832 with the horizontal scale below at 0° and 180°, 832 x 768 with the vertical scale beside the image at 90° and 270°, for photo PNGs and H.264 MP4s (~25 fps, duration within 1 s); a recording keeps its size until Stop even if the phone turns. This supersedes the fixed 768 x 832 of 002-AC1 / 002-AC6 for sideways captures; the `tc001` JSON fields do not change (`orientation` and the PNG size tell the layout).
- [x] **AC11** At 0° and 180° the picture, the screen and the captures are exactly as before this phase (180° superseded by 004-AC1: upside down the app turns like portrait).

## Phases

### Phase 1 — Orientation and turned picture
- Work: portrait lock; orientation listener and a pure snapping function (quarter, dead band, flat); composer drawing for the orientation; live view showing the turned picture; status message turned; photo JSON `orientation`; orientation held while recording.
- **Covers**: AC1, AC2, AC4, AC5, AC6, AC7
- **Data model impact**: none
- **DoD**: `./gradlew clean test check assembleDebug assembleRelease` green; unit tests on the snapping function (each quarter, both sides of every dead band, flat); on the USB-passthrough emulator with the camera, screenshots at 0°, 90°, 180°, 270°; a photo at 90° pulled and checked upright and 768 x 832 with `orientation` 90; a recording started at 0° and turned stays at 0° (`ffprobe` + a frame grab).

### Phase 2 — Readouts turned in place
- Work: the readout band's content turned with the phone and laid out for the turned box, values fitted as in 002-AC4.
- **Covers**: AC3
- **Data model impact**: none
- **DoD**: gates green; screenshots at the four orientations show the readouts upright, inside the band, equal to the picture's labels.

### Phase 3 — Scale beside the picture when sideways
- Work: composer drawing the sideways picture (thermal image and a vertical scale band with labels), live view showing it in the portrait box, photo and recorder sized per AC10.
- **Covers**: AC2, AC5, AC8, AC9, AC10, AC11
- **Data model impact**: none
- **DoD**: gates green; JVM tests on the pure parts (vertical scale colours and label positions, picture size per orientation); on the emulator (camera or debug replay): screenshots at 0/90/180/270 with the thermal image the same size; a sideways photo checked 832 x 768 and equal to the screen; a recording started sideways checked 832 x 768 with `ffprobe`, still 832 x 768 after turning the phone upright mid-recording; one started upright still 768 x 832.

## Data model impact (summary)

None (one more field in the photo JSON).

## Open questions

- (none)

## Decisions

- The app follows the accelerometer even when Android's auto-rotate is off, as camera apps do — because the content turns in place and the layout never moves.
- Photos are saved upright as seen, not in the phone's frame — because a gallery shows them without knowing how the phone was held.
- The orientation is frozen while recording — a video that turns half way plays sideways in every player.
- Button labels do not turn — four short buttons per row cannot hold their text turned a quarter.
- Sideways, Min over Max over Center: in the turned band Min / Max reach about 86 sp against 45 sp side by side (user choice).
- Sideways captures are 832 x 768, as the screen shows them, not 768 x 832 everywhere (review council, unanimous on the owner's request): one composition feeds screen, photo and video, and a capture stays what the screen showed.
