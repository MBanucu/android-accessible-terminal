# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
- A progress and ETA line after every check: how many checks are done, what the
  run has spent, what is left and when it should finish, from
  `test/timings.py eta`. The estimate now counts the run's wall clock (so the
  install it already paid for is included) and takes the median of each
  check's last few runs, so it follows a suite that got faster.

### Changed

- The e2e suite takes about a third less time: 194 s down to 123 s for the 23
  checks. The startup gate reads the probe's window count instead of dumping
  every window tree (32 s of the run), a launch is one adb call instead of
  four, a check asks for a screen snapshot instead of sleeping until the next
  periodic one, and a TUI is started as soon as its shell prompt arrives
  rather than after a fixed 1.5 s.

### Removed

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
