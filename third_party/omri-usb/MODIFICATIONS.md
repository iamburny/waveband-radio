# omri-usb: source and modifications

- Upstream: https://github.com/hradio/omri-usb
- Commit: `12f4b8ede51509226da102952646b97cb3c5752f` ("Initial release", 2020-02-07)
- Licence: GNU LGPL 2.1 or later, see [LICENSE](LICENSE). The bundled Reed-Solomon code in
  `src/thirdparty/fec` is Phil Karn's libfec (LGPL 2.1, see `src/thirdparty/fec/LICENSE`).
- Vendored: the native tree `omriusb/src/main/cpp` as `src/`, plus the upstream README as
  `README.upstream.md`. The upstream Java/Android layer is not used and not included.

The library is built as `libomri.so` (and `libfec.so`) by [CMakeLists.txt](CMakeLists.txt) and is
loaded dynamically by the app. Applications reach it only through the C API in
`src/platformspecific/android/omri_api.h`, so a rebuilt or modified `libomri.so` can replace the
shipped one.

To see the exact changes, diff `src/` against `omriusb/src/main/cpp` at the commit above.

## Removed

- `platformspecific/android/native-lib.cpp`, `jusbdevice.*`, `jtunerusbdevice.*`, `jdabservice.*`:
  the JNI layer, which was tied to the upstream `org.omri.*` Java classes.
- `platformspecific/android/ediinput.*`: EDI stream input (not used).
- `platformspecific/android/androidlogbuf.h`: replaced by a thread-safe logcat stream buffer in `omri_api.cpp`.

## Added

- `platformspecific/android/usbtunerdevice.{h,cpp}`: USB access through a usbfs file descriptor
  with `USBDEVFS_BULK`, replacing the JNI `UsbDeviceConnection.bulkTransfer` path, plus an abstract
  notification interface replacing the JNI callbacks.
- `platformspecific/android/dabservicelink.{h,cpp}`: replaces `JDabService` without JNI. Decoder
  callbacks hold shared state instead of `this`, so a callback that is already running cannot
  outlive the object. Labels are converted to UTF-8 for EBU Latin, ISO 8859-1, UCS-2 and UTF-8.
- `platformspecific/android/omri_api.{h,cpp}`: C API (open/close, scan, start/stop service,
  callbacks for state, services, reception, audio frames, dynamic label, slideshow and
  announcements). Found services carry the subchannel bitrate and ID of the primary audio
  component and their announcement support (flags and clusters).
- `CMakeLists.txt`: build script for the Android NDK.

## Changed

`platformspecific/android/raontunerinput.{h,cpp}`:

- Uses the new `UsbTunerDevice` and `DabServiceLink` types. The constructor does not request USB
  permission; the caller passes an already opened device. `initialize()` queues the chip
  initialisation and starts the polling thread.
- New `probe()`: sends the bridge's echo and version commands and fails if the device does not
  answer (16C0:05DC is a shared USB ID). It also detects whether the bridge acknowledges register
  writes with a one-byte reply (`0xA1`). If it does, every register write reads that reply, so
  register reads stay in step.
- Register writes and reads retry up to three times. Before each retry they drain stale replies,
  and they check the reply length. After 20 consecutive failed reads or acknowledgements (for
  example when the stick is unplugged) the driver reports `TUNER_CALLBACK_FAILED` once, stops USB
  I/O, and does not report READY again. A failed power-up also reports `TUNER_CALLBACK_FAILED`;
  power-up gives up early when the tuner is being closed.
- USB I/O is serialised: the command, scan and FIC threads hold a mutex for each unit of work, so
  a register page switch and the transfers that depend on it cannot interleave. The FIC thread
  waits between reads with the mutex released (100 ms without lock, 5 ms while locked and waiting
  for FIC data), so the scan thread can tune promptly.
- Scanning queues at most one step to the next channel at a time (`requestScanNext()`). Before, the
  FIC thread and the ensemble-complete callback could queue several, which skipped channels or
  left them before their ensemble was collected.
- The ensemble-complete callback runs on the FIB parser thread. It no longer touches the device;
  it queues the service start onto the command thread. A service is reported started before
  decoding is switched on, so the replayed label and slide follow the start notification.
- `startServiceSync()` keeps the requested service when the ensemble is still being collected.
  Before, it dropped the request and later started the previously requested service.
- `setFrequency()` ignores out-of-band frequencies instead of indexing past the channel tables.
  When a scan stops, the driver tunes to `0xFFFFFFFF` as a "no frequency" marker, which used to
  read out of bounds.
- Thread lifecycle: the destructor joins the scan thread before the FIC and data threads (the scan
  thread starts them), then stops the FIB parser thread while the object is still intact.
  `startServiceScan()` joins a finishing scan thread before stopping the data thread, and READY is
  reported only after the scan state has settled.
- The "service already running" check runs on the command thread. `stopAllRunningServices()` reads
  the subchannel when the command runs, not when it is queued.
- `scanProgress` reports the channel index and channel count instead of a percentage.
- `m_isInitialized` and `m_isScanning` are atomic. The polling loop idles while the tuner is not
  initialised or has failed.

- Registers for the ensemble's announcement support and switching callbacks and forwards them to
  the device's notification interface (`UsbTunerDevice::announcementSupport()` and
  `announcementSwitched()`).

`platformspecific/android/dabusbtunerinput.h`: `startService` takes a `DabServiceLink`.

`fig_00_ext_21.cpp`: FIG 0/21 parsing checks every broadcast length against the data left. It
skips the frequency list of an R&M type it does not handle and stops reading an FI list at a
truncated entry. Before, an unhandled type never advanced and appended records until memory ran
out. Multi-byte fields are read one byte at a time (the combined reads were unsequenced), the
DRM/AMSS frequency keeps all 15 bits instead of being cut to 8, and the adjacency `switch` no longer
falls through, so `adjacent` holds the signalled value.

`dabensemble.{h,cpp}`:

- New `stopFicParser()`, which stops the FIB parser thread.
- `checkServiceSanity()` accepts a service whose described components fall short of the number
  FIG 0/2 signals once the check has been failing for 8 seconds. Before, one such service (seen on
  the UK BBC National multiplex) kept the whole ensemble from completing, so none of its services
  were reported.
- FIG 0/18 (announcement support) is stored per service instead of being ignored, once FIG 0/0
  has identified the ensemble. `getAnnouncementSupport()` returns it, and
  `registerAnnouncementSupportCallback()` reports new or changed entries.
- FIG 0/19 (announcement switching) tracking reports through
  `registerAnnouncementSwitchingCallback()`: an announcement's start or change once, and its end
  every time it is signalled (after a retune the start may have been missed). A cluster that is
  not yet known starts an announcement even without the "new" flag.
- Both announcement tables are cleared by `reset()` and guarded by a mutex.

`fig_00_ext_18.cpp`: the SId and ASu flag bytes are read in separate statements (the operands of
`|` are unsequenced, so the byte order was not guaranteed), and entries and cluster IDs are read
only while bytes remain.

`fig_00_ext_19.cpp`: when the region flag is set, the following Region Id byte is skipped. It was
read as the start of the next cluster entry. An entry is parsed only if all four of its bytes are
present, so a truncated FIG no longer reads past its end. The two ASw flag bytes are read in separate
statements, so their order no longer depends on unsequenced evaluation.

`dabplusservicecomponentdecoder.{h,cpp}`: `m_superFrameSize` is atomic. The decoder thread sleeps
until the subchannel bitrate is known, where it used to busy-spin.
