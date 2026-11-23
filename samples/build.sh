#!/usr/bin/env bash
# Builds one sample app into samples/out/<app>.apk without Gradle.
#
#   bash samples/build.sh native-terminal
#   bash test/local-build.sh --samples   # builds them all, then runs their checks
#   bash samples/build.sh a11y-probe     # compatibility: ../a11y-probe/build.sh
#
# The point of these samples is to be readable and runnable on the device this
# repository was written on, where Gradle's aapt2 has no aarch64 build. The steps
# are the ones Gradle would take: aapt2 link, javac, d8, zip in the dex and the
# native libraries, zipalign, apksigner.
#
# Conventions, so each app stays a directory of files and no build config:
#   AndroidManifest.xml   the manifest
#   res/                  optional resources
#   src/                  Java sources
#   assets/               optional assets (xterm-a11y copies the app's copy in)
#   jni/*.c               optional native sources, built into one library named
#                         after the app directory (native-terminal ->
#                         libnative_terminal.so, so System.loadLibrary("native_terminal"))
#
# A dependency that is published is taken from Maven Central, the way a Gradle app
# would take it: the screenstream AAR, fetched once into samples/out/m2 and checked
# against the .sha1 the repository serves. SCREENSTREAM_VERSION pins which version;
# a change to the library has to be published before a sample can build against it.
# The terminal module has no release, so its sources are still compiled in.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
# The same candidates test/local-build.sh looks at, so a machine that can build
# the app can build a sample and neither script has its own idea of where the SDK
# lives. The main script exports ANDROID_HOME before calling this one.
sdk=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" /usr/lib/android-sdk; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        sdk=$candidate
        break
    fi
done
if [ -z "$sdk" ]; then
    echo "no Android SDK found (looked at ANDROID_HOME, ANDROID_SDK_ROOT," >&2
    echo "$HOME/android-sdk and /usr/lib/android-sdk)." >&2
    exit 1
fi
api=35
android_jar=$sdk/platforms/android-$api/android.jar
build_tools=$sdk/build-tools/35.0.0
[ -f "$android_jar" ] || { echo "no $android_jar" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner javac clang python3 curl sha1sum; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

# --- published dependencies ----------------------------------------------------
# An AAR is a zip with classes.jar inside; without Gradle the two steps it would
# do are: put classes.jar on the compile classpath, and hand it to d8. The sha1
# check is not ceremony: the build is taking code from the network instead of from
# this checkout, so it verifies it got the artifact the repository advertises.
fetch_aar() {
    local group=$1 artifact=$2 version=$3
    local dir=$here/out/m2/${group//.//}/$artifact/$version
    local jar=$dir/$artifact-$version-classes.jar
    if [ ! -f "$jar" ]; then
        local base=https://repo1.maven.org/maven2/${group//.//}/$artifact/$version
        echo "== $artifact $version: Maven Central ==" >&2
        mkdir -p "$dir"
        # Every step is checked rather than trusted to `set -e`: a command
        # substitution runs with errexit cleared, so inside one a failed download
        # would fall through and leave this function returning a path to nothing.
        if ! curl --fail --silent --show-error --location \
                -o "$dir/$artifact-$version.aar" "$base/$artifact-$version.aar"; then
            echo "$artifact $version is not at $base" >&2
            rmdir "$dir" 2>/dev/null
            return 1
        fi
        local want got
        if ! want=$(curl --fail --silent --show-error --location "$base/$artifact-$version.aar.sha1"); then
            echo "$artifact-$version.aar: the repository publishes no sha1" >&2
            return 1
        fi
        got=$(sha1sum "$dir/$artifact-$version.aar" | cut -d' ' -f1)
        if [ "$want" != "$got" ]; then
            echo "$artifact-$version.aar: sha1 $got, repository says $want" >&2
            rm -f "$dir/$artifact-$version.aar"
            return 1
        fi
        if ! python3 - "$dir/$artifact-$version.aar" "$jar" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as aar, open(sys.argv[2], 'wb') as jar:
    jar.write(aar.read('classes.jar'))
EOF
        then
            echo "$artifact-$version.aar: no classes.jar inside" >&2
            rm -f "$jar"
            return 1
        fi
    fi
    printf '%s' "$jar"
}

app=${1:-}
case "$app" in
    all)
        shift
        bash "$here/build.sh" native-terminal
        exec bash "$root/a11y-probe/build.sh" "$@"
        ;;
    a11y-probe)
        # Promoted to a first-class module with its own version and release
        # artifact. This path stays so the old command keeps working.
        shift
        exec bash "$root/a11y-probe/build.sh" "$@"
        ;;
    test|--test)
        shift
        # One entry for every suite. A second copy of the build order here is how
        # this script and the repository's build loop drifted apart.
        exec bash "$root/test/local-build.sh" --samples "$@"
        ;;
esac
app=${1:?usage: build.sh <app directory>}
dir=$here/$app
[ -f "$dir/AndroidManifest.xml" ] || { echo "no $dir/AndroidManifest.xml" >&2; exit 1; }

work=$here/out/$app
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$work/gen"

# --- resources -----------------------------------------------------------------
link_args=()
if [ -d "$dir/res" ]; then
    mkdir -p "$work/res"
    aapt2 compile --dir "$dir/res" -o "$work/res/"
    for flat in "$work"/res/*.flat; do link_args+=("$flat"); done
fi
if [ -d "$dir/assets" ]; then
    link_args+=(-A "$dir/assets")
fi

echo "== $app: aapt2 link =="
aapt2 link -o "$work/base.apk" \
    -I "$android_jar" \
    --manifest "$dir/AndroidManifest.xml" \
    --java "$work/gen" \
    --min-sdk-version 24 --target-sdk-version "$api" \
    --version-code 1 --version-name 1.0 \
    "${link_args[@]}"

# --- java ----------------------------------------------------------------------
echo "== $app: javac =="
# The whole app directory is searched, manifest included, so an app that is only a
# manifest picks up the dependency its components come from. The terminal module is
# still a source set because it has no release; the screen stream is an artifact.
modules=()
if grep -rq 'io\.github\.mbanucu\.terminal' "$dir" 2>/dev/null; then
    modules+=("$root/terminal/src/main/java")
fi
classpath=$android_jar
jars=()
if grep -rq 'io\.github\.mbanucu\.screenstream' "$dir" 2>/dev/null; then
    # A fetch that fails must stop the build here: without this the script goes on
    # to javac with the dependency missing and reports its symbols as errors.
    if ! stream=$(fetch_aar io.github.mbanucu screenstream "${SCREENSTREAM_VERSION:-0.2.0}"); then
        echo "could not get screenstream ${SCREENSTREAM_VERSION:-0.2.0} from Maven Central" >&2
        exit 1
    fi
    jars+=("$stream")
    classpath=$classpath:$stream
fi
mapfile -t sources < <(find "$dir/src" "$work/gen" ${modules[@]+"${modules[@]}"} -name '*.java' 2>/dev/null)
javac -nowarn --release 17 -classpath "$classpath" -d "$work/classes" "${sources[@]}"

python3 - "$work/classes" "$work/classes.jar" <<'EOF'
import os, sys, zipfile
classes, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(classes):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, classes))
EOF

echo "== $app: d8 =="
d8 --lib "$android_jar" --min-api 24 --output "$work/dex" "$work/classes.jar" ${jars[@]+"${jars[@]}"}

# --- native --------------------------------------------------------------------
libs=()
if [ -d "$dir/jni" ]; then
    echo "== $app: clang =="
    libname=$(echo "$app" | tr '-' '_')
    mkdir -p "$work/lib/arm64-v8a"
    # Termux's clang adds a runpath into the Termux prefix; it does not exist in
    # an app's mount namespace, so the loader skips it. Nothing here links
    # against a library an app cannot load: bionic's libc carries openpty.
    clang --target=aarch64-linux-android24 -fPIC -shared -O2 \
        -I "$PREFIX/include" \
        -o "$work/lib/arm64-v8a/lib$libname.so" "$dir"/jni/*.c
    command -v patchelf >/dev/null && patchelf --remove-rpath "$work/lib/arm64-v8a/lib$libname.so" || true
    libs+=("lib/arm64-v8a/lib$libname.so")
fi

# --- package -------------------------------------------------------------------
echo "== $app: package =="
python3 - "$work/base.apk" "$work/dex" "$work/unaligned.apk" "$work/lib" "$app" <<'EOF'
import os, sys, zipfile
base, dexdir, out, libroot, app = sys.argv[1:6]
with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, 'w') as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    # d8 splits into classes.dex, classes2.dex, ... when one jar plus a dependency
    # does not fit in a single dex; each one is a file the loader looks for.
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            dst.write(os.path.join(dexdir, name), name)
    if os.path.isdir(libroot):
        for base_dir, _, files in os.walk(libroot):
            for name in files:
                path = os.path.join(base_dir, name)
                # lib/<abi>/<name>.so: the prefix is what makes the loader find it.
                dst.write(path, 'lib/' + os.path.relpath(path, libroot))
EOF

zipalign -f 4 "$work/unaligned.apk" "$work/aligned.apk"
keystore=$HOME/.android/debug.keystore
[ -f "$keystore" ] || keytool -genkeypair -keystore "$keystore" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
mkdir -p "$here/out"
apksigner sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$here/out/$app.apk" "$work/aligned.apk"
echo "built $here/out/$app.apk ($(wc -c < "$here/out/$app.apk") bytes)"

# --- adb ----------------------------------------------------------------------
# A build is usually followed by an install, and the wireless-debugging port
# changes between sessions. Look it up here rather than letting the next adb
# command fail with "no devices"; this never fails the build, and ADB_CONNECT=0
# skips it for an offline build.
if [ "${ADB_CONNECT:-1}" != 0 ] && command -v adb >/dev/null; then
    watcher=$root/test/harness.sh
    if [ -x "$watcher" ] && ! bash "$watcher" watch --status >/dev/null 2>&1; then
        echo "== $app: starting the adb port watcher"
        setsid nohup bash "$watcher" watch >/dev/null 2>&1 < /dev/null &
    fi
    if adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'; then
        echo "== $app: adb device already connected"
    else
        echo "== $app: looking up the wireless-debugging port"
        bash "$root/test/harness.sh" connect --timeout 4 2>&1 | sed 's/^/   /' || true
    fi
fi
