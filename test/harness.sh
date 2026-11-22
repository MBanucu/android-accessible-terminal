#!/usr/bin/env bash
# The tools that drive the device - find its wireless-debugging port, keep the
# connection alive, record the screen - are their own project, published to PyPI
# as `adb-harness`. This installs the version pinned below into test/.deps/venv
# the first time it is needed and runs its commands, so the suite drives the phone
# with a release rather than a copy kept here, and a tool change has to be
# released before this suite can use it.
#
#   bash test/harness.sh capture show --dir samples/out/capture
#   bash test/harness.sh connect --timeout 4
#   bash test/harness.sh watch --status
#   HARNESS_VERSION=0.3.0 bash test/harness.sh connect     # another release
#
# HARNESS_SRC runs a checkout instead, for when a tool itself is what is being
# changed:
#
#   HARNESS_SRC=../adb-harness bash test/harness.sh capture show
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
version=${HARNESS_VERSION:-0.2.2}
deps=$here/.deps
venv=$deps/venv

# adb-connect asks mDNS through python-zeroconf; capture and the watcher do not
# need it, but the suite needs all three, so install the extra.
extras=${HARNESS_EXTRAS-mdns}

# The watcher's pid and log have always lived with this suite's other scratch
# files; they stay there rather than inside the installed package.
export ADB_WATCH_OUT=${ADB_WATCH_OUT:-$here/out}

src=""
if [ -n "${HARNESS_SRC:-}" ]; then
    if ! src=$(cd "$HARNESS_SRC" && pwd); then
        echo "harness: HARNESS_SRC=$HARNESS_SRC is not a directory" >&2
        exit 1
    fi
fi

install_tools() {
    [ -z "$src" ] || return 0
    local want="adb-harness${extras:+[$extras]}==$version"
    if [ -x "$venv/bin/capture" ] && [ "$(cat "$deps/version" 2>/dev/null || true)" = "$want" ]; then
        return 0
    fi
    mkdir -p "$deps"
    if [ ! -x "$venv/bin/python" ]; then
        if ! python3 -m venv "$venv"; then
            echo "harness: could not create a virtualenv in $venv" >&2
            exit 1
        fi
    fi
    # --no-cache-dir because this asks for one exact version that may have been
    # released minutes ago, and pip's cached copy of the package page can still be
    # the one from before it existed ("from versions: 0.2.0").
    if ! "$venv/bin/pip" install --quiet --no-cache-dir --disable-pip-version-check \
            --upgrade "$want"; then
        # No marker: the next call tries again instead of trusting a half install.
        rm -f "$deps/version"
        echo "harness: could not install $want from PyPI" >&2
        exit 1
    fi
    printf '%s\n' "$want" > "$deps/version"
}

# A checkout is run by its module, an install by its console script.
run() {
    local module=$1 command=$2
    shift 2
    install_tools
    if [ -n "$src" ]; then
        PYTHONPATH=$src exec python3 -m "adb_harness.$module" "$@"
    fi
    exec "$venv/bin/$command" "$@"
}

tool=${1:-}
[ $# -gt 0 ] && shift

case $tool in
    capture) run capture capture "$@" ;;
    connect) run connect adb-connect "$@" ;;
    watch) run watch adb-watch "$@" ;;
    "")
        echo "harness: name a tool: capture, connect or watch" >&2
        exit 2
        ;;
    *)
        echo "harness: unknown tool '$tool' (capture, connect, watch)" >&2
        exit 2
        ;;
esac
