# a11y-probe

An `AccessibilityService` for device tests: it dumps the tree of the window in
front, finds nodes with `text=`, `desc=`, `id=` or `index=` selectors, performs
`ACTION_SET_TEXT`, focus, click, long click, clear, scroll, a small allowlist of
keys and gesture swipes, asserts with `expect`, waits for an event or for the
screen to go idle, observes accessibility events, and counts pixels from
screenshots or the [screen stream](https://github.com/MBanucu/screenstream). It
is what [`samples/e2e.sh`](../samples/e2e.sh) drives the
[terminal view](../terminal) with, and it is published as its own artifact so
another test suite does not have to clone this repository. Its own fixture
activity and `probe-test.sh` exercise every action without the terminal app.

- **App id:** `io.github.mbanucu.a11yprobe`
- **Version:** `1.0.0` in [`AndroidManifest.xml`](AndroidManifest.xml)
- **Commands:** `content://io.github.mbanucu.a11yprobe.control` (synchronous),
  and `io.github.mbanucu.a11yprobe.COMMAND` (broadcast, one release)
- **APK:** `out/a11y-probe-<version>.apk`, with a `.sha256` beside it

## Build

```bash
bash a11y-probe/build.sh
# out/a11y-probe-1.0.0.apk
# out/a11y-probe-1.0.0.apk.sha256
# out/a11y-probe-1.0.0-legacy.apk          (one release)
# out/a11y-probe-1.0.0-legacy.apk.sha256
```

The build needs the SDK's tools (`aapt2`, `d8`, `zipalign`, `apksigner`,
`javac`, `keytool`) and no Gradle. The screen stream is a published dependency,
taken from Maven Central and checked against the `.sha1` the repository serves;
`SCREENSTREAM_VERSION` pins it. The APKs are signed with the local debug key, so
they install with `adb install -r` on a test device and are not release-signed.

`ADB_CONNECT=0` stops the build looking for a wireless-debugging port afterwards.
`bash a11y-probe/test.sh` runs the protocol tests on the JVM, with no device.

## Use from a release

The release workflow attaches the APK, its checksum and the legacy APK to the
GitHub release. See [the main README](../README.md#the-accessibility-probe) for
`gh release download`/`curl` plus `adb install -r`, and for the [`probe.sh`](../probe.sh)
wrapper:

```bash
bash probe.sh install
bash probe.sh enable
adb shell "content call --uri content://io.github.mbanucu.a11yprobe.control --method dump --arg tree"
bash probe.sh disable
```

The answer is in `data=`; the same call also wrote
`/sdcard/Android/data/io.github.mbanucu.a11yprobe/files/tree.txt`.

`probe.sh enable` saves `enabled_accessibility_services` and
`accessibility_enabled` byte for byte before changing either, and `disable`
restores them. The saved state is on disk and `restore` repairs an interrupted
run. `status` reports the installed version, whether that is the expected
version, the probe's own version answer, and whether the service is enabled.

A harness that pins this artifact asks the installed app rather than trusting a
file name:

```bash
adb shell "content call --uri content://io.github.mbanucu.a11yprobe.control --method version"
# a11y-probe 1.0.0 (1)
```

`bash probe.sh version` does the same and exits non-zero when the installed
version differs from the manifest.

## Synchronous commands (`content call`)

The provider answers in the call, so a harness does not have to poll a file, and
a failure is a code in the answer rather than a broadcast that was accepted and
did nothing. The broadcast below still works for one release; new callers should
use this surface.

> **Quoting.** `adb shell` joins its arguments and hands the line to the device's
> shell, so the host shell's quotes are already gone. Quote the whole remote
> command (and the value inside it) when a value has a space, a backslash or a
> quote: `adb shell "content call ... --arg 'echo hi\n'"`. The plain examples
> below do not need it, the multi-line ones do.

```bash
CONTROL=content://io.github.mbanucu.a11yprobe.control

# The tree, in data= and still in files/tree.txt.
adb shell "content call --uri $CONTROL --method dump --arg tree"

# One node by selector: text=, desc=, id= or index= (a bare needle still
# searches text, description and id, as find did before this release).
adb shell "content call --uri $CONTROL --method find --arg 'text=OK'"
adb shell "content call --uri $CONTROL --method find --arg 'desc=Close'"
adb shell "content call --uri $CONTROL --method find --arg 'id=io.example:id/save'"
adb shell "content call --uri $CONTROL --method find --arg 'index=2'"

# The actions a screen reader performs, aimed at one app's window.
adb shell "content call --uri $CONTROL --method click \
    --extra pkg:s:io.github.mbanucu.samples.nativeterminal --arg 'text=OK'"
adb shell "content call --uri $CONTROL --method long_click \
    --extra pkg:s:io.example --arg 'text=Item 03'"
adb shell "content call --uri $CONTROL --method clear \
    --extra pkg:s:io.example --arg 'id=io.example:id/field'"

# SET_TEXT. A literal \n means "and press enter".
adb shell "content call --uri $CONTROL --method set_text \
    --extra pkg:s:io.github.mbanucu.samples.nativeterminal --arg 'echo hi\n'"

# Scroll for real, and stop when the selector is in the tree. Then click it:
# no `input`, no coordinates.
adb shell "content call --uri $CONTROL --method scroll \
    --extra pkg:s:io.example --arg down --extra until:s:'text=Item 40'"
adb shell "content call --uri $CONTROL --method click \
    --extra pkg:s:io.example --arg 'text=Item 40'"

# A key from the allowlist, and a gesture swipe in screen coordinates.
adb shell "content call --uri $CONTROL --method key --arg back"
adb shell "content call --uri $CONTROL --method swipe \
    --extra from:s:540,1500 --extra to:s:540,500 --extra duration:l:300"

# Assertions: code=0 or code=400, with the matched node or the closest ones.
adb shell "content call --uri $CONTROL --method expect --arg 'text_contains=saved'"
adb shell "content call --uri $CONTROL --method expect --arg 'absent=text=Error'"
adb shell "content call --uri $CONTROL --method expect \
    --extra assertion:s:text_is --arg 'text=Saved'"

# Wait until no accessibility event for 500 ms, bounded at 5 s.
adb shell "content call --uri $CONTROL --method wait_idle \
    --extra idleMs:l:500 --extra timeout:l:5000"

# Wait for an event in the call: matched, or timeout, in at most 9 s.
adb shell "content call --uri $CONTROL --method wait \
    --arg 'RESULT-' --extra timeout:l:3000"

# The compact summary inspect already wrote to files/<name>.txt.
adb shell "content call --uri $CONTROL --method inspect --arg rows"

# Version, service state, target, event and window counts.
adb shell "content call --uri $CONTROL --method status"
adb shell "content call --uri $CONTROL --method version"
```

| method | arguments | `data` |
|---|---|---|
| `dump` | `--arg <name>` (default `tree`), `--extra json:z:true` for JSON | the tree (text, or one JSON document) |
| `find` | selector on `--arg` or `--extra find:s:<selector>` | one node line with `id=`, `desc=`, `matched=<selector>` and `field=` |
| `click` | selector on `--arg` or `--extra find:s:<selector>` | `CLICK on <class> id=... desc=... text=... -> true` |
| `long_click` | selector, or the focused node when omitted | `LONG_CLICK on ... -> true` |
| `set_text` | `--arg <text>`, or `--extra text:s:<text>`; optional `--extra find:s:<selector>` | `SET_TEXT on ... -> true` |
| `clear` | optional selector; the first editable field when omitted | `CLEAR on ... -> true`, read back from the field |
| `scroll` | `--arg down|forward|up|backward`; optional `--extra until:s:<selector>`, `--extra find:s:<scrollable>`, `--extra timeout:l:<ms>` | `SCROLL <dir> on ... -> true`, or `SCROLL <dir> found <selector> after N scroll(s): ...` |
| `key` | `--arg back|home|enter` | `KEY back -> true`, or `KEY enter on ... -> true` |
| `swipe` | `--extra from:s:<x,y> --extra to:s:<x,y>`, optional `--extra duration:l:<ms>`; or `--arg x1,y1,x2,y2` | `SWIPE from=... to=... duration=... -> completed` |
| `expect` | an assertion and a selector (below) | the matched node, or `code=400` with the closest candidates |
| `wait` | `--arg <needle>`, `--extra timeout:l:<ms>`, optional `--extra event:s:<TYPE>` | `matched seq=N <event>` or `timeout after Nms for '<needle>'` |
| `wait_idle` | `--extra idleMs:l:<ms>` (default 500), `--extra timeout:l:<ms>` (default 5000) | `idle quiet=... last=...`, or `timeout ... last seq=N ...` |
| `inspect` | `--arg <name>` (default `inspect`) | the summary (`rows=`, `prompt.actions=`, ...) |
| `status` | | `authority`, `version`, `service`, `target`, `events`, `windows`, one per line |
| `version` | | `a11y-probe <version> (<code>)` |
| `result` | `--extra id:s:<uuid>` | that wait's answer, or `pending id=<uuid>` |
| `batch` | `--arg` with one command per line | one result line per command (below) |

`wait_idle` answers `state=idle` when the screen settles and `state=timeout`
(the last event in `data=`) when it never does; a timeout is not a failed
command, exactly like `wait`. It waits off the main thread in the provider, so
events can still arrive while it waits.

Every method accepts `--extra pkg:s:<app id>`; without it the command works on
the window in front. The target is process-wide, as it was for the broadcast, so
do not interleave calls that need different packages.

### Selectors

A selector names the node an action or an assertion is about:

| selector | matches |
|---|---|
| `text=OK` | a node whose text contains `OK` (case-sensitive) |
| `desc=Close` | a node whose content description contains `Close` |
| `id=io.example:id/save` | a node whose resource id contains that string |
| `index=2` | the third node in the target window's dump order (zero-based) |
| `OK` | text, description or id contains `OK`; the old `find` form, one release |

A named selector is not a regular expression: a test says what it means. A miss
answers `code=400` and names the selector it tried, followed by the closest
candidates, each with its text, description, resource id and bounds:

```text
expect exists text=Save failed: no node matches; closest: text=Save changes desc=- id=io.example:id/save bounds=0,900,1080,980 | ...
```

`find` prints the same node line the dump uses, including `id=` and `desc=`, and
adds `matched=<selector> field=<text|desc|id|index>` so the answer says which
selector and which field it used. The text dump prints `id=` and `desc=` for
every node; the JSON dump has `"id"` and `"contentDescription"`.

### Assertions

`expect` is what lets a shell suite assert without grepping a dump. It returns
the probe's ordinary `code=0` or `code=400`; a failed assertion is a failed
command, so a suite stops on it.

| assertion | `code=0` when |
|---|---|
| `exists` (default) | the selector matches at least one node |
| `absent` | the selector matches no node |
| `text_is` | a selected node's text equals the expected value exactly |
| `text_contains` | a selected node's text contains the expected value |

The expected value for the text assertions is `--extra value:s:<text>`. Without
it a `text=` or bare selector supplies it, which is the compact form:

```bash
# Some node's text contains "saved".
adb shell "content call --uri $CONTROL --method expect --arg 'text_contains=saved'"

# The status node (found by id) has exactly this text.
adb shell "content call --uri $CONTROL --method expect \
    --arg 'id=io.example:id/status' --extra assertion:s:text_is \
    --extra value:s:Saved"

# Nothing with this text is on screen.
adb shell "content call --uri $CONTROL --method expect --arg 'absent=text=Error'"
```

The compact form also works in a batch: `expect text_contains=saved` is one
command line. A success returns the matched node line; a `400` returns the
selector, the reason and the closest candidates, so a failure is readable
without a second dump.

### The reply

`content call` prints the provider's bundle:

```text
Result: Bundle[{code=0, data=a11y-probe 1.0.0 (1)}]
```

| member | type | meaning |
|---|---|---|
| `code` | int | `0`: the command did what it was asked. `400`: it did not; `data` is one sentence saying why |
| `data` | string | the payload (for a batch, one escaped result line per command) |
| `summary` | string | present when `data` is long and the log form is short (`dump`, `inspect`) |
| `state` | string | for `wait`, `wait_idle` and `result`: `matched`, `idle`, `timeout`, `started`, `pending`, `done` |

A rejected command still answers; it does not stop the probe or its service:

```bash
adb shell content call --uri $CONTROL --method click --arg no-such-node
# Result: Bundle[{code=400, data=no node matching 'no-such-node'}]
```

The bundle's members are not printed in a fixed order; parse by key. For a dump
that is too big to echo comfortably, read the file the same call writes.

### Waiting and request ids

`wait` blocks in the binder call and returns the event or the timeout. The
longest it may block is **9 seconds**; a larger `timeout` is clamped to 9000 ms,
so a caller that needs longer uses an id and `result`:

```bash
id=$(uuidgen)   # any unique string; the probe only uses it as a key
adb shell content call --uri $CONTROL --method wait \
    --arg 'RESULT-123' --extra timeout:l:9000 \
    --extra id:s:"$id" --extra async:z:true
# data=started id=<id>, state=started

adb shell content call --uri $CONTROL --method result --extra id:s:"$id"
# data=pending id=<id>, state=pending   ... then:
# data=matched seq=42 <event>, state=done
```

Without `async`, `wait` answers matched or timeout in the call and, when an id
is given, also stores that answer under it. Two waits with different ids never
share an answer; the old `files/wait.txt` is still written for a harness that
polls it, and is the only part of the id path that is shared.

An id is single-use: reserving one that is already known answers `code=400`, and
a completed answer stays readable until the probe has to forget it (it keeps the
64 most recent ids, oldest first). Generate a fresh id per wait rather than
reusing one.

A timeout is not a failure: it answers `code=0` with `data=timeout ...` and
`state=timeout`. A missing service, unknown method, bad argument or an action
the node refused answers `code=400`.

### Batch

One call, several commands, in order, one result line each:

```bash
adb shell "content call --uri content://io.github.mbanucu.a11yprobe.control \
    --method batch --extra pkg:s:io.github.mbanucu.samples.nativeterminal \
    --arg 'find OK
click OK
wait done timeout=3000'"
```

A line is `METHOD [KEY=VALUE ...]`; the text before the first `key=` is the
positional argument, so `find OK` and `find text=OK` are the same, and a value
keeps its spaces until the next `key=`. Blank lines and `#` comments are
skipped. The batch's `pkg`, `json`, `timeout`, `waitMs` and `event` apply to a
line that does not set them.

Each answer line is:

```text
<number> code=<0|400> data=<payload with \n and \\ escaped> [state=<state>]
```

A line that fails answers `400` and the next command still runs, so a batch of
three always has three lines; the batch's own `code` is `400` if any line was.
A `wait` line is anchored to the sequence number the batch began at, not to the
moment the line runs, so `click` followed by `wait` still sees the event the
click caused. For an action that comes *after* the wait, give the wait
`--extra`-style `id=<id>` and `async=true`, then read `result id=<id>` in a later
call (a batch returns before a background wait has finished).
Keep a batch to commands whose answers are small: a dump already returns up to
the reply limit on its own, so give each dump its own call.

### JSON dump

`--extra json:z:true` turns `dump` into one JSON document, written to
`files/<name>.json` and returned in `data` on a single line:

```bash
adb shell content call --uri $CONTROL --method dump --arg e2e-tui \
    --extra json:z:true
adb shell cat /sdcard/Android/data/io.github.mbanucu.a11yprobe/files/e2e-tui.json \
    | python3 -c 'import json, sys; json.load(sys.stdin)'
```

```json
{
  "package": "io.github.mbanucu.a11yprobe",
  "windows": 3,
  "truncated": false,
  "nodes": [
    {
      "text": "hello",
      "contentDescription": null,
      "class": "android.widget.TextView",
      "bounds": {"left": 0, "top": 10, "right": 1080, "bottom": 60},
      "actions": ["CLICK", "A11Y_FOCUS"],
      "editable": false,
      "clickable": true,
      "focusable": true,
      "focused": false,
      "id": "io.example:id/row",
      "depth": 1
    }
  ]
}
```

`nodes` is the schema a test usually needs; `package` is the probe's own
package, as in the text dump's `package=` line, and `windows` is the window count
at the moment of the dump. `truncated` says the reply limit was reached: a JSON
reply is capped at 300,000 characters so it fits a binder transaction, and when
a node does not fit the array stops there. The file holds exactly the same
document (the cap is the dump's, not the transport's). Text remains the default.

### Migration from the broadcast

| old broadcast and file | provider call |
|---|---|
| `--es dump tree`, then `cat files/tree.txt` | `--method dump --arg tree`, `data` (file still written) |
| `--es dump tree --ez json true` | `--method dump --arg tree --extra json:z:true` |
| `--es inspect rows`, then `cat files/rows.txt` | `--method inspect --arg rows`, `data` (file still written) |
| `--es find X --es text Y` | `--method set_text --extra find:s:'text=X' --extra text:s:'Y\n'` |
| `--ez click true --es find X` | `--method click --arg 'text=X'` |
| `--ez longClick true --es find X` | `--method long_click --arg 'text=X'` |
| `--ez clear true --es find X` | `--method clear --arg 'text=X'` |
| `--es scroll down --es until X` | `--method scroll --arg down --extra until:s:'text=X'` |
| `--es key back` | `--method key --arg back` |
| `--es swipe x1,y1,x2,y2` | `--method swipe --extra from:s:x1,y1 --extra to:s:x2,y2` |
| `--es expect text_contains --es find X` | `--method expect --arg 'text_contains=X'` (answer in `files/expect.txt` for a broadcast) |
| `--el waitIdle 500`, then tail `files/idle.txt` | `--method wait_idle --extra idleMs:l:500` |
| `--es wait X --el waitMs N`, then tail `files/wait.txt` | `--method wait --arg X --extra timeout:l:N` (or `id` + `result`) |
| `--es pixels NAME ...`, then poll `files/NAME.txt` | unchanged: a pixel watch is asynchronous by nature |
| `--es version true`, then `cat files/version.txt` | `--method status` (or `--method version`) |

[`samples/e2e.sh`](../samples/e2e.sh) now sends `dump` and `inspect` through the
provider; its wait, text, click and pixel helpers still use the broadcast in this
release, because they arm a wait and trigger the action in one adb shell. The
broadcast action and the old file names remain for one release.

## The probe's own fixture and `probe-test.sh`

Every action can be checked without the terminal app: the APK contains
`ProbeFixtureActivity`, a screen with the three shapes an e2e suite needs.

| part | id | what it exercises |
|---|---|---|
| status | `...:id/fixture_status` | `text=`, `text_is`, `text_contains`, and status changes |
| text field | `...:id/fixture_field` | `set_text`, `clear`, `enter` (IME action), input focus |
| Save button | `...:id/fixture_save` | `click`, and the editor action's result |
| dialog button | `...:id/fixture_dialog_button` | a dialog's text, and `back` dismissing it |
| lazy list | `...:id/fixture_list` | `scroll until=`, `swipe`, `long_click`, row `click` |
| rows | `...:id/fixture_row` | `text=`, `desc=`, `id=` and `index=` selectors |

The list starts with twenty rows and adds twenty more when scrolled near the
bottom, so `scroll --arg down --extra until:s:'text=Item 40'` has to scroll to
find its node; a row that was already in the tree would not test scrolling.

```bash
bash a11y-probe/build.sh
bash a11y-probe/probe-test.sh --list     # the checks, offline
bash a11y-probe/probe-test.sh            # needs a test device
```

`probe-test.sh` installs the APK, enables the service through `probe.sh`, starts
the fixture and runs: `fixture`, `dump`, `find-text`, `find-desc`, `find-id`,
`find-index`, `expect-exists`, `expect-absent`, `expect-text-is`,
`expect-text-contains`, `expect-failure` (the `400` with candidates), `clear`,
`enter`, `long-click`, `scroll`, `swipe`, `dialog-back`, `wait-idle`,
`wait-idle-timeout` and `key-home`. It restores the accessibility setting in its
`EXIT` trap. It is device-only: it is not part of `a11y-probe/test.sh` and is not
run where there is no phone.

## What the probe deliberately cannot do

- **System dialogs and other apps' secure windows.** A service reads the
  accessibility tree, not the framebuffer's secrets. Windows marked secure (some
  password fields, banking apps, `FLAG_SECURE` screens) expose no text and no
  actionable nodes; permission dialogs belong to the system UI. The one system
  dialog the probe handles is the screen-capture consent it needs for the stream,
  through its own `setDialogClicker`.
- **Arbitrary keycodes and input methods.** `key` is an allowlist (`back`,
  `home`, `enter`), not `input keyevent`. `enter` is ACTION_IME_ENTER on the
  focused node; a node that does not expose it answers `400`.
- **Coordinate taps.** There is no "tap at x,y" action; `swipe` is a gesture, and
  click/long click act on a node. If a screen exposes no accessibility node, the
  probe cannot find it to tap.
- **Waiting longer than one binder call.** `wait` and `wait_idle` answer within
  **9 seconds**; a longer wait needs an id and `result` (for `wait`).
- **Bypassing the service toggle.** With the accessibility service disabled the
  provider still answers `status` and `version`, and everything else is a `400`
  saying the service is not running. The provider is not a second switch.

## Layout

| path | what it is |
|---|---|
| `AndroidManifest.xml` | app id, version, the service, the provider, the command receiver |
| `res/xml/probe.xml` | the accessibility service's capabilities |
| `src/io/github/mbanucu/a11yprobe/ProbeService.java` | the service: dumps, actions, events, waits |
| `src/io/github/mbanucu/a11yprobe/ProbeProvider.java` | the synchronous `content call` surface |
| `src/io/github/mbanucu/a11yprobe/ProbeCommand.java` | the broadcast commands, kept for one release |
| `src/io/github/mbanucu/a11yprobe/ProbeFixtureActivity.java` | the built-in fixture screen: list, field, dialog |
| `src/io/github/mbanucu/a11yprobe/ProbeSelector.java` | `text=`, `desc=`, `id=`, `index=` and candidate ranking |
| `src/io/github/mbanucu/a11yprobe/ProbeExpect.java` | the assertions and their `0`/`400` verdicts |
| `src/io/github/mbanucu/a11yprobe/ProbeArgs.java` | argument fallbacks and the safe file name |
| `src/io/github/mbanucu/a11yprobe/ProbeBatch.java` | the batch line grammar and its result lines |
| `src/io/github/mbanucu/a11yprobe/ProbeJson.java` | the JSON dump |
| `src/io/github/mbanucu/a11yprobe/ProbeNode.java` | one node, free of `android.*` |
| `src/io/github/mbanucu/a11yprobe/ProbeReply.java` | code `0`/`400` and the payload |
| `src/io/github/mbanucu/a11yprobe/ProbeResults.java` | request-id answers for asynchronous waits |
| `src/io/github/mbanucu/a11yprobe/PixelWatch.java` | screenshot and stream pixel checks |
| `src/io/github/mbanucu/a11yprobe/MainActivity.java` | the settings screen, for enabling by hand |
| `src/io/github/mbanucu/samples/a11yprobe/` | one-release component aliases for the old app id |
| `test.sh`, `test/java/` | the protocol tests, on the JVM |
| `probe-test.sh` | the device-only fixture suite for every action |

## Tests

`bash a11y-probe/test.sh` compiles the classes that import no `android.*` and
runs the protocol checks with a plain `javac`, like `terminal/test.sh`: the
selector parser and its candidate ranking, every `expect` verdict (including the
`400` sentences), the node brief/describe encodings, JSON escaping and the node
schema, the batch grammar and result lines, the argument fallbacks, and the
`0`/`400` replies with the request-id bookkeeping. The JSON sample is also parsed
by `python3`'s `json.loads` when Python is available. No device, Gradle, SDK or
test framework is needed.

`bash a11y-probe/probe-test.sh` is the other half: it needs a device and runs
every action against the fixture screen. `bash samples/e2e.sh --list` names the
terminal suite's checks; that run needs a phone too.

## Security boundary

An accessibility service can read every window on the phone and act on its
nodes. This one is a test tool:

- use a test device, not a phone with someone's real accounts on it;
- it writes only under its own external files directory,
  `/sdcard/Android/data/io.github.mbanucu.a11yprobe/files`, and the file-name
  argument is cleaned so it cannot name a path outside it;
- enable it for a run and disable it afterwards (`bash probe.sh disable`), which
  also puts back a screen reader that was on before;
- the provider is exported with **no permission**, exactly like the command
  receiver: while the accessibility service is enabled, any app on the device can
  read the tree and perform the actions a screen reader can. That is the same
  boundary the broadcast has always had, and it is why this belongs on a test
  device; uninstall the probe when it is not needed. The accessibility service
  being enabled, not the provider, is the switch.
