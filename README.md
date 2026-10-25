# Waveband Radio

An Android radio player, currently DAB/DAB+ via USB tuner sticks: Kotlin, single `app` module, package
`com.iamburny.waveband`.

Built for a landscape car head unit (1280×720, 160 dpi, steering-wheel media buttons). The UI is
Jetpack Compose with a nav rail and four screens; stations, favourites, presets and settings are
saved on the device. DAB decoding sits behind the `DabBackend` interface, which has two
implementations:

- `OmriDabBackend` drives the USB stick through IRT's [omri-usb](https://github.com/hradio/omri-usb)
  DAB stack (vendored in `third_party/omri-usb`, built as `libomri.so`). It scans Band III, lists the
  audio services, and plays them. DAB+ (HE-AAC) is decoded by the platform's `MediaCodec`, and classic
  DAB (MPEG Layer II) by libmpg123, because Android has no Layer II decoder. The real-hardware path
  has not been tested yet; see [Testing on real hardware](#testing-on-real-hardware).
- `StubDabBackend` is a demo tuner for debug builds (**Demo tuner** button). A scan "finds" 14
  fictional stations on three ensembles; playing one gives silence with realistic metadata: bitrate,
  stereo/mono, programme type, a changing dynamic label, generated slideshow images on some
  stations, a varying signal, and a traffic announcement on the "Demo National" ensemble a minute
  after you tune to it (then every three minutes).

## Screens

- **Playing**: DAB/DAB+ badge, ensemble and block, bitrate and stereo/mono, signal bars; the DAB
  slideshow image (when the station sends one and **Slideshow images** is on) or the station's
  monogram; preset number and programme type, station name, dynamic label (DLS), favourite toggle.
  Transport: previous/next saved station (in the station list's current order, wrapping round),
  seek down/up to the first station of the previous/next ensemble, play/pause. Below, presets 1–6:
  tap to play, tap an empty slot or long-press any slot to save the current station. Without a
  tuner this screen offers **Connect tuner** (and **Demo tuner** in debug builds); while scanning
  it shows the scan.
- **Stations**: all saved stations grouped by ensemble, or A–Z. Filters: Favourites, Music, Talk
  (from the programme type; stations without one appear only under All). Tap to play, heart to
  favourite.
- **Presets**: 12 presets. Tap plays, long-press saves the current station, an empty tile saves on
  tap. **Edit** shows a clear button on each preset; tapping a tile then saves the current station
  there.
- **Settings**: full Band III scan (5A–13F) with progress and a cancel button; traffic
  announcements; slideshow images; display mode (Auto follows the system's night mode, which head
  units usually switch with the headlights; Day; Night); accent colour; region (United Kingdom,
  Band III only); tuner status with connect/disconnect; open-source licences.

Station logos are generated monograms (initials on a colour derived from the service ID).

## Behaviour

- **Persistence**: stations, favourites and presets are kept in a Room database
  (`stations.db`), per tuner origin (USB stick or demo tuner), so demo stations never mix with real
  ones. Settings and the last station are kept in DataStore (`settings` preferences).
- **Start-up**: if a supported stick is attached, the app connects to it (debug builds reopen the
  demo tuner if it was used last). When the tuner is ready, the app scans automatically if there
  are no saved stations, and otherwise resumes the last station.
- **Scanning**: the stick has one tuner, so audio stops during a scan and the station that was
  playing resumes afterwards. A completed scan drops stations that were not found again, except
  favourites and presets; a cancelled scan only adds.
- **Traffic announcements**: with the setting on, when the ensemble signals a road-traffic
  announcement (FIG 0/19) for a cluster the current station belongs to (FIG 0/18), the app
  switches to the announcement's subchannel and back when it ends. This works only within the
  current ensemble; announcements on other ensembles are not followed.
- **Media session**: steering-wheel/media-button next and previous step through the saved
  stations. The session (notification, lock screen, Bluetooth/head-unit displays) shows the
  station name, the dynamic label, and the slideshow image or the station's monogram as artwork.

## Layout

```
app/src/main/java/com/iamburny/waveband/
  DabTunerApp.kt            Application; owns the RadioController, database and settings
  RadioController.kt        tuner lifecycle, USB connection, observable RadioState, stations/presets of the
                            active origin, scan bookkeeping, traffic announcement switching
  StationOrder.kt           station orderings, next/previous station and ensemble
  Monogram.kt               generated station logos (initials + colour)
  backend/DabBackend.kt     backend contract (open from a UsbDeviceConnection/fd, scan, play, PCM + metadata callbacks)
  backend/ProgrammeType.kt  programme type names and the Music/Talk split
  backend/StubDabBackend.kt demo tuner
  data/                     Room database (stations, presets) and DataStore settings
  backend/omri/             omri-usb backend: JNI bindings, AAC (MediaCodec) and MP2 (mpg123) decoding
  usb/SupportedTuners.kt    VID/PID list (mirror of res/xml/usb_device_filter.xml)
  usb/UsbTunerManager.kt    device lookup, permission requests, detach events
  playback/DabPlaybackService.kt  Media3 MediaSessionService (foreground, mediaPlayback)
  playback/DabPlayer.kt     SimpleBasePlayer mapping session commands (incl. skip next/previous) to the RadioController
  playback/AudioTrackSink.kt PCM -> AudioTrack
  ui/                       Compose UI: MainActivity, app shell and nav rail, the four screens, theme
                            (Manrope, night/day colours)
app/src/main/cpp/           JNI bridge (libdabtuner_jni.so) to the omri-usb C API and libmpg123
third_party/omri-usb/       omri-usb native sources (LGPL-2.1, modified; see MODIFICATIONS.md)
third_party/mpg123/         mpg123 1.33.7 release (LGPL-2.1, unmodified)
```

## Build

Requirements: JDK 17+ and an Android SDK with platform 36, NDK `27.1.12297006` and CMake `3.22.1`
(both from the SDK Manager). Set `sdk.dir` in `local.properties`, or `ANDROID_HOME`. Native code is
built for arm64-v8a, armeabi-v7a and x86_64.

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # decoder checks on a device or emulator
```

Debug builds show a **Demo tuner** button. It opens the backend without hardware, so you can
try the UI, the media notification and the session without a stick. A matching emulator is a
1280×720, 160 dpi, API 33 landscape AVD. `adb shell cmd uimode night yes|no` exercises the
**Auto** display mode, and `adb shell input keyevent KEYCODE_MEDIA_NEXT` the steering-wheel
buttons.

## Supported hardware

`16C0:05DC` (a USB DAB+ receiver with a Raontech demodulator behind a USB bridge). The ID is a
shared one, so on open the backend sends the bridge's echo and version commands and refuses the
device if it does not answer.

## Testing on real hardware

The emulator cannot see USB devices, so the tuner path needs a physical device:

1. Use an Android phone, tablet or head unit with USB host (OTG) support. Connect the stick, through
   an OTG adapter if needed, and enable USB debugging. If the stick occupies the only port, use
   `adb tcpip 5555` then `adb connect <ip>:5555`.
2. Install a debug build: `./gradlew installDebug`. Debug builds route omri-usb's own logging to logcat.
3. Start logging before plugging in the stick:
   `adb logcat -s DabTunerNative OmriDabBackend OmriNative omri AudioPipeline AndroidRuntime`
4. Plug in the stick and accept the USB permission prompt (or open the app and tap **Connect tuner**).
   With no saved stations the app scans by itself; otherwise start one from **Settings**. Then
   pick a station.

What to look for in logcat:

- `Native DAB backend loaded: omri-usb ... mpg123 ...`: the native libraries loaded.
- `omri_tuner_open(fd=..): ok (echo=... version=... write-ack=yes|no)`: the stick answered the probe.
  `failed (no reply to echo/version commands)` means that the device is not this tuner, or that the
  interface could not be used.
- `[RaonUsbTuner]  PowerUp okay!` and then the status line `ready` in the app: initialisation finished.
  `PowerUp failed!` or `Too many USB I/O failures` means that register access is not working. Report the
  probe line, especially `write-ack`.
- During a scan: `LockState at: <Hz> : 3` on channels that carry a multiplex, then
  `Ensemble collect finished` and services appearing in the list.
- When playing: `Starting SrvComp`, then the status changes to `playing`. If a DAB+ station is
  silent, check for `AudioPipeline` decoder errors. For an MP2 station, look for `mpg123` warnings.

## Third-party licences

omri-usb (with libfec) and mpg123 are LGPL-2.1 libraries. They are kept as separate shared libraries
(`libomri.so`, `libfec.so`, `libmpg123.so`) and their full source is in `third_party/`. Changes to
omri-usb are listed in `third_party/omri-usb/MODIFICATIONS.md`. If you distribute the app, provide
that source with it. The Manrope typeface (`res/font`, SIL Open Font License 1.1) is bundled as
static instances generated from the Google Fonts variable font. The app shows all notices under
**Settings → Open-source licences** (`res/raw/third_party_notices.txt`). No GPL code is included.

## Reverse-engineering notes

The behavioural reference for this project lives in `reverse-engineering/`, which is gitignored and
never committed. Start with `reverse-engineering/README.md`. It covers the USB IDs and open path, the
JNI/native tuner protocol, and the reference app's use of location (none is needed here). Code in
this repository is written from scratch; do not copy anything from that folder.
