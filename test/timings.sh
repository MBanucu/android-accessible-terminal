#!/usr/bin/env bash
# The timing store is its own repository (MBanucu/test-timings); this runs the
# version pinned below instead of a copy kept here. The download happens once,
# into test/.deps/, and its sha256 is checked, so what the suite estimates and
# gates with is exactly the release named here and not whatever main holds.
#
#   bash test/timings.sh history
#   bash test/timings.sh check --log samples/out/e2e-timings.log --run 1699999999
#
# TEST_TIMINGS_TOOL runs a checkout instead, for when the tool itself is what is
# being changed:
#
#   TEST_TIMINGS_TOOL=../../test-timings/timings.py bash test/timings.sh eta ...
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
version=0.1.0
sha256=4155eb9d56196642b11fc18f9a92a11343ea91806f9e873142a3234838458e04

tool=${TEST_TIMINGS_TOOL:-$here/.deps/timings.py}
# The tool defaults to .timings.db in the working directory; this suite's history
# has always lived beside the wrapper, so it stays there whether it is run from
# the repository root or from samples/ with --db left off.
export TEST_TIMINGS_DB=${TEST_TIMINGS_DB:-$here/.timings.db}

if [ ! -f "$tool" ]; then
    mkdir -p "$here/.deps"
    url=https://raw.githubusercontent.com/MBanucu/test-timings/v$version/timings.py
    partial=$tool.part-$$
    trap 'rm -f "$partial"' EXIT
    if ! curl -fsSL --max-time 60 "$url" -o "$partial"; then
        echo "timings: could not download $url" >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null; then
        got=$(sha256sum "$partial" | cut -d' ' -f1)
    else
        got=$(python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$partial")
    fi
    if [ "$got" != "$sha256" ]; then
        echo "timings: $url does not match the pinned sha256" >&2
        echo "  expected $sha256" >&2
        echo "  got      $got" >&2
        exit 1
    fi
    mv "$partial" "$tool"
    trap - EXIT
fi

exec python3 "$tool" "$@"
