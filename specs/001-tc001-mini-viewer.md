# 001 — TC001 Mini minimal viewer (Android)

**Status**: shipped
**Requested by**: babs
**Date**: 2026-11-19

## Problem

The only Android app for the TOPDON TC001 Mini is TopInfrared: ~260 MB, Firebase, Facebook, Alibaba and Zoho SDKs, 33 permissions, account login. The owner only wants to see the thermal image, read temperatures, and keep photos and videos.

## Solution

A small app with no third-party SDK and no network access. Plug the Mini in, the app opens on the live thermal view: false colours, a °C scale bar, the hottest and coldest spots marked with their temperature, the centre temperature. One button takes a photo, one records a video; both land in the phone gallery.

## Scope

- Live view from the Mini over USB (UVC "mode 2": 96x96 temperatures, `celsius = raw/64 - 50`), driven with the Android USB host API only.
- Colour palettes: ironbow (default), white-hot, black-hot.
- Scale bar with high / middle / low temperatures of the displayed range; auto range by default, a "lock range" toggle freezes it.
- Hot and cold spot markers with their temperature on a dark backdrop; spots are smoothed (3x3) and move only when a new place beats the current one by 0.3 °C, so noise does not make them jump.
- Centre crosshair and centre temperature.
- Rotate (90° steps) and mirror, for the way the Mini points when plugged into the phone.
- Display smoothing strength `s` in [0, 1] (slider, default 0.5): spatial blend `nearest + s * (bilinear - nearest)` on the normalised plane, plus temporal EMA with `alpha = 1 - 0.6 * s` (s = 0 disables both). Display only: readouts, spots, photo `.bin` always use the raw frame. Same maths as `../scripts/live_view.py --smooth`.
- Photo: PNG exactly as displayed (with scale bar and markers) saved to `Pictures/TC001`, + `.bin` file holding the raw mode-2 frame, saved to `Documents/TC001` (MediaStore refuses non-media files under `Pictures`).
- Video: MP4 (H.264, no audio) of the displayed view, saved to `Movies/TC001`.
- Settings kept across launches: palette, rotation, mirror, smoothing.
- Debug build only: replay a recorded capture instead of the camera, to test the app on an emulator.

## Out of scope

- Emissivity / distance / ambient corrections (needs the XU mailbox protocol, not decoded for the Mini yet); the camera defaults apply (30 cm, 100 %, 25 °C).
- Tap-to-measure spots, areas, alarms.
- AI upscaling (TISR) or any interpolation passed off as resolution.
- Other TOPDON models, Wi-Fi or Bluetooth cameras.
- Audio, in-app gallery (the phone gallery is used), sharing, cloud, accounts.
- Play Store publishing, iOS.

## Acceptance criteria

- [x] **AC1** Given the app is installed, when the Mini is plugged in and the CAMERA and USB access prompts are accepted, then a live thermal image shows within 3 s at the camera's frame rate (25 fps).
- [x] **AC2** Displayed temperatures equal `raw/64 - 50` of the frame: unit test against `app/src/debug/assets/replay-mode2.bin`, a byte copy of `../capture/raw-mode2-4x2321.bin` (frame 0 min/max/mean 22.70 / 23.69 / 23.04 °C ± 0.01).
- [x] **AC3** The scale bar shows the hot colour at the top and labels high / middle / low matching the current range; with "lock range" on, the labels and colours stop following the scene.
- [x] **AC4** Hot and cold markers sit on the 3x3-smoothed extremes and carry their temperature on a dark backdrop; a competing spot less than 0.3 °C warmer (colder) does not move the marker, a clear winner does (unit test).
- [x] **AC5** Palette, rotation (0/90/180/270), mirror and smoothing strength change the view immediately and survive an app restart; strength 0 shows real pixels, and the rendered image at 0.5 lies between 0 and 1 (unit test, as in `live_view.py --selftest`).
- [x] **AC6** The photo button writes `Pictures/TC001/TC001_<timestamp>.png` (identical to the screen view) and `Documents/TC001/TC001_<timestamp>.bin` (18,568 bytes, the raw frame of the same instant); the PNG shows up in the phone gallery; `scripts/live_view.py --file <bin>` can read the `.bin`.
- [x] **AC7** Record / stop writes a playable `Movies/TC001/TC001_<timestamp>.mp4` (H.264, no audio track, ~25 fps, duration matching the recording ± 1 s).
- [x] **AC8** Unplugging the Mini while streaming or recording does not crash: the app shows "camera disconnected", a recording in progress is finalised and playable, re-plugging resumes the view once the system USB access prompt is accepted.
- [x] **AC9** The release manifest declares exactly one `uses-permission`, `android.permission.CAMERA` (Android 9+ grants USB access to a UVC device only to apps holding it; asked at runtime only when the Mini is plugged in), and `uses-feature android.hardware.usb.host`; the APK has no network code path (no `INTERNET`).
- [x] **AC10** No runtime dependency besides the Android framework and the Kotlin standard library; release APK under 5 MB.
- [x] **AC11** The debug build replays a bundled capture until a usable camera is attached (camera errors show their message), so AC3-AC7 can be checked on an emulator.

## Phases

### Phase 1 — Frame decoding and rendering core
- Work: Gradle project (Kotlin, plain Android framework, minSdk 29, targetSdk 36), pure-Kotlin frame parser, palettes, scale bar, spot tracker with hysteresis, renderer to `Bitmap`; JVM unit tests against the recorded capture.
- **Covers**: AC2, AC4, AC10
- **Data model impact**: none
- **DoD**: `./gradlew test` green; `./gradlew assembleRelease` builds; `apkanalyzer`/`axml_perms` shows no extra dependency.

### Phase 2 — Live view UI with replay source
- Work: single activity: live view, scale bar, markers, centre readout, palette / lock / rotate / mirror / smoothing controls, persisted settings; debug-only replay source from a bundled capture.
- **Covers**: AC3, AC5, AC11
- **Data model impact**: none
- **DoD**: debug APK on emulator `Small_Phone_-_API_36` shows the replayed scene with scale and markers (screenshot); settings survive `am force-stop` + relaunch.

### Phase 3 — USB camera driver
- Work: USB device filter `2bdf:0102` (app opens on plug), CAMERA runtime permission then USB permission request, UVC probe/commit format 1 / frame 5 (`4x2321`), bulk reads on EP 0x81 with UVC payload-header stripping (FID/EOF framing, short-frame rejection), disconnect handling.
- **Covers**: AC1, AC8 (view part)
- **Data model impact**: none
- **DoD**: with the Mini plugged in (emulator with USB passthrough, see `../NOTES.md` §10, or the owner's phone): live image, ~25 fps counter in debug overlay, unplug/replug without crash (physical unplug, owner-run).

### Phase 4 — Photo and video capture
- Work: photo (PNG + `.bin`) and video (MediaCodec H.264 + MediaMuxer) through MediaStore, no storage permission; recording finalised on stop, when the app goes to the background (`onStop`) or on unplug.
- **Covers**: AC6, AC7, AC8 (recording part), AC9
- **Data model impact**: none
- **DoD**: on emulator with replay: photo PNG in `Pictures/TC001` and `.bin` in `Documents/TC001`, `.bin` read by `live_view.py --file`; MP4 in `Movies/TC001` probed by `ffprobe` (h264, no audio, ~25 fps); release manifest's only `uses-permission` is `android.permission.CAMERA`.

## Data model impact (summary)

None. Files only (MediaStore), settings in `SharedPreferences`.

## Open questions

- (none)

## Decisions

- Plain Android framework + Kotlin, no AndroidX / Compose — smallest APK, zero third-party code; overturnable if UI work gets painful.
- Bulk UVC handled in Kotlin with `UsbDeviceConnection.controlTransfer` / `bulkTransfer` — the Mini streams on a bulk endpoint, so no NDK / libusb / libuvc is needed.
- Photo `.bin` = the untouched 18,568-byte mode-2 frame — directly replayable by the Linux tools.
- Spot hysteresis 0.3 °C, label EMA alpha 0.3 — same values as the Linux viewer, tuned on live captures.
- Release signed with a local key in the repo-ignored `keys/` folder; sideload only.
- Code lives in `topdon/tc001-app/`, local git repo, no remote.
- One permission, `android.permission.CAMERA`: Android 9+ (targetSdk >= 28) refuses USB access to UVC devices without it (`UsbUserPermissionManager: Camera permission required for USB video class devices`); targetSdk 27 was rejected (compatibility mode, install warning).
