# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Waveband Radio: a DAB/DAB+ radio player for USB tuner sticks, targeting an aftermarket Android car head unit. Kotlin, single `app` module, package `com.iamburny.waveband`, native code via NDK/CMake.

## Commands

Windows host: use `.\gradlew.bat` from PowerShell (or `./gradlew` from Git Bash).

```
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.iamburny.waveband.backend.omri.AudioSpecificConfigTest"
./gradlew connectedDebugAndroidTest     # native decoder tests; needs a running device/emulator
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.iamburny.waveband.backend.omri.NativeDecodersTest
./gradlew installDebug
./gradlew bundleRelease                 # app/build/outputs/bundle/release/app-release.aab for Play
```

Debug builds use the application ID `com.iamburny.waveband.debug` (app name "Waveband Debug"), so they install alongside the Play build with their own stations and settings. Launch them with `adb shell am start -n com.iamburny.waveband.debug/com.iamburny.waveband.ui.MainActivity`.

Release signing reads `keystore.properties` in the project root (gitignored; template in `keystore.properties.example`) or the `WAVEBAND_KEYSTORE`, `WAVEBAND_KEYSTORE_PASSWORD`, `WAVEBAND_KEY_ALIAS` and `WAVEBAND_KEY_PASSWORD` environment variables. Without them, release builds are unsigned. The upload key lives outside the repo; never commit a keystore or its passwords. Release builds are shrunk by R8, so JNI-facing classes rely on the keep rules in `app/proguard-rules.pro`. Bump `versionCode` for every Play upload.

Releasing: bump `versionCode` and `versionName` in `app/build.gradle.kts`, move the `CHANGELOG.md` Unreleased entries under a dated version heading and add its compare link, build with `testDebugUnitTest assembleRelease bundleRelease`, commit as "… and release X.Y.Z", and tag `vX.Y.Z`. Play's "What's new" text is limited to 500 characters.

Toolchain is pinned in `app/build.gradle.kts`: NDK `27.1.12297006`, CMake `3.22.1`, ABIs arm64-v8a / armeabi-v7a / x86_64 (x86_64 is only for the emulator). Native libraries are linked with 16 KB page alignment.

SDK command-line tools are at `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin`. `sdkmanager.bat` splits package names on `;`, so pass packages via `--package_file=<file>`.

## Target device and emulator

- Real target: "H6" head unit, Android 13 (API 33), 1280×720 landscape at 240 dpi, Snapdragon 665 (arm64), steering-wheel controls arrive as media buttons, except while the FM/AM tuner is ours, when the vendor service delivers them to its mode callback instead (see `fm/EventCenterRadio.kt`). It is regular Android, not Android Automotive OS.
- Matching emulator: AVD `HeadUnit_H6_API33` (160 dpi; `adb shell wm density 240` reproduces the real unit's density). Start it with `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd HeadUnit_H6_API33`.
- `adb devices` often lists a stale offline `emulator-5562`; always pass `-s <serial>`.
- In Git Bash, set `MSYS_NO_PATHCONV=1` before `adb shell` commands with device paths, or they get rewritten to Windows paths.
- The Windows emulator cannot pass USB devices through. Anything touching the real stick (probe, register I/O, scan, live audio, signal/bitrate/PTy from omri) can only be verified on physical hardware; on the emulator use the debug-only **Demo tuner** (`StubDabBackend`). README.md has the real-hardware logcat checklist.

## Architecture

**Layers.** UI and the Media3 playback service observe `RadioController` (its `StateFlow<RadioState>`). `RadioController` owns the USB connection (via `usb/UsbTunerManager`) and the active `DabBackend`. Backends turn a `TunerSource` (a USB connection's file descriptor, or `Simulated`) into scan results, PCM and metadata through `DabBackend.Listener`. Only the backend layer knows about DAB internals; everything above talks to the `DabBackend` interface and its data classes in `backend/DabBackend.kt`.

**Threading contract.**
- `RadioController` public methods run on the main thread.
- All backend calls go through its single `DabBackend` executor thread. They can block, and they must stay ordered (close before reopen).
- Listener callbacks arrive on backend-owned or native threads. `onPcm` runs on the audio path and must return quickly.
- `OmriDabBackend`'s `AudioPipeline` decodes on its own thread and is paced by the blocking `AudioTrack` write.

**Native stack.** The layering exists for LGPL relinking, so keep it:
- `app/src/main/cpp` builds `libdabtuner_jni.so`, the app's JNI bridge.
- That bridge links `libomri.so` (from `third_party/omri-usb`) and `libmpg123.so` (from `third_party/mpg123`) as separate shared libraries.
- The bridge reaches omri-usb only through the C API in `third_party/omri-usb/src/platformspecific/android/omri_api.h`, and `libomri.so` exports only `omri_*` symbols.
- Do not statically link or merge these libraries, and do not call omri internals from the JNI bridge.
- USB I/O is done natively with `USBDEVFS_BULK` on the fd from `UsbDeviceConnection`. The stick is 16C0:05DC, a Raontech demodulator behind a USB bridge.
- Because 16C0:05DC is a shared VOTI ID, `omri_tuner_open` probes with echo/version commands before claiming it.
- Keep `usb/SupportedTuners.kt` and `res/xml/usb_device_filter.xml` in sync (the XML uses decimal IDs).

**Audio decoding.**
- DAB+ (HE-AAC) uses platform `MediaCodec` with a hand-built AudioSpecificConfig (960-sample frames, SBR/PS signalled explicitly). Unit-tested in `AudioSpecificConfigTest`.
- Classic DAB (MPEG Layer II) uses libmpg123, because Android has no Layer II decoder; the platform `audio/mpeg` decoder only handles Layer III.

**Single tuner.** The stick has one tuner, so a full scan interrupts playback, and only announcements on the current ensemble can be followed.

**Persistence.**
- Room (`data/StationDatabase.kt`) stores stations, the 12 presets, FM names and the song history. DataStore (`data/SettingsRepository.kt`) stores settings and the last station.
- Stations, presets and FM names are scoped by `TunerOrigin` (`USB` or `DEMO`), so demo stations never mix with real ones. The song history (`song_plays`) is not scoped. The station primary key is `(origin, key)`, where `key` is `DabServiceInfo.key` (service ID @ frequency).
- The Room schema is exported to `app/schemas/`. Bump the version and add a migration when you change entities.

**UI.**
- Jetpack Compose (Material3) in a single activity, `ui/MainActivity`. `ui/DabApp.kt` is the shell with the nav rail, and there is one file per screen.
- The look is copied exactly from a design canvas at 1280×720 (1 px = 1 dp at 160 dpi). `ui/DesignScale.kt` scales the whole tree uniformly so the 1280×664 dp content area (canvas minus the reference status and navigation bars) fits the window at any density; write layouts in design dp and never read the system density directly. Colours live in `ui/theme/Theme.kt` as night and day token sets with four accents. Don't substitute Material defaults.
- Display mode Auto follows the system night mode; the activity handles `uiMode` changes itself.
- The Manrope font is bundled in `res/font` (OFL).
- Station tiles (`StationTile` in `ui/Components.kt`) show the broadcaster's logo from `logos/StationLogos` (provided through `LocalStationLogos`) and fall back to generated monograms (`Monogram.kt`). Now Playing prefers the slideshow image over the logo.

**Station logos.** The app's only network access. `DabTunerApp` drives `StationLogos.refresh` from the USB origin's stations, the setting and connectivity; `RadioDnsClient` resolves the RadioDNS CNAME and `_radiospi._tcp` SRV (raw DNS via `DnsResolver` on API 29+, UDP to the network's DNS server below), and `ServiceInformation` parses `SI.xml`. Logos are keyed by `RadioDns.logoKey` (country code + SId, no ensemble). Cleartext HTTP is allowed (`res/xml/network_security_config.xml`) because many broadcasters' SPI servers are HTTP-only. Keep the privacy policy on bluegreenstudios.co.uk in step with any network use.

**DAB extras.** These are DAB-only, because FM/AM audio and metadata never pass through Android.
- **Visualisers:** Artwork, spectrum bars, oscilloscope, VU meters, radial spectrum and waterfall, in the `Visualiser` enum (`data/SettingsRepository.kt`, persisted as `AppSettings.visualiser`); tapping the artwork in `ui/NowPlayingScreen.kt` steps through them. `AudioTrackSink` copies PCM (left and right) into `playback/SpectrumTap` only while a view in `ui/Visualisers.kt` is attached, under the sink's lock so nothing is fed after a pause or flush. Views read the frames leaving the speaker now (frames written minus the playback head, clamped to the buffer size), not the newest decoded ones. `playback/SpectrumAnalyser` turns them into bands; the pure, unit-tested maths for the other views (`BandSmoother`, `ScopeTrigger`, `VuMeter`) is in `playback/Visualisation.kt`. `rememberAudioFrames` attaches the tap only while the screen is started and stops redrawing once the view has settled, so keep new views on it.
- **Song history:** omri-usb's DL Plus tags reach Kotlin through the `dl_plus` callback in `omri_api.h` and `OmriNative.Callbacks.onDynamicLabelPlus`, becoming `ServiceMetadata.item` (`PlayingItem`). `data/SongHistory` logs a song into Room's `song_plays` table after it has played for 10 s, skipping repeats of the station's last song, and keeps the newest 1000. The History tab (`ui/HistoryScreen.kt`) shows them and searches YouTube on tap: the YouTube app first, then the browser, launching directly and catching `ActivityNotFoundException`, so no `<queries>` entry is needed.
- **Signal details:** tapping the signal bars opens `ui/SignalScreen`, which shows `SignalInfo.errorRate` (the Raontech channel error rate, passed out through the `reception` callback in `omri_api.h`) and `audioFrameLoss` (`backend/omri/FrameLossMeter`, counting decoded frames against the expected rate), plus `RadioController.signalHistory` (reset on retune).
- The demo tuner plays a stereo chord at roughly broadcast loudness and tags its song labels like DL Plus, so all of this works on the emulator. Real-hardware checks still outstanding: VU calibration against broadcasts, visualiser sync with the H6's AudioTrack buffer, DL Plus stations and the error rate responding to the aerial.

**Media session.** Skip next/previous (steering-wheel buttons) steps through the saved stations in the current sort order (`StationOrder.kt`). The session metadata carries the station name, the DLS text and artwork.

**Head unit FM/AM (`fm/`).** Choiceway head units drive their own FM/AM tuner through the vendor service `com.szchoiceway.eventcenter/.EventService`, which relays commands to the MCU; the tuner's audio is analogue and switched by the MCU, not PCM through Android. `HeadUnitRadio` is the interface, and `EventCenterRadio` implements it.
- It reaches the service's private binder interface by raw `IBinder.transact`, using only the radio transaction codes in its `Txn` table. Never add a generated stub for the whole interface: it would expose the firmware upgrade, factory reset and CAN calls.
- It validates the descriptor, band and frequency before sending any command.
- Status comes from polling the cached getters plus the service's radio-info broadcast. Never call `setRadioCallback`: it is a single slot owned by the head unit's own radio app.
- Taking audio focus in `playback/DabPlayer` is what makes the stock FM radio hand the speakers back.
- The tuner's audio bypasses Android, so `EventCenterRadio` holds media audio focus while it has the speakers. On losing focus for good it only switches the tuner off if Android media playback starts within a few seconds: the stock radio app takes focus without playing anything, to drive the same tuner.
- `DabTunerApp.headUnitRadio` connects once for the life of the process (debug builds without the vendor service get `FakeHeadUnitRadio`). `RadioController.source` (persisted) says whether DAB or FM/AM is the source; `DabPlayer` mirrors the tuner in the media session while FM/AM is the source (play/pause switch it on/off; skip, and the steering-wheel keys the tuner reports as `skipRequests`, step through the saved presets of the band playing — `QuickPresets`) and switches the source to FM whenever the tuner becomes ours.
- The UI is `ui/FmNowPlaying.kt`; presets are shared with DAB (`ui/PresetItems.kt`, `data/QuickPresets.kt`). The behavioural notes are in `reverse-engineering/e-headunit-fm-radio/`.

## Licensing rules

- omri-usb (with libfec) and mpg123 are LGPL-2.1. Every change under `third_party/omri-usb/src` must be recorded in `third_party/omri-usb/MODIFICATIONS.md`; mpg123 is vendored unmodified.
- New third-party code or assets (fonts included) need their licence added to `res/raw/third_party_notices.txt`, which is shown in-app under "Open-source licences".
- No GPL components: for example faad2, welle.io or dab-cmdline code.

## Reverse-engineering material

`reverse-engineering/` (gitignored, never commit it) holds notes and decompiled code from the closed-source DAB-Z app, which used an older build of the same omri-usb stack. Use it only as a behavioural spec, such as the USB protocol, register sequences and the JNI surface. Never copy decompiled code, DAB-Z resources or its `.so` files into the project. Start at `reverse-engineering/README.md`.
