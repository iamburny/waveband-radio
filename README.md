# Waveband Radio

An Android radio player, currently DAB/DAB+ via USB tuner sticks: Kotlin, single `app` module, package
`com.iamburny.waveband`.

Built for a landscape car head unit (1280×720, 160 dpi, steering-wheel media buttons). The UI is
Jetpack Compose with a nav rail and five screens; stations, favourites, presets, song history and
settings are saved on the device. DAB decoding sits behind the `DabBackend` interface, which has two
implementations:

- `OmriDabBackend` drives the USB stick through IRT's [omri-usb](https://github.com/hradio/omri-usb)
  DAB stack (vendored in `third_party/omri-usb`, built as `libomri.so`). It scans Band III, lists the
  audio services, and plays them. DAB+ (HE-AAC) is decoded by the platform's `MediaCodec`, and classic
  DAB (MPEG Layer II) by libmpg123, because Android has no Layer II decoder. The real-hardware path
  has not been tested yet; see [Testing on real hardware](#testing-on-real-hardware).
- `StubDabBackend` is a demo tuner for debug builds (**Demo tuner** button). A scan "finds" 14
  fictional stations on three ensembles; playing one gives a demo chord (so the visualisers move)
  with realistic metadata: bitrate, stereo/mono, programme type, a changing dynamic label with
  artist/title tags on its songs, generated slideshow images on some stations, a varying signal and
  error rate, and a traffic announcement on the "Demo National" ensemble a minute
  after you tune to it (then every three minutes).

## Screens

- **Playing**: DAB/DAB+ badge, ensemble and block, bitrate and stereo/mono, signal bars; the DAB
  slideshow image (when the station sends one and **Slideshow images** is on) or the station's
  monogram; preset number and programme type, station name, dynamic label (DLS), favourite toggle.
  Tapping the artwork steps through the visualisers and back, and tapping the signal bars opens the
  signal details (see [DAB extras](#dab-extras)).
  Transport: previous/next saved station (in the station list's current order, wrapping round),
  seek down/up to the first station of the previous/next ensemble, play/pause. Below, presets 1–6:
  tap to play, tap an empty slot or long-press any slot to save the current station. Without a
  tuner this screen offers **Connect tuner** (and **Demo tuner** in debug builds); while scanning
  it shows the scan.
  On head units with their own FM/AM tuner (see Supported hardware), a DAB · FM · AM switch
  replaces the DAB badge. FM and AM show the frequency, the RDS station name, stereo/RDS/traffic
  flags, fine tuning (50 kHz FM, 9 kHz AM) and seek down/up, play/pause, and the tuner's own six
  presets for the band: tap to listen, long-press to save the current frequency. Station names
  heard over RDS are remembered per frequency and shown on presets.
- **Stations**: all saved stations grouped by ensemble, or A–Z. Filters: Favourites, Music, Talk
  (from the programme type; stations without one appear only under All). Tap to play, heart to
  favourite.
- **Presets**: 12 presets, each a DAB station or (on head units with their own tuner) an FM/AM
  frequency. Tap plays, switching source if needed; long-press saves what's playing, an empty tile
  saves on tap. **Edit** shows a clear button on each preset; tapping a tile then saves the current station
  there.
- **History**: songs heard on DAB stations that send the artist and title, newest first and
  grouped by day (Today, Yesterday, then the date), with the time, station and logo. Tap a song to
  search for it on YouTube (the YouTube app if installed, otherwise the browser). **Clear history**
  asks for a second tap.
- **Settings**: full Band III scan (5A–13F) with progress and a cancel button; traffic
  announcements; slideshow images; station logos; display mode (Auto follows the system's night mode, which head
  units usually switch with the headlights; Day; Night); accent colour; region (United Kingdom,
  Band III only); tuner status with connect/disconnect; open-source licences.

The Stations screen has a search field that matches station names, ensembles, programme types and
blocks, ignoring case, accents, spaces and punctuation.

Station logos come from the broadcasters through RadioDNS when the device is online (see
Behaviour). Stations without one get a generated monogram (initials on a colour derived from the
service ID).

## DAB extras

These work on DAB only, because FM/AM audio and metadata go through the head unit's MCU rather than
Android; on FM/AM they are hidden.

- **Visualisers**: tapping the artwork steps through Artwork → Spectrum bars → Oscilloscope → VU
  meters → Radial spectrum → Waterfall → Artwork, showing the mode's name briefly. The choice is
  remembered. The views follow the audio leaving the speaker now (compensating for the audio
  buffer), so they keep time with what you hear, and they stop when paused or off screen.
  - *Spectrum bars*: 32 log-spaced bands from 40 Hz to 16 kHz with peak caps.
  - *Oscilloscope*: the waveform, triggered so a steady note stands still.
  - *VU meters*: left and right needles with 300 ms ballistics (0 VU is 14 dB below a full-scale
    sine) and a PEAK light at −1 dBFS.
  - *Radial spectrum*: bars round the station logo, which pulses with the bass.
  - *Waterfall*: a scrolling spectrogram, newest at the top, in the accent colour.
- **Song history**: built from DL Plus artist and title tags. A song is logged once it has played
  for 10 seconds on a playing station outside a traffic announcement, unless it is the song last
  logged on that station. The newest 1000 are kept.
- **Signal details**: a quality gauge (0–100, from the tuner's channel error rate) with a verdict,
  the error rate, the share of audio lost (dropouts), the ensemble and block, and a graph of the
  last two minutes, which restarts when you retune. Useful for placing the aerial.

## Behaviour

- **Persistence**: stations, favourites and presets are kept in a Room database
  (`stations.db`), per tuner origin (USB stick or demo tuner), so demo stations never mix with real
  ones. The song history is kept in the same database. Settings and the last station are kept in DataStore (`settings` preferences).
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
  station name, the dynamic label, and the slideshow image, logo or monogram as artwork.
- **Station logos**: the only network use. With the setting on (the default), the app looks up
  each USB-tuner station on RadioDNS (`0.<SId>.<EId>.<GCC>.dab.radiodns.org`, CNAME then the
  `_radiospi._tcp` SRV record, or the older `_radioepg._tcp` record that Global still uses alone),
  downloads the broadcaster's `SI.xml` once per refresh, and stores
  one logo per station (scaled to 320 px) in `files/logos`. Logos are refreshed after 25 days, as
  broadcasters ask for at least monthly. Stations the broadcaster has no logo for are retried after
  7 days; when a broadcaster's server fails, the stored logo is kept and the station is retried
  after 6 hours. Lookups run after the station list settles, when a connection comes up, and
  hourly; they stop early when RadioDNS itself is unreachable. Ensembles that do not signal their ECC are assumed to be UK (E1) when the
  service ID has the UK country nibble.

## Layout

```
app/src/main/java/com/iamburny/waveband/
  DabTunerApp.kt            Application; owns the RadioController, database and settings
  RadioController.kt        tuner lifecycle, USB connection, observable RadioState, stations/presets of the
                            active origin, scan bookkeeping, traffic announcement switching
  StationOrder.kt           station orderings, next/previous station and ensemble
  Monogram.kt               generated station logos (initials + colour)
  StationSearch.kt          station list search matching
  logos/                    RadioDNS logo lookup (DNS, SI.xml parsing) and the on-device logo cache
  backend/DabBackend.kt     backend contract (open from a UsbDeviceConnection/fd, scan, play, PCM + metadata callbacks)
  backend/ProgrammeType.kt  programme type names and the Music/Talk split
  backend/StubDabBackend.kt demo tuner
  data/                     Room database (stations, presets) and DataStore settings
  backend/omri/             omri-usb backend: JNI bindings, AAC (MediaCodec) and MP2 (mpg123) decoding
  usb/SupportedTuners.kt    VID/PID list (mirror of res/xml/usb_device_filter.xml)
  usb/UsbTunerManager.kt    device lookup, permission requests, detach events
  playback/DabPlaybackService.kt  Media3 MediaSessionService (foreground, mediaPlayback)
  playback/DabPlayer.kt     SimpleBasePlayer mapping session commands (incl. skip next/previous) to the RadioController
  playback/AudioTrackSink.kt PCM -> AudioTrack, feeding SpectrumTap while a visualiser is showing
  playback/SpectrumTap.kt   recent left/right audio, aligned to what is playing now
  playback/SpectrumAnalyser.kt  FFT and log-spaced bands
  playback/Visualisation.kt band smoothing, oscilloscope trigger and VU meter maths
  fm/                       the head unit's FM/AM tuner (vendor service binder, audio focus)
  ui/                       Compose UI: MainActivity, app shell and nav rail, the five screens, signal
                            details (SignalScreen.kt), visualisers (Visualisers.kt), theme
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

FM/AM: Choiceway head units (for example the GT6-CAR) whose vendor service
`com.szchoiceway.eventcenter` controls a built-in tuner. The app detects it and validates the
service before offering FM/AM; elsewhere the switch is hidden. The tuner's sound is switched by the
head unit, so the app holds audio focus while it plays: starting FM pauses DAB and other players,
and playing DAB or another app switches FM off. The head unit's own radio app can take over the
tuner without switching it off.

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

Then check the DAB extras: the History tab fills on a station that sends DL Plus tags (many Global
and Bauer stations do), the signal details' error rate rises when you move or disconnect the
aerial, the visualisers keep time with the beat, and the VU needles sit around 0 on normal
programme.

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
