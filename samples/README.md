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
| `--ez colors true` | `\e[31m`, `\e[32m`, `\e[1;34m` and `\e[38;5;208m`; the probe also counts red, green and blue pixels on the device, because rows alone pass even in default grey |
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

It also takes the screenshots the pixel checks assert on (`PixelWatch`): the
service declares `canTakeScreenshot`, and a `--es pixels` command starts a watch
that answers in its own file. The platform allows one screenshot every 333 ms,
so a watch paces itself just above that, drops every frame that changed nothing,
and stops the moment a frame holds what was asked for. With the screen stream
running, a watch skips the screenshots and subscribes to the stream's changed
frames instead, which is every change rather than three a second; see below.

### The screen stream

The stream is not part of this app: it is its own repository,
[`screenstream`](https://github.com/MBanucu/screenstream), released to Maven
Central and taken from there. `samples/build.sh` fetches the published AAR —
`SCREENSTREAM_VERSION` pins the version, the `classes.jar` inside it goes on the
classpath and to `d8`, and the download is checked against the `.sha1` the
repository serves. So a sample builds against what a dependency would actually
get, and a change to the library has to be published before a sample can use it.
(The repository used to be checked in here as a submodule; once the build took the
artifact, that second copy was a pin that could disagree with the version being
compiled, so it is gone.) The library's `ScreenStreamActivity` asks the system for
the display once, and its
`ScreenStream` service serves it as a raw stream with no encoder and no container
in the way: RGBA_8888 frames straight from an `ImageReader`, numbered, timed on the
device, and sent only when the screen changes. Anything on the phone attaches to
`127.0.0.1:7100`; adb reaches the same port through a forward.

```bash
# Consent. The app-op is the quiet way and needs no screen reader:
adb shell appops set io.github.mbanucu.samples.a11yprobe PROJECT_MEDIA allow
adb shell am start -n io.github.mbanucu.samples.a11yprobe/io.github.mbanucu.screenstream.ScreenStreamActivity
# Or let the probe accept the system dialog itself, as a screen reader would:
# with the probe enabled, start the same activity and it clicks "Start".

adb forward tcp:7100 tcp:7100                 # for anything on the other end
# The client is the library's own reference consumer, in its repository:
python3 ../screenstream/client/stream.py --seconds 5          # frames, their shape
python3 ../screenstream/client/stream.py --frames 3 --png samples/out/stream
```

A frame is a 36-byte big-endian header — `RAW1`, width, height, row stride,
format, frame number, timestamp in nanoseconds — then `stride * height` bytes of
RGBA. The timestamp is the compositor's presentation time on the device's elapsed
clock, the clock the recording's per-frame metadata uses, so a frame here and a
frame in the video are comparable without an offset; on this phone the frames
matched `screencap` pixel for pixel (4,608,000 of them, none different).

The stream is VFR in the same sense the recording is: the compositor offers
frames at the display's rate either way, and only frames whose pixels changed are
numbered and sent. A still screen sends nothing, a blinking caret sends two
frames, and the gaps between frame numbers are idle time rather than lost frames.
Motion is around ten frames a second here, with gaps from 50 to 170 ms.

It is raw, so it is big: 18.4 MB a frame at native resolution (1440x3200).
`--ei scale 2` hands the frames over at half size instead (720x1600, 4.6 MB),
composited at that size rather than downscaled afterwards. A consumer that falls
behind gets the newest frame and older ones are dropped — `frames_skipped` in
`files/screenstream.txt` counts them — and a consumer that stops reading is
disconnected after two seconds. That is the trade a raw stream makes: the screen
never waits for a slow reader, and memory stays in a pool of three frames.

An app in this process can attach without a socket: the pixel watch does, which is
what `STREAM=1 bash samples/e2e.sh` turns on. Every pixel check then judges every
changed frame instead of a screenshot every 333 ms, and the frame number and time
in its answer are the source's — the same numbers a consumer on the socket sees,
so `python3 test/capture.py frames --event pixel:tui-blink:pass` still points at
the right picture in the recording. It also judges the screen it starts on, once,
from a screenshot: a stream says what changed, and a check that begins while the
app is already drawn (a caret that does not blink) would otherwise have nothing to
look at. That first frame carries the number of the last frame the source sent,
which is still what the display shows. A capture started this way is stopped with
`am start -n io.github.mbanucu.samples.a11yprobe/io.github.mbanucu.screenstream.ScreenStreamActivity --ez stop true`;
the app-op can then go back to `default`. A tap holds the same frame as the sockets (the
buffer goes back to the pool when the last holder lets go), so a watch and a
consumer can read at once; `taps` in `files/screenstream.txt` counts them. The default
run does not start the stream, because the screenshots need no screen-capture
consent and the stream does.

`VIDEO=1 STREAM=1 bash samples/e2e.sh` (or `--video`, which turns the stream on
itself) records the run from those same frames instead of `screenrecord`'s own
capture, into `samples/out/video.mp4`. The file's frame numbers are not the
stream's — it starts wherever the capture was, and drops frames the encoder was
busy for — but its times are, and `files/screenstream.txt` says what the file's
zero is (`recording_from_ns`), so the suite knows where a check's frame is in it.
It checks that the file reaches the last frame a check passed on, that the phone's
own frame count is the one `ffprobe` reads, and prints how far into the video the
last pass was. On a full run that is 390 frames over 137 s from 1248 changed
frames, so this recording is evidence of what was on the stream rather than a
smooth video: `capture.py`'s is the smooth one.

## The end-to-end suite

```bash
bash samples/e2e.sh                  # every check; APKs already installed
bash samples/e2e.sh --list           # the check names
bash samples/e2e.sh --only tui-echo  # one check
bash samples/e2e.sh --quiet          # failures and the summary only
bash samples/e2e.sh --no-record      # no recording: the fast path
bash samples/e2e.sh --restore        # put back what a dead run left changed
```

The suite enables the probe service for the run and restores the accessibility
settings afterwards, even when a check fails. It never changes the display size:
a window is resized through the app itself. A dropped wireless-debugging session
is not a failing check — `../test/adb-connect.py` asks mDNS for the current port.

### The recording

A run is recorded once, from the first check to the last, into
`samples/out/capture/recording.mp4`, and every check writes an event beside it.
No screenshot is taken during a run at all: the pixel checks watch the screen on
the device (below), and a frame is pulled out of the video when somebody asks for
one.

```bash
python3 test/capture.py show                      # every event, and its frame
python3 test/capture.py frames --event check:tui-blink:end --out samples/out/frames
python3 test/capture.py frames --at 12.5,40 --out samples/out/frames
python3 test/capture.py frames --all --out samples/out/frames
```

A failed check's frames are extracted at the end of the run by themselves, into
`samples/out/frames/`, because a failure is when a picture is worth having.

The mapping is exact rather than approximate: `screenrecord` writes the device
monotonic presentation time of every frame into a metadata track beside the video
(`writeWinscopeMetadata`), and `test/capture.py` reads that track out of the MP4
and pairs it with the container's pts. An event becomes the last frame presented
at or before it, which is the picture that was on screen. Nothing is assumed
about the frame rate: `screenrecord` writes a frame only when the screen changes,
so a still screen is a long gap and not a missing frame. The video is kept exactly
as `screenrecord` wrote it: its metadata track carries the frame times, so the
recording explains its own timing and nothing has to be remuxed. The one measured
number is the offset between this machine's clock and the device's, sampled at the
start and the end; on this phone the samples agree to 2-8 ms with a 20-28 ms round
trip, a frame being 33 ms at 30 fps.

The pixel checks do not read this video, and no pixels cross adb to make them
pass. The probe takes the frames itself - a screenshot through the accessibility
API, or, with `STREAM=1`, the stream's own frames - numbers only the ones where
the screen changed, counts their pixels on the phone, and answers with the frame
number and the device time of the first frame that held what the check asked for. Both are on the phone's clock, so the pass is
also an event — `pixel:colour:pass` — that `frames` takes out of the recording
with no clock offset in the way. What crosses the wire is the answer, a few lines
of text, not a 1440x3200 PNG. A watch only evaluates frames of the app under
test, so a colour left on screen by the previous check cannot pass the next one.

The video stays on the device (`/data/local/tmp/suite-capture.mp4`) until the run
is over: `stop` interrupts the recorder, waits for it to exit so the file is
finalized, and only then pulls it — 21 MB in about 0.2 s over adb. A whole `stop`
is about 1.4 s. A recording costs about 19 s on a 121 s suite, and 13 s of that is
traced to individual checks, where the encoder shares the device's CPU;
`--no-record` is the fast path for a run that only needs the verdict.

### What a run prints

A full run prints a progress line with an estimate after every check:

```
  eta: 7/23 done, 1m02s spent, 2m05s left, 3m07s total, now tui-bg, finish 14:32:05
```

and ends with the regression gate: every check has to finish within 20% and two
seconds of the median of its last five runs (`bash test/timings.sh check`). A
shorter median than three seconds is not judged, because a two-second check on a
phone moves by more than 20% on its own. The gate is why a run can fail with
every `ok` line in place — that is a slowdown, not a broken check, and the line
names the check, its time and the allowance it broke.
