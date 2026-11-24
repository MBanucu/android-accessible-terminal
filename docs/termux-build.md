# Building the Gradle artifacts on Termux

This is the on-device counterpart to the Gradle job in
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml): what a fresh checkout
needs on Termux, how the wrapper and versions are pinned, and which warnings are
upstream. The Gradle-free builds (`terminal/test.sh`, `samples/build.sh`) still
work with nothing but a JDK; this page is about `build.gradle.kts`, which adds
the AAR, `publishToMavenLocal` and the Maven Central bundle.

## What was verified here

Verified on 2026-11-24 on Termux aarch64 / Android 15:

| item | value |
|---|---|
| Gradle | 9.9.2 (wrapper; `pkg install gradle` ships the same version) |
| AGP | 9.5.2 (from `gradle/libs.versions.toml`) |
| Nmcp | 1.6.2 (from `gradle/libs.versions.toml`) |
| JDK | OpenJDK 21.0.13 |
| SDK | `$HOME/android-sdk`, platform 35, build-tools 36.0.0 |
| aapt2 | Termux 2.20-android-16.0.0_r4 via `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` |
| `bash scripts/termux-build.sh` | BUILD SUCCESSFUL, `build/outputs/aar/android-accessible-terminal-release.aar` |
| `assembleRelease --configuration-cache` | BUILD SUCCESSFUL, entry stored then reused |
| `publishToMavenLocal --configuration-cache` | BUILD SUCCESSFUL, AAR/POM/module/sources/javadoc in `~/.m2` |
| `nmcpZipAggregation --configuration-cache` | BUILD SUCCESSFUL, aggregation zip stored and reused |
| signing | a throwaway PGP key produced a `.asc` beside every published file |

## A fresh checkout

### 1. JDK

```bash
pkg install openjdk-21
java -version      # 17 or newer; 21 is what this project is tested with
```

The Gradle wrapper downloads Gradle 9.9.2, so a separate `gradle` package is
optional. If you have one, `gradle --version` must be 9.6 or newer for AGP 9.x.

### 2. Android SDK

The one-command build below resolves `$HOME/android-sdk` and works without any
`local.properties` edit. To configure it yourself, either export the SDK:

```bash
export ANDROID_HOME="$HOME/android-sdk"
api=$(bash scripts/versions.sh compileSdk)
build_tools=$(bash scripts/versions.sh buildTools)
sdkmanager "platforms;android-$api" "build-tools;$build_tools"
```

or copy the template and edit the path:

```bash
cp local.properties.example local.properties
$EDITOR local.properties        # sdk.dir=/data/data/com.termux/files/home/android-sdk
```

`local.properties` is machine-specific, ignored by git and must never be
committed. `ANDROID_HOME`/`ANDROID_SDK_ROOT` win when both are present. When
neither the environment nor `local.properties` names an SDK, the settings
script falls back to `$HOME/android-sdk`; if that does not exist it fails with
one message before AGP's generic "SDK location not found":

```
Android SDK not found.

Set ANDROID_HOME to an SDK directory, or copy local.properties.example
to local.properties and set sdk.dir:

    export ANDROID_HOME="$HOME/android-sdk"

On Termux, the one-command build resolves $HOME/android-sdk and
Termux's bionic aapt2 for you:

    bash scripts/termux-build.sh

See docs/termux-build.md for the full setup.
```

### 3. aapt2

AGP's aapt2 downloads are glibc builds; Termux uses bionic. Point AGP at
Termux's aapt2 in the **user** Gradle properties, not in this repository:

```bash
cat >> ~/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
EOF
```

`scripts/termux-build.sh` passes the same property on the command line when it
finds `$PREFIX/bin/aapt2`, so a one-off build works even without that line.

### 4. Build

```bash
bash scripts/termux-build.sh
```

That resolves the SDK and aapt2, runs `checkBuildEnv`, and then
`assembleRelease` with the configuration and build caches. To run the same tasks
by hand:

```bash
source scripts/termux-env.sh
./gradlew checkBuildEnv assembleRelease
```

`checkBuildEnv` checks JDK >= 17, the SDK directory, the
`platforms/android-<compileSdk>/android.jar`, the `build-tools/<buildTools>`
directory, and the aapt2 override when a Termux prefix is detected. It is wired
into `preBuild`, so a missing platform or a stale override fails early with one
block of problems instead of a stack trace.

The first build on a new machine needs network for AGP and its dependencies;
`OFFLINE=0 bash scripts/termux-build.sh` allows that. After the dependency cache
is warm, the script's default `--offline` is the normal path.

Termux's Gradle daemon cannot adopt a client's environment (Gradle reports that
there is no native integration), so a daemon started before `ANDROID_HOME` was
exported will not see it. The script therefore passes `--no-daemon` when there
is no `local.properties`. With `local.properties`, the daemon is fine.

The smoke script also runs on a self-hosted CI runner. Set the repository
variable `TERMUX_RUNNER=true` once a runner labelled `self-hosted, termux` is
registered; the `termux` job in `ci.yml` is skipped until then.

## Versions live in one place

`gradle/libs.versions.toml` owns:

| key | used by |
|---|---|
| `agp` | the `android-library` plugin alias |
| `nmcp` | both Nmcp plugin aliases |
| `compileSdk`, `minSdk`, `buildTools` | Gradle, CI's `sdkmanager`, `scripts/versions.sh` |
| `java` | the AAR's compile options |
| `projectVersion` | Gradle; `-PVERSION_NAME` and `-Pversion` override it |

The file has no literal versions in `build.gradle.kts`; plugin aliases are
`libs.plugins.android.library`, `libs.plugins.nmcp` and
`libs.plugins.nmcp.aggregation`. `scripts/versions.sh` reads the same file from
shell, so the SDK level CI installs cannot drift from the one Gradle compiles
against.

## Wrapper and dependency management

`gradle/wrapper/gradle-wrapper.properties` pins both the distribution URL and
its SHA-256:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.2-bin.zip
distributionSha256Sum=971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b
```

That hash was checked against
`https://services.gradle.org/distributions/gradle-9.9.2-bin.zip.sha256` and
matches the distribution already used by the sibling projects.
`gradle/actions/wrapper-validation@v4` in CI checks the checked-in
`gradle-wrapper.jar` against Gradle's published checksums, so neither half of
the wrapper can change silently.

This library has **no runtime dependencies**: the AAR is `java.*` and
`android.jar`, and its POM's `<dependencies>` is empty. AGP, Nmcp and the Kotlin
tooling are build-time plugins, pinned by `gradle/libs.versions.toml` and
resolved from `google()`/`mavenCentral()` in `settings.gradle.kts` with
`RepositoriesMode.FAIL_ON_PROJECT_REPOS`. A `gradle/verification-metadata.xml`
would lock a plugin classpath whose versions are already pinned; the wrapper
checksum plus CI wrapper validation is the boundary that matters for a
dependency-free library. If runtime dependencies are added, enable Gradle
dependency verification (`--write-verification-metadata sha256`) at that point
rather than carrying an empty file now.

## Configuration and build caches

Both are enabled in `gradle.properties`:

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
```

They cover the build and the release tasks:

| command | result |
|---|---|
| `./gradlew --offline assembleRelease` | entry stored, then reused |
| `./gradlew --offline publishToMavenLocal -Pversion=0.0.0-smoke` | entry stored, publication written |
| `./gradlew --offline nmcpZipAggregation` | entry stored, then reused |

`android.builtInKotlin=false` is deliberate. This is a Java-only library, but
AGP 9 otherwise wires its built-in Kotlin support into every variant. Turning it
off keeps the task graph Java-only. Re-enable it if Kotlin sources are ever
added.

## Publishing smoke test

Nothing here uploads. `publishToMavenLocal` writes under `~/.m2`:

```bash
source scripts/termux-env.sh
./gradlew --offline publishToMavenLocal -Pversion=0.0.0-smoke
ls ~/.m2/repository/io/github/mbanucu/android-accessible-terminal/0.0.0-smoke/
rm -rf ~/.m2/repository/io/github/mbanucu/android-accessible-terminal/0.0.0-smoke
```

`nmcpZipAggregation` builds the exact bundle the release workflow uploads.
CI runs `publishToMavenLocal` and a signed `nmcpZipAggregation` with a throwaway
key on every push, so both release paths are checked without a repository
secret. With `SIGNING_KEY` unset the bundle has no signatures; to exercise
signing locally, use a throwaway key and never pass the bundle to the portal:

```bash
temp_gpg=$(mktemp -d)
GNUPGHOME="$temp_gpg" gpg --batch --passphrase '' --quick-generate-key \
    'Build Smoke <smoke@example.invalid>' ed25519 sign 1d
export SIGNING_KEY="$(GNUPGHOME="$temp_gpg" gpg --armor --export-secret-keys)"
export SIGNING_PASSWORD=''
./gradlew --offline nmcpZipAggregation -PVERSION_NAME=0.0.0-sign-smoke
unzip -l build/nmcp/zip/aggregation.zip | grep '\.asc$'
rm -rf "$temp_gpg"
```

The aggregation zip contains the AAR, POM, Gradle module metadata, sources jar,
javadoc jar and their checksums. With a key it also contains an `.asc` beside
each file. `RELEASING.md` covers the real upload.

## Warnings left upstream

`./gradlew --offline assembleRelease --warning-mode all` is clean for this
repository's Gradle deprecations. What remains:

- **`android.aapt2FromMavenOverride` is experimental.** It is the documented
  Termux workaround; `android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE`
  suppresses only that notice. If AGP ships a bionic aapt2, remove both.
- **Java API deprecation notes from `javac`.** Those are Android API-level
  compatibility choices for `minSdk 24`, not Gradle deprecations. Fixing them
  needs version-guarded replacements, a source change separate from this build
  hardening.

## Troubleshooting

| symptom | fix |
|---|---|
| `Android SDK not found` | export `ANDROID_HOME`, set `sdk.dir`, or run `bash scripts/termux-build.sh` |
| `Android platform 35 is not installed` | `sdkmanager "platforms;android-$(bash scripts/versions.sh compileSdk)"` |
| `aapt2 ... not executable` | `chmod +x $PREFIX/bin/aapt2`, or fix the override path |
| aapt2 exits with a loader error | the override points at a glibc build; use Termux's bionic `aapt2` |
| `No cached version ... available for offline mode` on a first build | run once with `OFFLINE=0 bash scripts/termux-build.sh` |
| `SDK location not found` although `ANDROID_HOME` is exported | the Gradle daemon did not inherit it; use the script, add `local.properties`, or pass `--no-daemon` |
| configuration cache "cannot be reused" after editing a build file | expected; the next run stores a new entry |
