#!/usr/bin/env bash
# Resolve the Android SDK and Termux's bionic aapt2 for this checkout.
#
# Source it; do not run it:
#
#     source scripts/termux-env.sh
#     ./gradlew "${TERMUX_GRADLE_ARGS[@]}" assembleRelease
#
# It exports ANDROID_HOME (and ANDROID_SDK_ROOT) and defines
# TERMUX_GRADLE_ARGS with the aapt2 override. It looks at, in order:
#   1. ANDROID_HOME / ANDROID_SDK_ROOT
#   2. sdk.dir in local.properties
#   3. $HOME/android-sdk, the conventional Termux install
#
# scripts/termux-build.sh sources this file and runs the whole smoke build.

if [ "${BASH_SOURCE[0]:-$0}" = "$0" ]; then
    echo "source this file instead of running it: source scripts/termux-env.sh" >&2
    exit 1
fi

_termux_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$_termux_sdk" ] && [ -f local.properties ]; then
    _termux_sdk=$(sed -n \
        's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties |
        head -n 1)
fi

if [ -z "$_termux_sdk" ] && [ -d "${HOME:-}/android-sdk" ]; then
    _termux_sdk="${HOME}/android-sdk"
fi

if [ -z "$_termux_sdk" ]; then
    echo "Android SDK not found." >&2
    echo "  export ANDROID_HOME=\"\$HOME/android-sdk\"" >&2
    echo "  or copy local.properties.example to local.properties and set sdk.dir" >&2
    echo "  See docs/termux-build.md." >&2
    return 1
fi

if [ ! -d "$_termux_sdk" ]; then
    echo "Android SDK '$_termux_sdk' does not exist." >&2
    echo "  Set ANDROID_HOME or sdk.dir in local.properties; see docs/termux-build.md." >&2
    return 1
fi

export ANDROID_HOME="$_termux_sdk"
export ANDROID_SDK_ROOT="$_termux_sdk"

# AGP's aapt2 downloads are glibc builds; Termux is bionic. Pass Termux's own
# aapt2 on the command line so a fresh checkout needs no global setup.
TERMUX_GRADLE_ARGS=()
if [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/aapt2" ]; then
    TERMUX_GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2")
fi

unset _termux_sdk
