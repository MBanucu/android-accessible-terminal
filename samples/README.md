# Sample apps

Two small apps that prove the [terminal view](../terminal) against a real device:
one host app and the accessibility service the checks drive it with.

They are deliberately not Gradle modules: on the device this repository was
extracted on, Gradle's `aapt2` has no aarch64 build, and a sample should be a
directory of files you can read. `build.sh` does what Gradle would do — `aapt2
link`, `javac`, `d8`, zip in the dex and the native library, `zipalign`,
`apksigner` — and needs only an Android SDK.

```bash
bash samples/build.sh all            # native-terminal and a11y-probe
bash samples/build.sh native-terminal
bash samples/build.sh a11y-probe
```

Conventions the script relies on, so no build file is needed:

| path | meaning |
|---|---|
| `AndroidManifest.xml` | the manifest |
| `res/` | resources, if any |
| `src/` | Java sources |
| `assets/` | assets |
| `jni/*.c` | native sources, built into `lib<app dir with _>.so` (so `native-terminal` produces `libnative_terminal.so`) |

`build.sh` compiles `../terminal/src/main/java` into any sample that imports
`io.github.mbanucu.terminal`, which is the same source a Gradle library module
would hold.

## native-terminal — an app-owned PTY, and a screen reader can use it

*Proves: `forkpty()` works from an app (no Termux, no root), the app can resize
its own pty with `ioctl(TIOCSWINSZ)`, and a `View` with an
`AccessibilityNodeProvider` gives a screen reader rows to read and text to type.*

`jni/pty.c` is the whole PTY: `forkpty`, `execvp`, `read`, `write`,
`ioctl(TIOCSWINSZ)`, `waitpid` — about 180 lines, linked only against libc.
`TerminalView` draws lines and publishes them as virtual nodes (one per visible
line, plus an editable prompt node) and accepts `ACTION_SET_TEXT` as well as IME
text and raw key events.

On launch it asks the shell what it is connected to, then resizes the pty from
Java two seconds later. `files/evidence.txt` in the app's external directory
holds the transcript:

```
### pty:master-fd:136 pid:30653
/dev/pts/4
stdin-is-tty
stdout-is-tty
size-now=24 80
### resize:40x12 from app
### resize:result:0 cols:40 rows:12
SIGWINCH-arrived
size-after=12 40
```

`resize:result:0` is the `ioctl` returning success; `SIGWINCH-arrived` and
`size-after=12 40` are the kernel telling the foreground program.

Buttons: `80x24`, `40x12`, `tty` (prints `tty; stty size`), `dump`, and `probe`.

`probe` records whether this app can run programs it does not own — the question
behind "start Termux's shell in my own pty":

```
exec:/data/data/com.termux/files/usr/bin/bash access=ENOENT(2) stat=ENOENT(2) exec=ENOENT(2)
exec:/system/bin/sh                     access=ok(0) stat=ok(0) exec=ok(0)
exec:/data/user/0/.../files/sh-copy     access=ok(0) stat=ok(0) exec=EACCES(13)
```

Termux's directory is 0700 and belongs to another uid, so the path does not even
resolve; and a binary copied into this app's own `files/` cannot be executed on a
target SDK of 29 or later, which is why Termux still ships with 28. An app-owned
pty can run system binaries, not Termux.

### Termux mode: a real session, drawn by the same view

The app cannot start Termux's shell, so it asks Termux for a session and hands it
a loopback socket for stdin and stdout:

```bash
adb shell pm grant io.github.mbanucu.samples.nativeterminal \
    com.termux.permission.RUN_COMMAND      # after install or pm clear: clearing revokes it
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez termux true
```

The session answers what it is — `/dev/pts/4`, `session-stdin-is-tty`,
`session-stdout-is-tty`, `size=24 80` — then prints `TERMUX-SESSION-OK` and
`TICK-1..3` on a timer with nobody typing, and all of it appears as rows the
probe can read.

Two things bite: `script`'s pty starts at `0x0`, so a size has to be set from
inside it, and a service acts on the *focused* window — Termux's own toolbar field
is the first editable in a naive tree, so the probe needs `--es pkg`.

### Resizing

The view's grid is the source of truth. A window change — the keyboard opening, a
rotation, `wm size` — resizes the grid, and the app then tells whoever owns the
pty: `ioctl(TIOCSWINSZ)` for its own `forkpty` pty, a `cols rows` line on the
control port (and `stty -F` inside Termux) for a session. Measured: a grid going
62x68 → 33x22 makes the program answer `size-now=22 33` in both modes.

The activity sets `configChanges` so a window change reaches `onSizeChanged`
instead of recreating the activity and dropping the session, and `adjustResize` so
the keyboard shrinks the window rather than panning it.

### opencode mode: the TUI, drawn by the same view

opencode paints with cursor addressing on the alternate screen, so the view draws
a `Screen` grid instead of splitting output on newlines. `--ez opencode true`
starts opencode in the Termux session:

```bash
adb shell am start -n io.github.mbanucu.samples.nativeterminal/.MainActivity \
    --ez opencode true
```

At 80x24 — above the 60-column floor opencode drops its footer below — the view
shows the logo, the composer border and `2.3.2`, and the probe reads the same
lines as row nodes. The caret in the composer is drawn where opencode puts it,
because the view follows DEC mode 25.

The suite no longer drives this mode — opencode's composer is not a shell prompt,
and the scripts below check the same bytes it sends without needing opencode
installed. Manually, keys do reach it: once a program owns the alternate screen
the view hands committed text and key events to the pty as they arrive. The
session ends with the app: force-stopping closes the socket, the wrapper hangs
up the pty, and the program inside it exits. opencode still keeps a session
record (`opencode session list`).

### The scripted modes

Each takes an extra on the launch intent:

| extra | what it feeds |
|---|---|
| `--ez cursor true` | `CUP`, `CUU`/`CUF`, `VPA`/`CHA`, `CSI s`/`CSI u` and the alternate screen, one step at a time, recording `cursor=row,col` in `files/evidence.txt` |
| `--ez colors true` | `\e[31m`, `\e[32m`, `\e[1;34m` and `\e[38;5;208m`; the suite also counts red, green and blue pixels, because rows alone pass even in default grey |
| `--ez tui true` | a curses box, menu and status line, driven with `n`, `p`, `t`, `q`; also a blinking block caret the suite samples in two frames |
| `--ez echo true` | an input box and the last three submitted lines above it, one character at a time from the service |
| `--ez background true` | a blue box (SGR 44) with a yellow one (SGR 43) below it, checked by counting pixels |
| `--ez symbols true` | the seven non-ASCII glyphs opencode draws, with truecolor fills |
| `--ez modes true` | the modes and queries opencode sends, then one sentinel row |
| `--ez ptyFlags true` | `ttyname`, `isatty` on all three descriptors, the line discipline's flags and `TIOCGWINSZ` |
| `--ez ptyRaw true` | `tty.setraw`, the flags it cleared, then one byte read without Enter |
| `--ez tabs true` | `A\tB` and `ab\tcd`, so a dropped `HT` shows up as a wrong column |
| `--ez scroll true` | rows 2–3 as a `DECSTBM` region, scrolled once; rows 1 and 4 stay put |
| `--ez edit true` | `IL`, `DL`, `ICH` and `DCH`, with all four results on screen together |
| `--ez typing true` | every line it reads, numbered and measured, for the fast-typing check |
| `--ez tuiTyping true` | a curses form that logs each submitted line with its length and counts every key it consumed |
| `--ez tuiTypingLoad true` | the same form, repainting ten times a second, for typing that overlaps output |

## a11y-probe — the service the suite acts with

`ProbeService` is an `AccessibilityService` that dumps the tree of a target
package and performs actions on its nodes. `ProbeCommand` reads a command file
and writes the answer; `MainActivity` is only the settings screen to enable the
service by hand. Every check in `e2e.sh` — `ACTION_SET_TEXT`, a focus, a scroll,
a node dump — goes through this app, so nothing taps a coordinate or types with
`input text`.

## The end-to-end suite

```bash
bash samples/e2e.sh                  # every check; APKs already installed
bash samples/e2e.sh --list           # the check names
bash samples/e2e.sh --only tui-echo  # one check
bash samples/e2e.sh --quiet          # failures and the summary only
bash samples/e2e.sh --restore        # put back what a dead run left changed
```

The suite enables the probe service for the run and restores the accessibility
settings afterwards, even when a check fails. It never changes the display size:
a window is resized through the app itself. A dropped wireless-debugging session
is not a failing check — `../test/adb-connect.py` asks mDNS for the current port.

A full run prints a progress line with an estimate after every check:

```
  eta: 7/23 done, 1m02s spent, 2m05s left, 3m07s total, now tui-bg, finish 14:32:05
```

and ends with the regression gate: every check has to finish within 20% and two
seconds of the median of its last five runs (`python3 test/timings.py check`). A
shorter median than three seconds is not judged, because a two-second check on a
phone moves by more than 20% on its own. The gate is why a run can fail with
every `ok` line in place — that is a slowdown, not a broken check, and the line
names the check, its time and the allowance it broke.
