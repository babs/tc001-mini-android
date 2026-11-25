# 004 — Upside down, the whole app turns like portrait

**Status**: in progress
**Requested by**: babs
**Date**: 2026-11-25

## Problem

The camera plugs into the phone's USB port, at the bottom edge. Held upside down (180°), spec 003 turns each element in place, so the user sees the buttons on top, the readouts in the middle and the picture at the bottom, far from the camera, which now sticks out at the top. Pointing the camera and reading the picture are at opposite ends of the phone.

## Solution

Upside down, the app turns as a whole, like a normal app in reversed portrait: the picture at the user's top, next to the USB port and the camera, the readouts below it, the buttons and the slider at the bottom, every label readable. Sideways (90°, 270°) and upright nothing changes.

## Scope

- At 180° the screen goes to reversed portrait (Android's own rotation); the content is drawn for the phone's turn relative to the screen, so nothing is turned twice.
- The thermal scene stays upright for the user, as in spec 003.
- 0°, 90° and 270°: spec 003 behaviour unchanged.
- During a recording the screen orientation is held too (spec 003 AC6): no flip until Stop.
- Captures at 180°: as in spec 003 (768 x 832, upright, JSON `orientation` 180).

## Out of scope

- Reversed landscape layouts (sideways stays the in-place turn of spec 003).
- Large screens (as spec 003).

## Acceptance criteria

- [ ] **AC1** With the phone upside down, the picture is at the user's top (the USB port side), the readouts under it, the buttons and the smoothing slider at the bottom, and every text (picture labels, readouts, buttons, status message) reads upright; the thermal scene is upright.
- [ ] **AC2** At 0°, 90° and 270° the screen, the picture, the readouts and the captures are as in spec 003.
- [ ] **AC3** A photo taken upside down is the 768 x 832 picture as the screen showed it, upright, with `orientation` 180; a video started upside down is a 768 x 832 H.264 MP4.
- [ ] **AC4** The app goes to and from upside down also when Android's auto-rotate is off, as it turns at 90° and 270° (spec 003 decision).
- [ ] **AC5** While recording, turning the phone does not flip the screen nor the picture; the screen catches up after Stop.

## Phases

### Phase 1 — Reversed portrait at 180°
- Work: activity allowed in reversed portrait, the display rotation read and the content turned by the phone's turn relative to the screen (picture, readouts, status message), the screen orientation held while recording.
- **Covers**: AC1, AC2, AC3, AC4, AC5
- **Data model impact**: none
- **DoD**: `./gradlew clean test check assembleDebug assembleRelease` green; JVM tests on the pure parts (relative turn); on the emulator (debug replay): screenshots at 0/90/180/270, auto-rotate off at 180, a photo at 180 checked, a recording started at 0 and turned to 180 holds the screen until Stop.

## Data model impact (summary)

None.

## Open questions

- (none)

## Decisions

- Reversed portrait through Android rather than an in-app layout swap: the system already lays the whole window out upside down, labels and buttons included.
- The screen is held during a recording like the picture, so a video never shows the screen flipping.
- Supersedes 003-AC11 for 180° only.
