#!/usr/bin/env bash
# Pulls One Stream's problem reports (docs/MACHINE_FIRST_PLAN.md step 1) from every connected device
# into reports/<device-model>/ (never committed), skipping ones already pulled, then prints one line
# per new report: device, time, kind, screen, first line of the detail.
#   tool/pull_reports.sh          pull
#   tool/pull_reports.sh --clear  pull, then delete the pulled reports from the device
# Works on release builds: reports live in the app's external files dir, which adb can read.
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
REMOTE=/sdcard/Android/data/com.kirtanya.reel/files/reports
clear=0
[[ "${1:-}" == "--clear" ]] && clear=1

[[ -x "$ADB" ]] || { echo "adb not found at $ADB"; exit 1; }
serials=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
[[ -n "$serials" ]] || { echo "No device connected."; exit 1; }

total_new=0
for serial in $serials; do
  model=$("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r' | tr ' /' '__')
  dest="reports/${model:-$serial}"
  mkdir -p "$dest"
  # Finished reports only: one being written is still called <name>.tmp.
  names=$("$ADB" -s "$serial" shell "ls -1 $REMOTE 2>/dev/null" | tr -d '\r' | grep -E '^[0-9]{8}-[0-9]{6}-[a-z0-9-]+$' || true)
  new=0
  pulled=()
  for name in $names; do
    if [[ -f "$dest/$name/report.json" ]]; then
      pulled+=("$name")
      continue
    fi
    rm -rf "${dest:?}/$name"
    if "$ADB" -s "$serial" pull "$REMOTE/$name" "$dest/" > /dev/null 2>&1 && [[ -f "$dest/$name/report.json" ]]; then
      pulled+=("$name")
      new=$((new + 1))
      line=$(jq -r '[.time, .kind, (.screen.name // "?"), ((.detail // "") | split("\n")[0] | .[0:140])] | @tsv' "$dest/$name/report.json" 2> /dev/null \
        || printf '%s\t(unreadable report.json)' "$name")
      printf '%s\t%s\t%s\n' "$model" "$line" "$dest/$name"
    else
      echo "$model: couldn't pull $name" >&2
    fi
  done
  if [[ $clear == 1 && ${#pulled[@]} -gt 0 ]]; then
    for name in "${pulled[@]}"; do
      "$ADB" -s "$serial" shell "rm -rf '$REMOTE/$name'"
    done
    echo "$model: cleared ${#pulled[@]} pulled report(s) from the device" >&2
  fi
  echo "$model: $new new report(s), $(echo "$names" | grep -c . || true) on the device" >&2
  total_new=$((total_new + new))
done
echo "$total_new new report(s) in total under reports/" >&2
