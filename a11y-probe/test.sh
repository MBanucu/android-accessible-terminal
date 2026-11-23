#!/usr/bin/env bash
# Runs the probe's protocol tests on the JVM: the selectors and assertion
# verdicts, the JSON a dump emits, the node encodings, the batch line grammar,
# the argument fallbacks and the reply/result bookkeeping. These classes import
# no android.*, so this needs no device, no Gradle and no test framework - only
# a javac, the same way terminal/test.sh runs the grid.
#
#   bash a11y-probe/test.sh
#
# The pure classes are listed, not discovered: adding a file here is what says
# "this one must compile without android.jar", and javac enforces it. The JSON
# sample the test prints is also parsed by python3 when it is available, which is
# the check an acceptance run does with json.loads on the device's answer.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$here/out/test
rm -rf "$work"
mkdir -p "$work"

sources=(
    "$here/src/io/github/mbanucu/a11yprobe/ProbeReply.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeNode.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeSelector.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeExpect.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeJson.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeArgs.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeBatch.java"
    "$here/src/io/github/mbanucu/a11yprobe/ProbeResults.java"
)
mapfile -t tests < <(find "$here/test/java" -name '*.java' | sort)
[ "${#tests[@]}" -gt 0 ] || { echo "no tests under $here/test/java" >&2; exit 1; }

javac -nowarn --release 17 -d "$work" "${sources[@]}" "${tests[@]}"

set +e
output=$(java -cp "$work" io.github.mbanucu.a11yprobe.ProbeProtocolTest)
status=$?
set -e
printf '%s\n' "$output"

sample=$(printf '%s\n' "$output" | sed -n 's/^JSON-SAMPLE //p')
if command -v python3 >/dev/null; then
    if [ -z "$sample" ]; then
        printf 'FAIL json: the test printed no sample to parse\n'
        status=1
    elif printf '%s' "$sample" | python3 -c 'import json, sys; json.loads(sys.stdin.read())'; then
        printf 'ok   json: python3 json.loads accepted the sample\n'
    else
        printf 'FAIL json: python3 json.loads rejected the sample\n'
        status=1
    fi
fi

exit "$status"
