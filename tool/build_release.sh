#!/usr/bin/env bash
# Checks the design rules, then builds release APKs and copies them to ~/Desktop/APKs (never loose on the Desktop).
#   phone = arm64-v8a, tv = armeabi-v7a (the user's TV is 32-bit ARM)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

# The user's design rules first (CLAUDE.md "Design rules"): a new violation stops the release.
if ! tool/check_design.sh; then
    echo "Release stopped: design rules failed (see above)." >&2
    exit 1
fi

./gradlew :core:test :app:assembleRelease --console=plain -q

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
out="$HOME/Desktop/APKs"
mkdir -p "$out"
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk "$out/OneStream-$version-phone.apk"
cp app/build/outputs/apk/release/app-armeabi-v7a-release.apk "$out/OneStream-$version-tv.apk"
ls -lh "$out"/OneStream-"$version"-*.apk
