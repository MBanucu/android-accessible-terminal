#!/usr/bin/env bash
# The tools that drive the device - find its wireless-debugging port, keep the
# connection alive, record the screen - are their own repository,
# MBanucu/adb-harness. This fetches the release pinned below into
# test/.deps/harness/ on first use, checks each file against its sha256, and runs
# it, so the suite drives the phone with a release rather than a copy kept here.
#
#   bash test/harness.sh capture show --dir samples/out/capture
#   bash test/harness.sh connect --timeout 4
#   bash test/harness.sh watch --status
#
# HARNESS_SRC runs a checkout instead, for when a tool itself is what is being
# changed:
#
#   HARNESS_SRC=../adb-harness bash test/harness.sh capture show
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
version=${HARNESS_VERSION:-0.1.0}
dir=$here/.deps/harness

# The watcher's pid and log have always lived with this suite's other scratch
# files; they stay there rather than inside the fetched copy.
export ADB_WATCH_OUT=${ADB_WATCH_OUT:-$here/out}

declare -A sha=(
    [capture.py]=5b0151b6cfd4100eba407d573dc70be3508da3061415501234bea938bcb150c4
    [adb-connect.py]=a1dca1abada8924d2f139474a732eab200cf2b9b69a870a74ef37a5e9a9e9210
    [adb-watch.sh]=274e7e1847c8df57223770dd42c8f5616b350cdaabfb78a808bce4023160fd6c
)

digest() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | cut -d' ' -f1
    else
        python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
    fi
}

fetch() {
    local name=$1 want=${sha[$1]} partial=$dir/$1.part-$$
    mkdir -p "$dir"
    trap 'rm -f "$partial"' EXIT
    url=https://raw.githubusercontent.com/MBanucu/adb-harness/v$version/$name
    if ! curl -fsSL --max-time 60 "$url" -o "$partial"; then
        echo "harness: could not download $url" >&2
        exit 1
    fi
    local got
    got=$(digest "$partial")
    if [ "$got" != "$want" ]; then
        echo "harness: $url does not match the pinned sha256" >&2
        echo "  expected $want" >&2
        echo "  got      $got" >&2
        exit 1
    fi
    mv "$partial" "$dir/$name"
    trap - EXIT
}

# A file that is already there is still checked, so an edited or half-copied one
# is replaced rather than run.
ensure() {
    if [ -f "$dir/$1" ] && [ "$(digest "$dir/$1")" = "${sha[$1]}" ]; then
        return 0
    fi
    fetch "$1"
}

if [ -n "${HARNESS_SRC:-}" ]; then
    if ! src=$(cd "$HARNESS_SRC" && pwd); then
        echo "harness: HARNESS_SRC=$HARNESS_SRC is not a directory" >&2
        exit 1
    fi
else
    src=$dir
fi

tool=${1:-}
[ $# -gt 0 ] && shift

case $tool in
    capture)
        [ -n "${HARNESS_SRC:-}" ] || ensure capture.py
        exec python3 "$src/capture.py" "$@"
        ;;
    connect)
        [ -n "${HARNESS_SRC:-}" ] || ensure adb-connect.py
        exec python3 "$src/adb-connect.py" "$@"
        ;;
    watch)
        [ -n "${HARNESS_SRC:-}" ] || ensure adb-watch.sh
        # The watcher calls adb-connect.py from beside itself, which is why both
        # files are fetched even when only the watcher is asked for.
        [ -n "${HARNESS_SRC:-}" ] || ensure adb-connect.py
        exec bash "$src/adb-watch.sh" "$@"
        ;;
    "")
        echo "harness: name a tool: capture, connect or watch" >&2
        exit 2
        ;;
    *)
        echo "harness: unknown tool '$tool' (capture, connect, watch)" >&2
        exit 2
        ;;
esac
