#!/bin/bash
# Measures how smooth the TV home feels: drives a fixed D-pad route through Home and prints frame stats
# (dumpsys gfxinfo). Only run when the user asked for TV testing and the TV is idle (CLAUDE.md "Never disturb playback").
# Usage: tool/tv_jank.sh [label]
set -e
ADB=~/Library/Android/sdk/platform-tools/adb
S=${TV:-192.168.1.128:5555}
tv(){ "$ADB" -s "$S" "$@"; }
if tv shell dumpsys media_session | grep -q "state=PLAYING"; then echo "TV is playing something: not touching it"; exit 1; fi
tv shell am force-stop com.kirtanya.reel
tv shell am start -W -n com.kirtanya.reel/.MainActivity | grep -E "TotalTime|WaitTime" || true
sleep 6
tv shell dumpsys gfxinfo com.kirtanya.reel reset >/dev/null
keys(){ local k=$1 n=$2 d=${3:-0.35}; for _ in $(seq "$n"); do tv shell input keyevent "$k"; sleep "$d"; done; }
# Route: along Continue watching, down through the rows, back up, then fast scrolling (a held key).
keys DPAD_RIGHT 6; keys DPAD_LEFT 6; keys DPAD_DOWN 4; keys DPAD_RIGHT 8; keys DPAD_DOWN 3; keys DPAD_UP 7
keys DPAD_RIGHT 10 0.12; keys DPAD_DOWN 4 0.15; keys DPAD_UP 4 0.15
sleep 1
tv shell dumpsys gfxinfo com.kirtanya.reel | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands|Number Frame deadline missed" | sed "s/^/${1:-run}: /"
