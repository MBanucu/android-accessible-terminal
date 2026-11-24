#!/usr/bin/env bash
# Builds the accessibility probe without Gradle.
#
#   bash a11y-probe/build.sh
#
# Writes out/a11y-probe-<version>.apk and its .sha256. Also writes
# out/a11y-probe-<version>-legacy.apk, the same code under the old
# io.github.mbanucu.samples.a11yprobe package, for one release: an existing
# harness can keep installing and enabling that app id while it moves over.
#
# The steps are the ones Gradle would take: aapt2 link, javac, d8, zip in the
# dex, zipalign, apksigner. The screen stream is a published dependency, taken
# from Maven Central and checked against the .sha1 the repository serves, the
# same way samples/build.sh takes it. SCREENSTREAM_VERSION pins it.
#
# The version is in AndroidManifest.xml and is the APK's version: the release
# workflow uploads the file whose name carries it, and `probe.sh version` asks
# the installed app so a harness can refuse a probe it does not know.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
manifest=$here/AndroidManifest.xml
out=$root/out

version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$manifest" | head -n 1)
code=$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' "$manifest" | head -n 1)
[ -n "$version" ] || { echo "no android:versionName in $manifest" >&2; exit 1; }
[ -n "$code" ] || { echo "no android:versionCode in $manifest" >&2; exit 1; }
canonical=a11y-probe-$version.apk
legacy=a11y-probe-$version-legacy.apk

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
[ -f "$android_jar" ] || { echo "no $android_jar" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner javac keytool python3 curl sha1sum sha256sum; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

# --- published dependency ------------------------------------------------------
# An AAR is a zip with classes.jar inside. The sha1 check is not ceremony: this
# build takes code from the network, so it verifies it got what it asked for.
fetch_aar() {
    local group=$1 artifact=$2 aar_version=$3
    local dir=$out/m2/${group//.//}/$artifact/$aar_version
    local jar=$dir/$artifact-$aar_version-classes.jar
    if [ ! -f "$jar" ]; then
        # A shared CI egress IP gets 429 from repo1 often enough that a single
        # download is not reliable. Try Google's Maven Central mirror first, then
        # the canonical host; each URL is retried and the checksum is still from
        # the same repository that served the AAR.
        local bases=(
            https://maven-central.storage-download.googleapis.com/maven2
            https://repo1.maven.org/maven2
        )
        local base='' ok=''
        echo "== $artifact $aar_version: Maven Central ==" >&2
        mkdir -p "$dir"
        for base in "${bases[@]}"; do
            if curl --fail --silent --show-error --location \
                    --retry 4 --retry-delay 2 --retry-max-time 60 \
                    -o "$dir/$artifact-$aar_version.aar" \
                    "$base/${group//.//}/$artifact/$aar_version/$artifact-$aar_version.aar"; then
                ok=1
                break
            fi
            echo "$artifact $aar_version is not at $base" >&2
        done
        if [ -z "$ok" ]; then
            rmdir "$dir" 2>/dev/null
            return 1
        fi
        local want got
        if ! want=$(curl --fail --silent --show-error --location \
                --retry 4 --retry-delay 2 --retry-max-time 60 \
                "$base/${group//.//}/$artifact/$aar_version/$artifact-$aar_version.aar.sha1"); then
            echo "$artifact-$aar_version.aar: the repository publishes no sha1" >&2
            return 1
        fi
        got=$(sha1sum "$dir/$artifact-$aar_version.aar" | cut -d' ' -f1)
        if [ "$want" != "$got" ]; then
            echo "$artifact-$aar_version.aar: sha1 $got, repository says $want" >&2
            rm -f "$dir/$artifact-$aar_version.aar"
            return 1
        fi
        if ! python3 - "$dir/$artifact-$aar_version.aar" "$jar" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as aar, open(sys.argv[2], 'wb') as jar:
    jar.write(aar.read('classes.jar'))
EOF
        then
            echo "$artifact-$aar_version.aar: no classes.jar inside" >&2
            rm -f "$jar"
            return 1
        fi
    fi
    printf '%s' "$jar"
}

stream_version=${SCREENSTREAM_VERSION:-0.2.0}
if ! stream=$(fetch_aar io.github.mbanucu screenstream "$stream_version"); then
    echo "could not get screenstream $stream_version from Maven Central" >&2
    exit 1
fi

work=$out/a11y-probe
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$work/gen" "$work/gen-legacy" "$work/res" "$out"

# --- resources -----------------------------------------------------------------
echo "== a11y-probe $version ($code): aapt2 compile =="
aapt2 compile --dir "$here/res" -o "$work/res/"
link_args=()
for flat in "$work"/res/*.flat; do link_args+=("$flat"); done

echo "== a11y-probe: aapt2 link =="
aapt2 link -o "$work/base.apk" \
    -I "$android_jar" \
    --manifest "$manifest" \
    --java "$work/gen" \
    --min-sdk-version 24 --target-sdk-version "$api" \
    "${link_args[@]}"

# The same manifest with the old application id and the old component names.
# aapt2 resolves a relative name such as .ProbeService when it links, so the
# legacy manifest is generated rather than linked with --rename-manifest-package:
# the alias classes in src/io/github/mbanucu/samples/a11yprobe must be the
# components, or io.github.mbanucu.samples.a11yprobe/.ProbeService names a class
# the manifest never declared. This one file is the compatibility artifact.
echo "== a11y-probe: aapt2 link (legacy package) =="
legacy_manifest=$work/AndroidManifest-legacy.xml
sed -e 's/package="io\.github\.mbanucu\.a11yprobe"/package="io.github.mbanucu.samples.a11yprobe"/' \
    -e 's/android:name="\./android:name="io.github.mbanucu.samples.a11yprobe./g' \
    -e 's/io\.github\.mbanucu\.a11yprobe\.control/io.github.mbanucu.samples.a11yprobe.control/g' \
    "$manifest" > "$legacy_manifest"
aapt2 link -o "$work/base-legacy.apk" \
    -I "$android_jar" \
    --manifest "$legacy_manifest" \
    --java "$work/gen-legacy" \
    --min-sdk-version 24 --target-sdk-version "$api" \
    "${link_args[@]}"

# --- java ----------------------------------------------------------------------
echo "== a11y-probe: javac =="
mapfile -t sources < <(find "$here/src" "$work/gen" -name '*.java')
javac -nowarn --release 17 -classpath "$android_jar:$stream" \
    -d "$work/classes" "${sources[@]}"

python3 - "$work/classes" "$work/classes.jar" <<'EOF'
import os, sys, zipfile
classes, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(classes):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, classes))
EOF

echo "== a11y-probe: d8 =="
d8 --lib "$android_jar" --min-api 24 --output "$work/dex" \
    "$work/classes.jar" "$stream"

# --- package and sign ----------------------------------------------------------
package_apk() { # base name
    local base=$1 name=$2
    echo "== a11y-probe: package $name =="
    python3 - "$base" "$work/dex" "$work/unaligned.apk" <<'EOF'
import os, sys, zipfile
base, dexdir, out = sys.argv[1:4]
with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, 'w') as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            dst.write(os.path.join(dexdir, name), name)
EOF
    zipalign -f 4 "$work/unaligned.apk" "$work/aligned.apk"
    keystore=$HOME/.android/debug.keystore
    mkdir -p "$(dirname "$keystore")"
    [ -f "$keystore" ] || keytool -genkeypair -keystore "$keystore" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
    apksigner sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --out "$out/$name" "$work/aligned.apk"
    (cd "$out" && sha256sum "$name" > "$name.sha256")
    echo "built $out/$name ($(wc -c < "$out/$name") bytes)"
    echo "      $out/$name.sha256"
}

package_apk "$work/base.apk" "$canonical"
package_apk "$work/base-legacy.apk" "$legacy"

# --- adb ----------------------------------------------------------------------
# A build is usually followed by an install. Look up the wireless-debugging port
# rather than letting the next adb command fail with "no devices"; ADB_CONNECT=0
# skips this for an offline or CI build.
if [ "${ADB_CONNECT:-1}" != 0 ] && command -v adb >/dev/null; then
    watcher=$root/test/harness.sh
    if [ -x "$watcher" ] && ! bash "$watcher" watch --status >/dev/null 2>&1; then
        echo "== a11y-probe: starting the adb port watcher"
        setsid nohup bash "$watcher" watch >/dev/null 2>&1 < /dev/null &
    fi
    if adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit !found }'; then
        echo "== a11y-probe: adb device already connected"
    else
        echo "== a11y-probe: looking up the wireless-debugging port"
        bash "$watcher" connect --timeout 4 2>&1 | sed 's/^/   /' || true
    fi
fi
