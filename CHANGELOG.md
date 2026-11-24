# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Mouse input and bracketed paste reach a program. `MouseEncoder` sends press,
  release, motion and wheel reports for DEC tracking modes 1000/1002/1003 in SGR
  (1006) and urxvt (1015), with xterm's three-byte form as the default when a
  program enables tracking without an encoding. Touch follows the mode: with
  tracking on, one finger is the left button and a second finger is the wheel;
  with it off, drag-to-scroll and tap-to-type are unchanged and no mouse bytes
  are sent. `PasteEncoder` wraps pasted text in `ESC[200~` / `ESC[201~` when
  bracketed-paste mode (2004) is on, and `TerminalView.paste(String)`, the
  prompt's `ACTION_PASTE` and the IME paste action read the clipboard through it.
  Focus reports stay on the existing raw-input path. Two JVM suites cover every
  event kind, both extended encodings and the paste bracket; the touch gesture
  itself still needs the device check.

- The accessibility probe is a first-class module, `a11y-probe/`, with package
  `io.github.mbanucu.a11yprobe`, version `1.0.0` in its manifest, action
  `io.github.mbanucu.a11yprobe.COMMAND`, and its own `build.sh`. The build writes
  `out/a11y-probe-1.0.0.apk` and its `.sha256`; the release workflow attaches both
  to the GitHub release and names the version in the notes. `probe.sh` installs,
  enables, disables, reports status/version, and restores the previous
  `enabled_accessibility_services` byte for byte. The old
  `io.github.mbanucu.samples.a11yprobe` package is kept as a one-release legacy
  APK, and the old action is still accepted by the new one.
- The probe has a synchronous, scriptable surface at
  `content://io.github.mbanucu.a11yprobe.control`: `content call` methods `dump`,
  `find`, `set_text`, `click`, `wait`, `inspect`, `status`, `result` and `batch`
  answer `code=0|400` with a `data=` payload in the call. `wait` returns the
  matched event or a timeout (up to 9 s), request ids keep concurrent waits
  apart, a batch runs several commands in one call with one result line each, and
  `json:z:true` returns one JSON object per node. The broadcast action and the old
  file names still work for one release. `bash a11y-probe/test.sh` runs the
  protocol tests on the JVM.
- The probe can drive a general e2e suite: `scroll` with an optional `until`
  selector, `key` (`back`, `home`, `enter`), `long_click`, `clear`, `swipe`,
  `expect` (`exists`, `absent`, `text_is`, `text_contains`) and `wait_idle`,
  with `text=`, `desc=`, `id=` and `index=` selectors. A failed selector names
  the selector and lists the closest nodes, a failed `expect` is `code=400`, and
  the dump prints the resource id and content description. The APK also carries
  a built-in fixture activity (a lazy list, a text field and a dialog), and
  `a11y-probe/probe-test.sh` exercises every action against it on a device; the
  offline suite now covers the selector parser, the assertion verdicts and the
  node encodings.
- `screenstream` is published to Maven Central as `io.github.mbanucu:screenstream`,
  signed, through the repository's own `publish.sh` (build, sign, checksum, bundle,
  upload, and `--status`/`--publish` for the deployment). 0.1.0 came from
  `build.sh` and its POM carries no Gradle module metadata, so a Gradle consumer
  needs `@aar` on that coordinate; 0.2.0 is the same library published from the
  Gradle layout instead, so the bundle also carries the `.module` and
  `implementation("io.github.mbanucu:screenstream:0.2.0")` resolves the AAR as it
  is. The samples build against 0.2.0 (`SCREENSTREAM_VERSION`).
- A raw screen stream from the phone itself, in its own repository:
  [`screenstream`](https://github.com/MBanucu/screenstream). It ships as an app
  (`bash build.sh apk`) and as a library
  (AAR, jar, sources and POM, for an app or a test framework to depend on).
  `ScreenStreamActivity` asks for the display once and `ScreenStream` serves it as
  RGBA_8888 frames straight from an `ImageReader` - no encoder, no container - on
  `127.0.0.1:7100`, which apps reach directly and adb reaches through
  `adb forward`. Each frame is a 36-byte header (`RAW1`, size, stride, format,
  frame number, device timestamp) and its pixels. It is VFR the way the recording
  is: only frames whose pixels changed are numbered and sent, so a still screen
  sends nothing and the gaps are idle time. Consent can come from
  `adb shell appops set <pkg> PROJECT_MEDIA allow`, which starts the capture with
  no dialog at all, or from an accessibility service clicking the system dialog as
  a screen reader would (`ScreenStream.setDialogClicker`).
  The library's `client/stream.py` attaches, prints the shape of the stream and
  keeps frames when asked; a frame taken this way matched `screencap` pixel for
  pixel.
- `VIDEO=1 STREAM=1 bash samples/e2e.sh` (`--video`) records the run from the
  stream's own frames instead of `screenrecord`, and checks the file against what
  the phone wrote and what the checks were shown: the phone's recorded frame count
  must be the one `ffprobe` reads, and the file must reach the frame the last
  check passed on. The status file gained `recording_from_ns`, the file's zero on
  the stream's clock, because the recording's frame numbers are not the stream's
  — it starts mid-capture and drops frames the encoder was busy for — while its
  timestamps are the stream's own. Measured on a full run: 390 frames over 137 s,
  out of 1248 changed frames the compositor offered.
- `STREAM=1 bash samples/e2e.sh` makes the pixel checks read that stream: the
  probe's watch subscribes to the changed frames in-process through the module's
  `subscribe` instead of taking a screenshot every 333 ms, so it judges every change the screen made, and the
  frame number and device time in its answer are the stream's own. It judges the
  screen it starts on once as well, from a screenshot numbered as the source's
  last frame, because a stream says what changed and a check may start while the
  app is already drawn. A tap shares the same pooled frame as the socket
  consumers, so both can read at once. The default run is unchanged and needs no
  screen-capture consent.

- The pixel checks watch the screen on the phone: the probe service takes its own
  screenshots through the accessibility screenshot API, numbers only the frames
  where the screen changed, counts their pixels there, and answers with the frame
  number and the device time of the first frame that held what was asked for. No
  PNG crosses adb any more, a frame caught mid-repaint is simply not the frame
  that passes, and the blink check now fails a caret that is only ever on or off
  instead of counting samples. A pass is marked with its frame's device time, the
  clock the recording's frames already use, so `capture.py frames
  --event pixel:colour:pass` is that exact frame out of the video.

- Six sample checks for the pty and the terminal itself: the line discipline's
  flags, `ttyname`, `isatty` and `TIOCGWINSZ`; raw mode reading a key without
  Enter; eight-column tab stops; a `DECSTBM` region that scrolls without moving
  the rows outside it; `IL`/`DL`/`ICH`/`DCH`;  and two long lines typed back to back, checked for order, duplicates and
  complete contents.
- A curses fast-typing check: three lines submitted back to back into a curses
  form, asserted on order, duplicates, every character and the number of keys
  the program consumed.
- The same three lines into a curses form that repaints ten times a second, so
  fast typing is checked while output is arriving rather than only into a
  program sitting in `getch`.
- The e2e suite records itself: one video for the whole run, an event per check
  beside it, and frames pulled out of the video on request instead of screenshots
  taken while the checks run. The mapping is per frame, not guessed: `screenrecord`
  writes each frame's presentation time into a metadata track, so an event is the
  last frame presented at or before it. `test/capture.py` records, lists the
  events (`show`) and extracts frames (`frames --event`/`--at`/`--all`); a failed
  check's frames are extracted when the run ends. This costs about 13 s on a
  121 s suite, so `--no-record` runs without it.
- A regression gate at the end of a full run: every check has to finish within
  20% and two seconds of the median of its last five runs, and a check with a
  median under three seconds is not judged. `bash test/timings.sh check` does the
  comparing; the suite exits 1 and names the check, its time and the allowance
  it broke. The CI workflow grows an `e2e` job for it on a self-hosted
  `android-device` runner, off unless the repository sets `E2E_DEVICE=true`,
  because no hosted runner has a phone, an accessibility service and Termux.
- A progress and ETA line after every check: how many checks are done, what the
  run has spent, what is left and when it should finish, from
  `test/timings.sh eta`. The estimate now counts the run's wall clock (so the
  install it already paid for is included) and takes the median of each
  check's last few runs, so it follows a suite that got faster.

### Changed

- The tools that drive the device — `adb-connect` (the mDNS lookup for the
  wireless-debugging port), `adb-watch` (the loop that reconnects) and `capture`
  (the recording and the frame for an event) — are their own project,
  [`adb-harness`](https://github.com/MBanucu/adb-harness), published to PyPI as
  `adb-harness`, so a project that tests something else on a phone can
  `pip install` them without taking this suite along. `test/harness.sh` installs
  the pinned release into `test/.deps/venv` the first time it is needed and runs
  its commands from then on, as `capture`, `connect` or `watch`
  (`HARNESS_VERSION` pins the version, `HARNESS_SRC` runs a checkout instead), so
  this suite uses the package the way any other project does. The watcher's pid
  and log stay in `test/out/` through `ADB_WATCH_OUT`, and `kill "$(cat
  test/out/adb-watch.pid)"` now actually stops it — the old script's signal
  handler removed the pidfile and kept looping, which is fixed in 0.2.1. The
  names call sites use are one step longer — `bash test/harness.sh capture show`
  rather than `python3 test/capture.py show`. A full recorded run passes through
  the installed package, mapping all 52 of its events to frames.
- `adb-harness` ships `python-zeroconf` as the `mdns` extra rather than a
  dependency: only `adb-connect` needs it, and `capture` and the watcher work
  without it. This suite installs `adb-harness[mdns]` because it needs all three.
- The timing store — the ETA line, the per-check history and the regression gate —
  is its own project, `MBanucu/test-timings`, published to PyPI as
  `test-timings`, so a suite that is not this one can `pip install` it.
  `test/timings.sh` installs the pinned release into `test/.deps/venv` the first
  time it is needed and runs its `test-timings` command from then on
  (`TIMINGS_VERSION` pins the version, `TEST_TIMINGS_TOOL` runs a checkout
  instead), so this suite uses the package the way any other project does and a
  tool change has to be released before the suite can use it. The tool's own 22
  unit tests cover what this suite had no test for. Its estimate now counts a
  check the history has not seen when the run passes `--order`, which the copy
  kept here dropped from both the done count and the time left.
- The samples now depend on the published `screenstream` instead of compiling its
  submodule sources: `samples/build.sh` fetches `io.github.mbanucu:screenstream`'s
  AAR from Maven Central (`SCREENSTREAM_VERSION` pins the version), checks it
  against the `.sha1` the repository serves, and puts its `classes.jar` on the
  javac classpath and through `d8`. So a sample builds against what a real consumer
  gets, and a library change has to be published before a sample can use it. The
  e2e subset passes against the released 0.1.0.
- `screenstream` also has a Gradle build (`gradle :assembleRelease`) that works on
  this device with Gradle 9.9.2 and AGP 9.5.2; `build.sh` remains the build with
  no downloads.

- The e2e suite takes about a third less time: 194 s down to 121 s for the 23
  checks. The startup gate reads the probe's window count instead of dumping
  every window tree (32 s of the run), a launch is one adb call instead of
  four, a check asks for a screen snapshot instead of sleeping until the next
  periodic one, and a TUI is started as soon as its shell prompt arrives
  rather than after a fixed 1.5 s.
- What is left of the fixed waits is spent on answers rather than seconds. The
  app's Termux demo no longer sleeps 1.5 s, 1.2 s and 4.5 s between its lines:
  each line waits for the session's own answer to the one before. The session
  checks type once the demo's last line is answered, instead of racing it and
  retrying. The 4 s before a program's escapes is a wait for the app's last
  probe, and the grid settle polls at 0.25 s. Sleep time per run: 9.6 s to
  4.1 s; the run itself 123 s to 121 s, because the replaced seconds were
  waiting for events that arrive when they arrive.

### Removed

- The `screenstream` submodule. Its sources were what the samples compiled before
  they took the published AAR; after that it was a second pin that could disagree
  with the version actually being compiled, it made `git status` dirty whenever the
  checkout had work, and it required `git submodule update --init` for something no
  build read. The library stays its own repository; a checkout beside this one is
  what the client examples in `samples/README.md` refer to (`../screenstream`).

- The `opencode` check, which needed opencode installed and tested that program
  rather than the view. The miniature pages check the same bytes.

### Fixed

- A curses page counted the resize ncurses delivers as a key (`KEY_RESIZE`,
  410), so a typing check could report one key more than was typed depending on
  whether the view resized before or after the program's first frame. Resizes
  are skipped, and the check now asserts the program starts with no key
  waiting.
- The sample's live stream wrote each send from its own thread, so a line and
  its Enter could reach the pty in either order. One writer now drains a queue,
  keeping typed bytes and their Enter in the order they were typed.
- A sample's Termux session ends when the app does: the wrapper hangs up the
  pty once its control socket closes, instead of leaving the pty helper, a
  shell and the program behind. Android counts those against a 32-process
  limit and kills every process the terminal owns when it is reached.
- The e2e suite reaps any session leftovers after each check, including the
  ones a failed launch leaves with no control socket to report to.

### Changed

- Releases publish automatically once the Central Portal validates the bundle
  (`publishingType = AUTOMATIC`). 0.2.0 was released by hand to prove the path.

## [0.2.0] - 2026-11-22

The first release on Maven Central, as
`io.github.mbanucu:android-accessible-terminal`. Terminal behaviour is unchanged:
the sources under `terminal/src` are identical to 0.1.0.

### Added

- Publishing through the Central Portal API (Nmcp): one signed bundle with the
  AAR, sources jar, javadoc jar, Gradle module metadata and POM.
- A release workflow that builds the tag it was started from and publishes it;
  `--ref <tag>` retries a failed run without another tag.
- CI builds the bundle (`nmcpZipAggregation`) on every push, so a missing
  sources or javadoc jar is caught before a release is cut.

### Changed

- The Maven group is `io.github.mbanucu`, the namespace verified in the Central
  Portal. The JitPack coordinate stays
  `com.github.MBanucu:android-accessible-terminal`.

### Fixed

- The POM declares `GPL-3.0-or-later`, matching the `LICENSE` file, which has
  been GPL-3.0 since the extraction. The 0.1.0 POM said Apache-2.0 by mistake.
- CI installed the Android SDK with an action that runs `sdkmanager tools`, which
  current cmdline-tools no longer provide; the platform is installed directly now.

## [0.1.0] - 2026-11-22

Extracted from
[opencode-termux-setup](https://github.com/MBanucu/opencode-termux-setup) into
its own repository. The first standalone version of the library.

### Added

- `TerminalView`, a `View` that draws a character grid and publishes it to
  accessibility services: one node per non-blank row, an editable prompt node,
  `ACTION_SET_TEXT`, `InputConnection`, and announcements coalesced to one per
  400 ms of quiet.
- `Screen`, the ECMA-48 subset a TUI uses: alternate screen, cursor addressing,
  erases, insert/delete, scroll regions, and SGR colour (16, 256 and truecolor)
  with underline, reverse, dim, italic, strikethrough and hidden.
- A 3000-row primary-screen scrollback with per-cell colour and attributes, a
  cell-range selection model that joins wrapped rows, and touch drag/fling
  scrolling.
- Key encoding for hardware keys, DECCKM application-cursor mode, the Ctrl/Alt
  character forms and xterm's modifier parameters, plus bracketed-paste, focus
  and mouse-mode tracking.
- Query replies through a `ReplySink`: DA1/DA2, DSR/CPR, XTVERSION, the kitty
  keyboard query and OSC 10/11 colours.
- Width-2 handling for CJK and fullwidth glyphs, with whole-glyph wrapping at the
  right edge.
- Fifteen JVM suites, 330 checks, run by `bash terminal/test.sh` with no device,
  Gradle or test framework.
- The `samples/native-terminal` and `samples/a11y-probe` apps, with
  `samples/e2e.sh` driving the view on a device through an `AccessibilityService`.
- A Gradle `com.android.library` build producing the AAR, published to JitPack.
- GPL-3.0-or-later licensing, carried over from the repository it was extracted
  from.

[Unreleased]: https://github.com/MBanucu/android-accessible-terminal/compare/0.2.0...HEAD
[0.2.0]: https://github.com/MBanucu/android-accessible-terminal/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/MBanucu/android-accessible-terminal/releases/tag/0.1.0
