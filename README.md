# android-accessible-terminal

A native Android `View` that draws a terminal grid and publishes it to
accessibility services as rows, plus an editable prompt node. This is what lets a
screen reader read and type into a terminal that is not an `EditText`.

It was extracted from
[`opencode-termux-setup`](https://github.com/MBanucu/opencode-termux-setup), where
it was measured on a device: hundreds of row nodes in one dump, `ACTION_SET_TEXT`
reaching a Termux shell, `TYPE_ANNOUNCEMENT` as output arrives. The write-up,
including the comparison with xterm.js in a WebView, is
[`docs/tty-accessibility-research.md`](https://github.com/MBanucu/opencode-termux-setup/blob/main/docs/tty-accessibility-research.md).
The plan for replacing that app's xterm page with this view is in
[`docs/terminal-module-roadmap.md`](https://github.com/MBanucu/opencode-termux-setup/blob/main/docs/terminal-module-roadmap.md).

`TerminalView` is transport-agnostic: the caller feeds it bytes from a PTY and is
told when to resize the PTY or when a line was submitted.

## Install

Releases are published to Maven Central, so `mavenCentral()` is the only
repository needed:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.mbanucu:android-accessible-terminal:0.2.0")
}
```

[JitPack](https://jitpack.io) also serves tags that have not been released to
Central, including the Apache-labelled 0.1.0:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.MBanucu:android-accessible-terminal:0.2.0")
}
```

How a release is cut — the Central Portal namespace, the signing key and the CI
secrets — is in [`RELEASING.md`](RELEASING.md).

Requirements: `minSdk 24`, Java 17. The AAR has no dependencies of its own; it
uses only `android.*`. Every published file is PGP-signed.

The view loads `terminal-mono.ttf` from the host app's `assets/` when present —
the DejaVu Sans Mono the samples ship — and falls back to the system monospace
when it is not. A sample copy is in
[`samples/native-terminal/assets/`](samples/native-terminal/assets).

## The accessibility probe

[samples/e2e.sh](samples/e2e.sh) drives the view with an `AccessibilityService`
rather than a coordinate tap or `input text`. That service is published on its
own, so a device suite in another repository can use it without copying this
repository or building its samples:

- app id `io.github.mbanucu.a11yprobe`, versioned in its manifest;
- version `1.0.0` of the APK, with a `.sha256` beside it, on every GitHub
  release;
- a synchronous `content call` surface at
  `content://io.github.mbanucu.a11yprobe.control`, with actions and assertions
  (`scroll`, `key`, `long_click`, `clear`, `swipe`, `expect`, `wait_idle`) for a
  general e2e suite;
- the one-release broadcast action `io.github.mbanucu.a11yprobe.COMMAND`;
- [`probe.sh`](probe.sh), which saves and restores the accessibility setting.

Download the APK and the wrapper from a release tag (replace `<tag>` with the
release, for example `0.3.0`):

```bash
tag=0.3.0
gh release download "$tag" --pattern 'a11y-probe-*'
curl -LO "https://raw.githubusercontent.com/MBanucu/android-accessible-terminal/$tag/probe.sh"

sha256sum -c a11y-probe-1.0.0.apk.sha256
adb install -r a11y-probe-1.0.0.apk
# or, since probe.sh looks in the directory it is run from:
bash probe.sh install

bash probe.sh enable
bash probe.sh status           # package, version, version-match, enabled
```

`probe.sh` reads the version from the APK's filename when it is run outside a
checkout, so the three downloaded files are enough. Set `PROBE_VERSION=1.0.0`
if the APK was renamed.

The same files with `curl` instead of `gh`:

```bash
curl -LO "https://github.com/MBanucu/android-accessible-terminal/releases/download/$tag/a11y-probe-1.0.0.apk"
curl -LO "https://github.com/MBanucu/android-accessible-terminal/releases/download/$tag/a11y-probe-1.0.0.apk.sha256"
sha256sum -c a11y-probe-1.0.0.apk.sha256
adb install -r a11y-probe-1.0.0.apk
```

With the service enabled, a `content call` performs one command and answers in
the call. It can still write its file, but the caller no longer has to poll one:

```bash
CONTROL=content://io.github.mbanucu.a11yprobe.control

# The tree in data=, and still in files/tree.txt.
adb shell content call --uri $CONTROL --method dump --arg tree

# find + click + wait for an event in one call, one result line each.
adb shell "content call --uri $CONTROL --method batch --arg 'find OK
click OK
wait done timeout=3000'"

# The JSON form, in data= and files/tree.json (which json.loads reads).
adb shell content call --uri $CONTROL --method dump --arg tree --extra json:z:true

# Version, service state, target and event/window counts.
adb shell content call --uri $CONTROL --method status
```

A rejection answers `code=400` with a sentence and does not stop the probe; a
`wait` blocks for at most 9 seconds and needs `--extra id:s:<uuid>` plus
`--extra async:z:true` with `--method result` to wait longer. `scroll` can stop
at a `until` selector, and `expect` returns `0`/`400` with the matched node or
the closest candidates, so a shell check does not grep a dump. The reply and JSON
schemas, selectors, assertions, the batch grammar, the fixture screen and the
request-id form are in
[`a11y-probe/README.md`](a11y-probe/README.md#synchronous-commands-content-call).

The one-release broadcast still works:

```bash
adb shell am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es dump tree
adb shell cat /sdcard/Android/data/io.github.mbanucu.a11yprobe/files/tree.txt

# The version the installed app reports, for a harness that pins it.
adb shell am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es version true
adb shell cat /sdcard/Android/data/io.github.mbanucu.a11yprobe/files/version.txt
bash probe.sh version          # exits 1 when installed and expected differ
```

`probe.sh disable` restores `enabled_accessibility_services` (and
`accessibility_enabled`) to the exact value it found, including `none` or an
unset (`null`) setting. The saved value is kept on disk, so `bash probe.sh
restore` repairs a run that was killed before its cleanup.

The one-release compatibility names are still there: the new APK accepts
`io.github.mbanucu.samples.a11yprobe.COMMAND`, and
`out/a11y-probe-1.0.0-legacy.apk` (also attached to the release) keeps the old
`io.github.mbanucu.samples.a11yprobe` app id and `.ProbeService` component for a
phone that still has a harness pinned to it. Use `bash probe.sh --legacy install`
and `bash probe.sh --legacy enable` to drive that APK.

> **Security boundary.** An accessibility service can read every window on the
> phone and perform actions on its nodes. The probe is a test tool, not a screen
> reader to leave on: use a test device, expect it to write only under its own
> external files directory
> (`/sdcard/Android/data/io.github.mbanucu.a11yprobe/files`), and disable it when
> the run is over. `probe.sh enable` adds the probe to the list rather than
> replacing a screen reader the owner already uses, and `probe.sh disable` puts
> the list back. The `content call` provider is exported with no permission, the
> same boundary as the broadcast receiver: while the accessibility service is
> enabled, any app on the device can call it. Uninstall the probe when it is not
> needed.

## Building from source

`bash terminal/test.sh` runs the JVM suites with nothing but a `javac` — no SDK,
no Gradle, no device. `bash a11y-probe/test.sh` runs the probe's protocol tests
the same way: the selectors, the assertions, the JSON dump, the batch grammar and
the reply codes. `bash a11y-probe/probe-test.sh` is the device-only half: it
drives every action against the probe's own fixture screen.

`bash samples/build.sh all` builds both demo apps with the SDK's tools and no
Gradle, and `bash samples/e2e.sh` drives them on a connected device. The first
sample build needs the network once: the screen stream is a dependency taken from
Maven Central (`io.github.mbanucu:screenstream`, pinned by `SCREENSTREAM_VERSION`)
and cached under `samples/out/m2`, not a directory in this repository.

The Gradle build needs an Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT` or
`sdk.dir` in `local.properties`) and JDK 17. On an aarch64 host, such as Termux,
AGP's bundled `aapt2` has no usable build, so point it at the system one:

```bash
export ANDROID_HOME=$HOME/android-sdk
./gradlew -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2 assembleRelease
```

Or put `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` in
`~/.gradle/gradle.properties` once and drop the flag. A CI machine is x86_64 and
needs neither. `assembleRelease` writes the unsigned AAR to
`build/outputs/aar/`; `nmcpZipAggregation` writes the signed-for-release bundle
only when `SIGNING_KEY` is set, so a local build needs no secrets.

The terminal view has no release, so the samples still compile its sources
directly, which also works on an aarch64 host where Gradle's `aapt2` has no build:

```kotlin
android {
    sourceSets.main.java.srcDirs += "$rootDir/terminal/src/main/java"
}
```

## Use

```java
TerminalView terminal = new TerminalView(context);
terminal.setListener(line -> sendToTheProgram(line + "\r"));   // a submitted line
terminal.setSizeListener((cols, rows) -> resizeThePty(cols, rows));
terminal.feed(chunkFromThePty);        // often; a partial escape is buffered
terminal.setRawInput(text -> writeToTheProgram(text));  // keys, mouse, paste
terminal.paste(textFromTheClipboard);  // wrapped when the program asks for it
```

`feed` takes whole chunks and does not care where they split: an escape sequence
cut in half is held until it is complete. `screenText()` returns the primary
screen's history above the visible grid; on the alternate screen it returns only
that screen, because a full-screen program owns the display.

A row the terminal wrapped itself is marked as continuing on the next row, and
`text()`/`screenText()` join the two instead of inserting a newline the program
never wrote; the flag follows the row into history and survives a resize.

Dragging the view scrolls back through the primary screen's history, and a fling
carries on at finger speed. While scrolled back, output that pushes rows into
history keeps the row being read in place rather than sliding it away; a view at
the bottom follows new output, and typing returns it to the bottom.

On the alternate screen the caret is the view's to draw: a block at the grid's
cursor, hidden while the program says DEC mode 25 is off, and blinking when it
asks for a blinking style (`DECSCUSR 1` or DEC mode 12). A full-screen program
positions the caret and leaves drawing it to the terminal, so without that a TUI
has no visible caret at all.

### Touch, the mouse and paste

Touch follows the program. With mouse reporting on (DEC modes 1000/1002/1003) one
finger is the left button: a tap sends a press and a release, and a drag sends a
press, the motion 1002/1003 asked for, and a release. A second finger turns the
gesture into wheel ticks, one row per tick, so a TUI can still be scrolled;
fingers moving down are wheel up, the direction the content moves. A real wheel
or trackpad sends the same wheel buttons. The press waits until the gesture is
known to be a click or drag, so starting a two-finger scroll does not leave a
click behind.

With reporting off, one finger drags the primary screen's history, a fling
carries on at finger speed, and a tap focuses the prompt: the same path as before
mouse support, and no bytes are sent to the program. Mouse reporting is also
ignored when no raw-input listener is attached, so a view with no transport still
scrolls. Reports use SGR (1006), urxvt (1015), or xterm's three-byte default when
a program enables tracking without either encoding; the wheel is a press with
the button codes 64-67 and has no release.

`paste(text)` sends the text to a program on the alternate screen, wrapped in
`ESC[200~` / `ESC[201~` when it enabled bracketed-paste mode (2004) and unchanged
when it did not; an end marker that came in with the text is removed so a paste
cannot close its own bracket. On the primary screen the text joins the prompt
line, because the view owns that line and submits it later. The prompt node's
`ACTION_PASTE` and the IME's paste action read the clipboard through the same
method.

## What is here

| type | role |
|---|---|
| `TerminalView` | the view: grid, rendering, `AccessibilityNodeProvider`, `InputConnection`, announcements |
| `Screen` | the grid: the ECMA-48 subset a TUI uses — alternate screen, cursor addressing, erases, insert/delete, scroll regions, SGR colour and attributes. Package-private; not part of the API |
| `HistoryRow` | one row of primary-screen scrollback, with the colours and SGR attributes of its cells. Package-private |
| `AnsiPalette` | the 16-colour palette, the 256-colour cube and bold brightening. Package-private |
| `CellAttributes` | the per-cell SGR bits the view draws differently: underline, reverse, dim, italic, strikethrough, hidden. Package-private |
| `Width` | the display width of a character in cells: 2 for CJK/fullwidth, 1 otherwise. Package-private |
| `Viewport` | the window over the history and the live grid: scroll offset, clamping, follow-at-bottom. Package-private |
| `Selection` | an inclusive cell range over the viewport, and the text it copies with hard breaks kept and wrapped rows joined. Package-private |
| `KeyEncoder` | the bytes a hardware key sends, normal or application-cursor mode. Package-private |
| `MouseEncoder` | the bytes a mouse press, release, motion or wheel sends in tracking modes 1000/1002/1003 and encodings 1006/1015. Package-private |
| `PasteEncoder` | the bytes a paste sends, with the `ESC[200~` / `ESC[201~` bracket when mode 2004 is on. Package-private |
| `ReplySink` | the caller's write path for the answers a screen owes a querying program |
| `TerminalAccessibility` | the row and prompt nodes, their events, and the announcement timer. Package-private |

A program on the alternate screen receives hardware keys as terminal bytes:
arrows, Home/End, Page up/down, Insert/Delete, Esc, Tab, Backspace and F1-F12,
with DECCKM switching arrows and Home/End to the `ESC O` forms. Ctrl, Alt and
Shift modify a typed character or a cursor key: Alt+Left sends `ESC[1;3D`.
Bracketed-paste mode (2004), focus reporting (1004/2031) and mouse modes
1000/1002/1003 with 1006/1015 encodings are tracked. Paste is wrapped when 2004
is on, mouse reports follow the tracking mode, and focus events stay on the
raw-input path the view already used.

Queries are answered through a `ReplySink`: DA1/DA2, DSR and CPR, XTVERSION,
the kitty keyboard query and OSC 10/11 colours. Point it at the pty's write path;
with no sink the screen stays quiet.

## The accessibility contract

- one virtual child per non-blank row, id fixed by row position, class
  `android.widget.TextView`, with text, bounds and accessibility-focus actions;
- one editable node (class `android.widget.EditText`, `isEditable`, `ACTION_SET_TEXT`,
  `ACTION_PASTE`, `ACTION_FOCUS`) that also accepts the IME's `InputConnection`;
  `ACTION_PASTE` reads the clipboard and sends it through `paste(text)`, so a
  program that enabled 2004 gets the wrapped form;
- `ACTION_SET_TEXT` text ending in a newline submits the line, because a service has
  no other way to press Enter on a view that is not an `EditText`;
- `TYPE_WINDOW_CONTENT_CHANGED` per feed, and `TYPE_ANNOUNCEMENT` coalesced to at most
  one per 400 ms of quiet, so a repainting TUI does not speak a syllable stream;
- every event is guarded by `AccessibilityManager.isEnabled()`. Without that check,
  `requestSendAccessibilityEvent` throws `IllegalStateException: Accessibility off`,
  which is a normal phone's state.

## Tests

`bash terminal/test.sh` compiles the grid, its history rows and colour tables,
and the viewport with `src/test`, then runs them on the JVM. It discovers the
sources and every `*Test.java` instead of naming them, and needs no device, no
Gradle, no Android SDK and no test framework — only a `javac`. Eighteen suites
(483 checks) cover cursor addressing, erases, insert/delete, scroll regions,
scrollback, selection, SGR colour and attributes, the caret, wide characters,
combining marks and emoji cells, key encoding, mouse encoding, paste wrapping,
input modes and query replies.

`TerminalView` and `TerminalAccessibility` import `android.*` and need
`android.jar`; the view is covered on a device by the sample suite.

The demo app under `samples/` and the probe module are built without Gradle:

```bash
bash a11y-probe/build.sh             # -> out/a11y-probe-1.0.0.apk and .sha256
bash a11y-probe/test.sh              # the probe's protocol tests, on the JVM
bash samples/build.sh all            # native-terminal, and the probe via its build.sh
bash samples/e2e.sh                  # the fast checks, APK already installed
bash samples/e2e.sh --opencode       # also drive opencode's TUI (slow)
```

`samples/native-terminal` runs the view against an app-owned `forkpty` PTY or a
Termux `RUN_COMMAND` session; [`a11y-probe/`](a11y-probe) is the
`AccessibilityService` the suite drives the apps with, and `samples/e2e.sh` lists
its checks with `--list`, runs one with `--only <name>`, and puts the
accessibility settings back afterwards.

## What it is not

- **Not a VT emulator.** `SGR` colour is drawn — 16, 256 and truecolor, with
  `1` brightening a palette foreground — and so are underline, reverse, dim,
  italic, strikethrough and hidden. It keeps a primary-screen scrollback (the
  alternate screen is the program's own), a cell-range selection model that
  copies hard breaks and wrapped rows correctly, and mouse and paste input for a
  program that asks. It has no long-press selection handles or touch selection
  UI. A cell holds a base code point and its combining marks, so `e` + U+0301 is
  one cell and copies as the original two code points; a wide or emoji glyph
  takes the two cells the width table gives it and wraps whole at the right
  edge. ZWJ emoji sequences and variation selectors are not joined into one
  cluster yet. Enough to draw a TUI and read it; not enough to be someone's daily
  terminal without more work.
- **Not a transport.** An app-owned `forkpty` pty and Termux's `RUN_COMMAND`
  session are both in the sample, and both are the caller's business. The size
  callback is the whole contract between the view and the program.
- **Not verified against TalkBack**, only against an `AccessibilityService`
  probe, on one device (Android 15, API 35).

## Changelog

What changed in each release is in [`CHANGELOG.md`](CHANGELOG.md).

## License

GNU General Public License, version 3 or later. SPDX: `GPL-3.0-or-later`. The
full text is in [`LICENSE`](LICENSE).

This is a copyleft license: an app that links the library must itself be
distributed under GPL-3.0-or-later (or a compatible license). The repository this
was extracted from, [opencode-termux-setup](https://github.com/MBanucu/opencode-termux-setup),
is GPL-3.0 as well.

The license has been GPL-3.0-or-later since the extraction. The POM of the 0.1.0
JitPack artifact declared Apache-2.0 instead; that metadata was wrong, and 0.2.0
is the first release whose POM matches the license. The bundled DejaVu Sans Mono
font keeps its own
[DejaVu fonts license](https://dejavu-fonts.github.io/License.html).
