#!/usr/bin/env bash
# Print one value from gradle/libs.versions.toml for the Gradle-free CI and
# script steps. The Gradle build reads the catalog directly.
#
#     scripts/versions.sh compileSdk     # 35
#     scripts/versions.sh buildTools     # 35.0.0
#     scripts/versions.sh agp            # 9.5.2
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
key=${1:?usage: scripts/versions.sh <key>}

value=$(sed -n "s/^${key}[[:space:]]*=[[:space:]]*\"\\(.*\\)\"/\\1/p" \
    "$root/gradle/libs.versions.toml" | head -n 1)
if [ -z "$value" ]; then
    echo "versions.sh: no '$key' in gradle/libs.versions.toml" >&2
    exit 1
fi
printf '%s\n' "$value"
