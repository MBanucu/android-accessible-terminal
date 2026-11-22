# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `screenstream` 0.1.0 is published to Maven Central as
  `io.github.mbanucu:screenstream` — the AAR, POM, sources and javadoc, signed,
  through the repository's own `publish.sh` (build, sign, checksum, bundle,
  upload, and `--status`/`--publish` for the deployment). The POM carries no Gradle
  module metadata, so a Gradle consumer needs `@aar` on the coordinate.
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
  median under three seconds is not judged. `test/timings.py check` does the
  comparing; the suite exits 1 and names the check, its time and the allowance
  it broke. The CI workflow grows an `e2e` job for it on a self-hosted
  `android-device` runner, off unless the repository sets `E2E_DEVICE=true`,
  because no hosted runner has a phone, an accessibility service and Termux.
- A progress and ETA line after every check: how many checks are done, what the
  run has spent, what is left and when it should finish, from
  `test/timings.py eta`. The estimate now counts the run's wall clock (so the
  install it already paid for is included) and takes the median of each
  check's last few runs, so it follows a suite that got faster.

### Changed

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
