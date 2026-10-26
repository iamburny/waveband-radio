# Changelog

All notable changes to Waveband Radio are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.4] - 2026-10-26

### Added

- FM and AM from the head unit's own tuner, on Choiceway head units that have one (such as the
  GT6). A DAB · FM · AM switch on the Playing screen moves between them. The FM/AM view shows the
  frequency and RDS station name, with seek, fine tuning and a Local/DX sensitivity switch, and
  the last source is remembered.
- Presets can hold FM and AM frequencies as well as DAB stations, so one tap moves between sources.
  The quick presets on the Playing screen show the first six for DAB, FM or AM, whichever is
  playing, and steering-wheel next/previous step through them.
- FM station names are remembered from RDS and shown on presets.
- Presets can be rearranged by dragging one onto another, on the Presets page and on the quick
  presets.
- A station can only be saved to one preset: saving it to another moves it there.

### Fixed

- The radio now takes audio focus while playing. It pauses when another app starts playing, pauses
  during calls and resumes afterwards, lowers its volume for navigation prompts, and takes the
  speakers back from the head unit's own FM radio when you press play.

## [0.1.3] - 2026-10-25

### Fixed

- Global stations (Capital, Heart, Smooth and others) now get their logos. Stations whose logos
  are only available to licensed radio makers keep their generated tile and are retried weekly.

## [0.1.2] - 2026-10-25

### Added

- Station logos from the broadcasters, through RadioDNS, in the station list, presets, Now Playing
  and the media notification. They download when the head unit is online, refresh monthly, and can
  be switched off under Settings → Station logos. Stations without a logo keep their generated tile.
- Search on the Stations screen, by station name, multiplex, programme type or block.

### Changed

- The app now uses the internet, only to look up and download station logos.

## [0.1.1] - 2026-10-25

### Fixed

- A full scan found no stations on a real USB tuner: it moved on from channels before reading
  their station lists.
- The app crashed while scanning some multiplexes, including BBC National DAB.
- BBC National DAB stations were missing after a scan.
- On head units with a high-density screen (such as 240 dpi), screens were oversized and Settings
  was cut off. The layout now scales to fit any screen.

### Changed

- Saved presets on the Now Playing screen show the station name on up to two lines instead of the
  preset number.

## [0.1.0] - 2026-10-25

First test release, published to the Google Play internal testing track.

### Added

- DAB and DAB+ reception through a USB tuner stick (USB ID 16C0:05DC, Raontech-based), using the
  open-source omri-usb DAB stack. DAB+ audio is decoded by the platform AAC decoder and classic
  DAB (MPEG Layer II) audio by libmpg123.
- Full scan of Band III, with a station list ordered by ensemble or A–Z, favourites, and All,
  Favourites, Music and Talk filters.
- 12 presets.
- Now Playing screen with the station's dynamic label (DLS) text, slideshow images, programme
  type, bitrate and signal strength. Stations without a slideshow get a generated monogram tile.
- Traffic announcements on the current ensemble.
- Media session support: steering-wheel and media buttons skip between stations, and the
  notification shows the station, now-playing text and artwork.
- Landscape layout designed for 1280×720 car head units, with day, night and automatic display
  modes and four accent colours.
- Stations, favourites, presets, settings and the last station are saved on the device; the app
  reopens on the last station.
- In-app open-source licences screen.

### Known limitations

- The stick has a single tuner, so a full scan pauses playback and only announcements on the
  current ensemble can be followed.
- Only one USB tuner type is supported so far.
- Requires Android 7.0 (API 24) or later.

[Unreleased]: https://github.com/iamburny/waveband-radio/compare/v0.1.4...HEAD
[0.1.4]: https://github.com/iamburny/waveband-radio/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/iamburny/waveband-radio/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/iamburny/waveband-radio/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/iamburny/waveband-radio/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/iamburny/waveband-radio/releases/tag/v0.1.0
