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

Until the first Central release is out, or to build a tag that has not been
released there, [JitPack](https://jitpack.io) serves the same sources:

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

Alternatively, compile the sources directly, which is what the sample apps do
and what works on an aarch64 host where Gradle's `aapt2` has no build:

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
| `ReplySink` | the caller's write path for the answers a screen owes a querying program |
| `TerminalAccessibility` | the row and prompt nodes, their events, and the announcement timer. Package-private |

A program on the alternate screen receives hardware keys as terminal bytes:
arrows, Home/End, Page up/down, Insert/Delete, Esc, Tab, Backspace and F1-F12,
with DECCKM switching arrows and Home/End to the `ESC O` forms. Ctrl, Alt and
Shift modify a typed character or a cursor key: Alt+Left sends `ESC[1;3D`.
Bracketed-paste mode (2004), focus reporting (1004/2031) and mouse modes
1000/1002/1003 with 1006/1015 encodings are tracked; touch still scrolls.

Queries are answered through a `ReplySink`: DA1/DA2, DSR and CPR, XTVERSION,
the kitty keyboard query and OSC 10/11 colours. Point it at the pty's write path;
with no sink the screen stays quiet.

## The accessibility contract

- one virtual child per non-blank row, id fixed by row position, class
  `android.widget.TextView`, with text, bounds and accessibility-focus actions;
- one editable node (class `android.widget.EditText`, `isEditable`, `ACTION_SET_TEXT`,
  `ACTION_PASTE`, `ACTION_FOCUS`) that also accepts the IME's `InputConnection`;
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
Gradle, no Android SDK and no test framework — only a `javac`. Fifteen suites (330 checks)
cover cursor addressing, erases, insert/delete, scroll regions, scrollback,
selection, SGR colour and attributes, the caret, wide characters, key encoding,
input modes and query replies.

`TerminalView` and `TerminalAccessibility` import `android.*` and need
`android.jar`; the view is covered on a device by the sample suite.

The demos are two apps under `samples/`, built without Gradle:

```bash
bash samples/build.sh all            # -> samples/out/native-terminal.apk and a11y-probe.apk
bash samples/e2e.sh                  # the fast checks, APK already installed
bash samples/e2e.sh --opencode       # also drive opencode's TUI (slow)
```

`samples/native-terminal` runs the view against an app-owned `forkpty` PTY or a
Termux `RUN_COMMAND` session; `samples/a11y-probe` is the `AccessibilityService`
the suite drives the apps with. `samples/e2e.sh` lists its checks with
`--list`, runs one with `--only <name>`, and puts the accessibility settings
back afterwards.

## What it is not

- **Not a VT emulator.** `SGR` colour is drawn — 16, 256 and truecolor, with
  `1` brightening a palette foreground — and so are underline, reverse, dim,
  italic, strikethrough and hidden. It keeps a primary-screen scrollback (the
  alternate screen is the program's own) and a cell-range selection model that
  copies hard breaks and wrapped rows correctly, but it has no long-press
  handles, clipboard wiring or mouse events. Width-2 CJK glyphs take two cells
  and wrap whole; combining marks and emoji do not. Enough to draw a TUI and
  read it; not enough to be someone's daily terminal without more work.
- **Not a transport.** An app-owned `forkpty` pty and Termux's `RUN_COMMAND`
  session are both in the sample, and both are the caller's business. The size
  callback is the whole contract between the view and the program.
- **Not verified against TalkBack**, only against an `AccessibilityService`
  probe, on one device (Android 15, API 35).

## License

GNU General Public License, version 3 or later. SPDX: `GPL-3.0-or-later`. The
full text is in [`LICENSE`](LICENSE).

This is a copyleft license: an app that links the library must itself be
distributed under GPL-3.0-or-later (or a compatible license). If that is not what
you want, the module is small and self-contained enough to study and reimplement,
but there is no permissive grant.

Version 0.1.0, published to JitPack before this change, was Apache-2.0; release
0.2.0 is the first GPL release. The bundled DejaVu Sans Mono font keeps its own
[DejaVu fonts license](https://dejavu-fonts.github.io/License.html).
