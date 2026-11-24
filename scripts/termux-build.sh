#!/usr/bin/env bash
# Smoke-build the library on Termux, the environment this project is developed
# on rather than the ubuntu runner in .github/workflows/ci.yml.
#
#     bash scripts/termux-build.sh
#     OFFLINE=0 bash scripts/termux-build.sh          # allow downloads
#     CONFIGURATION_CACHE=0 bash scripts/termux-build.sh
#     bash scripts/termux-build.sh nmcpZipAggregation
#
# It resolves the SDK and aapt2 through scripts/termux-env.sh, then runs the
# environment check and assembleRelease with the configuration and build caches.
# It does not install the APK, publish anything or touch Maven Central.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

# shellcheck source=termux-env.sh
source "$root/scripts/termux-env.sh"

if [ -x ./gradlew ]; then
    gradle=./gradlew
elif command -v gradle >/dev/null 2>&1; then
    gradle=gradle
else
    echo "no ./gradlew and no gradle on PATH; pkg install gradle" >&2
    exit 1
fi

args=(--console=plain --warning-mode all)
if [ "${OFFLINE:-1}" = 1 ]; then
    args+=(--offline)
fi
if [ "${CONFIGURATION_CACHE:-1}" = 1 ]; then
    args+=(--configuration-cache)
fi

# Termux's Gradle daemon cannot adopt the client's environment, so a daemon
# started before ANDROID_HOME was exported would not see it. With no
# local.properties, use a one-shot daemon that inherits this shell.
if [ ! -f local.properties ]; then
    args+=(--no-daemon)
fi

if [ "$#" -gt 0 ]; then
    tasks=("$@")
else
    tasks=(checkBuildEnv assembleRelease)
fi

echo "== termux smoke build ==" >&2
"$gradle" "${args[@]}" "${TERMUX_GRADLE_ARGS[@]}" "${tasks[@]}"

if [ "${tasks[*]}" = "checkBuildEnv assembleRelease" ]; then
    echo "built $(find build/outputs/aar -name '*.aar' -print -quit)"
fi
