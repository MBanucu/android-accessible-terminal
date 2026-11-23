# a11y-probe

An `AccessibilityService` for device tests: it dumps the tree of the window in
front, finds nodes by text, performs `ACTION_SET_TEXT`, focus, click and scroll,
observes accessibility events, and counts pixels from screenshots or the
[screen stream](https://github.com/MBanucu/screenstream). It is what
[`samples/e2e.sh`](../samples/e2e.sh) drives the
[terminal view](../terminal) with, and it is published as its own artifact so
another test suite does not have to clone this repository.

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

# One node matching a needle (text, description, hint or view id).
adb shell "content call --uri $CONTROL --method find --arg 'OK'"

# The action a screen reader performs, aimed at one app's window.
adb shell "content call --uri $CONTROL --method click \
    --extra pkg:s:io.github.mbanucu.samples.nativeterminal --arg 'OK'"

# SET_TEXT. A literal \n means "and press enter".
adb shell "content call --uri $CONTROL --method set_text \
    --extra pkg:s:io.github.mbanucu.samples.nativeterminal --arg 'echo hi\n'"

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
| `find` | `--arg <needle>`, or `--extra find:s:<needle>` | one node line: class, text, desc, bounds, actions, editable, clickable |
| `click` | `--arg <needle>`, or `--extra find:s:<needle>` | the `CLICK on ... -> true` line |
| `set_text` | `--arg <text>`, or `--extra text:s:<text>`; optional `--extra find:s:<node>` | the `SET_TEXT on ... -> true` line |
| `wait` | `--arg <needle>`, `--extra timeout:l:<ms>`, optional `--extra event:s:<TYPE>` | `matched seq=N <event>` or `timeout after Nms for '<needle>'` |
| `inspect` | `--arg <name>` (default `inspect`) | the summary (`rows=`, `prompt.actions=`, ...) |
| `status` | | `authority`, `version`, `service`, `target`, `events`, `windows`, one per line |
| `version` | | `a11y-probe <version> (<code>)` |
| `result` | `--extra id:s:<uuid>` | that wait's answer, or `pending id=<uuid>` |
| `batch` | `--arg` with one command per line | one result line per command (below) |

Every method accepts `--extra pkg:s:<app id>`; without it the command works on
the window in front. The target is process-wide, as it was for the broadcast, so
do not interleave calls that need different packages.

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
| `state` | string | for `wait` and `result`: `matched`, `timeout`, `started`, `pending`, `done` |

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
| `--es find X --es text Y` | `--method set_text --extra find:s:X --extra text:s:'Y\n'` |
| `--ez click true --es find X` | `--method click --arg X` |
| `--es wait X --el waitMs N`, then tail `files/wait.txt` | `--method wait --arg X --extra timeout:l:N` (or `id` + `result`) |
| `--es pixels NAME ...`, then poll `files/NAME.txt` | unchanged: a pixel watch is asynchronous by nature |
| `--es version true`, then `cat files/version.txt` | `--method status` (or `--method version`) |

[`samples/e2e.sh`](../samples/e2e.sh) now sends `dump` and `inspect` through the
provider; its wait, text, click and pixel helpers still use the broadcast in this
release, because they arm a wait and trigger the action in one adb shell. The
broadcast action and the old file names remain for one release.

## Layout

| path | what it is |
|---|---|
| `AndroidManifest.xml` | app id, version, the service, the provider, the command receiver |
| `res/xml/probe.xml` | the accessibility service's capabilities |
| `src/io/github/mbanucu/a11yprobe/ProbeService.java` | the service: dumps, actions, events, waits |
| `src/io/github/mbanucu/a11yprobe/ProbeProvider.java` | the synchronous `content call` surface |
| `src/io/github/mbanucu/a11yprobe/ProbeCommand.java` | the broadcast commands, kept for one release |
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

## Tests

`bash a11y-probe/test.sh` compiles the six classes that import no `android.*` and
runs the protocol checks with a plain `javac`, like `terminal/test.sh`: JSON
escaping and the node schema, the batch grammar and result lines, the argument
fallbacks, and the `0`/`400` replies with the request-id bookkeeping. The JSON
sample is also parsed by `python3`'s `json.loads` when Python is available. No
device, Gradle, SDK or test framework is needed.

`bash samples/e2e.sh --list` names the device checks; the run itself needs a
phone.

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
