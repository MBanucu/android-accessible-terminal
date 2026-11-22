#!/usr/bin/env bash
# The timing store is its own project, published to PyPI as `test-timings`; this
# installs the version pinned below into test/.deps/venv the first time it is
# needed and runs that, so what estimates and gates the suite is a release rather
# than a copy kept here, and the suite uses the package the way anyone else would.
#
#   bash test/timings.sh history
#   bash test/timings.sh check --log samples/out/e2e-timings.log --run 1699999999
#   TIMINGS_VERSION=0.3.0 bash test/timings.sh eta ...    # another release
#
# TEST_TIMINGS_TOOL runs a checkout instead, for when the tool itself is what is
# being changed:
#
#   TEST_TIMINGS_TOOL=../../test-timings/test_timings.py bash test/timings.sh eta ...
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
version=${TIMINGS_VERSION:-0.2.0}
deps=$here/.deps
venv=$deps/venv
command=$venv/bin/test-timings

# The tool defaults to .timings.db in the working directory; this suite's history
# has always lived beside the wrapper, so it stays there whether this is run from
# the repository root or from samples/ with --db left off.
export TEST_TIMINGS_DB=${TEST_TIMINGS_DB:-$here/.timings.db}

if [ -n "${TEST_TIMINGS_TOOL:-}" ]; then
    exec python3 "$TEST_TIMINGS_TOOL" "$@"
fi

if [ ! -x "$command" ] || [ "$(cat "$deps/version" 2>/dev/null || true)" != "$version" ]; then
    mkdir -p "$deps"
    if [ ! -x "$venv/bin/python" ]; then
        if ! python3 -m venv "$venv"; then
            echo "timings: could not create a virtualenv in $venv" >&2
            exit 1
        fi
    fi
    if ! "$venv/bin/pip" install --quiet --disable-pip-version-check --upgrade \
            "test-timings==$version"; then
        # No marker: the next call tries again instead of trusting a half install.
        rm -f "$deps/version"
        echo "timings: could not install test-timings==$version from PyPI" >&2
        exit 1
    fi
    printf '%s\n' "$version" > "$deps/version"
fi

exec "$command" "$@"
