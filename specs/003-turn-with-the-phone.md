# 003 — Picture and readouts turn with the phone

**Status**: in progress
**Requested by**: babs
**Date**: 2026-11-23

## Problem

When the phone is turned sideways, Android relays the app out in landscape: the picture is squeezed into the short screen height and becomes tiny, and the camera, which turns with the phone, shows the scene on its side. Upside down, the app does not turn at all, so every number is upside down.

## Solution

The app behaves like a camera app. The layout never moves: the picture stays at the top of the phone, the readouts under it, the buttons at the bottom. When the phone is turned a quarter either way or upside down, the content turns in place so it reads upright: the picture keeps its full size, its scale, marker and centre labels face the user, and the large Min / Max / Center readouts turn too. Photos and videos come out upright, as the user saw them.

## Scope

- The activity stays in portrait; no system relayout on rotation.
- The phone's orientation (0°, 90°, 180°, 270°) is read from the accelerometer, snapped to the nearest quarter with a dead band, kept while the phone lies flat.
- The composed picture is drawn for that orientation (thermal image turned so the scene keeps its place on the screen, scale band, labels and badges upright for the user, still 768 x 832), and the live view shows it turned back onto the portrait screen.
- The readout band and the status message ("Plug in the camera", "Camera disconnected"…) turn in place, laid out for the turned box.
- Photos: the picture as the user saw it (768 x 832, upright) and the untouched `.bin`; the PNG JSON gains `orientation`.
- Videos: the orientation is held from Record to Stop, so a file never turns half way.
- The Rotate button (camera mounted another way) still applies, on top of the phone's orientation.

## Out of scope

- Turning the button labels and the smoothing label (they stay as in portrait).
- A landscape layout (picture on one side, controls on the other).
- Tablets whose natural orientation is landscape.
- The same field in the Linux viewer's photos.
- System toasts (drawn by Android in its own orientation).
- Large screens (sw >= 600 dp: tablets, foldable inner screens), where Android 16 ignores the portrait lock.

## Acceptance criteria

- [ ] **AC1** Turning the phone never relays the app out: the picture, the readout band and the buttons keep their places on the screen at 0°, 90°, 180° and 270°.
- [ ] **AC2** At each orientation the picture on screen is upright for the user: the scale band runs along the user's bottom edge of the picture, its labels and the marker and centre labels read upright, the thermal scene keeps its place on the screen as in portrait, and the picture is as wide as in portrait (no shrink to a landscape height).
- [ ] **AC3** At each orientation Min, Max and Center stay in the band under the picture (phone frame), turned to read upright, with the values fitted to the turned box as in 002-AC4 and equal to the picture's labels.
- [ ] **AC4** The orientation changes only once the phone is within 30° of the next quarter (no flicker around 45°), within 0.5 s, and holds while the phone lies flat; the status message turns with it.
- [ ] **AC5** A photo taken at any orientation is a 768 x 832 PNG showing exactly what the screen showed, upright (during a recording, the orientation held per AC6); its `.bin` is the untouched frame; its JSON keeps every 002-AC7 field (`rotation` is still the Rotate button setting) and adds `orientation` (0, 90, 180 or 270), the turn the picture was drawn for.
- [ ] **AC6** A video keeps the orientation it started with until Stop (the live view holds it too while recording) and is still the 002-AC6 MP4.
- [ ] **AC7** The Rotate button still turns the image by 90° steps at every phone orientation, and its setting survives a restart.

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

## Data model impact (summary)

None (one more field in the photo JSON).

## Open questions

- (none)

## Decisions

- The app follows the accelerometer even when Android's auto-rotate is off, as camera apps do — because the content turns in place and the layout never moves.
- Photos are saved upright as seen, not in the phone's frame — because a gallery shows them without knowing how the phone was held.
- The orientation is frozen while recording — a video that turns half way plays sideways in every player.
- Button labels do not turn — four short buttons per row cannot hold their text turned a quarter.
