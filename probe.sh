#!/usr/bin/env bash
# Installs, enables and disables the accessibility probe, and puts the
# accessibility setting back exactly as it was.
#
#   bash probe.sh install            # out/a11y-probe-<version>.apk
#   bash probe.sh enable
#   bash probe.sh status
#   bash probe.sh disable
#   bash probe.sh version
#   bash probe.sh restore            # after a run that died before disable
#
# Enabling an accessibility service changes a secure setting that may already
# hold the owner's screen reader. `enable` saves the value first, byte for byte,
# including "none" and an unset ("null") setting; `disable` puts that exact value
# back and only forgets it once the phone agrees. The saved state is on disk, so
# `restore` can repair a run that was killed.
#
# Security boundary: while enabled the probe can read every window on the device
# and perform actions on its nodes. It is a test tool. Use a test device, expect
# it to write only under its own external files directory, and disable it when
# the run is over.
#
# The old io.github.mbanucu.samples.a11yprobe app id is kept for one release:
#   bash probe.sh --legacy install
#   PROBE_LEGACY=1 bash probe.sh enable
# It uses out/a11y-probe-<version>-legacy.apk and the old component name.
#
# Environment: ADB (default adb), PROBE_PACKAGE, PROBE_APK, PROBE_STATE,
# PROBE_VERSION, PROBE_LEGACY=1, ANDROID_SERIAL is passed through by adb.
set -euo pipefail

ADB=${ADB:-adb}
root=$(cd "$(dirname "$0")" && pwd)
manifest=$root/a11y-probe/AndroidManifest.xml
out=$root/out
# A release download has probe.sh and the APK, not the checkout around them.
# The manifest is the best source when it exists; otherwise PROBE_VERSION, the
# APK's own name, or the installed package supplies the version.
expected=${PROBE_VERSION:-}
if [ -z "$expected" ] && [ -f "$manifest" ]; then
    expected=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$manifest" | head -n 1 || true)
fi

legacy=${PROBE_LEGACY:-0}
if [ "${1:-}" = --legacy ]; then
    legacy=1
    shift
fi
if [ "$legacy" = 1 ]; then
    package=${PROBE_PACKAGE:-io.github.mbanucu.samples.a11yprobe}
    action=$package.COMMAND
else
    package=${PROBE_PACKAGE:-io.github.mbanucu.a11yprobe}
    action=$package.COMMAND
fi
service=$package/.ProbeService
canonical_package=io.github.mbanucu.a11yprobe
legacy_package=io.github.mbanucu.samples.a11yprobe
canonical_service=$canonical_package/.ProbeService
legacy_service=$legacy_package/.ProbeService

state_home=${XDG_STATE_HOME:-${HOME:-$root}/.local/state}
state_dir=${PROBE_STATE:-$state_home/a11y-probe}
state_file=$state_dir/$package.state

say() { printf '%s\n' "$*"; }
fail() { printf 'probe: %s\n' "$*" >&2; exit 1; }

apk_version() { # path
    local base=${1##*/}
    base=${base%.apk}
    base=${base#a11y-probe-}
    base=${base%-legacy}
    case $base in
        ''|*[!0-9A-Za-z._+-]*) return 1 ;;
    esac
    printf '%s' "$base"
}

# The download directory is the usual standalone case. Take the version from
# the APK there so `install`, `enable` and `status` agree without a checkout.
if [ -z "$expected" ]; then
    for candidate in "${PROBE_APK:-}" "$PWD"/a11y-probe-*.apk "$out"/a11y-probe-*.apk; do
        [ -n "$candidate" ] && [ -f "$candidate" ] || continue
        if expected=$(apk_version "$candidate"); then
            break
        fi
    done
fi

device() { $ADB "$@"; }

device_ready() { device get-state >/dev/null 2>&1; }
require_device() {
    device_ready || fail "no device (adb get-state failed)"
}

read_setting() { # key
    device shell settings get secure "$1" 2>/dev/null | tr -d '\r'
}

restore_setting() { # key value
    local key=$1 value=$2
    if [ "$value" = null ]; then
        device shell settings delete secure "$key" >/dev/null
    elif [ -z "$value" ]; then
        # adb preserves an empty argument, but an explicit '' survives every
        # adb/shell version; the value is a value, not "unset".
        device shell "settings put secure $key ''" >/dev/null
    else
        device shell settings put secure "$key" "$value" >/dev/null
    fi
}

installed_version() {
    device shell dumpsys package "$package" 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n 1 || true
}

resolve_expected() {
    if [ -n "$expected" ]; then return 0; fi
    local installed
    installed=$(installed_version)
    if [ -n "$installed" ]; then
        expected=$installed
        printf 'probe: no manifest, PROBE_VERSION or APK; using installed %s\n' \
            "$expected" >&2
        return 0
    fi
    fail "cannot determine the expected version; set PROBE_VERSION or run from a checkout"
}

installed_code() {
    device shell dumpsys package "$package" 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1 || true
}

# Asks the app, not the APK: a stale install whose filename looks right still
# reports its own version. The receiver creates files/version.txt even when the
# service is disabled.
probe_version() {
    local files=/sdcard/Android/data/$package/files answer
    device shell "rm -f '$files/version.txt'; am broadcast -a $action -p $package \
        --es version true" >/dev/null 2>&1 || return 1
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        answer=$(device shell "cat '$files/version.txt'" 2>/dev/null | tr -d '\r' || true)
        if [ -n "$answer" ]; then
            printf '%s' "$answer"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

probe_version_name() {
    printf '%s\n' "$1" | sed -n 's/^a11y-probe \([^ ]*\).*/\1/p'
}

encode() { printf '%s' "$1" | base64 | tr -d '\n'; }
decode() { printf '%s' "$1" | base64 -d; }

has_state() {
    [ -f "$state_file" ] && grep -q '^services=' "$state_file"
}

state_get() { sed -n "s/^$1=//p" "$state_file" | head -n 1; }

load_state() {
    has_state || return 1
    [ "$(state_get version)" = 1 ] || fail "unknown state file version in $state_file"
    local saved_package
    saved_package=$(state_get package)
    [ "$saved_package" = "$package" ] \
        || fail "state in $state_file belongs to $saved_package, not $package"
    saved_services=$(decode "$(state_get services)")
    saved_accessibility=$(decode "$(state_get accessibility)")
}

save_state() {
    local services accessibility
    services=$(read_setting enabled_accessibility_services)
    accessibility=$(read_setting accessibility_enabled)
    mkdir -p "$state_dir"
    {
        printf 'version=1\n'
        printf 'package=%s\n' "$package"
        printf 'services=%s\n' "$(encode "$services")"
        printf 'accessibility=%s\n' "$(encode "$accessibility")"
        printf 'saved_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "$state_file.tmp"
    mv "$state_file.tmp" "$state_file"
}

# An exact field comparison: one service name can be a prefix of another, so a
# substring check would skip appending, and a substring removal would corrupt
# the list.
service_in_list() { # list service
    local list=$1 wanted=$2 entry
    local IFS=:
    for entry in $list; do
        [ "$entry" = "$wanted" ] && return 0
    done
    return 1
}

append_service() { # list service
    local list=$1 wanted=$2
    case "$list" in
        ''|null) printf '%s' "$wanted" ;;
        *)
            if service_in_list "$list" "$wanted"; then
                printf '%s' "$list"
            else
                printf '%s:%s' "$list" "$wanted"
            fi
            ;;
    esac
}

# The probe entries are removed without touching the order or spelling of any
# other service in the list.
strip_probe_services() { # list
    local list=$1 entry remaining=""
    local IFS=:
    for entry in $list; do
        case "$entry" in
            "$canonical_service"|"$legacy_service"|'') continue ;;
        esac
        if [ -n "$remaining" ]; then
            remaining=$remaining:$entry
        else
            remaining=$entry
        fi
    done
    printf '%s' "$remaining"
}

restore_saved() {
    load_state || return 1
    restore_setting enabled_accessibility_services "$saved_services"
    restore_setting accessibility_enabled "$saved_accessibility"
    local current_services current_accessibility
    current_services=$(read_setting enabled_accessibility_services)
    current_accessibility=$(read_setting accessibility_enabled)
    if [ "$current_services" != "$saved_services" ] \
            || [ "$current_accessibility" != "$saved_accessibility" ]; then
        printf 'probe: restore did not stick; saved state kept in %s\n' "$state_file" >&2
        printf '  enabled_accessibility_services: want %s, got %s\n' \
            "$saved_services" "$current_services" >&2
        printf '  accessibility_enabled: want %s, got %s\n' \
            "$saved_accessibility" "$current_accessibility" >&2
        return 1
    fi
    rm -f "$state_file"
}

enable() {
    require_device
    local installed
    installed=$(installed_version)
    [ -n "$installed" ] || fail "$package is not installed; run 'bash probe.sh install' first"
    if [ -z "$expected" ]; then
        expected=$installed
        printf 'probe: no manifest, PROBE_VERSION or APK; using installed %s\n' \
            "$expected" >&2
    fi
    if [ "$installed" != "$expected" ]; then
        printf 'probe: warning: installed %s, this wrapper is for %s\n' \
            "$installed" "$expected" >&2
    fi
    if has_state; then
        # A previous enable saved the phone before the probe was added. Keep that
        # snapshot: saving now would record the modified list as the old value.
        load_state
        say "keeping the saved accessibility state in $state_file"
    else
        save_state
    fi
    local services current
    services=$(read_setting enabled_accessibility_services)
    current=$(append_service "$services" "$service")
    if [ "$current" != "$services" ]; then
        device shell settings put secure enabled_accessibility_services "$current" >/dev/null
    fi
    device shell settings put secure accessibility_enabled 1 >/dev/null
    say "enabled $service"
    say "saved state: $state_file"
    say "disable when the run is over: bash probe.sh disable"
}

disable() {
    require_device
    if has_state; then
        restore_saved || exit 1
        say "disabled $package; the previous accessibility setting is back"
        return 0
    fi
    local services remaining
    services=$(read_setting enabled_accessibility_services)
    remaining=$(strip_probe_services "$services")
    if [ "$remaining" = "$services" ]; then
        say "no saved state, and $package is not in enabled_accessibility_services"
        return 0
    fi
    if [ -z "$remaining" ]; then
        device shell settings delete secure enabled_accessibility_services >/dev/null
        device shell settings put secure accessibility_enabled 0 >/dev/null
    else
        device shell settings put secure enabled_accessibility_services "$remaining" >/dev/null
    fi
    say "removed $package from enabled_accessibility_services (no saved state)"
}

restore() {
    require_device
    has_state || fail "no saved state in $state_file"
    restore_saved || exit 1
    say "restored the accessibility setting saved in $state_file"
}

status() {
    if ! device_ready; then
        say "device=offline"
        return 1
    fi
    local installed code reported services enabled=no state=pending version_match=no
    installed=$(installed_version)
    code=$(installed_code)
    [ -n "$installed" ] || installed=-
    [ -n "$code" ] || code=-
    if [ -z "$expected" ] && [ "$installed" != - ]; then
        expected=$installed
        printf 'probe: no manifest, PROBE_VERSION or APK; using installed %s\n' \
            "$expected" >&2
    fi
    services=$(read_setting enabled_accessibility_services)
    if service_in_list "$services" "$service"; then
        enabled=yes
    fi
    reported=$(probe_version || true)
    if [ -n "$reported" ] && [ "$(probe_version_name "$reported")" = "$expected" ]; then
        version_match=yes
    fi
    has_state && state=pending || state=none

    say "package=$package"
    say "installed=${installed} (${code})"
    say "expected=$expected"
    say "version-match=$version_match"
    say "probe=${reported:--}"
    say "enabled=$enabled"
    say "service=$service"
    say "state=$state"
    if has_state; then
        say "saved=$state_file"
    fi

    [ "$installed" != - ] && [ "$installed" = "$expected" ] \
        && [ "$version_match" = yes ]
}

version() {
    require_device
    local installed reported match=no
    installed=$(installed_version)
    [ -n "$installed" ] || fail "$package is not installed"
    resolve_expected
    reported=$(probe_version) || fail "$package did not answer the version command"
    [ "$(probe_version_name "$reported")" = "$expected" ] && match=yes
    say "package=$package"
    say "installed=$installed"
    say "expected=$expected"
    say "probe=$reported"
    say "version-match=$match"
    [ "$installed" = "$expected" ] && [ "$match" = yes ]
}

install_probe() {
    require_device
    local apk=${1:-} candidate name suffix=""
    if [ "$legacy" = 1 ]; then suffix=-legacy; fi
    name=a11y-probe-${expected:-<version>}$suffix.apk
    if [ -z "$apk" ] && [ -n "$expected" ]; then
        for candidate in "${PROBE_APK:-}" "$PWD/$name" "$out/$name"; do
            if [ -n "$candidate" ] && [ -f "$candidate" ]; then
                apk=$candidate
                break
            fi
        done
    fi
    if [ -z "$apk" ] && [ -z "$expected" ]; then
        # No checkout and no known version: the APK in this directory names it.
        for candidate in "${PROBE_APK:-}" "$PWD"/a11y-probe-*.apk \
                "$out"/a11y-probe-*.apk; do
            [ -n "$candidate" ] && [ -f "$candidate" ] || continue
            case $candidate in
                *-legacy.apk) [ "$legacy" = 1 ] || continue ;;
                *) [ "$legacy" = 0 ] || continue ;;
            esac
            apk=$candidate
            break
        done
    fi
    [ -n "$apk" ] \
        || fail "no $name in this directory or $out; run 'bash a11y-probe/build.sh' first"
    [ -f "$apk" ] || fail "no $apk"
    if [ -z "$expected" ]; then
        expected=$(apk_version "$apk") || fail "cannot read a version from $apk"
    fi
    say "installing $apk"
    device install -r "$apk" >/dev/null || fail "adb install failed"
    local installed
    installed=$(installed_version)
    [ -n "$installed" ] || fail "$package is still not installed"
    say "installed $package $installed"
    if [ "$installed" != "$expected" ]; then
        printf 'probe: warning: installed %s, wrapper expects %s\n' \
            "$installed" "$expected" >&2
    fi
}

usage() {
    cat <<EOF
usage: bash probe.sh [--legacy] <command>

  install [apk]   install the given APK, or a11y-probe-${expected:-<version>}.apk
                  from this directory or out/
  enable          save the accessibility setting, then enable the probe
  disable         restore the saved setting, or remove the probe if unsaved
  status          version, whether the service is enabled, pending saved state
  version         ask the installed probe its version (exit 1 if incompatible)
  restore         put back state an interrupted run saved

The probe is an accessibility service: while enabled it can read every window
and perform actions. Use a test device, and disable it when the run is over.
EOF
}

command=${1:-}
[ -n "$command" ] || { usage; exit 2; }
shift || true
case "$command" in
    install) install_probe "${1:-}" ;;
    enable) enable ;;
    disable) disable ;;
    status) status ;;
    version) version ;;
    restore) restore ;;
    help|-h|--help) usage ;;
    *) usage >&2; exit 2 ;;
esac
