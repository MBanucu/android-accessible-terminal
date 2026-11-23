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
- **Commands:** `io.github.mbanucu.a11yprobe.COMMAND`
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

## Use from a release

The release workflow attaches the APK, its checksum and the legacy APK to the
GitHub release. See [the main README](../README.md#the-accessibility-probe) for
`gh release download`/`curl` plus `adb install -r`, and for the [`probe.sh`](../probe.sh)
wrapper:

```bash
bash probe.sh install
bash probe.sh enable
adb shell am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es dump tree
adb shell cat /sdcard/Android/data/io.github.mbanucu.a11yprobe/files/tree.txt
bash probe.sh disable
```

`probe.sh enable` saves `enabled_accessibility_services` and
`accessibility_enabled` byte for byte before changing either, and `disable`
restores them. The saved state is on disk and `restore` repairs an interrupted
run. `status` reports the installed version, whether that is the expected
version, the probe's own version answer, and whether the service is enabled.

A harness that pins this artifact asks the installed app rather than trusting a
file name:

```bash
adb shell am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es version true
adb shell cat /sdcard/Android/data/io.github.mbanucu.a11yprobe/files/version.txt
# a11y-probe 1.0.0 (1)
```

`bash probe.sh version` does the same and exits non-zero when the installed
version differs from the manifest.

## Layout

| path | what it is |
|---|---|
| `AndroidManifest.xml` | app id, version, the service, the command receiver |
| `res/xml/probe.xml` | the accessibility service's capabilities |
| `src/io/github/mbanucu/a11yprobe/ProbeService.java` | the service: dumps, actions, events, waits |
| `src/io/github/mbanucu/a11yprobe/ProbeCommand.java` | the broadcast commands, including `version` |
| `src/io/github/mbanucu/a11yprobe/PixelWatch.java` | screenshot and stream pixel checks |
| `src/io/github/mbanucu/a11yprobe/MainActivity.java` | the settings screen, for enabling by hand |
| `src/io/github/mbanucu/samples/a11yprobe/` | one-release component aliases for the old app id |

## Security boundary

An accessibility service can read every window on the phone and act on its
nodes. This one is a test tool:

- use a test device, not a phone with someone's real accounts on it;
- it writes only under its own external files directory,
  `/sdcard/Android/data/io.github.mbanucu.a11yprobe/files`;
- enable it for a run and disable it afterwards (`bash probe.sh disable`), which
  also puts back a screen reader that was on before.
