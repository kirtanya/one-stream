#!/bin/bash
# Installs a TV build and compiles ALL of it ahead of time (-m speed): a plain `adb install` leaves the app
# interpreted until the TV's idle dexopt runs, and the profile route (speed-profile) left most Compose code
# interpreted: building a row of cards took 740 ms vs 190 ms fully compiled (perfetto, 2026-11-04). Costs ~30 MB of
# the TV's storage. Usage: tool/install_tv.sh [apk]
set -e
ADB=~/Library/Android/sdk/platform-tools/adb
S=${TV:-192.168.1.128:5555}
APK=${1:-$(ls -t ~/Desktop/APKs/OneStream-*-tv.apk | head -1)}
"$ADB" connect "$S" >/dev/null || true
"$ADB" -s "$S" install -r "$APK"
"$ADB" -s "$S" shell cmd package compile -f -m speed com.kirtanya.reel
"$ADB" -s "$S" shell dumpsys package dexopt | grep -A2 "\[com.kirtanya.reel\]" | grep status
"$ADB" -s "$S" shell dumpsys package com.kirtanya.reel | grep versionName
